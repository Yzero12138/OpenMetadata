package org.openmetadata.service.integration;

import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcCheck;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightInput;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.SqlServerCdcMetadata.Row;

/** Conservative SQL Server 2019 standalone prerequisite strategy; no source rows are queried. */
final class SqlServerCdcPreflight {
  private final SqlServerCdcMetadata metadata;
  private final List<CdcCheck> checks;
  private final String registeredDatabase;

  SqlServerCdcPreflight(
      SqlServerCdcMetadata metadata, List<CdcCheck> checks, String registeredDatabase) {
    this.metadata = metadata;
    this.checks = checks;
    this.registeredDatabase = registeredDatabase;
  }

  record Evidence(
      String version,
      String database,
      TableDefinition source,
      List<FieldMapping> mappings,
      SqlServerCaptureIdentity identity) {}

  Evidence inspect(CdcPreflightInput input, List<FieldMapping> requested, String requestedKey) {
    var server = row(metadata::server);
    var database = row(metadata::database);
    version(server);
    String name = server.text("database_name");
    String configured = input.cdc() == null ? null : input.cdc().databaseName();
    boolean nameKnown =
        SqlServerCdcAdapter.identifier(name)
            && SqlServerCdcAdapter.identifier(database.text("name"));
    add(
        "database-name",
        !nameKnown
            ? "UNKNOWN"
            : name.equals(registeredDatabase)
                    && name.equals(database.text("name"))
                    && (configured == null || name.equals(configured))
                ? "PASS"
                : "FAIL",
        "MSSQL_DB_NAME_PROVEN",
        "MSSQL_DB_NAME_MISMATCH",
        "MSSQL_DB_NAME_UNVERIFIED");
    boolean timezone =
        input.cdc() != null && SqlServerCdcAdapter.validTimeZone(input.cdc().serverTimeZone());
    add(
        "server-time-zone",
        timezone ? "PASS" : "UNKNOWN",
        "MSSQL_TIME_ZONE_CONFIGURED",
        "MSSQL_TIME_ZONE_REQUIRED",
        "MSSQL_TIME_ZONE_REQUIRED");
    flag(
        "database-cdc",
        database,
        "is_cdc_enabled",
        1,
        "MSSQL_DATABASE_CDC_ENABLED",
        "MSSQL_DATABASE_CDC_DISABLED",
        "MSSQL_DATABASE_CDC_UNVERIFIED");
    var topology =
        List.of(
            "state",
            "is_read_only",
            "containment",
            "is_published",
            "is_merge_published",
            "is_subscribed",
            "is_distributor");
    boolean topologyKnown =
        topology.stream().allMatch(database::known) && server.known("is_clustered");
    boolean standalone =
        topology.stream().allMatch(key -> database.is(key, 0))
            && server.is("is_clustered", 0)
            && List.of("source_database_id", "replica_id", "group_database_id", "mirroring_guid")
                .stream()
                .noneMatch(database::known);
    add(
        "database-topology",
        !topologyKnown ? "UNKNOWN" : standalone ? "PASS" : "FAIL",
        "MSSQL_STANDALONE_METADATA_PROVEN",
        "MSSQL_CDC_TOPOLOGY_UNSUPPORTED",
        "MSSQL_TOPOLOGY_UNVERIFIED");
    if ("15".equals(server.text("product_major_version"))) {
      flag(
          "database-adr",
          row(metadata::adr),
          "is_accelerated_database_recovery_on",
          0,
          "MSSQL_ADR_DISABLED",
          "MSSQL_CDC_ADR_INCOMPATIBLE",
          "MSSQL_ADR_UNVERIFIED");
    } else {
      unknown("database-adr", "MSSQL_ADR_UNVERIFIED");
    }
    String collation = database.text("collation_name");
    add(
        "database-collation",
        collation == null
            ? "UNKNOWN"
            : collation.toUpperCase(Locale.ROOT).contains("_CI_")
                    && !collation.toUpperCase(Locale.ROOT).contains("_BIN")
                ? "PASS"
                : "FAIL",
        "MSSQL_CDC_DATABASE_COLLATION_PROVEN",
        "MSSQL_CDC_DATABASE_COLLATION_UNSUPPORTED",
        "MSSQL_CDC_DATABASE_COLLATION_UNVERIFIED");
    permissions(
        row(metadata::nativeAccess),
        "native-metadata",
        List.of("change_tables", "captured_columns", "ddl_history", "lsn_time_mapping"),
        "MSSQL_CDC_NATIVE_METADATA");
    health();
    if (input.table() == null) {
      unknown("table-metadata", "CDC_TABLE_REQUIRED");
      return new Evidence(server.text("product_version"), name, null, List.of(), null);
    }
    var table = row(() -> metadata.table(input.schema(), input.table()));
    if (!positive(table.number("object_id"))
        || !table.known("schema_name")
        || !table.known("table_name")) {
      unknown("table-metadata", "CDC_TABLE_METADATA_UNVERIFIED");
      return new Evidence(server.text("product_version"), name, null, List.of(), null);
    }
    pass("table-metadata", "CDC_TABLE_METADATA_PROVEN");
    flag(
        "table-cdc",
        table,
        "is_tracked_by_cdc",
        1,
        "MSSQL_TABLE_CDC_ENABLED",
        "MSSQL_TABLE_CDC_DISABLED",
        "MSSQL_TABLE_CDC_UNVERIFIED");
    var tableFlags =
        List.of(
            "is_memory_optimized",
            "is_filetable",
            "temporal_type",
            "is_external",
            "is_ms_shipped",
            "has_columnstore");
    add(
        "table-kind",
        tableFlags.stream().anyMatch(key -> !table.known(key))
            ? "UNKNOWN"
            : tableFlags.stream().allMatch(key -> table.is(key, 0)) ? "PASS" : "FAIL",
        "MSSQL_CDC_TABLE_KIND_PROVEN",
        "MSSQL_CDC_TABLE_KIND_UNSUPPORTED",
        "MSSQL_CDC_TABLE_KIND_UNVERIFIED");
    int sourceId = table.number("object_id");
    List<Row> columns = rows(() -> metadata.columns(sourceId));
    List<Row> keys = rows(() -> metadata.primaryKey(sourceId));
    if (columns == null || columns.isEmpty()) {
      unknown("columns", "MSSQL_CDC_COLUMNS_UNVERIFIED");
      return new Evidence(server.text("product_version"), name, null, List.of(), null);
    }
    Row key = keys != null && keys.size() == 1 ? keys.getFirst() : Row.EMPTY;
    boolean keyKnown =
        keys != null
            && (keys.size() != 1
                || List.of(
                        "name",
                        "column_id",
                        "is_unique",
                        "is_disabled",
                        "is_hypothetical",
                        "is_nullable",
                        "key_ordinal")
                    .stream()
                    .allMatch(key::known));
    boolean validKey =
        key.is("is_unique", 1)
            && key.is("is_disabled", 0)
            && key.is("is_hypothetical", 0)
            && key.is("is_nullable", 0)
            && key.is("key_ordinal", 1)
            && positive(key.number("column_id"));
    final boolean inspectedKey = validKey;
    var definitions =
        columns.stream()
            .map(
                column ->
                    definition(
                        column,
                        inspectedKey
                            && column.number("column_id") != null
                            && column.number("column_id").equals(key.number("column_id"))
                            && Objects.equals(column.text("name"), key.text("name"))))
            .toList();
    var source =
        new TableDefinition(table.text("schema_name"), table.text("table_name"), definitions);
    List<FieldMapping> mappings =
        requested != null
            ? List.copyOf(requested)
            : (input.columns() == null
                    ? definitions.stream().map(ColumnDefinition::name).toList()
                    : input.columns())
                .stream().map(column -> new FieldMapping(column, column)).toList();
    List<ColumnDefinition> selected;
    try {
      SqlServerCdcAdapter.requireIdentifiers(source);
      if (!input.schema().equals(source.schema()) || !input.table().equals(source.name())) {
        throw new IntegrationException("MSSQL_CDC_IDENTIFIER_UNSUPPORTED", 400);
      }
      pass("source-identifiers", "MSSQL_CDC_IDENTIFIERS_PROVEN");
    } catch (IntegrationException e) {
      fail("source-identifiers", "MSSQL_CDC_IDENTIFIER_UNSUPPORTED");
    }
    try {
      selected = OracleCdcAdapter.selectedColumns(source, mappings);
      pass("columns", "CDC_COLUMNS_PROVEN");
    } catch (IntegrationException e) {
      fail("columns", "CDC_COLUMNS_INVALID");
      selected = List.of();
    }
    final var selectedColumns = selected;
    validKey =
        validKey
            && (requestedKey == null || requestedKey.equals(key.text("name")))
            && definitions.stream()
                .anyMatch(
                    column ->
                        column.primaryKey()
                            && !column.nullable()
                            && selectedColumns.contains(column));
    add(
        "primary-key",
        !keyKnown ? "UNKNOWN" : validKey ? "PASS" : "FAIL",
        "CDC_PRIMARY_KEY_PROVEN",
        "INVALID_PRIMARY_KEY",
        "MSSQL_PRIMARY_KEY_UNVERIFIED");
    var captures = rows(() -> metadata.captures(sourceId));
    if (captures == null || captures.isEmpty()) {
      unknown("capture-instance", "MSSQL_CAPTURE_INSTANCE_UNVERIFIED");
      return new Evidence(server.text("product_version"), name, source, mappings, null);
    }
    if (captures.size() != 1) {
      fail("capture-instance", "MSSQL_CAPTURE_INSTANCE_UNSUPPORTED");
      return new Evidence(server.text("product_version"), name, source, mappings, null);
    }
    Row capture = captures.getFirst();
    if (capture.is("has_drop_pending", 1)) {
      fail("capture-instance", "MSSQL_CAPTURE_DROP_PENDING");
      return new Evidence(server.text("product_version"), name, source, mappings, null);
    }
    if (!capture.is("has_drop_pending", 0)
        || !positive(capture.number("object_id"))
        || !Integer.valueOf(sourceId).equals(capture.number("source_object_id"))
        || !SqlServerCdcAdapter.identifier(capture.text("capture_instance"))
        || capture.time("create_date") == null
        || !nonzero(capture.bytes("start_lsn"))) {
      unknown("capture-instance", "MSSQL_CAPTURE_INSTANCE_UNVERIFIED");
      return new Evidence(server.text("product_version"), name, source, mappings, null);
    }
    pass("capture-instance", "MSSQL_CAPTURE_INSTANCE_PROVEN");
    List<Row> captured = rows(() -> metadata.captured(capture.number("object_id")));
    if (captured == null || captured.isEmpty()) {
      unknown("captured-columns", "MSSQL_CAPTURED_COLUMNS_UNVERIFIED");
      return new Evidence(server.text("product_version"), name, source, mappings, null);
    }
    Map<Integer, Row> byId = new HashMap<>();
    boolean consistent = true;
    for (Row column : columns) {
      Integer id = column.number("column_id");
      if (!positive(id) || byId.putIfAbsent(id, column) != null || !column.known("is_nullable"))
        consistent = false;
    }
    Set<String> capturedNames = new HashSet<>();
    int ordinal = 0;
    for (Row column : captured) {
      Row original = byId.get(column.number("column_id"));
      consistent &=
          original != null
              && Objects.equals(original.text("name"), column.text("column_name"))
              && capturedNames.add(column.text("column_name"))
              && Integer.valueOf(++ordinal).equals(column.number("column_ordinal"))
              && column.known("column_type")
              && column.known("is_computed")
              && Objects.equals(original.text("base_type"), column.text("column_type"));
    }
    consistent &=
        selected.stream().allMatch(column -> capturedNames.contains(column.name()))
            && capturedNames.contains(key.text("name"));
    boolean capturedKnown =
        captured.stream()
            .allMatch(
                column ->
                    List.of(
                            "column_id",
                            "column_name",
                            "column_ordinal",
                            "column_type",
                            "is_computed")
                        .stream()
                        .allMatch(column::known));
    add(
        "captured-columns",
        !capturedKnown ? "UNKNOWN" : consistent ? "PASS" : "FAIL",
        "MSSQL_CAPTURED_COLUMNS_PROVEN",
        "MSSQL_CAPTURED_COLUMNS_INCOMPLETE",
        "MSSQL_CAPTURED_COLUMNS_UNVERIFIED");
    types(columns, captured, capturedNames, collation);
    var permission =
        row(
            () ->
                metadata.permissions(capture.text("role_name"), capture.text("capture_instance")));
    List<Row> capturedSources =
        columns.stream().filter(column -> capturedNames.contains(column.text("name"))).toList();
    List<Integer> select =
        new ArrayList<>(
            capturedSources.stream().map(column -> column.number("can_select")).toList());
    select.add(permission.number("can_select_changes"));
    if (capture.text("role_name") != null
        && !permission.is("is_sysadmin", 1)
        && !permission.is("is_db_owner", 1)) select.add(permission.number("is_gating_role_member"));
    permission("source-permissions", select, "MSSQL_CDC_SELECT");
    permissions(
        permission,
        "metadata-permissions",
        List.of("can_view_database_state", "can_view_server_state"),
        "MSSQL_CDC_STATE");
    var range = row(() -> metadata.lsn(capture.text("capture_instance")));
    byte[] min = range.bytes("min_lsn");
    byte[] max = range.bytes("max_lsn");
    add(
        "cdc-lsn-range",
        nonzero(min) && nonzero(max) && Arrays.compareUnsigned(min, max) <= 0 ? "PASS" : "UNKNOWN",
        "MSSQL_CDC_LSN_RANGE_AVAILABLE",
        "MSSQL_CDC_LSN_RANGE_UNAVAILABLE",
        "MSSQL_CDC_LSN_RANGE_UNAVAILABLE");
    SqlServerCaptureIdentity identity = null;
    if (consistent
        && positive(database.number("database_id"))
        && database.time("create_date") != null
        && nameKnown) {
      identity =
          new SqlServerCaptureIdentity(
              name,
              database.number("database_id"),
              database.time("create_date").toString(),
              source.schema(),
              source.name(),
              sourceId,
              capture.number("object_id"),
              capture.number("source_object_id"),
              capture.text("capture_instance"),
              capture.time("create_date").toString(),
              capture.text("role_name"),
              capture.text("index_name"),
              captured.stream()
                  .map(
                      column ->
                          new SqlServerCaptureIdentity.CapturedColumn(
                              column.number("column_id"),
                              column.text("column_name"),
                              column.number("column_ordinal"),
                              column.text("column_type"),
                              column.is("is_computed", 1)))
                  .toList());
    }
    add(
        "capture-identity",
        identity == null ? "UNKNOWN" : "PASS",
        "MSSQL_CAPTURE_IDENTITY_PROVEN",
        "MSSQL_CAPTURE_IDENTITY_UNVERIFIED",
        "MSSQL_CAPTURE_IDENTITY_UNVERIFIED");
    return new Evidence(server.text("product_version"), name, source, mappings, identity);
  }

