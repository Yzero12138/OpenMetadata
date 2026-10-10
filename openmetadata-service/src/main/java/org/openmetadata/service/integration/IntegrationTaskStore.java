package org.openmetadata.service.integration;

import java.util.List;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

public interface IntegrationTaskStore {
  List<IntegrationTask> list();

  IntegrationTask get(UUID id);

  void save(IntegrationTask task);
}
