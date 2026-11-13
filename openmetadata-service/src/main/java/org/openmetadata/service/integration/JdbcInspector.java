package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
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
  private final ConnectionFactory connections;

  public JdbcInspector(Function<String, JdbcEndpoint> endpoints) {
    this(
        endpoints,
        (endpoint, properties) -> DriverManager.getConnection(endpoint.jdbcUrl(), properties));
  }

  JdbcInspector(Function<String, JdbcEndpoint> endpoints, ConnectionFactory connections) {
    this.endpoints = endpoints;
    this.connections = connections;
  }

  public TableDefinition query(
      JdbcEndpoint endpoint, String schema, String query, LocalDate businessDate) {
    if (endpoint == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    IntegrationSql.validate(query, Set.copyOf(endpoint.schemas()), schema, endpoint.dialect());
    String rendered =
        IntegrationSql.renderForSource(query, businessDate, schema, endpoint.dialect());
    if (endpoint.dialect() == JdbcDialect.Mssql) {
      rendered = ";" + rendered;
    }
    try (Connection connection = open(endpoint, true);
        var statement = connection.prepareStatement(rendered)) {
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      ResultSetMetaData metadata = statement.getMetaData();
      if (metadata == null) {
        throw new IntegrationException("QUERY_METADATA_UNAVAILABLE", 503);
      }
      int count = metadata.getColumnCount();
      if (count < 1 || count > MAX_COLUMNS) {
        throw new IntegrationException("INVALID_QUERY_COLUMNS", 400);
      }
      Set<String> names = new HashSet<>();
      List<ColumnDefinition> columns = new ArrayList<>();
      for (int index = 1; index <= count; index++) {
        String name = metadata.getColumnLabel(index);
        String type = metadata.getColumnTypeName(index);
        if (!IntegrationValidator.safeIdentifier(name)
            || !names.add(name.toLowerCase(Locale.ROOT))
            || type == null
            || type.isBlank()) {
          throw new IntegrationException("INVALID_QUERY_COLUMNS", 400);
        }
        int jdbcType = metadata.getColumnType(index);
        int size = metadata.getPrecision(index);
        boolean national =
            Set.of(Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(jdbcType);
        int unicodeCapacity = national ? size : 0;
        if (national
            && Set.of(JdbcDialect.Mssql, JdbcDialect.Oracle).contains(endpoint.dialect())) {
          unicodeCapacity = size / 2;
        }
        columns.add(
            new ColumnDefinition(
                name,
                type,
                metadata.isNullable(index) != ResultSetMetaData.columnNoNulls,
                false,
                jdbcType,
                size,
                metadata.getScale(index),
                false,
                national,
                unicodeCapacity,
                endpoint.dialect()));
      }
      return new TableDefinition(schema, "sql_result", List.copyOf(columns));
    } catch (SQLException e) {
      throw new IntegrationException("QUERY_METADATA_UNAVAILABLE", 503);
    }
  }

  public TableDefinition query(
      String connectionId, String schema, String query, LocalDate businessDate) {
    return query(endpoints.apply(connectionId), schema, query, businessDate);
  }

  @FunctionalInterface
  interface ConnectionFactory {
    Connection open(JdbcEndpoint endpoint, Properties properties) throws SQLException;
  }

  public boolean test(String connectionId) {
    JdbcEndpoint endpoint = endpoints.apply(connectionId);
    try (Connection connection = open(endpoint)) {
      return connection.isValid(5)
          && (endpoint.dialect() != JdbcDialect.Doris || DorisHttpHealth.healthy(endpoint));
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
    return open(endpoint, false);
  }

  private Connection open(JdbcEndpoint endpoint, boolean queryMetadata) throws SQLException {
    Properties properties = new Properties();
    properties.putAll(endpoint.connectionProperties());
    if (queryMetadata && endpoint.dialect() == JdbcDialect.Mysql) {
      properties.setProperty("useServerPrepStmts", "true");
      properties.setProperty("emulateUnsupportedPstmts", "false");
    }
    Connection connection = connections.open(endpoint, properties);
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
    if (endpoint.dialect() == JdbcDialect.Doris) {
      return DorisTableMetadata.read(connection, schema, name);
    }
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
                unicodeCapacity,
                endpoint.dialect()));
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
    if (endpoint.dialect() == JdbcDialect.Doris) {
      return endpoint.database();
    }
    return endpoint.dialect() == JdbcDialect.Oracle ? null : connection.getCatalog();
  }

  private String schemaPattern(DatabaseMetaData metadata, JdbcEndpoint endpoint, String schema)
      throws SQLException {
    return catalogSchema(endpoint) ? null : pattern(metadata, schema);
  }

  private String schema(ResultSet result, JdbcEndpoint endpoint) throws SQLException {
    return result.getString(catalogSchema(endpoint) ? "TABLE_CAT" : "TABLE_SCHEM");
  }

  private boolean catalogSchema(JdbcEndpoint endpoint) {
    return endpoint.dialect() == JdbcDialect.Mysql || endpoint.dialect() == JdbcDialect.Doris;
  }

  private String pattern(DatabaseMetaData metadata, String literal) throws SQLException {
    String escape = metadata.getSearchStringEscape();
    return literal
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }
}
