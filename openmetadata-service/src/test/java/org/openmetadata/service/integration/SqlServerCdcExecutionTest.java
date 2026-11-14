package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class SqlServerCdcExecutionTest {
  private final SqlT1ServiceTest.MemoryStore store = new SqlT1ServiceTest.MemoryStore();
  private final SqlServerCdcPreflightTest.Fixture fixture = new SqlServerCdcPreflightTest.Fixture();
  private final List<Map<String, Object>> submitted = new ArrayList<>();
  private IntegrationService service;
  private SeaTunnelClient engine;
  private TaskInput input;

  @BeforeEach
  void prepare() throws Exception {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    var configuration = IntegrationCdcExecutionTest.configuration();
    service =
        new IntegrationService(
            configuration, store, new IntegrationConnectionsTest.MemoryConnections());
    var source = IntegrationConnectionsTest.input("Mssql");
    source.host = "sql.example.internal";
    source.database = "HisDb";
    source.tlsMode = "VERIFY";
    var definition = service.createConnection(source, "admin");
    input = SqlServerCdcPreflightTest.task();
    input.sourceConnectionId = definition.id;
    var endpoint = new JdbcEndpoint(definition, source.password);
    var preflight =
        new IntegrationCdcPreflight(
            id -> id.equals(definition.id) ? endpoint : configuration.endpoint(id),
            (route, properties) -> fixture.connection(),
            (id, schema, table) -> fixture.target,
            plugin -> true);
    inject("cdcPreflight", preflight);
    var jdbc = mock(JdbcInspector.class);
    when(jdbc.table(definition.id, "dbo", "ORDERS"))
        .thenReturn(fixture.preflight().inspect(SqlServerCdcPreflightTest.task()).source());
    when(jdbc.table("doris-ods-validation", "ods_validation", "patient_ods"))
        .thenReturn(fixture.target);
    inject("inspector", jdbc);
    engine = mock(SeaTunnelClient.class);
    when(engine.info(anyString(), any(Duration.class)))
        .thenReturn(new SeaTunnelClient.JobSnapshot("SAVEPOINT_DONE", 1L, 1L));
    when(engine.hasCompletedSavepoint(anyString())).thenReturn(true);
    when(engine.hasCompletedSavepoint(anyString(), any(Duration.class))).thenReturn(true);
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
  void savesValidNotReadyDraftButFreshMissingEvidenceNeverSubmits() {
    input.cdc = new CdcOptions("HisDb", null, null, null);
    var task = service.create(input, "admin");
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertTrue(submitted.isEmpty());
    assertNull(store.get(UUID.fromString(task.id)).latestRun);
  }

  @Test
  void freshMetadataOnResumeBlocksChangedCaptureWithoutMutatingCheckpointFingerprint() {
    var task = service.run(service.create(input, "admin").id, false, "admin");
    String fingerprint = task.latestRun.configurationFingerprint;
    task.latestRun.status = "SAVEPOINT_DONE";
    task.latestRun.canResume = true;
    store.save(task);
    fixture.capture.put("create_date", SqlServerCdcPreflightTest.time("2026-11-02 00:00:00"));
    assertEquals(
        "CDC_CONFIGURATION_CHANGED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, true, "admin")).code());
    assertEquals(1, submitted.size());
    assertEquals(
        fingerprint, store.get(UUID.fromString(task.id)).latestRun.configurationFingerprint);
  }

  @Test
  void stableCaptureWithAdvancingLsnCanResumeButEveryRetryRechecksPermissions() {
    var task = service.run(service.create(input, "admin").id, false, "admin");
    task.latestRun.status = "SAVEPOINT_DONE";
    task.latestRun.canResume = true;
    store.save(task);
    fixture.lsn.put("max_lsn", SqlServerCdcPreflightTest.bytes(9));
    var resumed = service.run(task.id, true, "admin");
    assertEquals(2, submitted.size());
    resumed.latestRun.status = "SUBMISSION_UNKNOWN";
    store.save(resumed);
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    fixture.permissions.put("can_select_changes", 0);
    assertEquals(
        "CDC_PREFLIGHT_FAILED",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "admin"))
            .code());
    assertEquals(2, submitted.size());
  }

  @Test
  void legacySqlServerHasNoPostgresCompatibilityEscapeAndContainerOptionsRemainInvalid() {
    var task = service.create(input, "admin");
    task.latestRun = IntegrationCdcExecutionTest.run("SAVEPOINT_DONE");
    task.runs.add(task.latestRun);
    store.save(task);
    assertEquals(
        "CDC_LEGACY_RESUME_UNSAFE",
        assertThrows(IntegrationException.class, () -> service.run(task.id, true, "admin")).code());
    input.name = "bad_container";
    input.cdc = new CdcOptions("HisDb", "UTC", "PDB", "ROOT");
    assertThrows(IntegrationException.class, () -> service.create(input, "admin"));
    assertTrue(submitted.isEmpty());
  }

  private void inject(String name, Object value) throws Exception {
    Field field = IntegrationService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(service, value);
  }
}
