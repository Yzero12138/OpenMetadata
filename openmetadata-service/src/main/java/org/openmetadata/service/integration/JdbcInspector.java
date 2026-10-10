package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class JdbcInspector {
  private static final int MAX_TABLES = 5000;
  private static final int MAX_TABLES_WITH_COLUMNS = 200;
  private static final int MAX_COLUMNS = 128;
  private final Function<String, JdbcEndpoint> endpoints;

  public JdbcInspector(Function<String, JdbcEndpoint> endpoints) {
    this.endpoints = endpoints;
  }

  public boolean test(String connectionId) {
    JdbcEndpoint endpoint = endpoints.apply(connectionId);
    try (Connection connection = open(endpoint)) {
      return connection.isValid(5);
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  public List<TableDefinition> tables(String connectionId) {
    return tables(connectionId, true);
  }

  public List<TableDefinition> tables(String connectionId, boolean includeColumns) {
    JdbcEndpoint endpoint = endpoints.apply(connectionId);
    long deadline = System.nanoTime() + 15_000_000_000L;
    try (Connection connection = open(endpoint)) {
      DatabaseMetaData metadata = connection.getMetaData();
      List<TableDefinition> names = new ArrayList<>();
      for (String schema : endpoint.schemas()) {
        try (ResultSet result =
            metadata.getTables(
                catalog(connection, endpoint),
                schemaPattern(metadata, endpoint, schema),
                "%",
                new String[] {"TABLE"})) {
          while (result.next()) {
            if (System.nanoTime() > deadline) {
              throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
            }
            String name = result.getString("TABLE_NAME");
            if (schema.equals(schema(result, endpoint))
                && IntegrationValidator.safeIdentifier(name)) {
              if (names.size() >= (includeColumns ? MAX_TABLES_WITH_COLUMNS : MAX_TABLES)) {
                throw new IntegrationException("TABLE_LIMIT_EXCEEDED", 409);
              }
              names.add(new TableDefinition(schema, name, List.of()));
            }
          }
        }
      }
      names.sort(
          java.util.Comparator.comparing(TableDefinition::schema)
              .thenComparing(TableDefinition::name));
      if (!includeColumns) {
        return List.copyOf(names);
      }
      List<TableDefinition> tables = new ArrayList<>();
      for (TableDefinition table : names) {
        if (System.nanoTime() > deadline) {
          throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
        }
        tables.add(inspect(connection, endpoint, table.schema(), table.name()));
      }
      return List.copyOf(tables);
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  public TableDefinition table(String connectionId, String schema, String name) {
    IntegrationValidator.requireTable(schema, name);
    JdbcEndpoint endpoint = endpoints.apply(connectionId);
    if (!endpoint.schemas().contains(schema)) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    try (Connection connection = open(endpoint)) {
      boolean exists = false;
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet result =
          metadata.getTables(
              catalog(connection, endpoint),
              schemaPattern(metadata, endpoint, schema),
              pattern(metadata, name),
              new String[] {"TABLE"})) {
        while (result.next()) {
          exists |=
              schema.equals(schema(result, endpoint))
                  && name.equals(result.getString("TABLE_NAME"));
        }
      }
      return exists ? inspect(connection, endpoint, schema, name) : null;
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
    } catch (SQLFeatureNotSupportedException e) {
      if (endpoint.dialect() == JdbcDialect.Oracle) {
        return connection;
      }
      connection.close();
      throw e;
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
  }

  private TableDefinition inspect(
      Connection connection, JdbcEndpoint endpoint, String schema, String name)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    Set<String> primaryKeys = new HashSet<>();
    try (ResultSet keys =
        metadata.getPrimaryKeys(
            catalog(connection, endpoint),
            endpoint.dialect() == JdbcDialect.Mysql ? null : schema,
            name)) {
      while (keys.next()) {
        primaryKeys.add(keys.getString("COLUMN_NAME"));
      }
    }
    Set<String> unicodeColumns = unicodeColumns(connection, endpoint, schema, name);
    boolean unicodeDatabase =
        endpoint.dialect() == JdbcDialect.Postgres && postgresUtf8(connection);
    List<ColumnDefinition> columns = new ArrayList<>();
    try (ResultSet result =
        metadata.getColumns(
            catalog(connection, endpoint),
            schemaPattern(metadata, endpoint, schema),
            pattern(metadata, name),
            "%")) {
      while (result.next()) {
        if (!schema.equals(schema(result, endpoint))
            || !name.equals(result.getString("TABLE_NAME"))) {
          continue;
        }
        String column = result.getString("COLUMN_NAME");
        if (!IntegrationValidator.safeIdentifier(column) || columns.size() >= MAX_COLUMNS) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        int jdbcType = result.getInt("DATA_TYPE");
        int size = result.getInt("COLUMN_SIZE");
        boolean national =
            Set.of(Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(jdbcType);
        boolean unicode = national || unicodeDatabase || unicodeColumns.contains(column);
        // SQL Server/Oracle national lengths can count UTF-16 units; reserve two for a code point.
        int unicodeCapacity = unicode ? size : 0;
        if (national
            && Set.of(JdbcDialect.Mssql, JdbcDialect.Oracle).contains(endpoint.dialect())) {
          unicodeCapacity = size / 2;
        }
        columns.add(
            new ColumnDefinition(
                column,
                result.getString("TYPE_NAME"),
                result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                primaryKeys.contains(column),
                jdbcType,
                size,
                result.getInt("DECIMAL_DIGITS"),
                result.getString("COLUMN_DEF") != null,
                unicode,
                unicodeCapacity));
      }
    }
    return new TableDefinition(schema, name, List.copyOf(columns));
  }

  private boolean postgresUtf8(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SHOW server_encoding")) {
        return rows.next() && "UTF8".equalsIgnoreCase(rows.getString(1));
      }
    }
  }

  private Set<String> unicodeColumns(
      Connection connection, JdbcEndpoint endpoint, String schema, String table)
      throws SQLException {
    if (endpoint.dialect() != JdbcDialect.Mysql) {
      return Set.of();
    }
    Set<String> columns = new HashSet<>();
    try (var statement =
        connection.prepareStatement(
            "SELECT COLUMN_NAME, CHARACTER_SET_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setQueryTimeout(5);
      int count = 0;
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (++count > MAX_COLUMNS) {
            throw new IntegrationException("INVALID_CONFIGURATION", 400);
          }
          if ("utf8mb4".equalsIgnoreCase(rows.getString(2))) {
            columns.add(rows.getString(1));
          }
        }
      }
    }
    return Set.copyOf(columns);
  }

  private String catalog(Connection connection, JdbcEndpoint endpoint) throws SQLException {
    return endpoint.dialect() == JdbcDialect.Oracle ? null : connection.getCatalog();
  }

  private String schemaPattern(DatabaseMetaData metadata, JdbcEndpoint endpoint, String schema)
      throws SQLException {
    return endpoint.dialect() == JdbcDialect.Mysql ? null : pattern(metadata, schema);
  }

  private String schema(ResultSet result, JdbcEndpoint endpoint) throws SQLException {
    return result.getString(endpoint.dialect() == JdbcDialect.Mysql ? "TABLE_CAT" : "TABLE_SCHEM");
  }

  private String pattern(DatabaseMetaData metadata, String literal) throws SQLException {
    String escape = metadata.getSearchStringEscape();
    return literal
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }
}
