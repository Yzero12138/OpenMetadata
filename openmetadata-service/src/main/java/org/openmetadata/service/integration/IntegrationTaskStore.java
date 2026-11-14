package org.openmetadata.service.integration;

import java.util.List;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

public interface IntegrationTaskStore {
  List<IntegrationTask> list();

  IntegrationTask get(UUID id);

  void save(IntegrationTask task);

  /** Atomically reserve/replace an existing task; implementations backed by SQL override this. */
  default boolean compareAndSave(IntegrationTask task, long expectedVersion) {
    synchronized (this) {
      if (get(UUID.fromString(task.id)).version != expectedVersion) {
        return false;
      }
      save(task);
      return true;
    }
  }
}
