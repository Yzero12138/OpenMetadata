package org.openmetadata.service.integration;

import java.sql.Types;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class OracleCdcAdapter implements CdcSourceAdapter {
  @Override
  public String pluginName() {
    return "Oracle-CDC";
  }

  @Override
  public Map<String, Object> buildSource(
      String taskId,
      JdbcEndpoint endpoint,
      TableDefinition table,
      List<FieldMapping> mappings,
      CdcOptions options,
      IntegrationRuntimeOptions.Resolved runtime) {
    if (endpoint == null || endpoint.dialect() != JdbcDialect.Oracle) {
      throw new IntegrationException("MODE_UNSUPPORTED", 400);
    }
    requireOptions(options);
    if (options.pdbName() != null) {
      throw new IntegrationException("ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED", 400);
    }
    if (table == null || runtime == null || runtime.splitSize() == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    IntegrationValidator.requireTable(table.schema(), table.name());
    requireIdentifiers(table);
    if (!endpoint.schemas().contains(table.schema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    List<ColumnDefinition> selected = selectedColumns(table, mappings);
    List<ColumnDefinition> keys =
        table.columns().stream().filter(ColumnDefinition::primaryKey).toList();
    if (keys.size() != 1 || keys.getFirst().nullable() || !selected.contains(keys.getFirst())) {
      throw new IntegrationException("INVALID_PRIMARY_KEY", 400);
    }
    Map<String, String> fields = new LinkedHashMap<>();
    selected.forEach(column -> fields.put(column.name(), fieldType(column)));
    String database = options.databaseName().toUpperCase(Locale.ROOT);
    String qualified = database + "." + table.schema() + "." + table.name();
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("plugin_name", pluginName());
    source.put("plugin_output", "source");
    source.put("url", endpoint.jdbcUrl());
    source.put("username", endpoint.username());
    source.put("password", endpoint.password());
    source.put("database-names", List.of(database));
    source.put("schema-names", List.of(table.schema()));
    source.put("table-names", List.of(qualified));
    source.put("startup.mode", "initial");
    source.put("server-time-zone", options.serverTimeZone());
    source.put("incremental.parallelism", 1);
    source.put("snapshot.fetch.size", runtime.fetchSize());
    source.put("snapshot.split.size", runtime.splitSize());
    source.put("split.allow-sampling", false);
    source.put("enable_concurrent_read", true);
    source.put("skip_analyze", true);
    source.put("use_select_count", true);
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
    debezium.put("time.precision.mode", "adaptive");
    debezium.put("decimal.handling.mode", "precise");
    debezium.put("log.mining.read.only", "true");
    debezium.put("log.mining.strategy", "online_catalog");
    debezium.put("include.schema.changes", "false");
    debezium.put("lob.enabled", "false");
    endpoint
        .connectionProperties()
        .forEach(
            (key, value) -> {
              if (!Set.of("user", "password").contains(key.toString())) {
                debezium.put("database." + key, value.toString());
              }
            });
    source.put("debezium", Map.copyOf(debezium));
    return source;
  }

  static void requireOptions(CdcOptions options) {
    if (options == null
        || !nativeName(options.databaseName())
        || !validTimeZone(options.serverTimeZone())
        || (options.pdbName() == null) != (options.rootServiceName() == null)
        || (options.pdbName() != null
            && (!nativeName(options.pdbName()) || !serviceName(options.rootServiceName())))) {
      throw new IntegrationException("ORACLE_CDC_OPTIONS_INVALID", 400);
    }
  }

  static boolean nativeName(String name) {
    return name != null && name.matches("[a-zA-Z][a-zA-Z0-9_$#]{0,29}");
  }

  static boolean serviceName(String name) {
    return name != null && name.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,126}");
  }

  static boolean validTimeZone(String name) {
    try {
      return name != null && name.length() <= 64 && ZoneId.of(name) != null;
    } catch (DateTimeException e) {
      return false;
    }
  }

  static void requireIdentifiers(TableDefinition table) {
    if (!sourceRouteIdentifier(table.schema())
        || !sourceRouteIdentifier(table.name())
        || table.columns() == null
        || table.columns().stream()
            .anyMatch(column -> column == null || !logMinerIdentifier(column.name()))) {
      throw new IntegrationException("ORACLE_CDC_IDENTIFIER_UNSUPPORTED", 400);
    }
  }

  private static boolean sourceRouteIdentifier(String identifier) {
    return logMinerIdentifier(identifier) && identifier.indexOf('$') < 0;
  }

  private static boolean logMinerIdentifier(String identifier) {
    return identifier != null && identifier.matches("[a-zA-Z_][a-zA-Z0-9_$#]{0,29}");
  }

  static List<ColumnDefinition> selectedColumns(
      TableDefinition table, List<FieldMapping> mappings) {
    if (table.columns() == null
        || table.columns().isEmpty()
        || table.columns().size() > 128
        || mappings == null
        || mappings.isEmpty()
        || mappings.size() > 128) {
      throw new IntegrationException("INVALID_MAPPING", 400);
    }
    Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
    for (ColumnDefinition column : table.columns()) {
      if (column == null
          || !IntegrationValidator.safeIdentifier(column.name())
          || columns.putIfAbsent(column.name(), column) != null) {
        throw new IntegrationException("INVALID_MAPPING", 400);
      }
    }
    Set<String> sources = new HashSet<>();
    Set<String> targets = new HashSet<>();
    for (FieldMapping mapping : mappings) {
      if (mapping == null
          || !IntegrationValidator.safeIdentifier(mapping.source())
          || !IntegrationValidator.safeIdentifier(mapping.target())
          || !sources.add(mapping.source())
          || !targets.add(mapping.target())
          || !columns.containsKey(mapping.source())) {
        throw new IntegrationException("INVALID_MAPPING", 400);
      }
    }
    return mappings.stream().map(mapping -> columns.get(mapping.source())).toList();
  }

  static String fieldType(ColumnDefinition column) {
    if (column.inspectedDialect() != JdbcDialect.Oracle || column.dataType() == null) {
      throw new IntegrationException("ORACLE_CDC_TYPE_UNSUPPORTED", 400);
    }
    String type = column.dataType().toUpperCase(Locale.ROOT);
    if (Set.of("CLOB", "NCLOB").contains(type)
        || Set.of(Types.CLOB, Types.NCLOB).contains(column.jdbcType())) {
      throw new IntegrationException("ORACLE_CDC_LOB_UNVERIFIED", 400);
    }
    if ("NUMBER".equals(type)
        && Set.of(Types.NUMERIC, Types.DECIMAL).contains(column.jdbcType())
        && column.size() >= 1
        && column.size() <= 38
        && column.scale() >= 0
        && column.scale() <= column.size()) {
      return "DECIMAL(" + column.size() + "," + column.scale() + ")";
    }
    if (("DATE".equals(type) || type.matches("TIMESTAMP(?:\\([0-9]\\))?"))
        && column.jdbcType() == Types.TIMESTAMP
        && column.scale() >= 0
        && column.scale() <= 9) {
      return "TIMESTAMP";
    }
    if (Set.of("CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2").contains(type)
        && Set.of(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR)
            .contains(column.jdbcType())
        && column.size() > 0) {
      if (column.oracleDataLengthBytes() == null || column.oracleDataLengthBytes() <= 0) {
        throw new IntegrationException("ORACLE_CDC_STRING_BYTES_UNVERIFIED", 400);
      }
      if (column.oracleDataLengthBytes() > 4000) {
        throw new IntegrationException("ORACLE_CDC_EXTENDED_STRING_UNVERIFIED", 400);
      }
      return "STRING";
    }
    if ("BINARY_FLOAT".equals(type) && column.jdbcType() == Types.REAL) {
      return "FLOAT";
    }
    if ("BINARY_DOUBLE".equals(type) && column.jdbcType() == Types.DOUBLE) {
      return "DOUBLE";
    }
    throw new IntegrationException("ORACLE_CDC_TYPE_UNSUPPORTED", 400);
  }
}
