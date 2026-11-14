package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.service.integration.IntegrationModels.InitializationConfig;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;

class IntegrationInitializationTest {
  private static final Instant CREATED_AT = Instant.parse("2026-11-13T12:00:00Z");

  @Test
  void absentAndDisabledConfigurationsDoNotCreateInitializationState() {
    InitializationConfig config = config("invalid", "unknown");
    config.enabled = false;
    IntegrationInitialization.validate(null, null, CREATED_AT);
    IntegrationInitialization.validate(config, null, CREATED_AT);
    assertNull(IntegrationInitialization.create(null, null, CREATED_AT));
    assertNull(IntegrationInitialization.create(config, null, CREATED_AT));
  }

  @Test
  void creationFreezesTheConfiguredTimezoneCutoffAndInitialProgress() {
    Instant beforeUtcMidnight = Instant.parse("2026-11-13T17:00:00Z");
    var state =
        IntegrationInitialization.create(
            config("2026-11-10", "DAY"), schedule(), beforeUtcMidnight);
    assertNotNull(state);
    assertNotNull(state.id);
    assertEquals("PENDING", state.status);
    assertEquals("2026-11-10", state.startDate);
    assertEquals("2026-11-14", state.cutoffDate);
    assertEquals("2026-11-10", state.nextDate);
    assertEquals("DAY", state.batchUnit);
    assertEquals(0, state.completedBatches);
    assertEquals(4, state.totalBatches);
    assertNull(state.pendingJobId);
    assertNull(state.pendingStartDate);
    assertNull(state.pendingEndDate);
    assertNull(state.startedAt);
    assertNull(state.completedAt);
    assertNull(state.lastError);
    assertNotEquals(
        state.id,
        IntegrationInitialization.create(config("2026-11-10", "DAY"), schedule(), beforeUtcMidnight)
            .id);

    ScheduleConfig utc = schedule();
    utc.timeZone = "UTC";
    assertEquals(
        "2026-11-13",
        IntegrationInitialization.create(config("2026-11-10", "DAY"), utc, beforeUtcMidnight)
            .cutoffDate);
  }

  @Test
  void dayBatchCountIncludesLeapDay() {
    var state =
        IntegrationInitialization.create(
            config("2024-02-28", "DAY"), schedule(), Instant.parse("2024-03-01T12:00:00Z"));
    assertEquals(2, state.totalBatches);
    assertEquals("2024-03-01", state.cutoffDate);
  }

  @Test
  void monthlyBatchCountIncludesPartialFirstAndLastMonthsAcrossAYearBoundary() {
    var state =
        IntegrationInitialization.create(
            config("2023-12-31", "MONTH"), schedule(), Instant.parse("2024-03-15T12:00:00Z"));
    assertEquals(4, state.totalBatches);
    assertEquals("MONTH", state.batchUnit);
  }

  @Test
  void cutoffOnTheFirstOfAMonthDoesNotAddAnEmptyBatch() {
    var state =
        IntegrationInitialization.create(
            config("2024-01-31", "MONTH"), schedule(), Instant.parse("2024-03-01T12:00:00Z"));
    assertEquals(2, state.totalBatches);
  }

