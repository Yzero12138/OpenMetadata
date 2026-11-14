package org.openmetadata.service.integration;

import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;

public final class HospitalIntegrationScheduler {
  public static final String GROUP = "HOSPITAL_INTEGRATION";
  public static final JobKey JOB_KEY = new JobKey("daily-sql-dispatcher", GROUP);
  public static final TriggerKey TRIGGER_KEY = new TriggerKey("daily-sql-dispatcher", GROUP);

  private HospitalIntegrationScheduler() {}

  public static void register(Scheduler scheduler, IntegrationConfiguration configuration)
      throws SchedulerException {
    if (!configuration.configured()) {
      return;
    }
    if (!scheduler.checkExists(JOB_KEY)) {
      try {
        scheduler.addJob(
            JobBuilder.newJob(HospitalIntegrationSchedulerJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably()
                .build(),
            false);
      } catch (ObjectAlreadyExistsException ignored) {
        // Another cluster member completed registration before this member acquired the Quartz
        // lock.
      }
    }
    if (!scheduler.checkExists(TRIGGER_KEY)) {
      try {
        scheduler.scheduleJob(
            TriggerBuilder.newTrigger()
                .withIdentity(TRIGGER_KEY)
                .forJob(JOB_KEY)
                .startNow()
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(60)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build());
      } catch (ObjectAlreadyExistsException ignored) {
        // Preserve the trigger and its next-fire time registered by the other cluster member.
      }
    }
  }
}