  private void version(Row server) {
    String edition = server.text("edition");
    String status = "UNKNOWN";
    if (edition != null) {
      String normalized = edition.toLowerCase(Locale.ROOT);
      if (normalized.contains("express") || normalized.contains("web")) status = "FAIL";
      else if ("15".equals(server.text("product_major_version"))
          && server.text("product_version") != null
          && server.text("product_version").matches("15[.][0-9]+[.][0-9]+[.][0-9]+")
          && server.known("product_level")
          && server.known("edition_id")
          && (server.is("engine_edition", 2) || server.is("engine_edition", 3))
          && (normalized.startsWith("standard edition")
              || normalized.startsWith("enterprise edition")
              || normalized.startsWith("developer edition")
              || normalized.startsWith("enterprise evaluation edition")
              || normalized.startsWith("evaluation edition"))) status = "PASS";
    }
    add(
        "database-version",
        status,
        "MSSQL_CDC_2019_BASELINE",
        "MSSQL_CDC_EDITION_UNSUPPORTED",
        "MSSQL_CDC_VERSION_UNCERTIFIED");
  }

  private void types(
      List<Row> columns,
      List<Row> capturedColumns,
      Set<String> captured,
      String databaseCollation) {
    String status =
        capturedColumns.stream()
                .anyMatch(column -> column.known("is_computed") && !column.is("is_computed", 0))
            ? "FAIL"
            : capturedColumns.stream().anyMatch(column -> !column.known("is_computed"))
                ? "UNKNOWN"
                : "PASS";
    String collationStatus = "PASS";
    for (Row column : columns) {
      if (!captured.contains(column.text("name"))) continue;
      var flags =
          List.of(
              "is_computed",
              "is_user_defined",
              "is_assembly_type",
              "is_filestream",
              "is_sparse",
              "is_column_set",
              "generated_always_type",
              "is_hidden");
      if (flags.stream().anyMatch(flag -> column.known(flag) && !column.is(flag, 0))
          || column.known("encryption_type")
          || (column.known("base_type")
              && column.known("declared_type")
              && !Objects.equals(column.text("base_type"), column.text("declared_type"))))
        status = "FAIL";
      else if (flags.stream().anyMatch(flag -> !column.known(flag))
          || !column.known("max_length")
          || !column.known("precision")
          || !column.known("scale")
          || !column.known("base_type")
          || !column.known("declared_type")) {
        if (!status.equals("FAIL")) status = "UNKNOWN";
      }
      if (List.of("max_length", "precision", "scale", "base_type", "declared_type").stream()
          .allMatch(column::known)) {
        try {
          SqlServerCdcAdapter.fieldType(definition(column, false));
        } catch (IntegrationException e) {
          status = "FAIL";
        }
      }
      if (Set.of("char", "varchar")
          .contains(column.text("base_type") == null ? "" : column.text("base_type"))) {
        if (databaseCollation == null || column.text("collation_name") == null) {
          if (!collationStatus.equals("FAIL")) collationStatus = "UNKNOWN";
        } else if (!databaseCollation.equals(column.text("collation_name")))
          collationStatus = "FAIL";
      }
    }
    add(
        "column-types",
        status,
        "MSSQL_CDC_TYPES_PROVEN",
        "MSSQL_CDC_TYPE_UNSUPPORTED",
        "MSSQL_CDC_TYPES_UNVERIFIED");
    add(
        "column-collation",
        collationStatus,
        "MSSQL_CDC_COLLATION_PROVEN",
        "MSSQL_CDC_COLLATION_RISK",
        "MSSQL_CDC_COLLATION_UNVERIFIED");
  }

