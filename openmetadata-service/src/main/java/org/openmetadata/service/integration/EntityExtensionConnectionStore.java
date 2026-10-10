package org.openmetadata.service.integration;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.openmetadata.service.integration.IntegrationModels.StoredConnection;
import org.openmetadata.service.jdbi3.CollectionDAO.EntityExtensionDAO;

public final class EntityExtensionConnectionStore implements IntegrationConnectionStore {
  public static final String EXTENSION = "hospital.integration.connection";
  private final Supplier<EntityExtensionDAO> dao;

  public EntityExtensionConnectionStore(Supplier<EntityExtensionDAO> dao) {
    this.dao = dao;
  }

  @Override
  public List<StoredConnection> list() {
    try {
      return dao.get().getExtensionsByPrefixBatch(EXTENSION).stream()
          .filter(record -> EXTENSION.equals(record.getExtension()))
          .map(record -> IntegrationModels.deserializeConnection(record.getJson()))
          .toList();
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  @Override
  public StoredConnection get(UUID id) {
    try {
      String value = dao.get().getExtension(id, EXTENSION);
      if (value == null) {
        throw new IntegrationException("CONNECTION_NOT_FOUND", 404);
      }
      return IntegrationModels.deserializeConnection(value);
    } catch (IntegrationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  @Override
  public void save(StoredConnection connection) {
    String json = IntegrationModels.serializeConnection(connection);
    if (json.length() > 32768) {
      throw new IntegrationException("CAPACITY_EXCEEDED", 409);
    }
    try {
      dao.get()
          .insert(
              UUID.fromString(connection.definition.id),
              EXTENSION,
              "hospitalIntegrationConnection",
              json);
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  @Override
  public void delete(UUID id) {
    try {
      dao.get().delete(id, EXTENSION);
    } catch (RuntimeException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }
}
