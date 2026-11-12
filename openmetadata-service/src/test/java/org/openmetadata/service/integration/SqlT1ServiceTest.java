package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunInput;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class SqlT1ServiceTest {
  private final MemoryStore store = new MemoryStore();
  private final AtomicReference<String> status = new AtomicReference<>("RUNNING");
  private final List<Map<String, Object>> submitted = new ArrayList<>();
  private IntegrationService service;
  private SeaTunnelClient engine;
  private JdbcInspector jdbc;

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
    service =
        new IntegrationService(
            IntegrationConfiguration.fromValues(values),
            store,
            new IntegrationConnectionsTest.MemoryConnections());
    jdbc = mock(JdbcInspector.class);
    TableDefinition source =
        new TableDefinition("public", "sql_result", IntegrationValidatorTest.source().columns());
    TableDefinition target =
        new TableDefinition(
            "ods_validation", "daily_visits", IntegrationValidatorTest.target().columns());
    when(jdbc.query(
            any(IntegrationConfiguration.JdbcEndpoint.class), eq("public"), anyString(), any()))
        .thenReturn(source);
    when(jdbc.table("doris-ods-validation", "ods_validation", "daily_visits")).thenReturn(target);
    inject("inspector", jdbc);
    engine = mock(SeaTunnelClient.class);
    when(engine.info(anyString(), any(Duration.class)))
        .thenAnswer(call -> new SeaTunnelClient.JobSnapshot(status.get(), 2L, 2L));
    doAnswer(
            call -> {
              IntegrationTask saved = store.list().getFirst();
              assertEquals("SUBMITTING", saved.latestRun.status);
              assertEquals(call.getArgument(0), saved.latestRun.jobId);
              assertNotNull(saved.latestRun.businessDate);
              submitted.add(call.getArgument(2));
              return null;
            })
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), eq(false));
    inject("engine", engine);
  }

  @Test
  void reservesBeforeSubmissionAndRetryKeepsBusinessDate() {
    IntegrationTask task = service.create(input(false), "admin");
    task = service.run(task.id, new RunInput(false, "2026-11-01", false), "admin");
    assertEquals("2026-11-01", task.latestRun.businessDate);
    assertEquals("2026-11-02 00:00:00", task.latestRun.windowEnd);
    assertEquals("MANUAL", task.latestRun.trigger);
    assertTrue(submitted.getFirst().toString().contains("2026-11-01 00:00:00"));
    String first = task.latestRun.jobId;
    status.set("FAILED");
    task = service.run(task.id, new RunInput(false, null, true), "admin");
    assertNotEquals(first, task.latestRun.jobId);
    assertEquals("2026-11-01", task.latestRun.businessDate);
    assertEquals("RETRY", task.latestRun.trigger);
    assertEquals(2, task.runs.size());
    assertFalse(IntegrationModels.serialize(task).contains("test-secret"));
  }

  @Test
  void failedScheduleBlocksLaterDatesUntilSameDateRetrySucceeds() {
    IntegrationTask task = service.create(input(true), "admin");
    task.scheduleState.nextRunAt = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli();
    store.save(task);
    service.dispatchScheduled(Instant.parse("2026-11-12T00:00:00Z"));
    task = store.list().getFirst();
    assertEquals("2026-11-10", task.latestRun.businessDate);
    assertEquals("SCHEDULED", task.latestRun.trigger);
    status.set("FAILED");
    service.dispatchScheduled(Instant.parse("2026-11-12T00:01:00Z"));
    task = store.list().getFirst();
    assertNotNull(task.scheduleState.lastError);
    assertEquals(1, submitted.size());
    final String id = task.id;
    assertEquals(
        "SCHEDULE_DATE_PENDING",
        assertThrows(
                IntegrationException.class,
                () -> service.run(id, new RunInput(false, "2026-11-11", false), "admin"))
            .code());
    service.run(id, new RunInput(false, null, true), "admin");
    status.set("FINISHED");
    service.dispatchScheduled(Instant.parse("2026-11-12T00:02:00Z"));
    task = store.list().getFirst();
    assertEquals("2026-11-10", task.scheduleState.lastBusinessDate);
    assertEquals("2026-11-11", task.latestRun.businessDate);
    assertEquals(3, submitted.size());
  }

  @Test
  void disabledScheduleNeverSubmitsAndFutureBusinessDatesFailBeforeSubmit() {
    var task = service.create(input(false), "admin");
    service.dispatchScheduled(Instant.parse("2027-01-01T00:00:00Z"));
    assertTrue(submitted.isEmpty());
    assertEquals(
        "BUSINESS_DATE_INVALID",
        assertThrows(
                IntegrationException.class,
                () -> service.run(task.id, new RunInput(false, "2099-01-01", false), "admin"))
            .code());
    assertTrue(submitted.isEmpty());
  }

  @Test
  void recentUncertainReservationIsNotSubmittedTwice() {
    var task = service.create(input(false), "admin");
    doThrow(new IntegrationException("SUBMISSION_UNKNOWN", 503))
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), eq(false));
    task = service.run(task.id, new RunInput(false, "2026-11-01", false), "admin");
    String jobId = task.latestRun.jobId;
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    task = service.run(task.id, new RunInput(false, null, false), "admin");
    assertEquals(jobId, task.latestRun.jobId);
    verify(engine, times(1)).submit(anyString(), anyString(), anyMap(), eq(false));
  }

  @Test
  void disabledUnknownSubmissionOnlyRefreshesAndNeverResubmits() {
    var task = service.create(input(false), "admin");
    doThrow(new IntegrationException("SUBMISSION_UNKNOWN", 503))
        .when(engine)
        .submit(anyString(), anyString(), anyMap(), eq(false));
    task = service.run(task.id, new RunInput(false, "2026-11-01", false), "admin");
    task.latestRun.submittedAt = System.currentTimeMillis() - 60_000;
    store.save(task);
    String reservedJobId = task.latestRun.jobId;
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    doNothing().when(engine).submit(anyString(), anyString(), anyMap(), eq(false));
    clearInvocations(engine);

    service.dispatchScheduled(Instant.now());

    verify(engine, atLeastOnce()).info(eq(reservedJobId), any(Duration.class));
    assertEquals(0L, submissionCount());
    IntegrationTask persisted = store.get(UUID.fromString(task.id));
    assertEquals("SUBMISSION_UNKNOWN", persisted.latestRun.status);
    assertEquals(reservedJobId, persisted.latestRun.jobId);
    assertEquals("2026-11-01", persisted.latestRun.businessDate);
  }

  @Test
  void reloadedFullTaskIsSkippedEvenWhenScanSnapshotStillSaysSql() throws Exception {
    IntegrationTask oldSql = service.create(input(true), "admin");
    IntegrationTask currentFull = IntegrationTask.from(IntegrationValidatorTest.validInput());
    currentFull.id = oldSql.id;
    currentFull.version = oldSql.version + 1;
    currentFull.latestRun = new RunSummary();
    currentFull.latestRun.jobId = "12345";
    currentFull.latestRun.status = "SUBMISSION_UNKNOWN";
    currentFull.latestRun.submittedAt = System.currentTimeMillis() - 60_000;
    currentFull.runs.add(currentFull.latestRun);
    IntegrationTaskStore concurrentStore = mock(IntegrationTaskStore.class);
    when(concurrentStore.list()).thenReturn(List.of(oldSql));
    when(concurrentStore.get(UUID.fromString(oldSql.id))).thenReturn(currentFull);
    inject("store", concurrentStore);
    clearInvocations(engine);

    service.dispatchScheduled(Instant.now());

    verify(engine, never()).info(anyString(), any(Duration.class));
    assertEquals(0L, submissionCount());
    verify(concurrentStore, never()).compareAndSave(any(IntegrationTask.class), anyLong());
  }

  @Test
  void threeRecentUncertainLeasesDoNotConsumeHealthyTaskSubmissionQuota() {
    when(jdbc.table(eq("doris-ods-validation"), eq("ods_validation"), anyString()))
        .thenAnswer(
            call ->
                new TableDefinition(
                    "ods_validation",
                    call.getArgument(2),
                    IntegrationValidatorTest.target().columns()));
    doNothing().when(engine).submit(anyString(), anyString(), anyMap(), eq(false));
    when(engine.info(anyString(), any(Duration.class)))
        .thenThrow(new IntegrationException("JOB_NOT_FOUND", 404));
    String healthyId = null;
    for (int index = 0; index < 4; index++) {
      TaskInput definition = input(true);
      definition.name += "-" + index;
      definition.targetTable += "_" + index;
      IntegrationTask task = IntegrationTask.from(definition);
      task.id = String.format("00000000-0000-0000-0000-%012d", index + 1);
      task.version = 1;
      task.scheduleState = new ScheduleState();
      task.scheduleState.nextRunAt = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli();
      if (index < 3) {
        RunSummary run = new RunSummary();
        run.jobId = String.valueOf(1000 + index);
        run.status = "SUBMISSION_UNKNOWN";
        run.submittedAt = System.currentTimeMillis();
        run.businessDate = "2026-11-10";
        run.windowStart = "2026-11-10 00:00:00";
        run.windowEnd = "2026-11-11 00:00:00";
        run.trigger = "SCHEDULED";
        run.scheduledFor = task.scheduleState.nextRunAt;
        task.scheduleState.pendingBusinessDate = run.businessDate;
        task.latestRun = run;
        task.runs.add(run);
      } else {
        healthyId = task.id;
      }
      store.save(task);
    }

    service.dispatchScheduled(Instant.parse("2026-11-12T00:00:00Z"));

    assertNotNull(store.get(UUID.fromString(healthyId)).latestRun);
    assertEquals(1L, submissionCount());
  }

  @Test
  void staleValidationErrorDoesNotBlockAConcurrentRepair() {
    IntegrationTask task = service.create(input(true), "admin");
    task.scheduleState.nextRunAt = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli();
    store.save(task);
    String id = task.id;
    long attemptedVersion = task.version;
    when(jdbc.query(
            any(IntegrationConfiguration.JdbcEndpoint.class), eq("public"), anyString(), any()))
        .thenAnswer(
            call -> {
              IntegrationTask repaired = store.get(UUID.fromString(id));
              repaired.sourceQuery =
                  repaired.sourceQuery.replace("public.patient", "public.patient_repaired");
              repaired.version++;
              store.save(repaired);
              throw new IntegrationException("TABLE_NOT_FOUND", 404);
            });

    service.dispatchScheduled(Instant.parse("2026-11-12T00:00:00Z"));

    IntegrationTask repaired = store.get(UUID.fromString(id));
    assertEquals(attemptedVersion + 1, repaired.version);
    assertTrue(repaired.sourceQuery.contains("public.patient_repaired"));
    assertNull(repaired.scheduleState.lastError);
    assertNull(repaired.latestRun);
    assertEquals(0L, submissionCount());
  }

  private long submissionCount() {
    return mockingDetails(engine).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals("submit"))
        .count();
  }

  @Test
  void atomicStoreRejectsAStaleReservation() {
    var task = service.create(input(false), "admin");
    var second = IntegrationModels.deserialize(IntegrationModels.serialize(task));
    task.version++;
    assertTrue(store.compareAndSave(task, 1));
    second.version++;
    assertFalse(store.compareAndSave(second, 1));
  }

  private TaskInput input(boolean enabled) {
    TaskInput task = IntegrationValidatorTest.validInput();
    task.mode = "SQL_T1";
    task.sourceTable = "sql_result";
    task.sourceQuery =
        "SELECT id, name FROM public.patient WHERE updated_at >= CAST(:window_start AS timestamp) AND updated_at < CAST(:window_end AS timestamp)";
    task.targetConnectionId = "doris-ods-validation";
    task.targetSchema = "ods_validation";
    task.targetTable = "daily_visits";
    task.schedule = new ScheduleConfig();
    task.schedule.enabled = enabled;
    return task;
  }

  private void inject(String name, Object value) throws Exception {
    Field field = IntegrationService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(service, value);
  }

  static final class MemoryStore implements IntegrationTaskStore {
    private final Map<UUID, String> data = new LinkedHashMap<>();

    public synchronized List<IntegrationTask> list() {
      return data.values().stream().map(IntegrationModels::deserialize).toList();
    }

    public synchronized IntegrationTask get(UUID id) {
      return IntegrationModels.deserialize(data.get(id));
    }

    public synchronized void save(IntegrationTask task) {
      data.put(UUID.fromString(task.id), IntegrationModels.serialize(task));
    }
  }
}
