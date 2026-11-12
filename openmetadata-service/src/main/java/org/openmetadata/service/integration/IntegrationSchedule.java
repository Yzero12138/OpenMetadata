package org.openmetadata.service.integration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;

public final class IntegrationSchedule {
  private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private IntegrationSchedule() {}

  public record Occurrence(
      String businessDate, String windowStart, String windowEnd, long scheduledFor) {}

  public static void validate(ScheduleConfig schedule) {
    if (schedule == null) {
      return;
    }
    if (schedule.dailyTime == null
        || !schedule.dailyTime.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
        || schedule.timeZone == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    try {
      ZoneId.of(schedule.timeZone);
    } catch (DateTimeException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public static Long initialize(ScheduleConfig schedule, Instant now) {
    validate(schedule);
    if (schedule == null || !schedule.enabled) {
      return null;
    }
    LocalDate today = now.atZone(ZoneId.of(schedule.timeZone)).toLocalDate();
    long candidate = scheduledAt(schedule, today);
    return candidate > now.toEpochMilli() ? candidate : scheduledAt(schedule, today.plusDays(1));
  }

  public static Optional<Occurrence> due(
      ScheduleConfig schedule, ScheduleState state, Instant now) {
    if (schedule == null
        || !schedule.enabled
        || state == null
        || state.nextRunAt == null
        || state.nextRunAt > now.toEpochMilli()
        || state.pendingBusinessDate != null
        || state.lastError != null) {
      return Optional.empty();
    }
    return Optional.of(occurrence(schedule, state.nextRunAt));
  }

  public static Occurrence occurrence(ScheduleConfig schedule, long scheduledFor) {
    validate(schedule);
    ZoneId zone = ZoneId.of(schedule.timeZone);
    LocalDate executionDate = Instant.ofEpochMilli(scheduledFor).atZone(zone).toLocalDate();
    LocalDate businessDate = executionDate.minusDays(1);
    return new Occurrence(
        businessDate.toString(),
        businessDate.atStartOfDay(zone).format(WINDOW),
        executionDate.atStartOfDay(zone).format(WINDOW),
        scheduledFor);
  }

  public static long next(ScheduleConfig schedule, long scheduledFor) {
    validate(schedule);
    LocalDate nextDate =
        Instant.ofEpochMilli(scheduledFor)
            .atZone(ZoneId.of(schedule.timeZone))
            .toLocalDate()
            .plusDays(1);
    return scheduledAt(schedule, nextDate);
  }

  private static long scheduledAt(ScheduleConfig schedule, LocalDate localDate) {
    return localDate
        .atTime(LocalTime.parse(schedule.dailyTime))
        .atZone(ZoneId.of(schedule.timeZone))
        .toInstant()
        .toEpochMilli();
  }
}
