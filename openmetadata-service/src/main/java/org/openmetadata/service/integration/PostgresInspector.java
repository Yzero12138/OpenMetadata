package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class PostgresInspector {
  private static final int MAX_TABLES = 100;
  private static final int MAX_COLUMNS = 128;
  private final IntegrationConfiguration configuration;

  public PostgresInspector(IntegrationConfiguration configuration) {
    this.configuration = configuration;
  }

  public boolean test(String connectionId) {
    JdbcEndpoint endpoint = configuration.endpoint(connectionId);
    try (Connection connection = open(endpoint)) {
      return connection.isValid(5);
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  public List<TableDefinition> tables(String connectionId) {
    JdbcEndpoint endpoint = configuration.endpoint(connectionId);
    long deadline = System.nanoTime() + 15_000_000_000L;
    try (Connection connection = open(endpoint)) {
      DatabaseMetaData metadata = connection.getMetaData();
      List<String> names = new ArrayList<>();
      try (ResultSet result =
          metadata.getTables(connection.getCatalog(), "public", "%", new String[] {"TABLE"})) {
        while (result.next()) {
          String name = result.getString("TABLE_NAME");
          if ("public".equals(result.getString("TABLE_SCHEM"))
              && IntegrationValidator.safeIdentifier(name)) {
            if (names.size() >= MAX_TABLES) {
              throw new IntegrationException("CAPACITY_EXCEEDED", 409);
            }
            names.add(name);
          }
        }
      }
      names.sort(String::compareTo);
      List<TableDefinition> tables = new ArrayList<>();
      for (String name : names) {
        if (System.nanoTime() > deadline) {
          throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
        }
        tables.add(inspect(connection, "public", name));
      }
      return List.copyOf(tables);
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  public TableDefinition table(String connectionId, String schema, String name) {
    IntegrationValidator.requireTable(schema, name);
    JdbcEndpoint endpoint = configuration.endpoint(connectionId);
    try (Connection connection = open(endpoint)) {
      boolean exists = false;
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet result =
          metadata.getTables(
              connection.getCatalog(), schema, pattern(metadata, name), new String[] {"TABLE"})) {
        while (result.next()) {
          exists |=
              schema.equals(result.getString("TABLE_SCHEM"))
                  && name.equals(result.getString("TABLE_NAME"));
        }
      }
      return exists ? inspect(connection, schema, name) : null;
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  private Connection open(JdbcEndpoint endpoint) throws SQLException {
    Connection connection =
        DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties());
    try {
      connection.setReadOnly(true);
      return connection;
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
  }

  private TableDefinition inspect(Connection connection, String schema, String name)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    Set<String> primaryKeys = new HashSet<>();
    try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), schema, name)) {
      while (keys.next()) {
        primaryKeys.add(keys.getString("COLUMN_NAME"));
      }
    }
    List<ColumnDefinition> columns = new ArrayList<>();
    try (ResultSet result =
        metadata.getColumns(connection.getCatalog(), schema, pattern(metadata, name), "%")) {
      while (result.next()) {
        if (!schema.equals(result.getString("TABLE_SCHEM"))
            || !name.equals(result.getString("TABLE_NAME"))) {
          continue;
        }
        String column = result.getString("COLUMN_NAME");
        if (!IntegrationValidator.safeIdentifier(column) || columns.size() >= MAX_COLUMNS) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        columns.add(
            new ColumnDefinition(
                column,
                result.getString("TYPE_NAME"),
                result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                primaryKeys.contains(column),
                result.getInt("DATA_TYPE"),
                result.getInt("COLUMN_SIZE"),
                result.getInt("DECIMAL_DIGITS"),
                result.getString("COLUMN_DEF") != null));
      }
    }
    return new TableDefinition(schema, name, List.copyOf(columns));
  }

  private String pattern(DatabaseMetaData metadata, String literal) throws SQLException {
    String escape = metadata.getSearchStringEscape();
    return literal
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }
}
