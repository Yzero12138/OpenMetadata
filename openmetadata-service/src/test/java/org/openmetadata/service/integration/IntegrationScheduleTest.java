package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;

class IntegrationScheduleTest {
  @Test
  void firstRunIsStrictlyAfterTheCurrentLocalTime() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    assertEquals(
        millis("2027-01-01T18:00:00Z"),
        IntegrationSchedule.initialize(schedule, Instant.parse("2026-12-31T18:00:00Z")));
    assertEquals(
        millis("2026-12-31T18:00:00Z"),
        IntegrationSchedule.initialize(schedule, Instant.parse("2026-12-31T17:59:59Z")));
  }

  @Test
  void januaryFirstRunProcessesDecemberThirtyFirst() {
    var occurrence =
        IntegrationSchedule.occurrence(
            schedule("02:00", "Asia/Shanghai"), millis("2026-12-31T18:00:00Z"));
    assertEquals("2026-12-31", occurrence.businessDate());
    assertEquals("2026-12-31 00:00:00", occurrence.windowStart());
    assertEquals("2027-01-01 00:00:00", occurrence.windowEnd());
    assertEquals(millis("2026-12-31T18:00:00Z"), occurrence.scheduledFor());
  }

  @Test
  void latePollAfterMidnightKeepsThePersistedBusinessDate() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    ScheduleState state = state("2026-12-31T18:00:00Z");
    var occurrence =
        IntegrationSchedule.due(schedule, state, Instant.parse("2027-01-01T17:00:00Z"))
            .orElseThrow();
    assertEquals("2026-12-31", occurrence.businessDate());
    assertEquals(millis("2026-12-31T18:00:00Z"), state.nextRunAt);
  }

  @Test
  void missedDaysRemainSequentialAcrossARestart() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    ScheduleState state = state("2026-12-31T18:00:00Z");
    Instant restartedAt = Instant.parse("2027-01-04T10:00:00Z");
    for (String expected : new String[] {"2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03"}) {
      var occurrence = IntegrationSchedule.due(schedule, state, restartedAt).orElseThrow();
      assertEquals(expected, occurrence.businessDate());
      state.nextRunAt = IntegrationSchedule.next(schedule, occurrence.scheduledFor());
    }
    assertTrue(IntegrationSchedule.due(schedule, state, restartedAt).isEmpty());
  }

  @Test
  void pendingAndFailedDatesBlockAutomaticResubmission() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    ScheduleState state = state("2026-12-31T18:00:00Z");
    Instant now = Instant.parse("2027-01-04T10:00:00Z");
    state.pendingBusinessDate = "2026-12-31";
    assertTrue(IntegrationSchedule.due(schedule, state, now).isEmpty());
    state.pendingBusinessDate = null;
    state.lastError = "ENGINE_JOB_FAILED";
    assertTrue(IntegrationSchedule.due(schedule, state, now).isEmpty());
    assertEquals(
        "2026-12-31", IntegrationSchedule.occurrence(schedule, state.nextRunAt).businessDate());
    assertEquals(millis("2026-12-31T18:00:00Z"), state.nextRunAt);
  }

  @Test
  void aCursorIsDueAtItsExactScheduledInstant() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    ScheduleState state = state("2026-12-31T18:00:00Z");
    assertFalse(
        IntegrationSchedule.due(schedule, state, Instant.parse("2026-12-31T17:59:59Z"))
            .isPresent());
    assertTrue(
        IntegrationSchedule.due(schedule, state, Instant.parse("2026-12-31T18:00:00Z"))
            .isPresent());
  }

  @Test
  void disabledSchedulesHaveNoCursorAndReenableStartsInTheFuture() {
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    ScheduleState state = state("2026-12-31T18:00:00Z");
    Instant now = Instant.parse("2027-01-04T10:00:00Z");
    schedule.enabled = false;
    assertNull(IntegrationSchedule.initialize(schedule, now));
    assertTrue(IntegrationSchedule.due(schedule, state, now).isEmpty());
    schedule.enabled = true;
    assertEquals(millis("2027-01-04T18:00:00Z"), IntegrationSchedule.initialize(schedule, now));
  }

  @Test
  void absentOptionalScheduleAndStateAreNeverDue() {
    Instant now = Instant.parse("2027-01-04T10:00:00Z");
    assertNull(IntegrationSchedule.initialize(null, now));
    assertTrue(IntegrationSchedule.due(null, null, now).isEmpty());
    ScheduleConfig schedule = schedule("02:00", "Asia/Shanghai");
    assertTrue(IntegrationSchedule.due(schedule, null, now).isEmpty());
    assertTrue(IntegrationSchedule.due(schedule, new ScheduleState(), now).isEmpty());
  }

  @Test
  void eachTimezoneUsesItsOwnLocalDate() {
    var shanghai =
        IntegrationSchedule.occurrence(
            schedule("02:00", "Asia/Shanghai"), millis("2026-12-31T18:00:00Z"));
    var losAngeles =
        IntegrationSchedule.occurrence(
            schedule("02:00", "America/Los_Angeles"), millis("2026-12-31T18:00:00Z"));
    assertEquals("2026-12-31", shanghai.businessDate());
    assertEquals("2026-12-30", losAngeles.businessDate());
  }

  @Test
  void daylightSavingGapPreservesDailyLocalTimeAfterTransition() {
    ScheduleConfig schedule = schedule("02:30", "America/New_York");
    long transitionDay =
        IntegrationSchedule.initialize(schedule, Instant.parse("2027-03-13T12:00:00Z"));
    assertEquals(millis("2027-03-14T07:30:00Z"), transitionDay);
    assertEquals(millis("2027-03-15T06:30:00Z"), IntegrationSchedule.next(schedule, transitionDay));
    assertEquals(
        "2027-03-13", IntegrationSchedule.occurrence(schedule, transitionDay).businessDate());
  }

  @Test
  void daylightSavingOverlapExecutesOncePerLocalDate() {
    ScheduleConfig schedule = schedule("01:30", "America/New_York");
    long overlapDay =
        IntegrationSchedule.initialize(schedule, Instant.parse("2027-11-06T12:00:00Z"));
    assertEquals(millis("2027-11-07T05:30:00Z"), overlapDay);
    assertEquals(millis("2027-11-08T06:30:00Z"), IntegrationSchedule.next(schedule, overlapDay));
    assertEquals(
        millis("2027-11-08T06:30:00Z"),
        IntegrationSchedule.initialize(schedule, Instant.parse("2027-11-07T05:45:00Z")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2:00", "24:00", "02:60", "02:00:00", " 02:00", "", "-1:00"})
  void rejectsInvalidDailyTimes(String dailyTime) {
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () -> IntegrationSchedule.validate(schedule(dailyTime, "Asia/Shanghai")))
            .code());
  }

  @Test
  void rejectsMissingTimeAndInvalidTimezone() {
    assertThrows(
        IntegrationException.class,
        () -> IntegrationSchedule.validate(schedule(null, "Asia/Shanghai")));
    assertThrows(
        IntegrationException.class,
        () -> IntegrationSchedule.validate(schedule("02:00", "Asia/Unknown")));
    assertThrows(
        IntegrationException.class, () -> IntegrationSchedule.validate(schedule("02:00", null)));
  }

  private static ScheduleConfig schedule(String dailyTime, String timeZone) {
    ScheduleConfig schedule = new ScheduleConfig();
    schedule.enabled = true;
    schedule.dailyTime = dailyTime;
    schedule.timeZone = timeZone;
    return schedule;
  }

  private static ScheduleState state(String scheduledFor) {
    ScheduleState state = new ScheduleState();
    state.nextRunAt = millis(scheduledFor);
    return state;
  }

  private static long millis(String instant) {
    return Instant.parse(instant).toEpochMilli();
  }
}