  @Test
  void initializationAcceptsPausedButOtherwiseValidDailySchedules() {
    ScheduleConfig schedule = schedule();
    schedule.enabled = false;
    assertEquals(
        "PENDING",
        IntegrationInitialization.create(config("2026-11-12", "DAY"), schedule, CREATED_AT).status);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1999-12-31",
        "2026-11-13",
        "2026-11-14",
        "2026-2-01",
        "2026-02-29",
        "2026-13-01",
        "2026-11-12T00:00:00",
        " 2026-11-12",
        "+10000-01-01",
        ""
      })
  void rejectsDatesOutsideTheSupportedHistoryInterval(String startDate) {
    assertInvalid(config(startDate, "DAY"), schedule());
  }

  @Test
  void earliestSupportedDateAndDefaultDailyUnitAreAccepted() {
    InitializationConfig config = new InitializationConfig();
    config.enabled = true;
    config.startDate = "2000-01-01";
    assertEquals("DAY", IntegrationInitialization.create(config, schedule(), CREATED_AT).batchUnit);
  }

  @Test
  void rejectsMissingStartDateAndUnsupportedBatchUnits() {
    assertInvalid(config(null, "DAY"), schedule());
    assertInvalid(config("2026-11-12", null), schedule());
    assertInvalid(config("2026-11-12", "WEEK"), schedule());
    assertInvalid(config("2026-11-12", "day"), schedule());
  }

  @Test
  void enabledInitializationRequiresAValidDailySchedule() {
    InitializationConfig config = config("2026-11-12", "DAY");
    assertInvalid(config, null);
    ScheduleConfig schedule = schedule();
    schedule.dailyTime = "25:00";
    assertInvalid(config, schedule);
    schedule = schedule();
    schedule.timeZone = "Asia/Unknown";
    assertInvalid(config, schedule);
  }

  @Test
  void dailyBatchesCoverLeapDayWithoutGaps() {
    IntegrationTask task = task("2024-02-28", "DAY", "2024-03-01T12:00:00Z");
    assertBatch(task, "2024-02-28", "2024-02-29", 1);
    moveCursor(task, "2024-02-29", 1);
    assertBatch(task, "2024-02-29", "2024-03-01", 2);
    moveCursor(task, "2024-03-01", 2);
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
  }

  @Test
  void monthlyBatchesClampNaturalMonthBoundariesAcrossYearsAndPartialMonths() {
    IntegrationTask task = task("2023-12-31", "MONTH", "2024-03-15T12:00:00Z");
    List<String> dates =
        List.of("2023-12-31", "2024-01-01", "2024-02-01", "2024-03-01", "2024-03-15");
    for (int i = 0; i < dates.size() - 1; i++) {
      assertBatch(task, dates.get(i), dates.get(i + 1), i + 1);
      moveCursor(task, dates.get(i + 1), i + 1);
    }
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
  }

  @Test
  void monthlyStartOnMonthEndDoesNotSkipTheNextPartialMonth() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-02-29T12:00:00Z");
    assertBatch(task, "2024-01-31", "2024-02-01", 1);
    moveCursor(task, "2024-02-01", 1);
    assertBatch(task, "2024-02-01", "2024-02-29", 2);
  }

  @Test
  void batchPlanningUsesPersistedCutoffInsteadOfEditedConfigurationOrClock() {
    IntegrationTask task = task("2026-11-10", "MONTH", "2026-11-13T12:00:00Z");
    task.initialization.startDate = "2020-01-01";
    task.initialization.batchUnit = "DAY";
    task.schedule.timeZone = "UTC";
    assertBatch(task, "2026-11-10", "2026-11-13", 1);
    assertEquals("2026-11-13", task.initializationState.cutoffDate);
  }

  @Test
  void nextBatchIsAvailableOnlyForEnabledPendingInitialization() {
    assertTrue(IntegrationInitialization.nextBatch(null).isEmpty());
    assertTrue(IntegrationInitialization.nextBatch(new IntegrationTask()).isEmpty());
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    task.schedule.enabled = false;
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    task.schedule.enabled = true;
    for (String status : List.of("RUNNING", "FAILED", "COMPLETED")) {
      task.initializationState.status = status;
      assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    }
    task.initializationState.status = "PENDING";
    assertBatch(task, "2026-11-10", "2026-11-11", 1);
    task.schedule = null;
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
  }

  @Test
  void resumedBatchRetainsItsOriginalRangeAndIndex() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    task.initializationState.pendingStartDate = "2024-01-31";
    task.initializationState.pendingEndDate = "2024-02-01";
    task.initializationState.pendingJobId = "failed-job";
    task.initializationState.lastError = "ENGINE_JOB_FAILED";
    assertBatch(task, "2024-01-31", "2024-02-01", 1);
    assertEquals("failed-job", task.initializationState.pendingJobId);
    assertEquals("ENGINE_JOB_FAILED", task.initializationState.lastError);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2024-01-30", "2024-02-02", "2024-04-01", "invalid"})
  void rejectsPendingRangesThatWouldSkipOrRepeatHistoricalDates(String pendingEnd) {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    task.initializationState.pendingStartDate = "2024-01-31";
    task.initializationState.pendingEndDate = pendingEnd;
    assertInvalidState(() -> IntegrationInitialization.nextBatch(task));
  }

  @Test
  void rejectsAnIncompleteOrShiftedPendingRange() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    task.initializationState.pendingStartDate = "2024-01-30";
    task.initializationState.pendingEndDate = "2024-02-01";
    assertInvalidState(() -> IntegrationInitialization.nextBatch(task));
    task.initializationState.pendingStartDate = "2024-01-31";
    task.initializationState.pendingEndDate = null;
    assertInvalidState(() -> IntegrationInitialization.nextBatch(task));
  }

  @Test
  void reservationAssociatesTheRunAndPendingStateWithoutPublishingTheRun() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    var batch = IntegrationInitialization.nextBatch(task).orElseThrow();
    RunSummary run = run("job-1", "SUBMITTING");
    IntegrationInitialization.reserve(task, run, batch, CREATED_AT);
    assertEquals(task.initializationState.id, run.initializationId);
    assertEquals(1L, run.batchIndex);
    assertEquals("INITIALIZATION", run.trigger);
    assertEquals("2024-01-31", run.businessDate);
    assertEquals("2024-01-31 00:00:00", run.windowStart);
    assertEquals("2024-02-01 00:00:00", run.windowEnd);
    assertEquals("SUBMITTING", run.status);
    assertEquals("RUNNING", task.initializationState.status);
    assertEquals("job-1", task.initializationState.pendingJobId);
    assertEquals("2024-01-31", task.initializationState.pendingStartDate);
    assertEquals("2024-02-01", task.initializationState.pendingEndDate);
    assertEquals(CREATED_AT.toEpochMilli(), task.initializationState.startedAt);
    assertEquals(0, task.initializationState.completedBatches);
    assertEquals("2024-01-31", task.initializationState.nextDate);
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    assertNull(task.latestRun);
    assertTrue(task.runs.isEmpty());
  }

  @Test
  void retryReservationReplacesThePendingJobWithoutChangingFirstStartTime() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    var batch = IntegrationInitialization.nextBatch(task).orElseThrow();
    IntegrationInitialization.reserve(task, run("first-job", "FAILED"), batch, CREATED_AT);
    task.initializationState.status = "PENDING";
    task.initializationState.lastError = "ENGINE_JOB_FAILED";
    Instant later = Instant.parse("2027-04-01T12:00:00Z");
    RunSummary retry = run("retry-job", "SUBMITTING");
    IntegrationInitialization.reserve(
        task, retry, IntegrationInitialization.nextBatch(task).orElseThrow(), later);
    assertEquals(CREATED_AT.toEpochMilli(), task.initializationState.startedAt);
    assertEquals("2024-03-15", task.initializationState.cutoffDate);
    assertEquals("retry-job", task.initializationState.pendingJobId);
    assertEquals("2024-01-31 00:00:00", retry.windowStart);
    assertEquals("2024-02-01 00:00:00", retry.windowEnd);
    assertEquals(1L, retry.batchIndex);
    assertNull(task.initializationState.lastError);
  }

  @Test
  void reservationRejectsAnUnplannedBatchOrMissingJobId() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    var batch = IntegrationInitialization.nextBatch(task).orElseThrow();
    assertInvalidState(
        () -> IntegrationInitialization.reserve(task, run(null, "SUBMITTING"), batch, CREATED_AT));
    var wrongBatch =
        new IntegrationInitialization.Batch(batch.start(), batch.end().plusDays(1), batch.index());
    assertInvalidState(
        () ->
            IntegrationInitialization.reserve(
                task, run("job-1", "SUBMITTING"), wrongBatch, CREATED_AT));
    assertEquals("PENDING", task.initializationState.status);
    assertNull(task.initializationState.startedAt);
  }

  @Test
  void initializationRemainsActiveUntilCompletedAndStartEvidenceIsPersistent() {
    assertFalse(IntegrationInitialization.active(null));
    assertFalse(IntegrationInitialization.active(new IntegrationTask()));
    assertFalse(IntegrationInitialization.started(null));
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    assertTrue(IntegrationInitialization.active(task));
    assertFalse(IntegrationInitialization.started(task.initializationState));
    task.initializationState.status = "FAILED";
    task.initializationState.pendingJobId = "unknown-job";
    assertTrue(IntegrationInitialization.active(task));
    assertTrue(IntegrationInitialization.started(task.initializationState));
    task.initializationState.pendingJobId = null;
    task.initializationState.completedBatches = 1;
    assertTrue(IntegrationInitialization.started(task.initializationState));
    task.initializationState.completedBatches = 0;
    task.initializationState.startedAt = CREATED_AT.toEpochMilli();
    assertTrue(IntegrationInitialization.started(task.initializationState));
    task.initializationState.status = "COMPLETED";
    assertFalse(IntegrationInitialization.active(task));
    assertTrue(IntegrationInitialization.started(task.initializationState));
  }

  @Test
  void finishedZeroRowBatchAdvancesExactlyOnceAndClearsItsPendingContext() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    RunSummary run = submit(task, "first-job", "FINISHED", CREATED_AT);
    run.sourceReceivedCount = 0L;
    run.sinkWriteCount = 0L;
    task.initializationState.lastError = "stale-error";
    assertTrue(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(30)));
    assertEquals("PENDING", task.initializationState.status);
    assertEquals("2026-11-11", task.initializationState.nextDate);
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals(3, task.initializationState.totalBatches);
    assertNull(task.initializationState.pendingJobId);
    assertNull(task.initializationState.pendingStartDate);
    assertNull(task.initializationState.pendingEndDate);
    assertNull(task.initializationState.lastError);
    assertNull(task.initializationState.completedAt);
    assertNull(task.scheduleState);
    String settled = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(60)));
    assertEquals(settled, IntegrationModels.serialize(task));
    assertBatch(task, "2026-11-11", "2026-11-12", 2);
  }

  @Test
  void finalBatchHandsOffToTheOriginalNextBusinessDateEvenWhenItIsOverdue() {
    IntegrationTask task = task("2026-11-12", "DAY", CREATED_AT.toString());
    ScheduleState oldSchedule = new ScheduleState();
    oldSchedule.nextRunAt = Instant.parse("2027-01-01T18:00:00Z").toEpochMilli();
    oldSchedule.lastBusinessDate = "2027-01-01";
    oldSchedule.pendingBusinessDate = "2027-01-02";
    oldSchedule.lastError = "ENGINE_JOB_FAILED";
    task.scheduleState = oldSchedule;
    submit(task, "final-job", "FINISHED", CREATED_AT);
    Instant completedAt = Instant.parse("2026-11-20T12:00:00Z");
    assertTrue(IntegrationInitialization.complete(task, completedAt));
    assertEquals("COMPLETED", task.initializationState.status);
    assertEquals("2026-11-13", task.initializationState.nextDate);
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals(completedAt.toEpochMilli(), task.initializationState.completedAt);
    assertEquals("2026-11-12", task.scheduleState.lastBusinessDate);
    assertEquals(
        Instant.parse("2026-11-13T18:00:00Z").toEpochMilli(), task.scheduleState.nextRunAt);
    assertNull(task.scheduleState.pendingBusinessDate);
    assertNull(task.scheduleState.lastError);
    assertFalse(IntegrationInitialization.active(task));
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    String settled = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, completedAt.plusSeconds(60)));
    assertEquals(settled, IntegrationModels.serialize(task));
    for (String expected :
        List.of(
            "2026-11-13",
            "2026-11-14",
            "2026-11-15",
            "2026-11-16",
            "2026-11-17",
            "2026-11-18",
            "2026-11-19")) {
      var occurrence =
          IntegrationSchedule.due(task.schedule, task.scheduleState, completedAt).orElseThrow();
      assertEquals(expected, occurrence.businessDate());
      task.scheduleState.nextRunAt =
          IntegrationSchedule.next(task.schedule, occurrence.scheduledFor());
    }
    assertTrue(IntegrationSchedule.due(task.schedule, task.scheduleState, completedAt).isEmpty());
  }

  @Test
  void pausedCurrentBatchMayFinishButCannotSubmitTheNextBatch() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    submit(task, "paused-job", "FINISHED", CREATED_AT);
    task.schedule.enabled = false;
    assertTrue(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(10)));
    assertEquals("PENDING", task.initializationState.status);
    assertEquals(1, task.initializationState.completedBatches);
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    task.schedule.enabled = true;
    assertBatch(task, "2026-11-11", "2026-11-12", 2);
  }

  @Test
  void pausedFinalBatchSeedsTheSameDailyCursorForLaterContinuation() {
    IntegrationTask task = task("2026-11-12", "DAY", CREATED_AT.toString());
    submit(task, "paused-final-job", "FINISHED", CREATED_AT);
    task.schedule.enabled = false;
    Instant later = Instant.parse("2026-12-01T12:00:00Z");
    assertTrue(IntegrationInitialization.complete(task, later));
    assertEquals("COMPLETED", task.initializationState.status);
    assertTrue(IntegrationSchedule.due(task.schedule, task.scheduleState, later).isEmpty());
    task.schedule.enabled = true;
    assertEquals(
        "2026-11-13",
        IntegrationSchedule.due(task.schedule, task.scheduleState, later)
            .orElseThrow()
            .businessDate());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "CANCELED", "CANCELLED", "STOPPED", "SAVEPOINT_DONE"})
  void unsuccessfulTerminalBatchesPreserveTheCursorAndFailedRange(String status) {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    RunSummary run = submit(task, "failed-job", status, CREATED_AT);
    run.errorCode = "JOB_NOT_FOUND";
    assertTrue(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(30)));
    assertEquals("FAILED", task.initializationState.status);
    assertEquals("JOB_NOT_FOUND", task.initializationState.lastError);
    assertEquals("2024-01-31", task.initializationState.nextDate);
    assertEquals("2024-01-31", task.initializationState.pendingStartDate);
    assertEquals("2024-02-01", task.initializationState.pendingEndDate);
    assertEquals("failed-job", task.initializationState.pendingJobId);
    assertEquals(0, task.initializationState.completedBatches);
    assertNull(task.initializationState.completedAt);
    assertNull(task.scheduleState);
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    String failed = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(60)));
    assertEquals(failed, IntegrationModels.serialize(task));
  }

  @Test
  void failedBatchWithoutAnEngineCodeGetsAStableErrorCode() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    submit(task, "failed-job", "FAILED", CREATED_AT);
    assertTrue(IntegrationInitialization.complete(task, CREATED_AT));
    assertEquals("ENGINE_JOB_FAILED", task.initializationState.lastError);
  }

  @Test
  void failureContinuationRetriesTheSameBatchAfterTheClockHasAdvanced() {
    IntegrationTask task = task("2024-01-31", "MONTH", "2024-03-15T12:00:00Z");
    submit(task, "failed-job", "FAILED", CREATED_AT);
    assertTrue(IntegrationInitialization.complete(task, CREATED_AT));
    task.initializationState.status = "PENDING";
    Instant continuedAt = Instant.parse("2027-04-01T12:00:00Z");
    String awaitingRetry = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, continuedAt));
    assertEquals(awaitingRetry, IntegrationModels.serialize(task));
    RunSummary retry = submit(task, "retry-job", "FINISHED", continuedAt);
    assertEquals("2024-01-31 00:00:00", retry.windowStart);
    assertEquals("2024-02-01 00:00:00", retry.windowEnd);
    assertEquals(1L, retry.batchIndex);
    assertTrue(IntegrationInitialization.complete(task, continuedAt.plusSeconds(30)));
    assertEquals("2024-02-01", task.initializationState.nextDate);
    assertEquals("2024-03-15", task.initializationState.cutoffDate);
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals(CREATED_AT.toEpochMilli(), task.initializationState.startedAt);
    assertBatch(task, "2024-02-01", "2024-03-01", 2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUBMITTING", "SUBMISSION_UNKNOWN", "SUBMITTED", "RUNNING", "UNKNOWN"})
  void nonterminalLatestRunsBlockCompletionEvenWithAnOlderTerminalHistorySnapshot(String status) {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    RunSummary run = submit(task, "uncertain-job", status, CREATED_AT);
    RunSummary staleTerminal = copyContext(run, "FINISHED");
    task.runs.add(staleTerminal);
    String before = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(30)));
    assertEquals(before, IntegrationModels.serialize(task));
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
    assertEquals("uncertain-job", task.initializationState.pendingJobId);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jobId",
        "initializationId",
        "batchIndex",
        "missingBatchIndex",
        "businessDate",
        "windowStart",
        "windowEnd",
        "trigger"
      })
  void terminalRunMustMatchEveryPartOfTheReservedBatchContext(String changedField) {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    RunSummary run = submit(task, "current-job", "FINISHED", CREATED_AT);
    task.runs.add(copyContext(run, "FINISHED"));
    switch (changedField) {
      case "jobId" -> run.jobId = "other-job";
      case "initializationId" -> run.initializationId = "other-initialization";
      case "batchIndex" -> run.batchIndex = 2L;
      case "missingBatchIndex" -> run.batchIndex = null;
      case "businessDate" -> run.businessDate = "2026-11-09";
      case "windowStart" -> run.windowStart = "2026-11-09 00:00:00";
      case "windowEnd" -> run.windowEnd = "2026-11-12 00:00:00";
      case "trigger" -> run.trigger = "MANUAL";
      default -> throw new AssertionError(changedField);
    }
    String before = IntegrationModels.serialize(task);
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(30)));
    assertEquals(before, IntegrationModels.serialize(task));
  }

  @Test
  void historyAloneCannotSettleAPendingBatch() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    submit(task, "current-job", "FINISHED", CREATED_AT);
    task.latestRun = null;
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT));
    assertEquals("RUNNING", task.initializationState.status);
    assertEquals(0, task.initializationState.completedBatches);
  }

  @Test
  void completedProgressSurvivesMoreBatchesThanTheRetainedRunHistoryAndARestart() {
    IntegrationTask task = task("2026-10-14", "DAY", CREATED_AT.toString());
    assertEquals(30, task.initializationState.totalBatches);
    for (int i = 1; i <= 30; i++) {
      RunSummary run = submit(task, "job-" + i, "FINISHED", CREATED_AT.plusSeconds(i));
      assertEquals((long) i, run.batchIndex);
      while (task.runs.size() > 20) {
        task.runs.remove(task.runs.size() - 1);
      }
      assertTrue(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(i + 1)));
      assertFalse(IntegrationInitialization.complete(task, CREATED_AT.plusSeconds(i + 2)));
      assertEquals(i, task.initializationState.completedBatches);
      if (i == 15) {
        task = IntegrationModels.deserialize(IntegrationModels.serialize(task));
      }
    }
    assertEquals("COMPLETED", task.initializationState.status);
    assertEquals("2026-11-13", task.initializationState.nextDate);
    assertEquals(20, task.runs.size());
    assertEquals("job-30", task.latestRun.jobId);
    assertEquals(30, task.initializationState.completedBatches);
    assertTrue(IntegrationInitialization.nextBatch(task).isEmpty());
  }

  @Test
  void corruptPendingRangeCannotBeConsumedEvenByATerminalRun() {
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    submit(task, "current-job", "FINISHED", CREATED_AT);
    task.initializationState.pendingEndDate = "2026-11-12";
    String before = IntegrationModels.serialize(task);
    assertInvalidState(() -> IntegrationInitialization.complete(task, CREATED_AT));
    assertEquals(before, IntegrationModels.serialize(task));
  }

  @Test
  void completionWithoutAnActiveReservationDoesNothing() {
    assertFalse(IntegrationInitialization.complete(null, CREATED_AT));
    assertFalse(IntegrationInitialization.complete(new IntegrationTask(), CREATED_AT));
    IntegrationTask task = task("2026-11-10", "DAY", CREATED_AT.toString());
    assertFalse(IntegrationInitialization.complete(task, CREATED_AT));
  }

  private static RunSummary copyContext(RunSummary original, String status) {
    RunSummary copy = run(original.jobId, status);
    copy.initializationId = original.initializationId;
    copy.batchIndex = original.batchIndex;
    copy.trigger = original.trigger;
    copy.businessDate = original.businessDate;
    copy.windowStart = original.windowStart;
    copy.windowEnd = original.windowEnd;
    return copy;
  }

  private static RunSummary submit(
      IntegrationTask task, String jobId, String status, Instant submittedAt) {
    RunSummary run = run(jobId, status);
    IntegrationInitialization.reserve(
        task, run, IntegrationInitialization.nextBatch(task).orElseThrow(), submittedAt);
    task.latestRun = run;
    task.runs.add(0, run);
    return run;
  }

  private static void assertInvalidState(Runnable action) {
    assertEquals(
        "INITIALIZATION_STATE_INVALID",
        assertThrows(IntegrationException.class, action::run).code());
  }

  private static void assertBatch(IntegrationTask task, String start, String end, long index) {
    var batch = IntegrationInitialization.nextBatch(task).orElseThrow();
    assertEquals(LocalDate.parse(start), batch.start());
    assertEquals(LocalDate.parse(end), batch.end());
    assertEquals(index, batch.index());
  }

  private static void moveCursor(IntegrationTask task, String nextDate, long completedBatches) {
    task.initializationState.nextDate = nextDate;
    task.initializationState.completedBatches = completedBatches;
  }

  private static RunSummary run(String jobId, String status) {
    RunSummary run = new RunSummary();
    run.jobId = jobId;
    run.status = status;
    return run;
  }

  private static IntegrationTask task(String startDate, String batchUnit, String now) {
    IntegrationTask task = new IntegrationTask();
    task.schedule = schedule();
    task.initialization = config(startDate, batchUnit);
    task.initializationState =
        IntegrationInitialization.create(task.initialization, task.schedule, Instant.parse(now));
    return task;
  }

  private static void assertInvalid(InitializationConfig config, ScheduleConfig schedule) {
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () -> IntegrationInitialization.validate(config, schedule, CREATED_AT))
            .code());
  }

  private static InitializationConfig config(String startDate, String batchUnit) {
    InitializationConfig config = new InitializationConfig();
    config.enabled = true;
    config.startDate = startDate;
    config.batchUnit = batchUnit;
    return config;
  }

  private static ScheduleConfig schedule() {
    ScheduleConfig schedule = new ScheduleConfig();
    schedule.enabled = true;
    return schedule;
  }
}
