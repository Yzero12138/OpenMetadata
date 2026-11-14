package org.openmetadata.service.integration;

import java.sql.Types;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class SqlServerCdcAdapter implements CdcSourceAdapter {
  @Override
  public String pluginName() {
    return "SqlServer-CDC";
  }

  @Override
  public Map<String, Object> buildSource(
      String taskId,
      JdbcEndpoint endpoint,
      TableDefinition table,
      List<FieldMapping> mappings,
      CdcOptions options,
      IntegrationRuntimeOptions.Resolved runtime) {
    requireRoute(endpoint);
    validateOptions(options);
    if (options == null
        || options.databaseName() == null
        || !validTimeZone(options.serverTimeZone())) {
      throw new IntegrationException("MSSQL_CDC_OPTIONS_INVALID", 400);
    }
    if (!endpoint.database().equals(options.databaseName())) {
      throw new IntegrationException("MSSQL_DB_NAME_MISMATCH", 400);
    }
    if (table == null || runtime == null || runtime.splitSize() == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    requireIdentifiers(table);
    if (!endpoint.schemas().contains(table.schema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    var selected = OracleCdcAdapter.selectedColumns(table, mappings);
    var keys = table.columns().stream().filter(ColumnDefinition::primaryKey).toList();
    if (keys.size() != 1 || keys.getFirst().nullable() || !selected.contains(keys.getFirst())) {
      throw new IntegrationException("INVALID_PRIMARY_KEY", 400);
    }
    var fields = new LinkedHashMap<String, String>();
    selected.forEach(column -> fields.put(column.name(), fieldType(column)));
    String database = options.databaseName();
    String qualified = database + "." + table.schema() + "." + table.name();
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("plugin_name", pluginName());
    source.put("plugin_output", "source");
    source.put("url", endpoint.jdbcUrl());
    source.put("username", endpoint.username());
    source.put("password", endpoint.password());
    source.put("database-names", List.of(database));
    source.put("table-names", List.of(qualified));
    source.put("startup.mode", "initial");
    source.put("server-time-zone", options.serverTimeZone());
    source.put("incremental.parallelism", 1);
    source.put("exactly_once", false);
    source.put("snapshot.fetch.size", runtime.fetchSize());
    source.put("snapshot.split.size", runtime.splitSize());
    source.put("split.allow-sampling", false);
    source.put("enable_concurrent_read", true);
    source.put("schema-changes.enabled", false);
    source.put("connect.timeout.ms", 5000);
    source.put(
        "schema",
        Map.of(
            "table",
            qualified,
            "schema_first",
            true,
            "fields",
            fields,
            "primaryKey",
            Map.of("name", "hospital_pk", "columnNames", List.of(keys.getFirst().name()))));
    Map<String, String> debezium = new LinkedHashMap<>();
    debezium.put("decimal.handling.mode", "precise");
    debezium.put("time.precision.mode", "adaptive");
    debezium.put("include.schema.changes", "false");
    debezium.put("database.include.list", Pattern.quote(database));
    debezium.put(
        "table.include.list", Pattern.quote(table.schema()) + "\\." + Pattern.quote(table.name()));
    debezium.put("database.encrypt", Boolean.toString("VERIFY".equals(endpoint.tlsMode())));
    debezium.put("database.trustServerCertificate", "false");
    debezium.put("database.loginTimeout", "5");
    debezium.put("database.socketTimeout", "15000");
    debezium.put("database.sendTimeAsDatetime", "false");
    source.put("debezium", Map.copyOf(debezium));
    return source;
  }

  static boolean identifier(String name) {
    return name != null && name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}");
  }

  static boolean validTimeZone(String name) {
    return name != null && ZoneId.getAvailableZoneIds().contains(name);
  }

  static void validateOptions(CdcOptions options) {
    if (options != null
        && ((options.databaseName() != null && !identifier(options.databaseName()))
            || (options.serverTimeZone() != null && !validTimeZone(options.serverTimeZone()))
            || options.pdbName() != null
            || options.rootServiceName() != null)) {
      throw new IntegrationException("MSSQL_CDC_OPTIONS_INVALID", 400);
    }
  }

  static void requireIdentifiers(TableDefinition table) {
    if (!identifier(table.schema())
        || !identifier(table.name())
        || table.columns() == null
        || table.columns().stream()
            .anyMatch(column -> column == null || !identifier(column.name()))) {
      throw new IntegrationException("MSSQL_CDC_IDENTIFIER_UNSUPPORTED", 400);
    }
  }

  static void requireRoute(JdbcEndpoint endpoint) {
    if (endpoint == null
        || endpoint.dialect() != JdbcDialect.Mssql
        || endpoint.host() == null
        || !endpoint.host().matches("(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?")
        || endpoint.host().contains("..")
        || endpoint.port() < 1
        || endpoint.port() > 65535
        || !identifier(endpoint.database())
        || !Set.of("VERIFY", "DISABLED")
            .contains(endpoint.tlsMode() == null ? "" : endpoint.tlsMode())) {
      throw new IntegrationException("MSSQL_CDC_ROUTE_UNSUPPORTED", 400);
    }
    String expected =
        "jdbc:sqlserver://"
            + endpoint.host()
            + ":"
            + endpoint.port()
            + ";databaseName="
            + endpoint.database()
            + ";encrypt="
            + "VERIFY".equals(endpoint.tlsMode())
            + ";trustServerCertificate=false;loginTimeout=5;socketTimeout=15000;sendTimeAsDatetime=false";
    if (!expected.equals(endpoint.jdbcUrl())) {
      throw new IntegrationException("MSSQL_CDC_ROUTE_UNSUPPORTED", 400);
    }
    var properties = endpoint.connectionProperties();
    if (!properties
            .stringPropertyNames()
            .equals(Set.of("user", "password", "loginTimeout", "socketTimeout"))
        || !"5".equals(properties.getProperty("loginTimeout"))
        || !"15000".equals(properties.getProperty("socketTimeout"))) {
      throw new IntegrationException("MSSQL_CDC_ROUTE_UNSUPPORTED", 400);
    }
  }

  static String fieldType(ColumnDefinition column) {
    if (column.inspectedDialect() != JdbcDialect.Mssql || column.dataType() == null) {
      throw unsupported();
    }
    String type = column.dataType().toLowerCase(Locale.ROOT);
    int jdbc = column.jdbcType();
    if ("bit".equals(type) && Set.of(Types.BIT, Types.BOOLEAN).contains(jdbc)) {
      return "BOOLEAN";
    }
    if ("tinyint".equals(type) && Set.of(Types.TINYINT, Types.SMALLINT).contains(jdbc)) {
      return "SMALLINT";
    }
    if ("smallint".equals(type) && jdbc == Types.SMALLINT) {
      return "SMALLINT";
    }
    if ("int".equals(type) && jdbc == Types.INTEGER) {
      return "INT";
    }
    if ("bigint".equals(type) && jdbc == Types.BIGINT) {
      return "BIGINT";
    }
    if (Set.of("decimal", "numeric", "money", "smallmoney").contains(type)
        && Set.of(Types.DECIMAL, Types.NUMERIC).contains(jdbc)
        && column.size() >= 1
        && column.size() <= 38
        && column.scale() >= 0
        && column.scale() <= column.size()) {
      return "DECIMAL(" + column.size() + "," + column.scale() + ")";
    }
    if (Set.of("char", "varchar", "nchar", "nvarchar").contains(type)
        && Set.of(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR).contains(jdbc)
        && column.size() > 0
        && column.size() <= (type.startsWith("n") ? 4000 : 8000)) {
      return "STRING";
    }
    if ("uniqueidentifier".equals(type)
        && Set.of(Types.CHAR, Types.VARCHAR).contains(jdbc)
        && column.size() == 36) {
      return "STRING";
    }
    if ("date".equals(type) && jdbc == Types.DATE) {
      return "DATE";
    }
    // The released MicroTimestamp converter throws for negative fractional epoch values.
    if (Set.of("datetime", "smalldatetime", "datetime2").contains(type)
        && jdbc == Types.TIMESTAMP
        && column.scale() >= 0
        && column.scale() <= 3) {
      return "TIMESTAMP";
    }
    throw unsupported();
  }

  private static IntegrationException unsupported() {
    return new IntegrationException("MSSQL_CDC_TYPE_UNSUPPORTED", 400);
  }
}