  private void health() {
    var services = rows(metadata::agent);
    Row agent = services != null && services.size() == 1 ? services.getFirst() : Row.EMPTY;
    LocalDateTime startup = agent.time("last_startup_time");
    LocalDateTime now = agent.time("server_now");
    boolean lifetime = startup != null && now != null && !startup.isAfter(now);
    add(
        "agent-service",
        agent.is("startup_type", 4)
            ? "FAIL"
            : !agent.known("status") || !agent.known("startup_type") || !lifetime
                ? "UNKNOWN"
                : agent.is("status", 4) ? "PASS" : "FAIL",
        "MSSQL_AGENT_RUNNING",
        "MSSQL_AGENT_NOT_RUNNING",
        "MSSQL_AGENT_STATE_UNVERIFIED");
    var jobs = rows(metadata::jobs);
    List<Row> captures =
        jobs == null
            ? List.of()
            : jobs.stream().filter(job -> "capture".equals(job.text("job_type"))).toList();
    List<Row> cleanups =
        jobs == null
            ? List.of()
            : jobs.stream().filter(job -> "cleanup".equals(job.text("job_type"))).toList();
    if (jobs == null) {
      unknown("capture-job", "MSSQL_CAPTURE_JOB_UNVERIFIED");
      unknown("cleanup-retention", "MSSQL_CLEANUP_RETENTION_UNVERIFIED");
    } else {
      Row capture = captures.size() == 1 ? captures.getFirst() : Row.EMPTY;
      LocalDateTime start = capture.time("start_execution_date");
      String captureStatus =
          captures.isEmpty()
                  || capture.is("enabled", 0)
                  || capture.is("continuous", 0)
                  || capture.known("stop_execution_date")
              ? "FAIL"
              : captures.size() != 1
                      || !capture.is("enabled", 1)
                      || !capture.is("continuous", 1)
                      || !lifetime
                      || start == null
                      || start.isBefore(startup)
                      || start.isAfter(now)
                  ? "UNKNOWN"
                  : "PASS";
      add(
          "capture-job",
          captureStatus,
          "MSSQL_CAPTURE_JOB_RUNNING",
          "MSSQL_CAPTURE_JOB_UNAVAILABLE",
          "MSSQL_CAPTURE_JOB_UNVERIFIED");
      Row cleanup = cleanups.size() == 1 ? cleanups.getFirst() : Row.EMPTY;
      LocalDateTime next = cleanup.time("next_scheduled_run_date");
      String cleanupStatus =
          cleanups.isEmpty()
                  || cleanup.is("enabled", 0)
                  || cleanup.is("has_enabled_schedule", 0)
                  || (cleanup.number("retention") != null && cleanup.number("retention") <= 0)
                  || (next != null && now != null && !next.isAfter(now))
              ? "FAIL"
              : cleanups.size() != 1
                      || !cleanup.is("enabled", 1)
                      || !cleanup.is("has_enabled_schedule", 1)
                      || !positive(cleanup.number("retention"))
                      || next == null
                      || now == null
                  ? "UNKNOWN"
                  : "PASS";
      add(
          "cleanup-retention",
          cleanupStatus,
          "MSSQL_CLEANUP_RETENTION_CONFIGURED",
          "MSSQL_CLEANUP_RETENTION_INVALID",
          "MSSQL_CLEANUP_RETENTION_UNVERIFIED");
    }
    Row scan = row(metadata::scan);
    var errors = rows(metadata::errors);
    LocalDateTime scanStart = scan.time("start_time");
    LocalDateTime scanEnd = scan.time("end_time");
    boolean scanKnown =
        lifetime
            && scanStart != null
            && !scanStart.isBefore(startup)
            && !scanStart.isAfter(now)
            && (scanEnd == null || (!scanEnd.isBefore(scanStart) && !scanEnd.isAfter(now)))
            && positive(scan.number("session_id"))
            && scan.number("error_count") != null
            && scan.number("error_count") >= 0
            && scan.known("scan_phase")
            && errors != null
            && errors.stream()
                .allMatch(
                    error ->
                        positive(error.number("session_id"))
                            && error.time("entry_time") != null
                            && error.number("error_number") != null);
    boolean hasErrors =
        scanKnown
            && (scan.number("error_count") > 0
                || errors.stream()
                    .anyMatch(error -> error.number("session_id") >= scan.number("session_id")));
    add(
        "capture-errors",
        !scanKnown ? "UNKNOWN" : hasErrors ? "FAIL" : "PASS",
        "MSSQL_CDC_CURRENT_SCAN_NO_ERRORS",
        "MSSQL_CDC_CURRENT_SCAN_ERRORS",
        "MSSQL_CDC_CAPTURE_HEALTH_UNVERIFIED");
  }

