package org.openmetadata.service.integration;

import java.util.List;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

/** Compatibility entry point for the environment-managed PostgreSQL fixtures. */
public final class PostgresInspector {
  private final JdbcInspector inspector;

  public PostgresInspector(IntegrationConfiguration configuration) {
    inspector = new JdbcInspector(configuration::endpoint);
  }

  public boolean test(String connectionId) {
    return inspector.test(connectionId);
  }

  public List<TableDefinition> tables(String connectionId) {
    return inspector.tables(connectionId);
  }

  public TableDefinition table(String connectionId, String schema, String name) {
    return inspector.table(connectionId, schema, name);
  }
}
