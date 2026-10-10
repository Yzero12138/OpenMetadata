package org.openmetadata.service.integration;

import java.util.List;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.StoredConnection;

public interface IntegrationConnectionStore {
  List<StoredConnection> list();

  StoredConnection get(UUID id);

  void save(StoredConnection connection);

  void delete(UUID id);
}