  private static ColumnDefinition definition(Row row, boolean key) {
    String type = row.text("base_type");
    int jdbc =
        switch (type == null ? "" : type) {
          case "bit" -> Types.BIT;
          case "tinyint" -> Types.SMALLINT;
          case "smallint" -> Types.SMALLINT;
          case "int" -> Types.INTEGER;
          case "bigint" -> Types.BIGINT;
          case "decimal", "numeric", "money", "smallmoney" -> Types.DECIMAL;
          case "char", "uniqueidentifier" -> Types.CHAR;
          case "varchar" -> Types.VARCHAR;
          case "nchar" -> Types.NCHAR;
          case "nvarchar" -> Types.NVARCHAR;
          case "date" -> Types.DATE;
          case "datetime", "smalldatetime", "datetime2" -> Types.TIMESTAMP;
          default -> Types.OTHER;
        };
    int size =
        Set.of(Types.DECIMAL, Types.SMALLINT, Types.INTEGER, Types.BIGINT).contains(jdbc)
            ? number(row.number("precision"))
            : "uniqueidentifier".equals(type) ? 36 : number(row.number("max_length"));
    if (Set.of(Types.NCHAR, Types.NVARCHAR).contains(jdbc) && size > 0) size /= 2;
    return new ColumnDefinition(
        row.text("name"),
        type,
        !row.is("is_nullable", 0),
        key,
        jdbc,
        size,
        number(row.number("scale")),
        false,
        false,
        0,
        JdbcDialect.Mssql);
  }

