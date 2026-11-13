package org.openmetadata.service.integration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.InitializationConfig;
import org.openmetadata.service.integration.IntegrationModels.InitializationState;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;

public final class IntegrationInitialization {
  private static final LocalDate EARLIEST_DATE = LocalDate.of(2000, 1, 1);
  private static final DateTimeFormatter WINDOW =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private IntegrationInitialization() {}

  public record Batch(LocalDate start, LocalDate end, long index) {}

  public static void validate(InitializationConfig config, ScheduleConfig schedule, Instant now) {
    if (config == null || !config.enabled) {
      return;
    }
    if (schedule == null || !("DAY".equals(config.batchUnit) || "MONTH".equals(config.batchUnit))) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    IntegrationSchedule.validate(schedule);
    LocalDate start = parseDate(config.startDate, "INVALID_CONFIGURATION");
    LocalDate cutoff = today(schedule, now);
    if (start.isBefore(EARLIEST_DATE) || !start.isBefore(cutoff)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public static InitializationState create(
      InitializationConfig config, ScheduleConfig schedule, Instant now) {
    validate(config, schedule, now);
    if (config == null || !config.enabled) {
      return null;
    }
    LocalDate start = LocalDate.parse(config.startDate);
    LocalDate cutoff = today(schedule, now);
    InitializationState state = new InitializationState();
    state.id = UUID.randomUUID().toString();
    state.status = "PENDING";
    state.startDate = start.toString();
    state.cutoffDate = cutoff.toString();
    state.nextDate = start.toString();
    state.batchUnit = config.batchUnit;
    state.totalBatches =
        "DAY".equals(state.batchUnit)
            ? ChronoUnit.DAYS.between(start, cutoff)
            : ChronoUnit.MONTHS.between(
                    start.withDayOfMonth(1), cutoff.minusDays(1).withDayOfMonth(1))
                + 1;
    return state;
  }

  public static Optional<Batch> nextBatch(IntegrationTask task) {
    if (task == null
        || task.schedule == null
        || !task.schedule.enabled
        || task.initializationState == null
        || !"PENDING".equals(task.initializationState.status)) {
      return Optional.empty();
    }
    return plannedBatch(task.initializationState);
  }

  public static void reserve(IntegrationTask task, RunSummary run, Batch batch, Instant now) {
    if (run == null
        || run.jobId == null
        || run.jobId.isBlank()
        || !nextBatch(task).filter(candidate -> candidate.equals(batch)).isPresent()) {
      throw invalidState();
    }
    InitializationState state = task.initializationState;
    run.initializationId = state.id;
    run.batchIndex = batch.index();
    run.trigger = "INITIALIZATION";
    run.businessDate = batch.start().toString();
    run.windowStart = window(batch.start());
    run.windowEnd = window(batch.end());
    state.pendingStartDate = batch.start().toString();
    state.pendingEndDate = batch.end().toString();
    state.pendingJobId = run.jobId;
    state.status = "RUNNING";
    state.lastError = null;
    if (state.startedAt == null) {
      state.startedAt = now.toEpochMilli();
    }
  }

  public static boolean active(IntegrationTask task) {
    return task != null
        && task.initializationState != null
        && !"COMPLETED".equals(task.initializationState.status);
  }

  public static boolean started(InitializationState state) {
    return state != null
        && (state.startedAt != null || state.pendingJobId != null || state.completedBatches > 0);
  }

  public static boolean complete(IntegrationTask task, Instant now) {
    if (task == null
        || task.initializationState == null
        || !"RUNNING".equals(task.initializationState.status)
        || task.latestRun == null) {
      return false;
    }
    InitializationState state = task.initializationState;
    RunSummary run = task.latestRun;
    Batch batch = plannedBatch(state).orElseThrow(IntegrationInitialization::invalidState);
    if (!matches(state, run, batch)) {
      return false;
    }
    if ("FAILED".equals(run.status)
        || "CANCELED".equals(run.status)
        || "CANCELLED".equals(run.status)
        || "STOPPED".equals(run.status)
        || "SAVEPOINT_DONE".equals(run.status)) {
      state.status = "FAILED";
      state.lastError =
          run.errorCode == null || run.errorCode.isBlank() ? "ENGINE_JOB_FAILED" : run.errorCode;
      return true;
    }
    if (!"FINISHED".equals(run.status)) {
      return false;
    }
    boolean finished = batch.end().toString().equals(state.cutoffDate);
    ScheduleState continuation = finished ? dailyContinuation(task.schedule, batch.end()) : null;
    state.nextDate = batch.end().toString();
    state.completedBatches++;
    state.pendingStartDate = null;
    state.pendingEndDate = null;
    state.pendingJobId = null;
    state.lastError = null;
    state.status = finished ? "COMPLETED" : "PENDING";
    if (finished) {
      state.completedAt = now.toEpochMilli();
      task.scheduleState = continuation;
    }
    return true;
  }

  private static boolean matches(InitializationState state, RunSummary run, Batch batch) {
    return state.pendingJobId != null
        && !state.pendingJobId.isBlank()
        && state.pendingJobId.equals(run.jobId)
        && state.id.equals(run.initializationId)
        && run.batchIndex != null
        && run.batchIndex == batch.index()
        && "INITIALIZATION".equals(run.trigger)
        && batch.start().toString().equals(run.businessDate)
        && window(batch.start()).equals(run.windowStart)
        && window(batch.end()).equals(run.windowEnd);
  }

  private static ScheduleState dailyContinuation(ScheduleConfig schedule, LocalDate cutoff) {
    if (schedule == null) {
      throw invalidState();
    }
    IntegrationSchedule.validate(schedule);
    ScheduleState result = new ScheduleState();
    result.nextRunAt =
        cutoff
            .plusDays(1)
            .atTime(LocalTime.parse(schedule.dailyTime))
            .atZone(ZoneId.of(schedule.timeZone))
            .toInstant()
            .toEpochMilli();
    result.lastBusinessDate = cutoff.minusDays(1).toString();
    return result;
  }

  private static Optional<Batch> plannedBatch(InitializationState state) {
    LocalDate start = parseDate(state.startDate, "INITIALIZATION_STATE_INVALID");
    LocalDate cursor = parseDate(state.nextDate, "INITIALIZATION_STATE_INVALID");
    LocalDate cutoff = parseDate(state.cutoffDate, "INITIALIZATION_STATE_INVALID");
    if (state.id == null
        || state.id.isBlank()
        || !("DAY".equals(state.batchUnit) || "MONTH".equals(state.batchUnit))
        || !start.isBefore(cutoff)
        || cursor.isBefore(start)
        || cursor.isAfter(cutoff)
        || state.completedBatches < 0
        || state.completedBatches > state.totalBatches) {
      throw invalidState();
    }
    if (cursor.equals(cutoff)) {
      return Optional.empty();
    }
    if (state.completedBatches == state.totalBatches) {
      throw invalidState();
    }
    LocalDate next =
        "DAY".equals(state.batchUnit) ? cursor.plusDays(1) : cursor.withDayOfMonth(1).plusMonths(1);
    Batch batch =
        new Batch(cursor, next.isAfter(cutoff) ? cutoff : next, state.completedBatches + 1);
    if ((state.pendingStartDate != null
            || state.pendingEndDate != null
            || state.pendingJobId != null)
        && (!Objects.equals(state.pendingStartDate, batch.start().toString())
            || !Objects.equals(state.pendingEndDate, batch.end().toString()))) {
      throw invalidState();
    }
    return Optional.of(batch);
  }

  private static String window(LocalDate date) {
    return date.atStartOfDay().format(WINDOW);
  }

  private static IntegrationException invalidState() {
    return new IntegrationException("INITIALIZATION_STATE_INVALID", 409);
  }

  private static LocalDate today(ScheduleConfig schedule, Instant now) {
    return now.atZone(ZoneId.of(schedule.timeZone)).toLocalDate();
  }

  private static LocalDate parseDate(String value, String errorCode) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw new IntegrationException(errorCode, 400);
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException e) {
      throw new IntegrationException(errorCode, 400);
    }
  }
}
