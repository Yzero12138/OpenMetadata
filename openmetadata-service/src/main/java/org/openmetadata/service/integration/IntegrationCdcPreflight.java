package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcCheck;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightInput;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightResult;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

/** SELECT-only prerequisite evidence; ready does not certify or authorize CDC runtime execution. */
public final class IntegrationCdcPreflight {
  private final Function<String, JdbcEndpoint> endpoints;
  private final JdbcInspector.ConnectionFactory connections;
  private final TableReader tables;
  private final Predicate<String> plugins;

  public IntegrationCdcPreflight(
      Function<String, JdbcEndpoint> endpoints, JdbcInspector inspector, SeaTunnelClient engine) {
    this(
        endpoints,
        (endpoint, properties) -> DriverManager.getConnection(endpoint.jdbcUrl(), properties),
        inspector::table,
        engine::hasSourceConnector);
  }

  IntegrationCdcPreflight(
      Function<String, JdbcEndpoint> endpoints,
      JdbcInspector.ConnectionFactory connections,
      TableReader tables,
      Predicate<String> plugins) {
    this.endpoints = endpoints;
    this.connections = connections;
    this.tables = tables;
    this.plugins = plugins;
  }

  @FunctionalInterface
  interface TableReader {
    TableDefinition table(String connectionId, String schema, String table);
  }

  public record Inspection(
      CdcPreflightResult result,
      TableDefinition source,
      TableDefinition target,
      List<FieldMapping> mappings) {
    public Inspection {
      source = snapshot(source);
      target = snapshot(target);
      mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }

    /** Resolve a discovered native DB_NAME without treating a configured service as its identity. */
    public CdcOptions resolvedOptions(CdcOptions configured) {
      return new CdcOptions(
          result.databaseName(),
          configured == null ? null : configured.serverTimeZone(),
          configured == null ? null : configured.pdbName(),
          configured == null ? null : configured.rootServiceName());
    }
  }

  public CdcPreflightResult check(String connectionId, CdcPreflightInput input) {
    return inspect(connectionId, input, null, null).result();
  }

  public CdcPreflightResult check(TaskInput input) {
    return inspect(input).result();
  }

  public Inspection inspect(TaskInput input) {
    if (input == null
        || !"CDC".equals(input.mode)
        || input.fieldMappings == null
        || input.fieldMappings.isEmpty()
        || input.fieldMappings.size() > 128
        || !IntegrationValidator.safeIdentifier(input.primaryKey)) {
      throw invalid();
    }
    return inspect(
        input.sourceConnectionId,
        new CdcPreflightInput(
            input.sourceSchema,
            input.sourceTable,
            input.fieldMappings.stream()
                .map(
                    mapping -> {
                      if (mapping == null
                          || !IntegrationValidator.safeIdentifier(mapping.source())
                          || !IntegrationValidator.safeIdentifier(mapping.target())) {
                        throw invalid();
                      }
                      return mapping.source();
                    })
                .toList(),
            input.cdc,
            input.targetConnectionId,
            input.targetSchema,
            input.targetTable),
        input.fieldMappings,
        input.primaryKey);
  }