  private void permissions(Row row, String id, List<String> fields, String prefix) {
    permission(id, fields.stream().map(row::number).toList(), prefix);
  }

  private void permission(String id, List<Integer> values, String prefix) {
    String status =
        values.contains(0)
            ? "FAIL"
            : values.stream().allMatch(value -> Integer.valueOf(1).equals(value))
                ? "PASS"
                : "UNKNOWN";
    add(id, status, prefix + "_PROVEN", prefix + "_DENIED", prefix + "_UNVERIFIED");
  }

  private void flag(
      String id, Row row, String field, int expected, String pass, String fail, String unknown) {
    add(
        id,
        !row.known(field) ? "UNKNOWN" : row.is(field, expected) ? "PASS" : "FAIL",
        pass,
        fail,
        unknown);
  }

  private void add(String id, String status, String pass, String fail, String unknown) {
    checks.add(
        new CdcCheck(
            id,
            status,
            status.equals("PASS") ? pass : status.equals("FAIL") ? fail : unknown,
            true));
  }

  private void pass(String id, String code) {
    checks.add(new CdcCheck(id, "PASS", code, true));
  }

  private void fail(String id, String code) {
    checks.add(new CdcCheck(id, "FAIL", code, true));
  }

  private void unknown(String id, String code) {
    checks.add(new CdcCheck(id, "UNKNOWN", code, true));
  }

  private static boolean positive(Integer value) {
    return value != null && value > 0;
  }

  private static int number(Integer value) {
    return value == null ? -1 : value;
  }

  private static boolean nonzero(byte[] value) {
    if (value == null || value.length != 10) return false;
    for (byte item : value) if (item != 0) return true;
    return false;
  }

  @FunctionalInterface
  private interface Query<T> {
    T get() throws SQLException;
  }

  private static Row row(Query<Row> query) {
    try {
      return query.get();
    } catch (SQLException e) {
      return Row.EMPTY;
    }
  }

  private static List<Row> rows(Query<List<Row>> query) {
    try {
      return query.get();
    } catch (SQLException e) {
      return null;
    }
  }
}
