package org.openmetadata.service.integration;

import java.time.Instant;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.search.SearchRepository;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@DisallowConcurrentExecution
public final class HospitalIntegrationSchedulerJob implements Job {
  private final CollectionDAO collectionDAO;

  public HospitalIntegrationSchedulerJob(
      CollectionDAO collectionDAO, SearchRepository searchRepository) {
    this.collectionDAO = collectionDAO;
  }

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    IntegrationConfiguration configuration = IntegrationConfiguration.fromEnvironment();
    if (!configuration.configured()) {
      return;
    }
    try {
      new IntegrationService(
              configuration, new EntityExtensionTaskStore(collectionDAO::entityExtensionDAO))
          .dispatchScheduled(Instant.now());
    } catch (RuntimeException e) {
      throw new JobExecutionException("Hospital integration dispatch failed", e, false);
    }
  }
}