  private Inspection inspect(
      String connectionId,
      CdcPreflightInput input,
      List<FieldMapping> requestedMappings,
      String requestedKey) {
    validateInput(connectionId, input);
    JdbcEndpoint endpoint = endpoints.apply(connectionId);
    if (endpoint == null) {
      throw new IntegrationException("CONNECTION_NOT_FOUND", 404);
    }
    if (input.schema() != null && !endpoint.schemas().contains(input.schema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    List<CdcCheck> checks = new ArrayList<>();
    boolean implemented = CdcAdapters.implemented(endpoint.dialect());
    if (!implemented) {
      add(checks, "implementation", "FAIL", "CDC_NOT_IMPLEMENTED", true);
      return inspection(connectionId, endpoint, null, null, false, checks, null, null, List.of());
    }
    engine(checks, CdcAdapters.require(endpoint.dialect()).pluginName());
    add(checks, "engine-network", "UNKNOWN", "ENGINE_SOURCE_NETWORK_UNVERIFIED", false);
    return endpoint.dialect() == JdbcDialect.Oracle
        ? oracle(connectionId, endpoint, input, requestedMappings, requestedKey, checks)
        : postgres(connectionId, endpoint, input, requestedMappings, requestedKey, checks);
  }

  private Inspection oracle(
      String connectionId,
      JdbcEndpoint endpoint,
      CdcPreflightInput input,
      List<FieldMapping> requestedMappings,
      String requestedKey,
      List<CdcCheck> checks) {
    validateOracleOptions(input.cdc());
    add(checks, "runtime-certification", "UNKNOWN", "ORACLE_CDC_RUNTIME_UNVERIFIED", false);
    String version = null;
    OracleCdcMetadata.Database database = null;
    TableDefinition source = null;
    TableDefinition target = null;
    List<FieldMapping> mappings = List.of();
    long deadline = System.nanoTime() + 20_000_000_000L;
    try (Connection connection = open(endpoint)) {
      OracleCdcMetadata metadata = new OracleCdcMetadata(connection, deadline);
      try {
        version = metadata.version();
        add(checks, "database-version", "UNKNOWN", "ORACLE_CDC_VERSION_UNCERTIFIED", false);
      } catch (SQLException e) {
        add(checks, "database-version", "UNKNOWN", "ORACLE_DATABASE_VERSION_UNVERIFIED", true);
      }
      try {
        database = metadata.database();
        String configured = input.cdc() == null ? null : input.cdc().databaseName();
        boolean matches = configured == null || database.name().equalsIgnoreCase(configured);
        add(
            checks,
            "database-name",
            matches ? "PASS" : "FAIL",
            matches ? "ORACLE_DB_NAME_PROVEN" : "ORACLE_DB_NAME_MISMATCH",
            true);
      } catch (SQLException e) {
        add(checks, "database-name", "UNKNOWN", "ORACLE_DB_NAME_UNVERIFIED", true);
      }
      loggingFlags(checks, database);
      boolean timezone =
          input.cdc() != null && OracleCdcAdapter.validTimeZone(input.cdc().serverTimeZone());
      add(
          checks,
          "server-time-zone",
          timezone ? "PASS" : "UNKNOWN",
          timezone ? "ORACLE_TIME_ZONE_CONFIGURED" : "ORACLE_TIME_ZONE_REQUIRED",
          true);
      container(checks, metadata, version, input.cdc());
      boolean packages = metadata.packages();
      add(
          checks,
          "package-execute",
          packages ? "PASS" : "UNKNOWN",
          packages ? "EXECUTE_GRANT_PROVEN" : "EXECUTE_GRANT_UNVERIFIED",
          true);
      boolean views = metadata.views();
      add(
          checks,
          "mining-views",
          views ? "PASS" : "UNKNOWN",
          views ? "ORACLE_MINING_VIEW_ACCESS_PROVEN" : "ORACLE_MINING_VIEW_ACCESS_UNVERIFIED",
          true);
      boolean privileges = false;
      try {
        privileges = metadata.privileges(major(version));
      } catch (SQLException ignored) {
        privileges = false;
      }
      add(
          checks,
          "mining-privileges",
          privileges ? "PASS" : "UNKNOWN",
          privileges ? "ORACLE_MINING_PRIVILEGES_PROVEN" : "ORACLE_MINING_PRIVILEGES_UNVERIFIED",
          true);
      if (input.table() == null) {
        add(checks, "table-metadata", "UNKNOWN", "CDC_TABLE_REQUIRED", true);
      } else {
        try {
          var inspected = metadata.table(input.schema(), input.table());
          source = inspected.definition();
          if (source == null) {
            add(checks, "table-metadata", "FAIL", "CDC_TABLE_NOT_FOUND", true);
          } else {
            add(checks, "table-metadata", "PASS", "CDC_TABLE_METADATA_PROVEN", true);
            identifiers(checks, source);
            mappings = mappings(source, input.columns(), requestedMappings);
            List<ColumnDefinition> selected = selected(checks, source, mappings);
            boolean key =
                inspected.validPrimaryKey()
                    && (requestedKey == null
                        || source.columns().stream()
                            .anyMatch(
                                column ->
                                    column.primaryKey() && column.name().equals(requestedKey)))
                    && source.columns().stream()
                        .filter(ColumnDefinition::primaryKey)
                        .allMatch(selected::contains);
            add(
                checks,
                "primary-key",
                key ? "PASS" : "FAIL",
                key ? "CDC_PRIMARY_KEY_PROVEN" : "INVALID_PRIMARY_KEY",
                true);
            if (!selected.isEmpty()) {
              types(checks, selected);
              coverage(checks, metadata, database, source, selected);
            }
          }
        } catch (SQLException | IntegrationException e) {
          add(checks, "table-metadata", "UNKNOWN", "CDC_TABLE_METADATA_UNVERIFIED", true);
        }
      }
    } catch (SQLException e) {
      add(checks, "source-connection", "UNKNOWN", "CDC_SOURCE_METADATA_UNVERIFIED", true);
    }
    if (input.targetConnectionId() != null) {
      target = target(checks, connectionId, endpoint, input, source, mappings, requestedKey);
    }
    return inspection(
        connectionId,
        endpoint,
        version,
        database == null ? null : database.name(),
        true,
        checks,
        source,
        target,
        mappings);
  }

  private Inspection postgres(
      String connectionId,
      JdbcEndpoint endpoint,
      CdcPreflightInput input,
      List<FieldMapping> requestedMappings,
      String requestedKey,
      List<CdcCheck> checks) {
    if (endpoint.database().contains(".")) {
      add(checks, "database-name", "FAIL", "CDC_DATABASE_NAME_UNSUPPORTED", true);
    }
    TableDefinition source = null;
    TableDefinition target = null;
    List<FieldMapping> mappings = List.of();
    if (input.table() == null) {
      add(checks, "table-metadata", "UNKNOWN", "CDC_TABLE_REQUIRED", true);
    } else {
      try {
        source = snapshot(tables.table(connectionId, input.schema(), input.table()));
        if (source == null) {
          add(checks, "table-metadata", "FAIL", "CDC_TABLE_NOT_FOUND", true);
        } else {
          add(checks, "table-metadata", "PASS", "CDC_TABLE_METADATA_PROVEN", true);
          mappings = mappings(source, input.columns(), requestedMappings);
          List<ColumnDefinition> selected = selected(checks, source, mappings);
          List<ColumnDefinition> keys =
              source.columns().stream().filter(ColumnDefinition::primaryKey).toList();
          boolean key =
              keys.size() == 1
                  && !keys.getFirst().nullable()
                  && selected.contains(keys.getFirst())
                  && (requestedKey == null || keys.getFirst().name().equals(requestedKey));
          add(
              checks,
              "primary-key",
              key ? "PASS" : "FAIL",
              key ? "CDC_PRIMARY_KEY_PROVEN" : "INVALID_PRIMARY_KEY",
              true);
        }
      } catch (IntegrationException e) {
        add(checks, "table-metadata", "UNKNOWN", "CDC_TABLE_METADATA_UNVERIFIED", true);
      }
    }
    if (input.targetConnectionId() != null) {
      target = target(checks, connectionId, endpoint, input, source, mappings, requestedKey);
    }
    String database =
        OracleCdcAdapter.serviceName(endpoint.database()) ? endpoint.database() : null;
    return inspection(
        connectionId, endpoint, null, database, true, checks, source, target, mappings);
  }

  private TableDefinition target(
      List<CdcCheck> checks,
      String connectionId,
      JdbcEndpoint sourceEndpoint,
      CdcPreflightInput input,
      TableDefinition source,
      List<FieldMapping> mappings,
      String requestedKey) {
    JdbcEndpoint endpoint = endpoints.apply(input.targetConnectionId());
    if (endpoint == null) {
      throw new IntegrationException("CONNECTION_NOT_FOUND", 404);
    }
    if (!endpoint.schemas().contains(input.targetSchema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    try {
      TableDefinition target =
          snapshot(
              tables.table(input.targetConnectionId(), input.targetSchema(), input.targetTable()));
      if (source == null || target == null || mappings.isEmpty()) {
        add(checks, "target-mapping", "UNKNOWN", "CDC_TARGET_METADATA_UNVERIFIED", true);
        return target;
      }
      if (sourceEndpoint.dialect() == JdbcDialect.Oracle
          && endpoint.dialect() != JdbcDialect.Doris) {
        add(checks, "target-mapping", "FAIL", "ORACLE_CDC_TARGET_UNSUPPORTED", true);
        return target;
      }
      TaskInput validation = new TaskInput();
      validation.name = "cdc_preflight";
      validation.sourceConnectionId = connectionId;
      validation.targetConnectionId = input.targetConnectionId();
      validation.sourceSchema = source.schema();
      validation.sourceTable = source.name();
      validation.targetSchema = target.schema();
      validation.targetTable = target.name();
      validation.mode = "CDC";
      validation.primaryKey =
          requestedKey == null
              ? source.columns().stream()
                  .filter(ColumnDefinition::primaryKey)
                  .map(ColumnDefinition::name)
                  .findFirst()
                  .orElse(null)
              : requestedKey;
      validation.fieldMappings = mappings;
      Set<String> mapped = new HashSet<>();
      mappings.forEach(mapping -> mapped.add(mapping.target()));
      if (sourceEndpoint.dialect() == JdbcDialect.Oracle
          && !mapped.equals(
              new HashSet<>(target.columns().stream().map(ColumnDefinition::name).toList()))) {
        add(checks, "target-mapping", "FAIL", "ORACLE_CDC_TARGET_PROJECTION_INCOMPLETE", true);
      } else {
        boolean valid = IntegrationValidator.validateTables(validation, source, target).valid();
        add(
            checks,
            "target-mapping",
            valid ? "PASS" : "FAIL",
            valid ? "CDC_TARGET_MAPPING_PROVEN" : "CDC_TARGET_MAPPING_INCOMPATIBLE",
            true);
      }
      return target;
    } catch (IntegrationException e) {
      add(checks, "target-mapping", "UNKNOWN", "CDC_TARGET_METADATA_UNVERIFIED", true);
      return null;
    }
  }

  private void engine(List<CdcCheck> checks, String plugin) {
    try {
      boolean present = plugins.test(plugin);
      add(
          checks,
          "engine-plugin",
          present ? "PASS" : "FAIL",
          present ? "CDC_ENGINE_PLUGIN_AVAILABLE" : "CDC_ENGINE_PLUGIN_MISSING",
          true);
    } catch (RuntimeException e) {
      add(checks, "engine-plugin", "UNKNOWN", "CDC_ENGINE_PLUGIN_UNVERIFIED", true);
    }
  }

  private static void container(
      List<CdcCheck> checks, OracleCdcMetadata metadata, String version, CdcOptions options) {
    boolean configured = options != null && options.pdbName() != null;
    int major = major(version);
    if (major == 11 && !configured) {
      add(checks, "container", "PASS", "ORACLE_NON_CDB_PROVEN", true);
      return;
    }
    if (major > 11) {
      try {
        String cdb = metadata.cdb();
        if ("NO".equals(cdb) && !configured) {
          add(checks, "container", "PASS", "ORACLE_NON_CDB_PROVEN", true);
          return;
        }
        if ("YES".equals(cdb) && configured) {
          boolean matches = options.pdbName().equals(metadata.container());
          add(
              checks,
              "container",
              matches ? "PASS" : "FAIL",
              matches ? "ORACLE_PDB_SOURCE_PROVEN" : "ORACLE_PDB_SOURCE_MISMATCH",
              true);
          add(checks, "root-container", "UNKNOWN", "ORACLE_CDC_ROOT_ROUTE_UNVERIFIED", true);
          add(
              checks,
              "container-runtime",
              "UNKNOWN",
              "ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED",
              true);
          return;
        }
      } catch (SQLException ignored) {
        add(checks, "container", "UNKNOWN", "ORACLE_CDC_CONTAINER_UNVERIFIED", true);
        return;
      }
    }
    add(checks, "container", "UNKNOWN", "ORACLE_CDC_CONTAINER_UNVERIFIED", true);
    if (configured) {
      add(checks, "container-runtime", "UNKNOWN", "ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED", true);
    }
  }

  private static void loggingFlags(List<CdcCheck> checks, OracleCdcMetadata.Database database) {
    String archive = database == null ? null : database.archive();
    String minimum = database == null ? null : database.minimum();
    add(
        checks,
        "archive-log",
        "ARCHIVELOG".equals(archive) ? "PASS" : "NOARCHIVELOG".equals(archive) ? "FAIL" : "UNKNOWN",
        "ARCHIVELOG".equals(archive)
            ? "ORACLE_ARCHIVELOG_ENABLED"
            : "NOARCHIVELOG".equals(archive)
                ? "ORACLE_ARCHIVELOG_REQUIRED"
                : "ORACLE_ARCHIVELOG_UNVERIFIED",
        true);
    boolean enabled = "YES".equals(minimum) || "IMPLICIT".equals(minimum);
    add(
        checks,
        "minimum-supplemental-logging",
        enabled ? "PASS" : "NO".equals(minimum) ? "FAIL" : "UNKNOWN",
        enabled
            ? "ORACLE_MIN_SUPPLEMENTAL_ENABLED"
            : "NO".equals(minimum)
                ? "ORACLE_MIN_SUPPLEMENTAL_REQUIRED"
                : "ORACLE_MIN_SUPPLEMENTAL_UNVERIFIED",
        true);
  }

  private static void coverage(
      List<CdcCheck> checks,
      OracleCdcMetadata metadata,
      OracleCdcMetadata.Database database,
      TableDefinition table,
      List<ColumnDefinition> selected) {
    OracleCdcMetadata.Coverage coverage;
    try {
      coverage = metadata.coverage(database, table, selected);
    } catch (SQLException e) {
      coverage = OracleCdcMetadata.Coverage.UNKNOWN;
    }
    add(
        checks,
        "table-supplemental-logging",
        coverage == OracleCdcMetadata.Coverage.COMPLETE
            ? "PASS"
            : coverage == OracleCdcMetadata.Coverage.INCOMPLETE ? "FAIL" : "UNKNOWN",
        coverage == OracleCdcMetadata.Coverage.COMPLETE
            ? "TABLE_SUPPLEMENTAL_LOGGING_PROVEN"
            : coverage == OracleCdcMetadata.Coverage.INCOMPLETE
                ? "TABLE_SUPPLEMENTAL_LOGGING_INCOMPLETE"
                : "TABLE_SUPPLEMENTAL_LOGGING_UNVERIFIED",
        true);
  }

  private static List<FieldMapping> mappings(
      TableDefinition source, List<String> columns, List<FieldMapping> requested) {
    return requested == null
        ? (columns == null
                ? source.columns().stream().map(ColumnDefinition::name).toList()
                : columns)
            .stream().map(column -> new FieldMapping(column, column)).toList()
        : List.copyOf(requested);
  }

  private static List<ColumnDefinition> selected(
      List<CdcCheck> checks, TableDefinition source, List<FieldMapping> mappings) {
    try {
      List<ColumnDefinition> selected = OracleCdcAdapter.selectedColumns(source, mappings);
      add(checks, "columns", "PASS", "CDC_COLUMNS_PROVEN", true);
      return selected;
    } catch (IntegrationException e) {
      add(checks, "columns", "FAIL", "CDC_COLUMNS_INVALID", true);
      return List.of();
    }
  }

  private static void types(List<CdcCheck> checks, List<ColumnDefinition> selected) {
    try {
      selected.forEach(OracleCdcAdapter::fieldType);
      add(checks, "column-types", "PASS", "ORACLE_CDC_COLUMN_TYPES_PROVEN", true);
    } catch (IntegrationException e) {
      String code =
          switch (e.code()) {
            case "ORACLE_CDC_LOB_UNVERIFIED",
                "ORACLE_CDC_EXTENDED_STRING_UNVERIFIED",
                "ORACLE_CDC_STRING_BYTES_UNVERIFIED" -> e.code();
            default -> "ORACLE_CDC_TYPE_UNSUPPORTED";
          };
      add(
          checks,
          "column-types",
          "ORACLE_CDC_STRING_BYTES_UNVERIFIED".equals(code) ? "UNKNOWN" : "FAIL",
          code,
          true);
    }
  }

  private static void identifiers(List<CdcCheck> checks, TableDefinition source) {
    try {
      OracleCdcAdapter.requireIdentifiers(source);
      add(checks, "logminer-identifiers", "PASS", "ORACLE_CDC_IDENTIFIERS_PROVEN", true);
    } catch (IntegrationException e) {
      add(checks, "logminer-identifiers", "FAIL", "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", true);
    }
  }

  private Connection open(JdbcEndpoint endpoint) throws SQLException {
    Connection connection = connections.open(endpoint, endpoint.connectionProperties());
    try {
      connection.setReadOnly(true);
      return connection;
    } catch (SQLFeatureNotSupportedException e) {
      return connection;
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
  }

  private static void validateInput(String connectionId, CdcPreflightInput input) {
    if (connectionId == null
        || !connectionId.matches("[a-zA-Z0-9_-]{1,64}")
        || input == null
        || (input.schema() == null) != (input.table() == null)
        || (input.schema() != null
            && (!IntegrationValidator.safeIdentifier(input.schema())
                || !IntegrationValidator.safeIdentifier(input.table())))
        || (input.table() == null && input.columns() != null)) {
      throw invalid();
    }
    if (input.columns() != null
        && (input.columns().isEmpty()
            || input.columns().size() > 128
            || input.columns().stream()
                .anyMatch(column -> !IntegrationValidator.safeIdentifier(column))
            || new HashSet<>(input.columns()).size() != input.columns().size())) {
      throw invalid();
    }
    int targetFields =
        (input.targetConnectionId() == null ? 0 : 1)
            + (input.targetSchema() == null ? 0 : 1)
            + (input.targetTable() == null ? 0 : 1);
    if ((targetFields != 0 && targetFields != 3)
        || (targetFields == 3
            && (input.table() == null
                || !input.targetConnectionId().matches("[a-zA-Z0-9_-]{1,64}")
                || !IntegrationValidator.safeIdentifier(input.targetSchema())
                || !IntegrationValidator.safeIdentifier(input.targetTable())))) {
      throw invalid();
    }
  }

  static void validateOracleOptions(CdcOptions options) {
    if (options != null
        && ((options.databaseName() != null && !OracleCdcAdapter.nativeName(options.databaseName()))
            || (options.serverTimeZone() != null
                && !OracleCdcAdapter.validTimeZone(options.serverTimeZone()))
            || (options.pdbName() == null) != (options.rootServiceName() == null)
            || (options.pdbName() != null
                && (!OracleCdcAdapter.nativeName(options.pdbName())
                    || !OracleCdcAdapter.serviceName(options.rootServiceName()))))) {
      throw new IntegrationException("ORACLE_CDC_OPTIONS_INVALID", 400);
    }
  }

  private static Inspection inspection(
      String connectionId,
      JdbcEndpoint endpoint,
      String version,
      String database,
      boolean implemented,
      List<CdcCheck> checks,
      TableDefinition source,
      TableDefinition target,
      List<FieldMapping> mappings) {
    boolean ready =
        implemented
            && checks.stream()
                .filter(CdcCheck::required)
                .allMatch(check -> "PASS".equals(check.status()));
    return new Inspection(
        new CdcPreflightResult(
            connectionId, endpoint.dialect().name(), version, database, implemented, ready, checks),
        source,
        target,
        mappings);
  }

  private static TableDefinition snapshot(TableDefinition table) {
    return table == null
        ? null
        : new TableDefinition(table.schema(), table.name(), List.copyOf(table.columns()));
  }

  private static int major(String version) {
    return version == null ? 0 : Integer.parseInt(version.split("[.]", 2)[0]);
  }

  private static void add(
      List<CdcCheck> checks, String id, String status, String code, boolean required) {
    checks.add(new CdcCheck(id, status, code, required));
  }

  private static IntegrationException invalid() {
    return new IntegrationException("INVALID_CONFIGURATION", 400);
  }
}
