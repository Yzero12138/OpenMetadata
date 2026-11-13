package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.service.integration.IntegrationModels.InitializationConfig;
import org.openmetadata.service.integration.IntegrationModels.InitializationInput;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunInput;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.integration.IntegrationModels.UpdateInput;

class InitializationServiceTest {
  private final SqlT1ServiceTest.MemoryStore store = new SqlT1ServiceTest.MemoryStore();
  private final AtomicReference<String> status = new AtomicReference<>("RUNNING");
  private final List<Map<String, Object>> submitted = new ArrayList<>();
  private IntegrationService service;
  private SeaTunnelClient engine;
  private JdbcInspector jdbc;
  private IntegrationConfiguration configuration;
  private Map<String, String> configurationValues;
  private final MutableClock clock =
      new MutableClock(Instant.parse("2026-11-13T02:00:00Z"), ZoneId.of("UTC"));

  @BeforeEach
  void prepare() throws Exception {
    Map<String, String> values = new HashMap<>();
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
    configurationValues = Map.copyOf(values);
    configuration = IntegrationConfiguration.fromValues(values);
    service =
        new IntegrationService(
            configuration, store, new IntegrationConnectionsTest.MemoryConnections(), clock);
    jdbc = mock(JdbcInspector.class);
    when(jdbc.query(
            any(IntegrationConfiguration.JdbcEndpoint.class), eq("public"), anyString(), any()))
        .thenReturn(
            new TableDefinition(
                "public", "sql_result", IntegrationValidatorTest.source().columns()));
    when(jdbc.table("doris-ods-validation", "ods_validation", "daily_visits"))
        .thenReturn(
            new TableDefinition(
                "ods_validation", "daily_visits", IntegrationValidatorTest.target().columns()));
    inject("inspector", jdbc);
    engine = mock(SeaTunnelClient.class);
    when(engine.info(anyString(), any(Duration.class)))
        .thenAnswer(call -> new SeaTunnelClient.JobSnapshot(status.get(), 2L, 2L));
    doAnswer(
            call -> {
              IntegrationTask saved = store.list().getFirst();
              assertEquals("SUBMITTING", saved.latestRun.status);
              if ("INITIALIZATION".equals(saved.latestRun.trigger)) {
                assertEquals("RUNNING", saved.initializationState.status);
                assertEquals(saved.latestRun.jobId, saved.initializationState.pendingJobId);
              }
              submitted.add(call.getArgument(2));
              return null;
            })
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), eq(false));
    inject("engine", engine);
  }

  @Test
  void firstInitializationFreezesCutoffAndDoesNotPretendHistoryIsAlreadyCovered() {
    IntegrationTask task = service.create(input(), "admin");
    assertNotNull(task.initializationState);
    assertEquals(today().toString(), task.initializationState.cutoffDate);
    assertEquals(today().minusDays(3).toString(), task.initializationState.nextDate);
    assertEquals(0, task.initializationState.completedBatches);
    assertEquals(3, task.initializationState.totalBatches);
  }

  @Test
  void completedHistoryHandsOffAtTheFirstUncoveredDateEvenAfterSeveralDays() {
    IntegrationTask task = service.create(input(), "admin");
    String cutoff = task.initializationState.cutoffDate;
    status.set("FINISHED");
    for (int i = 0; i < 4; i++) service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("COMPLETED", task.initializationState.status);
    assertEquals(3, task.initializationState.completedBatches);
    assertEquals(cutoff, task.initializationState.nextDate);
    assertEquals(3, submitted.size());
    assertEquals(
        Instant.parse("2026-11-13T18:00:00Z").toEpochMilli(),
        task.scheduleState.nextRunAt.longValue());
    clock.advance(Duration.ofDays(4));
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("SCHEDULED", task.latestRun.trigger);
    assertEquals("2026-11-13", task.latestRun.businessDate);
    assertEquals("2026-11-13 00:00:00", task.latestRun.windowStart);
    assertEquals("2026-11-14 00:00:00", task.latestRun.windowEnd);
    assertEquals(3, task.initializationState.completedBatches);
  }

  @Test
  void failedBatchRetainsItsRangeAcrossReopenedServiceAndClockAdvance() throws Exception {
    var task = service.create(input(), "admin");
    service.dispatchScheduled(clock.instant());
    status.set("FINISHED");
    service.dispatchScheduled(clock.instant());
    status.set("FAILED");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("FAILED", task.initializationState.status);
    assertEquals(1, task.initializationState.completedBatches);
    String failedId = task.latestRun.jobId,
        start = task.latestRun.windowStart,
        end = task.latestRun.windowEnd;
    clock.advance(Duration.ofDays(5));
    service =
        new IntegrationService(
            configuration, store, new IntegrationConnectionsTest.MemoryConnections(), clock);
    inject("inspector", jdbc);
    inject("engine", engine);
    service.dispatchScheduled(clock.instant());
    assertEquals(2, submitted.size());
    clearInvocations(jdbc, engine);
    task =
        service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    verifyNoInteractions(jdbc, engine);
    status.set("RUNNING");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertNotEquals(failedId, task.latestRun.jobId);
    assertEquals(start, task.latestRun.windowStart);
    assertEquals(end, task.latestRun.windowEnd);
    assertEquals(2L, task.latestRun.batchIndex.longValue());
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals("2026-11-13", task.initializationState.cutoffDate);
    assertEquals(3, submitted.size());
  }

  @Test
  void pauseKeepsCurrentBatchAndContinueDoesNotRequireSourceAvailability() {
    var task = service.create(input(), "admin");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    clearInvocations(jdbc, engine);
    task = service.initialization(task.id, new InitializationInput("PAUSE", task.version), "admin");
    assertFalse(task.schedule.enabled);
    verifyNoInteractions(jdbc, engine);
    status.set("FINISHED");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals(1, submitted.size());
    clearInvocations(jdbc, engine);
    task =
        service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    assertTrue(task.schedule.enabled);
    verifyNoInteractions(jdbc, engine);
    service.dispatchScheduled(clock.instant());
    assertEquals(2, submitted.size());
  }

  @Test
  void uncertainInitializationReusesJobAndWindowAndPausedTaskNeverResubmits() {
    var task = service.create(input(), "admin");
    doThrow(new IntegrationException("SUBMISSION_UNKNOWN", 503))
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), eq(false));
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("SUBMISSION_UNKNOWN", task.latestRun.status);
    String id = task.latestRun.jobId,
        start = task.latestRun.windowStart,
        end = task.latestRun.windowEnd;
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    task = service.initialization(task.id, new InitializationInput("PAUSE", task.version), "admin");
    clock.advance(Duration.ofMinutes(2));
    service.dispatchScheduled(clock.instant());
    verify(engine, times(1)).submit(anyString(), anyString(), anyMap(), eq(false));
    task = store.list().getFirst();
    service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    verify(engine, times(2)).submit(eq(id), anyString(), anyMap(), eq(false));
    assertEquals(id, task.latestRun.jobId);
    assertEquals(start, task.latestRun.windowStart);
    assertEquals(end, task.latestRun.windowEnd);
    assertEquals(1, task.runs.size());
    assertEquals(0, task.initializationState.completedBatches);
  }

  @Test
  void freezeAndManualRunGuardsProtectTheSavedProgress() {
    var task = service.create(input(), "admin");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    final String id = task.id;
    assertEquals(
        "INITIALIZATION_IN_PROGRESS",
        assertThrows(
                IntegrationException.class,
                () -> service.run(id, new RunInput(false, "2026-11-01", false), "admin"))
            .code());
    task = service.initialization(task.id, new InitializationInput("PAUSE", task.version), "admin");
    status.set("FINISHED");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    UpdateInput changed = new UpdateInput();
    changed.copyInput(task);
    changed.version = task.version;
    changed.initialization.startDate = "2026-01-01";
    assertEquals(
        "INITIALIZATION_CONFIGURATION_LOCKED",
        assertThrows(IntegrationException.class, () -> service.update(id, changed, "admin"))
            .code());
    assertEquals(1, store.list().getFirst().initializationState.completedBatches);
    long stale = task.version - 1;
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                IntegrationException.class,
                () ->
                    service.initialization(id, new InitializationInput("CONTINUE", stale), "admin"))
            .code());
  }

  @Test
  void dailyClockEditsAfterHistoryDoNotSkipTheOverdueDate() {
    var task = service.create(input(), "admin");
    status.set("FINISHED");
    for (int i = 0; i < 4; i++) service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    clock.advance(Duration.ofDays(5));
    UpdateInput changed = new UpdateInput();
    changed.copyInput(task);
    changed.version = task.version;
    changed.schedule.dailyTime = "03:15";
    task = service.update(task.id, changed, "admin");
    assertEquals(
        Instant.parse("2026-11-13T19:15:00Z").toEpochMilli(),
        task.scheduleState.nextRunAt.longValue());
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("2026-11-13", task.latestRun.businessDate);
  }

  @Test
  void timezoneEditBeforeFirstSubmissionRebuildsTheUntouchedCutoff() {
    var task = service.create(input(), "admin");
    String firstId = task.initializationState.id;
    UpdateInput changed = new UpdateInput();
    changed.copyInput(task);
    changed.version = task.version;
    changed.schedule.timeZone = "Pacific/Honolulu";
    task = service.update(task.id, changed, "admin");
    assertEquals("2026-11-12", task.initializationState.cutoffDate);
    assertEquals(2, task.initializationState.totalBatches);
    assertNotEquals(firstId, task.initializationState.id);
    assertNull(task.initializationState.startedAt);
    assertTrue(submitted.isEmpty());
  }

  @Test
  void managedEndpointDriftCannotMixSourcesAfterAPartialInitialization() throws Exception {
    var task = service.create(input(), "admin");
    service.dispatchScheduled(clock.instant());
    status.set("FAILED");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    String bound = task.initializationState.sourceRouteHash;
    assertNotNull(bound);
    var moved = new HashMap<>(configurationValues);
    moved.put("HOSPITAL_SOURCE_JDBC_URL", "jdbc:postgresql://relocated:5432/integration_test");
    service =
        new IntegrationService(
            IntegrationConfiguration.fromValues(moved),
            store,
            new IntegrationConnectionsTest.MemoryConnections(),
            clock);
    inject("inspector", jdbc);
    inject("engine", engine);
    task =
        service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    clearInvocations(jdbc, engine);
    service.dispatchScheduled(clock.instant());
    verifyNoInteractions(jdbc, engine);
    task = store.list().getFirst();
    assertEquals("FAILED", task.initializationState.status);
    assertEquals("INITIALIZATION_CONFIGURATION_LOCKED", task.initializationState.lastError);
    assertEquals(bound, task.initializationState.sourceRouteHash);
    assertEquals(1, submitted.size());
    service =
        new IntegrationService(
            configuration, store, new IntegrationConnectionsTest.MemoryConnections(), clock);
    inject("inspector", jdbc);
    inject("engine", engine);
    service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    status.set("RUNNING");
    service.dispatchScheduled(clock.instant());
    assertEquals(2, submitted.size());
    assertEquals(bound, store.list().getFirst().initializationState.sourceRouteHash);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELLED", "SAVEPOINT_DONE"})
  void stoppedHistoryBatchCanBeContinuedWithItsOriginalWindow(String terminal) {
    var task = service.create(input(), "admin");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    String first = task.latestRun.jobId;
    String start = task.latestRun.windowStart;
    status.set(terminal);
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertEquals("FAILED", task.initializationState.status);
    service.initialization(task.id, new InitializationInput("CONTINUE", task.version), "admin");
    status.set("RUNNING");
    service.dispatchScheduled(clock.instant());
    task = store.list().getFirst();
    assertNotEquals(first, task.latestRun.jobId);
    assertEquals(start, task.latestRun.windowStart);
    assertEquals(0, task.initializationState.completedBatches);
    assertEquals(2, submitted.size());
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
  }

  @Test
  void historyInitializationDoesNotClaimOrAdvanceWhileAnotherWriterOwnsTarget() {
    var task = service.create(input(), "admin");
    var alias = IntegrationCdcExecutionTest.mysqlTargetAlias(service);
    var blocker =
        IntegrationCdcExecutionTest.blockingWriter(store, "daily_visits", "SUBMISSION_UNKNOWN");
    blocker.targetConnectionId = alias.id;
    store.save(blocker);
    service.dispatchScheduled(clock.instant());
    var persisted = store.get(UUID.fromString(task.id));
    assertEquals(task.version, persisted.version);
    assertEquals(task.initializationState.nextDate, persisted.initializationState.nextDate);
    assertEquals(0, persisted.initializationState.completedBatches);
    assertNull(persisted.initializationState.pendingJobId);
    assertNull(persisted.initializationState.startedAt);
    assertNull(persisted.initializationState.lastError);
    assertNull(persisted.latestRun);
    assertTrue(submitted.isEmpty());
  }

  private TaskInput input() {
    TaskInput task = IntegrationValidatorTest.validInput();
    task.mode = "SQL_T1";
    task.sourceTable = "sql_result";
    task.sourceQuery =
        "SELECT id,name FROM public.patient WHERE updated_at>=CAST(:window_start AS timestamp) AND updated_at<CAST(:window_end AS timestamp)";
    task.targetConnectionId = "doris-ods-validation";
    task.targetSchema = "ods_validation";
    task.targetTable = "daily_visits";
    task.schedule = new ScheduleConfig();
    task.schedule.enabled = true;
    task.initialization = new InitializationConfig();
    task.initialization.enabled = true;
    task.initialization.startDate = today().minusDays(3).toString();
    return task;
  }

  private void inject(String name, Object value) throws Exception {
    Field field = IntegrationService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(service, value);
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value;
    private final ZoneId zone;

    MutableClock(Instant now, ZoneId zone) {
      this(new AtomicReference<>(now), zone);
    }

    private MutableClock(AtomicReference<Instant> value, ZoneId zone) {
      this.value = value;
      this.zone = zone;
    }

    public ZoneId getZone() {
      return zone;
    }

    public Clock withZone(ZoneId next) {
      return new MutableClock(value, next);
    }

    public Instant instant() {
      return value.get();
    }

    void advance(Duration duration) {
      value.updateAndGet(now -> now.plus(duration));
    }
  }
}
