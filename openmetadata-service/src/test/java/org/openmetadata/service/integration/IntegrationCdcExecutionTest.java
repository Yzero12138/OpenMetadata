package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationCdcModels.CheckpointSummary;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class IntegrationCdcExecutionTest {
  private final SqlT1ServiceTest.MemoryStore store = new SqlT1ServiceTest.MemoryStore();
  private final List<Map<String, Object>> submitted = new ArrayList<>();
  private IntegrationService service;
  private SeaTunnelClient engine;
  private JdbcInspector jdbc;

  @BeforeEach
  void prepare() throws Exception {
    service =
        new IntegrationService(
            configuration(),
            store,
            new IntegrationConnectionsTest.MemoryConnections(),
            Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC));
    jdbc = mock(JdbcInspector.class);
    when(jdbc.table("synthetic-source", "public", "patient"))
        .thenReturn(IntegrationValidatorTest.source());
    when(jdbc.table("doris-ods-validation", "ods_validation", "patient_ods"))
        .thenReturn(
            new TableDefinition(
                "ods_validation", "patient_ods", IntegrationValidatorTest.target().columns()));
    inject("inspector", jdbc);
    engine = mock(SeaTunnelClient.class);
    when(engine.hasSourceConnector("Postgres-CDC")).thenReturn(true);
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("RUNNING", 1L, 1L));
    doAnswer(
            call -> {
              submitted.add(call.getArgument(2));
              return null;
            })
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), anyBoolean());
    inject("engine", engine);
  }

  @Test
  void savesDraftWithoutPluginButEveryFreshSubmitRequiresReadyPreflight() {
    when(engine.hasSourceConnector("Postgres-CDC")).thenReturn(false);
    var task = service.create(input(), "admin");
    assertTrue(service.validate(task).valid());
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertTrue(submitted.isEmpty());
    assertNull(store.get(UUID.fromString(task.id)).latestRun);
  }

  @Test
  void freshPostgresExecutionUsesAdapterAndPersistsNonsecretFingerprint() throws Exception {
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    var source = source(submitted.getFirst());
    assertEquals(1000, source.get("snapshot.fetch.size"));
    assertEquals(8192, source.get("snapshot.split.size"));
    assertEquals(1, source.get("incremental.parallelism"));
    var json = new ObjectMapper().readTree(IntegrationModels.serialize(task));
    assertTrue(
        json.path("latestRun").path("configurationFingerprint").asText().matches("[a-f0-9]{64}"));
    assertFalse(json.toString().contains("test-secret"));
  }

  @Test
  void legacyPostgresSavepointKeepsExactOldGraph() {
    var task = service.create(input(), "admin");
    task.latestRun = run("SAVEPOINT_DONE");
    task.runs.add(task.latestRun);
    store.save(task);
    when(engine.info(eq("12345"), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("SAVEPOINT_DONE", 1L, 1L));
    when(engine.hasCompletedSavepoint(eq("12345"), any(Duration.class))).thenReturn(true);
    when(engine.hasCompletedSavepoint("12345")).thenReturn(true);
    task = service.run(task.id, true, "admin");
    assertFalse(source(submitted.getFirst()).containsKey("snapshot.fetch.size"));
    assertEquals(5000, ((Map<?, ?>) submitted.getFirst().get("env")).get("checkpoint.interval"));
    var sink = (Map<?, ?>) ((List<?>) submitted.getFirst().get("sink")).getFirst();
    assertEquals(100, sink.get("doris.batch.size"));
    assertFalse(((Map<?, ?>) sink.get("doris.config")).containsKey("num_as_string"));
    assertFalse(IntegrationModels.serialize(task).contains("configurationFingerprint"));
  }

  @Test
  void runningCheckpointSampleSurvivesUnavailableAndTerminalMonitorCleanup() throws Exception {
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    when(engine.checkpoints(anyString(), any(Duration.class)))
        .thenReturn(new CheckpointSummary(5, 4, 1, 0, 1700L, null, 1800L))
        .thenThrow(new IntegrationException("ENGINE_UNAVAILABLE", 503));
    var first = service.task(task.id, "admin");
    var json = new ObjectMapper().readTree(IntegrationModels.serialize(first)).path("latestRun");
    assertEquals(4, json.path("checkpoints").path("completed").asLong());
    assertEquals(1800, json.path("checkpoints").path("updatedAt").asLong());
    assertEquals(1794538800000L, json.path("checkpointsCheckedAt").asLong());
    var second = service.task(task.id, "admin");
    assertEquals(
        json.path("checkpoints"),
        new ObjectMapper()
            .readTree(IntegrationModels.serialize(second))
            .path("latestRun")
            .path("checkpoints"));
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("FINISHED", 1L, 1L));
    var finished = service.task(task.id, "admin");
    assertEquals("FINISHED", finished.latestRun.status);
    assertEquals(
        json.path("checkpoints"),
        new ObjectMapper()
            .readTree(IntegrationModels.serialize(finished))
            .path("latestRun")
            .path("checkpoints"));
    assertNotNull(store.get(UUID.fromString(task.id)));
  }

  @Test
  void checkpointObservationTimeDoesNotAdvanceWhenPollFailsAndNeverObservedStaysAbsent()
      throws Exception {
    var clock =
        new InitializationServiceTest.MutableClock(
            Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC);
    inject("clock", clock);
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    when(engine.checkpoints(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("ENGINE_UNAVAILABLE", 503));
    var unavailable = service.task(task.id, "admin");
    assertNull(unavailable.latestRun.checkpoints);
    assertNull(unavailable.latestRun.checkpointsCheckedAt);
    when(engine.checkpoints(anyString(), any(Duration.class)))
        .thenReturn(new CheckpointSummary(1, 1, 0, 0, 10L, null, 20L));
    var observed = service.task(task.id, "admin");
    long firstChecked = observed.latestRun.checkpointsCheckedAt;
    clock.advance(Duration.ofMinutes(1));
    when(engine.checkpoints(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("ENGINE_UNAVAILABLE", 503));
    var failedPoll = service.task(task.id, "admin");
    assertEquals(firstChecked, failedPoll.latestRun.checkpointsCheckedAt);
    assertEquals(observed.latestRun.checkpoints, failedPoll.latestRun.checkpoints);
    assertEquals("RUNNING", failedPoll.latestRun.status);
    assertNull(failedPoll.latestRun.errorCode);
  }

  @Test
  void conflictingFullWriterIsBlockedBeforeReservationOrEngineSubmission() {
    var first = service.create(input(), "admin");
    first.latestRun = run("SAVEPOINT_DONE");
    first.latestRun.canResume = true;
    first.runs.add(first.latestRun);
    store.save(first);
    var other = input();
    other.name = "other_full";
    other.mode = "FULL";
    other.sourceTable = "patient_two";
    when(jdbc.table("synthetic-source", "public", "patient_two"))
        .thenReturn(
            new TableDefinition(
                "public", "patient_two", IntegrationValidatorTest.source().columns()));
    var draft = service.create(other, "admin");
    assertEquals(
        "TARGET_WRITER_ACTIVE",
        assertThrows(IntegrationException.class, () -> service.run(draft.id, false, "admin"))
            .code());
    assertNull(store.get(UUID.fromString(draft.id)).latestRun);
    verify(engine, never()).submit(anyString(), anyString(), anyMap(), anyBoolean());
  }

  @Test
  void nonCdcTasksRejectCdcOptionsDuringDraftValidation() {
    var draft = input();
    draft.mode = "FULL";
    draft.cdc = new CdcOptions(null, "Asia/Shanghai", null, null);
    assertFalse(service.validate(draft).valid());
  }

  @Test
  void uncertainPostgresResumptionWithoutFingerprintUsesTheLegacyGraphAndFreshPluginCheck() {
    var task = service.create(input(), "admin");
    task.latestRun = run("RESUMPTION_UNKNOWN");
    task.runs.add(task.latestRun);
    store.save(task);
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    when(engine.hasCompletedSavepoint(eq("12345"), any(Duration.class))).thenReturn(true);
    when(engine.hasCompletedSavepoint("12345")).thenReturn(true);
    service.run(task.id, false, "admin");
    assertEquals(5000, ((Map<?, ?>) submitted.getFirst().get("env")).get("checkpoint.interval"));
    verify(engine).hasSourceConnector("Postgres-CDC");
  }

  @Test
  void uncertainResumptionRequiresFreshCompletedSavepointBeforeAnyEnginePost() {
    var task = service.create(input(), "admin");
    task.latestRun = run("RESUMPTION_UNKNOWN");
    task.runs.add(task.latestRun);
    store.save(task);
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    String id = task.id;
    assertEquals(
        "SAVEPOINT_UNAVAILABLE",
        assertThrows(IntegrationException.class, () -> service.run(id, false, "admin")).code());
    assertTrue(submitted.isEmpty());
    assertEquals("RESUMPTION_UNKNOWN", store.get(UUID.fromString(id)).latestRun.status);
  }

  @Test
  void incompatibleInspectedShapeBlocksUncertainRetryWithoutReplacingFingerprintOrReservation() {
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    String fingerprint = task.latestRun.configurationFingerprint;
    task.latestRun.status = "SUBMISSION_UNKNOWN";
    store.save(task);
    var columns = new ArrayList<>(IntegrationValidatorTest.source().columns());
    columns.add(
        new IntegrationModels.ColumnDefinition(
            "new_column", "varchar", true, false, Types.VARCHAR, 30, 0, false));
    when(jdbc.table("synthetic-source", "public", "patient"))
        .thenReturn(new TableDefinition("public", "patient", columns));
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    clearInvocations(engine);
    String id = task.id;
    assertEquals(
        "CDC_CONFIGURATION_CHANGED",
        assertThrows(IntegrationException.class, () -> service.run(id, false, "admin")).code());
    assertEquals(fingerprint, store.get(UUID.fromString(id)).latestRun.configurationFingerprint);
    assertEquals(1, submitted.size());
    verify(engine, never()).submit(anyString(), anyString(), anyMap(), anyBoolean());
  }

  @Test
  void uncertainRetryRepeatsMandatoryPreflightAndNeverSubmitsWhenItIsNoLongerReady() {
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    task.latestRun.status = "SUBMISSION_UNKNOWN";
    store.save(task);
    when(engine.hasSourceConnector("Postgres-CDC")).thenReturn(false);
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    String id = task.id;
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(id, false, "admin")).code());
    assertEquals(1, submitted.size());
    verify(engine, times(2)).hasSourceConnector("Postgres-CDC");
  }

  @Test
  void oracleExecutionUsesSameFreshTrustedInspectionAndResolvedNativeDbName() throws Exception {
    var fixture = oracleFixture();
    var input = oracleInput(fixture.source.toString());
    var task = service.create(input, "admin");
    clearInvocations(jdbc);
    var running = service.run(task.id, false, "admin");
    assertEquals(1, fixture.opens);
    verify(jdbc, never()).table(eq(input.sourceConnectionId), anyString(), anyString());
    var source = source(submitted.getFirst());
    assertEquals("Oracle-CDC", source.get("plugin_name"));
    assertEquals(fixture.source.jdbcUrl(), source.get("url"));
    assertEquals(List.of("HISDB"), source.get("database-names"));
    assertEquals(List.of("HISDB.HIS.ORDERS"), source.get("table-names"));
    assertEquals(1, source.get("incremental.parallelism"));
    assertEquals(1000, source.get("snapshot.fetch.size"));
    assertTrue(source.get("url").toString().contains("PROTOCOL=TCPS"));
    var schema = (Map<?, ?>) source.get("schema");
    assertEquals(Map.of("ID", "DECIMAL(18,0)", "VALUE", "STRING"), schema.get("fields"));
    var debezium = (Map<?, ?>) source.get("debezium");
    assertEquals("true", debezium.get("log.mining.read.only"));
    assertEquals("precise", debezium.get("decimal.handling.mode"));
    var transform = (Map<?, ?>) ((List<?>) submitted.getFirst().get("transform")).getFirst();
    assertEquals("SELECT `ID` AS `id`, `VALUE` AS `name` FROM source", transform.get("query"));
    var sink = (Map<?, ?>) ((List<?>) submitted.getFirst().get("sink")).getFirst();
    assertEquals(true, sink.get("sink.enable-delete"));
    assertEquals(
        Map.of(
            "format",
            "json",
            "read_json_by_line",
            "true",
            "strict_mode",
            "true",
            "max_filter_ratio",
            "0",
            "num_as_string",
            "true"),
        sink.get("doris.config"));
    assertNotNull(running.latestRun.configurationFingerprint);
  }

  @Test
  void oracleNoArchiveDraftSavesButPreflightAndEverySubmitFailClosed() throws Exception {
    var fixture = oracleFixture();
    fixture.logMode = "NOARCHIVELOG";
    fixture.minimum = "NO";
    var input = oracleInput(fixture.source.toString());
    var task = service.create(input, "admin");
    assertTrue(service.validate(input).valid());
    var checks = service.cdcPreflight(input);
    assertFalse(checks.ready());
    assertTrue(
        checks.checks().stream()
            .anyMatch(check -> check.code().equals("ORACLE_ARCHIVELOG_REQUIRED")));
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertTrue(submitted.isEmpty());
    assertNull(store.get(UUID.fromString(task.id)).latestRun);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oracleDollarRoutesRemainValidDraftsButCannotSubmitBeforeFreshPreflight(boolean dollarSchema)
      throws Exception {
    String schema = dollarSchema ? "HOS$" : "HIS";
    String table = dollarSchema ? "ORDERS" : "PAT$ENT";
    var fixture = oracleFixture(schema);
    var input = oracleInput(fixture.source.toString());
    input.sourceSchema = schema;
    input.sourceTable = table;
    var columns = jdbc.table(input.sourceConnectionId, schema, "ORDERS").columns();
    when(jdbc.table(input.sourceConnectionId, schema, table))
        .thenReturn(new TableDefinition(schema, table, columns));
    var task = service.create(input, "admin");
    assertTrue(service.validate(input).valid());
    var result = service.cdcPreflight(input);
    assertFalse(result.ready());
    assertTrue(
        result.checks().stream()
            .anyMatch(
                check ->
                    check.id().equals("logminer-identifiers")
                        && check.status().equals("FAIL")
                        && check.required()
                        && check.code().equals("ORACLE_CDC_IDENTIFIER_UNSUPPORTED")));
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertNull(store.get(UUID.fromString(task.id)).latestRun);
    assertTrue(submitted.isEmpty());
    verify(engine, never()).submit(anyString(), anyString(), anyMap(), anyBoolean());
  }

  @Test
  void legacyOracleSavepointCannotResumeWithoutFingerprint() throws Exception {
    var fixture = oracleFixture();
    var task = service.create(oracleInput(fixture.source.toString()), "admin");
    task.latestRun = run("SAVEPOINT_DONE");
    task.runs.add(task.latestRun);
    store.save(task);
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("SAVEPOINT_DONE", 1L, 1L));
    when(engine.hasCompletedSavepoint(eq("12345"), any(Duration.class))).thenReturn(true);
    String id = task.id;
    assertEquals(
        "CDC_LEGACY_RESUME_UNSAFE",
        assertThrows(IntegrationException.class, () -> service.run(id, true, "admin")).code());
    assertTrue(submitted.isEmpty());
  }

  @Test
  void oracleNativeByteLengthChangeBlocksSavepointResumeWithoutEnginePost() throws Exception {
    var fixture = oracleFixture();
    var task =
        service.run(
            service.create(oracleInput(fixture.source.toString()), "admin").id, false, "admin");
    String fingerprint = task.latestRun.configurationFingerprint;
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.stringColumn("VALUE", "VARCHAR2", 100, 201));
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("SAVEPOINT_DONE", 1L, 1L));
    when(engine.hasCompletedSavepoint(anyString(), any(Duration.class))).thenReturn(true);
    clearInvocations(engine);
    String id = task.id;
    assertEquals(
        "CDC_CONFIGURATION_CHANGED",
        assertThrows(IntegrationException.class, () -> service.run(id, true, "admin")).code());
    assertEquals(fingerprint, store.get(UUID.fromString(id)).latestRun.configurationFingerprint);
    verify(engine, never()).submit(anyString(), anyString(), anyMap(), anyBoolean());
    assertEquals(1, submitted.size());
  }

  @Test
  void startingANewJobDoesNotInheritCheckpointSamplesFromThePreviousJob() {
    var task = service.run(service.create(input(), "admin").id, false, "admin");
    when(engine.checkpoints(anyString(), any(Duration.class)))
        .thenReturn(new CheckpointSummary(1, 1, 0, 0, 10L, null, 20L));
    task = service.task(task.id, "admin");
    var previous = task.latestRun.checkpoints;
    assertNotNull(previous);
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("FINISHED", 1L, 1L));
    var fresh = service.run(task.id, false, "admin");
    assertNull(fresh.latestRun.checkpoints);
    assertNull(fresh.latestRun.checkpointsCheckedAt);
    assertEquals(previous, fresh.runs.getFirst().checkpoints);
  }

  @Test
  void publicPreflightRejectsUnknownColumnsAndWrongRequestedKeyAndEscapedScope() {
    var source =
        service.cdcPreflight(
            "synthetic-source",
            new IntegrationCdcModels.CdcPreflightInput(
                "public", "patient", List.of("does_not_exist"), null, null, null, null));
    assertFalse(source.ready());
    assertTrue(
        source.checks().stream().anyMatch(check -> "CDC_COLUMNS_INVALID".equals(check.code())));
    var task = input();
    task.primaryKey = "name";
    assertFalse(service.cdcPreflight(task).ready());
    clearInvocations(jdbc);
    assertEquals(
        "SCHEMA_NOT_ALLOWED",
        assertThrows(
                IntegrationException.class,
                () ->
                    service.cdcPreflight(
                        "synthetic-source",
                        new IntegrationCdcModels.CdcPreflightInput(
                            "other", "patient", null, null, null, null, null)))
            .code());
    verify(jdbc, never()).table(anyString(), anyString(), anyString());
  }

  @Test
  void targetCountIsManualReturnsZeroWithClockTimeAndRejectsSourceRoleBeforeJdbc() {
    var task = service.create(input(), "admin");
    when(jdbc.targetCount("doris-ods-validation", "ods_validation", "patient_ods")).thenReturn(0L);
    var count = service.targetCount(task.id);
    assertEquals(0, count.count());
    assertEquals(Instant.parse("2026-11-13T03:00:00Z").toEpochMilli(), count.checkedAt());
    verify(jdbc, times(1)).targetCount(anyString(), anyString(), anyString());
    clearInvocations(jdbc);
    service.tasks("admin");
    service.task(task.id, "admin");
    verify(jdbc, never()).targetCount(anyString(), anyString(), anyString());
    task.targetConnectionId = "synthetic-source";
    store.save(task);
    assertEquals(
        "CONNECTION_ROLE_MISMATCH",
        assertThrows(IntegrationException.class, () -> service.targetCount(task.id)).code());
    verify(jdbc, never()).targetCount(anyString(), anyString(), anyString());
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(IntegrationException.class, () -> service.targetCount("../escape")).code());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "RUNNING",
        "STOP_FAILED",
        "SUBMITTING",
        "SUBMISSION_UNKNOWN",
        "RESUMING",
        "RESUMPTION_UNKNOWN",
        "SAVEPOINT_DONE",
        "MALFORMED_EMPTY_STATUS"
      })
  void writerGuardMatchesPhysicalDorisTargetAcrossRegisteredIdsAndUsers(String status) {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    var aliasInput = IntegrationConnectionsTest.input("Doris");
    aliasInput.name = "same_doris_physical";
    aliasInput.role = "TARGET";
    aliasInput.databaseVersion = "4.0.8";
    aliasInput.host = "DORIS-ISOLATED";
    aliasInput.port = 9030;
    aliasInput.dorisHttpPort = 8030;
    aliasInput.database = "ods_validation";
    aliasInput.schemas = List.of("ods_validation");
    aliasInput.username = "different_writer";
    var alias = service.createConnection(aliasInput, "admin");
    var first = blockingWriter(store, "patient_ods", status);
    if (status.equals("MALFORMED_EMPTY_STATUS")) {
      first.latestRun.status = null;
      store.save(first);
    }
    var draft = input();
    draft.targetConnectionId = alias.id;
    draft.mode = "FULL";
    when(jdbc.table(alias.id, "ods_validation", "patient_ods"))
        .thenReturn(
            new TableDefinition(
                "ods_validation", "patient_ods", IntegrationValidatorTest.target().columns()));
    var task = service.create(draft, "admin");
    assertEquals(
        "TARGET_WRITER_ACTIVE",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertNull(store.get(UUID.fromString(task.id)).latestRun);
    assertEquals(first.latestRun.jobId, store.get(UUID.fromString(first.id)).latestRun.jobId);
    assertTrue(submitted.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void physicalTargetWriterGuardCannotBeBypassedByMysqlRegistrationInEitherDirection(
      boolean newDorisWriter) {
    var alias = mysqlTargetAlias(service);
    when(jdbc.table(alias.id, "ods_validation", "patient_ods"))
        .thenReturn(
            new TableDefinition(
                "ods_validation", "patient_ods", IntegrationValidatorTest.target().columns()));
    var blocker = blockingWriter(store, "patient_ods", "RUNNING");
    if (newDorisWriter) {
      blocker.targetConnectionId = alias.id;
      store.save(blocker);
    }
    var input = input();
    input.mode = "FULL";
    if (!newDorisWriter) {
      input.targetConnectionId = alias.id;
    }
    var draft = service.create(input, "admin");
    long version = draft.version;
    clearInvocations(engine);

    assertEquals(
        "TARGET_WRITER_ACTIVE",
        assertThrows(IntegrationException.class, () -> service.run(draft.id, false, "admin"))
            .code());

    var persisted = store.get(UUID.fromString(draft.id));
    assertEquals(version, persisted.version);
    assertNull(persisted.latestRun);
    assertTrue(persisted.runs.isEmpty());
    assertEquals(blocker.latestRun.jobId, store.get(UUID.fromString(blocker.id)).latestRun.jobId);
    assertTrue(submitted.isEmpty());
    verify(engine, never()).submit(anyString(), anyString(), anyMap(), anyBoolean());
  }

  static IntegrationModels.ConnectionDefinition mysqlTargetAlias(IntegrationService service) {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    var aliasInput = IntegrationConnectionsTest.input("Mysql");
    aliasInput.name = "same_physical_mysql";
    aliasInput.role = "TARGET";
    aliasInput.host = "DORIS-ISOLATED";
    aliasInput.port = 9030;
    aliasInput.database = "ods_validation";
    aliasInput.schemas = List.of("ods_validation");
    aliasInput.username = "different_mysql_writer";
    return service.createConnection(aliasInput, "admin");
  }

  @Test
  void cdcResumeAndUncertainSubmitCannotBypassCompetingWriter() {
    var task = service.create(input(), "admin");
    task.latestRun = run("SAVEPOINT_DONE");
    task.runs.add(task.latestRun);
    store.save(task);
    var blocker = blockingWriter(store, "patient_ods", "RUNNING");
    blocker.sourceTable = "patient_two";
    store.save(blocker);
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("SAVEPOINT_DONE", 1L, 1L));
    when(engine.hasCompletedSavepoint(eq("12345"), any(Duration.class))).thenReturn(true);
    String id = task.id;
    assertEquals(
        "TARGET_WRITER_ACTIVE",
        assertThrows(IntegrationException.class, () -> service.run(id, true, "admin")).code());
    task = store.get(UUID.fromString(id));
    task.latestRun.status = "SUBMISSION_UNKNOWN";
    store.save(task);
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    assertEquals(
        "TARGET_WRITER_ACTIVE",
        assertThrows(IntegrationException.class, () -> service.run(id, false, "admin")).code());
    assertTrue(submitted.isEmpty());
  }

  static IntegrationTask blockingWriter(IntegrationTaskStore store, String table, String status) {
    var writer = IntegrationTask.from(input());
    writer.id = UUID.randomUUID().toString();
    writer.name = "blocking_writer";
    writer.targetTable = table;
    writer.version = 1;
    writer.latestRun = run(status);
    writer.runs.add(writer.latestRun);
    store.save(writer);
    return writer;
  }

  private IntegrationCdcPreflightTest.OracleFixture oracleFixture() throws Exception {
    return oracleFixture("HIS");
  }

  private IntegrationCdcPreflightTest.OracleFixture oracleFixture(String schema) throws Exception {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    var sourceInput = IntegrationConnectionsTest.input("Oracle");
    sourceInput.tlsMode = "VERIFY";
    sourceInput.schemas = List.of(schema);
    var definition = service.createConnection(sourceInput, "admin");
    var fixture = new IntegrationCdcPreflightTest.OracleFixture();
    fixture.source = new IntegrationConfiguration.JdbcEndpoint(definition, sourceInput.password);
    fixture.targetEndpoint = configuration().endpoint("doris-ods-validation");
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.stringColumn("VALUE", "VARCHAR2", 100, 200));
    fixture.target =
        new TableDefinition(
            "ods_validation",
            "patient_ods",
            List.of(
                DorisTableMetadata.column("id", "decimal(18,0)", false, true, false),
                DorisTableMetadata.column("name", "string", true, false, false)));
    when(jdbc.table(definition.id, schema, "ORDERS"))
        .thenReturn(
            new TableDefinition(
                schema,
                "ORDERS",
                List.of(
                    new IntegrationModels.ColumnDefinition(
                        "ID", "NUMBER", false, true, Types.NUMERIC, 18, 0, false),
                    new IntegrationModels.ColumnDefinition(
                        "VALUE", "VARCHAR2", true, false, Types.VARCHAR, 100, 0, false))));
    when(jdbc.table("doris-ods-validation", "ods_validation", "patient_ods"))
        .thenReturn(fixture.target);
    inject("cdcPreflight", fixture.preflight());
    return fixture;
  }

  private TaskInput oracleInput(String sourceId) {
    var task = input();
    task.sourceConnectionId = sourceId;
    task.sourceSchema = "HIS";
    task.sourceTable = "ORDERS";
    task.primaryKey = "ID";
    task.fieldMappings =
        List.of(
            new IntegrationModels.FieldMapping("ID", "id"),
            new IntegrationModels.FieldMapping("VALUE", "name"));
    task.cdc = new CdcOptions(null, "Asia/Shanghai", null, null);
    return task;
  }

  static IntegrationConfiguration configuration() {
    var values = new HashMap<String, String>();
    values.put("HOSPITAL_INTEGRATION_ENABLED", "true");
    values.put("HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801");
    for (String role : List.of("SOURCE", "TARGET")) {
      values.put(
          "HOSPITAL_" + role + "_JDBC_URL", "jdbc:postgresql://isolated:5432/integration_test");
      values.put("HOSPITAL_" + role + "_JDBC_USER", "synthetic");
      values.put("HOSPITAL_" + role + "_JDBC_PASSWORD", "test-secret");
    }
    values.put("HOSPITAL_DORIS_ENABLED", "true");
    values.put("HOSPITAL_DORIS_HOST", "doris-isolated");
    values.put("HOSPITAL_DORIS_SQL_PORT", "9030");
    values.put("HOSPITAL_DORIS_HTTP_PORT", "8030");
    values.put("HOSPITAL_DORIS_DATABASE", "ods_validation");
    values.put("HOSPITAL_DORIS_USER", "writer");
    values.put("HOSPITAL_DORIS_PASSWORD", "test-secret");
    return IntegrationConfiguration.fromValues(values);
  }

  static TaskInput input() {
    var input = IntegrationValidatorTest.validInput();
    input.mode = "CDC";
    input.targetConnectionId = "doris-ods-validation";
    input.targetSchema = "ods_validation";
    return input;
  }

  static RunSummary run(String status) {
    var run = new RunSummary();
    run.jobId = "12345";
    run.status = status;
    run.submittedAt = 1;
    run.canResume = "SAVEPOINT_DONE".equals(status);
    return run;
  }

  private Map<?, ?> source(Map<String, Object> config) {
    return (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
  }

  private void inject(String name, Object value) throws Exception {
    Field field = IntegrationService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(service, value);
  }
}
