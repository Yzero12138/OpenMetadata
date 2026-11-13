package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.apps.scheduler.AppScheduler;
import org.openmetadata.service.apps.scheduler.CustomJobFactory;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;

class HospitalIntegrationSchedulerTest {
  private Scheduler scheduler;

  @BeforeEach
  void createScheduler() throws Exception {
    Properties properties = new Properties();
    properties.setProperty(
        "org.quartz.scheduler.instanceName", "hospital-test-" + UUID.randomUUID());
    properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
    properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
    properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
    scheduler = new StdSchedulerFactory(properties).getScheduler();
  }

  @AfterEach
  void shutDownScheduler() throws Exception {
    scheduler.shutdown();
  }

  @Test
  void disabledOrIncompleteConfigurationDoesNotRegisterAnyJob() throws Exception {
    HospitalIntegrationScheduler.register(scheduler, IntegrationConfiguration.fromValues(Map.of()));
    HospitalIntegrationScheduler.register(
        scheduler,
        IntegrationConfiguration.fromValues(Map.of("HOSPITAL_INTEGRATION_ENABLED", "true")));
    assertTrue(scheduler.getJobKeys(GroupMatcher.anyJobGroup()).isEmpty());
    assertTrue(scheduler.getTriggerKeys(GroupMatcher.anyTriggerGroup()).isEmpty());
  }

  @Test
  void registersOneNonconcurrentDispatcherInItsOwnGroup() throws Exception {
    HospitalIntegrationScheduler.register(scheduler, configuration());
    JobDetail job = scheduler.getJobDetail(HospitalIntegrationScheduler.JOB_KEY);
    assertEquals(HospitalIntegrationSchedulerJob.class, job.getJobClass());
    assertTrue(job.isConcurrentExecutionDisallowed());
    assertTrue(job.isDurable());
    assertTrue(job.getJobDataMap().isEmpty());
    assertEquals("HOSPITAL_INTEGRATION", job.getKey().getGroup());
    assertNotEquals(AppScheduler.APPS_JOB_GROUP, job.getKey().getGroup());
    assertFalse(scheduler.isStarted());
    SimpleTrigger trigger =
        (SimpleTrigger) scheduler.getTrigger(HospitalIntegrationScheduler.TRIGGER_KEY);
    assertEquals(job.getKey(), trigger.getJobKey());
    assertEquals(60_000, trigger.getRepeatInterval());
    assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.getRepeatCount());
    assertEquals(
        SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
        trigger.getMisfireInstruction());
  }

  @Test
  void theSharedJobFactoryCanConstructTheDispatcher() throws Exception {
    HospitalIntegrationScheduler.register(scheduler, configuration());
    JobDetail job = scheduler.getJobDetail(HospitalIntegrationScheduler.JOB_KEY);
    OperableTrigger trigger =
        (OperableTrigger) scheduler.getTrigger(HospitalIntegrationScheduler.TRIGGER_KEY);
    Date fireTime = trigger.getStartTime();
    TriggerFiredBundle bundle =
        new TriggerFiredBundle(job, trigger, null, false, fireTime, fireTime, null, null);
    assertInstanceOf(
        HospitalIntegrationSchedulerJob.class,
        new CustomJobFactory(null, null).newJob(bundle, scheduler));
  }

  @Test
  void repeatedRegistrationKeepsThePersistedTriggerAndNextFireTime() throws Exception {
    HospitalIntegrationScheduler.register(scheduler, configuration());
    Trigger original = scheduler.getTrigger(HospitalIntegrationScheduler.TRIGGER_KEY);
    scheduler.rescheduleJob(
        original.getKey(),
        original.getTriggerBuilder().startAt(Date.from(Instant.now().plusSeconds(86_400))).build());
    Trigger before = scheduler.getTrigger(HospitalIntegrationScheduler.TRIGGER_KEY);
    HospitalIntegrationScheduler.register(scheduler, configuration());
    Trigger after = scheduler.getTrigger(HospitalIntegrationScheduler.TRIGGER_KEY);
    assertEquals(before.getStartTime(), after.getStartTime());
    assertEquals(before.getNextFireTime(), after.getNextFireTime());
    assertEquals(1, scheduler.getJobKeys(GroupMatcher.anyJobGroup()).size());
    assertEquals(1, scheduler.getTriggerKeys(GroupMatcher.anyTriggerGroup()).size());
  }

  @Test
  void recoversAnInterruptedRegistrationWithADurableJobButNoTrigger() throws Exception {
    HospitalIntegrationScheduler.register(scheduler, configuration());
    scheduler.unscheduleJob(HospitalIntegrationScheduler.TRIGGER_KEY);
    assertTrue(scheduler.checkExists(HospitalIntegrationScheduler.JOB_KEY));
    HospitalIntegrationScheduler.register(scheduler, configuration());
    assertEquals(1, scheduler.getTriggersOfJob(HospitalIntegrationScheduler.JOB_KEY).size());
  }

  @Test
  void concurrentNodeRegistrationsLeaveOneJobAndTrigger() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var registrations =
          List.of(
              executor.submit(
                  () -> {
                    start.await();
                    HospitalIntegrationScheduler.register(scheduler, configuration());
                    return null;
                  }),
              executor.submit(
                  () -> {
                    start.await();
                    HospitalIntegrationScheduler.register(scheduler, configuration());
                    return null;
                  }));
      start.countDown();
      for (var registration : registrations) {
        registration.get(5, TimeUnit.SECONDS);
      }
    }
    assertEquals(1, scheduler.getJobKeys(GroupMatcher.anyJobGroup()).size());
    assertEquals(1, scheduler.getTriggerKeys(GroupMatcher.anyTriggerGroup()).size());
  }

  private static IntegrationConfiguration configuration() {
    return IntegrationConfiguration.fromValues(
        Map.of(
            "HOSPITAL_INTEGRATION_ENABLED", "true",
            "HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801",
            "HOSPITAL_SOURCE_JDBC_URL", "jdbc:postgresql://source:5432/source_db",
            "HOSPITAL_SOURCE_JDBC_USER", "source_user",
            "HOSPITAL_SOURCE_JDBC_PASSWORD", "test-source-password",
            "HOSPITAL_TARGET_JDBC_URL", "jdbc:postgresql://target:5432/target_db",
            "HOSPITAL_TARGET_JDBC_USER", "target_user",
            "HOSPITAL_TARGET_JDBC_PASSWORD", "test-target-password"));
  }
}
