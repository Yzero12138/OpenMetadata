package org.openmetadata.service.integration;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.jdbi3.CollectionDAO.EntityExtensionDAO;

public final class EntityExtensionTaskStore implements IntegrationTaskStore {
  public static final String EXTENSION = "hospital.integration.task";
  private static final String JSON_SCHEMA = "hospitalIntegrationTask";
  private static final int MAX_STORED_BYTES = 131072;
  private final Supplier<EntityExtensionDAO> dao;

  public EntityExtensionTaskStore(EntityExtensionDAO dao) {
    this(() -> dao);
  }

  public EntityExtensionTaskStore(Supplier<EntityExtensionDAO> dao) {
    this.dao = dao;
  }

  @Override
  public List<IntegrationTask> list() {
    try {
      return dao.get().getExtensionsByPrefixBatch(EXTENSION).stream()
          .filter(record -> EXTENSION.equals(record.getExtension()))
          .map(record -> IntegrationModels.deserialize(record.getJson()))
          .sorted(Comparator.comparingLong((IntegrationTask task) -> task.createdAt).reversed())
          .toList();
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  @Override
  public IntegrationTask get(UUID id) {
    try {
      String json = dao.get().getExtension(id, EXTENSION);
      if (json == null) {
        throw new IntegrationException("TASK_NOT_FOUND", 404);
      }
      return IntegrationModels.deserialize(json);
    } catch (IntegrationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  @Override
  public void save(IntegrationTask task) {
    String json = IntegrationModels.serialize(task);
    if (json.length() > MAX_STORED_BYTES) {
      throw new IntegrationException("CAPACITY_EXCEEDED", 409);
    }
    try {
      dao.get().insert(UUID.fromString(task.id), EXTENSION, JSON_SCHEMA, json);
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }
}
