package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightInput;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightResult;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class SqlServerCdcPreflightTest {
  @Test
  void provesOnlyBoundedSelectEvidenceAndKeepsRuntimeCertificationUnknown() throws Exception {
    var fixture = new Fixture();
    var result = fixture.check();
    assertTrue(result.ready(), result.checks().toString());
    assertEquals("HisDb", result.databaseName());
    assertEquals("15.0.4430.1", result.databaseVersion());
    check(result, "runtime-certification", "UNKNOWN", "MSSQL_CDC_RUNTIME_UNVERIFIED", false);
    check(result, "engine-network", "UNKNOWN", "ENGINE_SOURCE_NETWORK_UNVERIFIED", false);
    assertTrue(fixture.readOnly);
    assertTrue(fixture.queries.size() >= 14);
    for (String sql : fixture.queries) {
      assertTrue(sql.startsWith("SELECT "), sql);
      assertFalse(
          sql.matches("(?is).*\\b(EXEC|USE|GRANT|ALTER|INSERT|UPDATE|DELETE|syssessions)\\b.*"),
          sql);
      assertFalse(sql.contains("FROM [dbo].[ORDERS]"));
    }
    assertTrue(fixture.timeouts.stream().allMatch(value -> value > 0 && value <= 5));
    assertTrue(fixture.limits.stream().allMatch(value -> value > 0 && value <= 1025));
    assertTrue(
        fixture.bindings.stream().anyMatch(values -> values.equals(List.of("dbo", "ORDERS"))));
    assertTrue(fixture.bindings.stream().anyMatch(values -> values.equals(List.of(200))));
    assertFalse(new ObjectMapper().writeValueAsString(result).contains("000000"));
  }

  @Test
  void actualEditionAndMajorMustBeCertifiedAndAdrQueryNeverRunsOn2016() {
    for (String edition : List.of("Web Edition", "Express Edition")) {
      var fixture = new Fixture();
      fixture.server.put("edition", edition);
      check(fixture.check(), "database-version", "FAIL", "MSSQL_CDC_EDITION_UNSUPPORTED", true);
    }
    for (int major : List.of(13, 16, 17)) {
      var fixture = new Fixture();
      fixture.server.put("product_major_version", String.valueOf(major));
      check(fixture.check(), "database-version", "UNKNOWN", "MSSQL_CDC_VERSION_UNCERTIFIED", true);
      assertFalse(
          fixture.queries.stream()
              .anyMatch(sql -> sql.contains("is_accelerated_database_recovery_on")));
    }
  }

  @Test
  void everyRequiredUnknownOrObservedIncompatibilityBlocksReadiness() {
    for (String field :
        List.of(
            "is_cdc_enabled",
            "state",
            "is_read_only",
            "containment",
            "is_published",
            "is_merge_published",
            "is_subscribed",
            "is_distributor")) {
      var fixture = new Fixture();
      fixture.database.put(field, null);
      assertFalse(fixture.check().ready(), field);
    }
    var fixture = new Fixture();
    fixture.database.put("is_cdc_enabled", 0);
    check(fixture.check(), "database-cdc", "FAIL", "MSSQL_DATABASE_CDC_DISABLED", true);
    fixture = new Fixture();
    fixture.database.put("name", "hisdb");
    check(fixture.check(), "database-name", "FAIL", "MSSQL_DB_NAME_MISMATCH", true);
    fixture = new Fixture();
    fixture.adr.put("is_accelerated_database_recovery_on", 1);
    check(fixture.check(), "database-adr", "FAIL", "MSSQL_CDC_ADR_INCOMPATIBLE", true);
    fixture = new Fixture();
    fixture.database.put("collation_name", "Latin1_General_CS_AS");
    check(
        fixture.check(),
        "database-collation",
        "FAIL",
        "MSSQL_CDC_DATABASE_COLLATION_UNSUPPORTED",
        true);
  }

  @Test
  void configuredIdentityAndTimezoneAreRequiredButValidIncompleteDraftsRemainInspectable() {
    var fixture = new Fixture();
    var result =
        fixture
            .preflight()
            .check(
                "synthetic-source",
                new CdcPreflightInput("dbo", "ORDERS", null, null, null, null, null));
    assertFalse(result.ready());
    check(result, "database-name", "PASS", "MSSQL_DB_NAME_PROVEN", true);
    check(result, "server-time-zone", "UNKNOWN", "MSSQL_TIME_ZONE_REQUIRED", true);
    assertThrows(
        IntegrationException.class,
        () ->
            fixture
                .preflight()
                .check(
                    "synthetic-source",
                    new CdcPreflightInput(
                        "dbo",
                        "ORDERS",
                        null,
                        new CdcOptions("HisDb", "UTC", "PDB", "ROOT"),
                        null,
                        null,
                        null)));
  }

  @Test
  void optionalDatabaseHintDiscoversActualIdentityButCannotChangeTheRegisteredDatabase() {
    var fixture = new Fixture();
    var request =
        new CdcPreflightInput(
            "dbo", "ORDERS", null, new CdcOptions(null, "UTC", null, null), null, null, null);
    assertTrue(fixture.preflight().check("synthetic-source", request).ready());
    fixture.server.put("database_name", "OtherDb");
    fixture.database.put("name", "OtherDb");
    check(
        fixture.preflight().check("synthetic-source", request),
        "database-name",
        "FAIL",
        "MSSQL_DB_NAME_MISMATCH",
        true);
  }

  @Test
  void requiresExactlyOneActiveConsistentCaptureAndEveryMappedAndKeyColumn() {
    for (int count : List.of(0, 2, 3)) {
      var fixture = new Fixture();
      fixture.captures = Collections.nCopies(count, fixture.capture);
      assertFalse(fixture.check().ready());
    }
    for (String field :
        List.of("object_id", "source_object_id", "capture_instance", "create_date", "start_lsn")) {
      var fixture = new Fixture();
      fixture.capture.put(field, null);
      assertFalse(fixture.check().ready(), field);
    }
    var fixture = new Fixture();
    fixture.capture.put("has_drop_pending", 1);
    check(fixture.check(), "capture-instance", "FAIL", "MSSQL_CAPTURE_DROP_PENDING", true);
    fixture = new Fixture();
    fixture.captured.get(1).put("column_name", "WRONG");
    check(fixture.check(), "captured-columns", "FAIL", "MSSQL_CAPTURED_COLUMNS_INCOMPLETE", true);
    fixture = new Fixture();
    fixture.pk.getFirst().put("is_disabled", 1);
    check(fixture.check(), "primary-key", "FAIL", "INVALID_PRIMARY_KEY", true);
  }

  @Test
  void requiresSelectForAllCapturedColumnsAndTreatsNullAndZeroLsnAsUnknown() {
    for (Integer permission : new Integer[] {0, null}) {
      var fixture = new Fixture();
      fixture.columns.get(1).put("can_select", permission);
      check(
          fixture.check(List.of("ID")),
          "source-permissions",
          permission == null ? "UNKNOWN" : "FAIL",
          permission == null ? "MSSQL_CDC_SELECT_UNVERIFIED" : "MSSQL_CDC_SELECT_DENIED",
          true);
    }
    var fixture = new Fixture();
    fixture.permissions.put("can_select_changes", null);
    assertFalse(fixture.check().ready());
    fixture = new Fixture();
    fixture.lsn.put("min_lsn", new byte[10]);
    check(fixture.check(), "cdc-lsn-range", "UNKNOWN", "MSSQL_CDC_LSN_RANGE_UNAVAILABLE", true);
    fixture = new Fixture();
    fixture.lsn.put("min_lsn", bytes(3));
    fixture.lsn.put("max_lsn", bytes(2));
    assertFalse(fixture.check().ready());
  }

  @Test
  void snapshotPartitionMetadataRequiresEffectiveDatabaseViewDefinition() {
    for (Integer permission : new Integer[] {0, null}) {
      var fixture = new Fixture();
      fixture.permissions.put("can_view_definition", permission);
      check(
          fixture.check(),
          "metadata-permissions",
          permission == null ? "UNKNOWN" : "FAIL",
          permission == null ? "MSSQL_CDC_STATE_UNVERIFIED" : "MSSQL_CDC_STATE_DENIED",
          true);
    }
  }

  @Test
  void nativeInitialSnapshotRequiresSelectOnUncapturedColumnsWithoutExpandingTypeGuards() {
    var fixture = new Fixture();
    var uncaptured = Fixture.column(3, "UNCAPTURED", "xml", -1, 0, 0, 1);
    fixture.columns.add(uncaptured);
    assertTrue(fixture.check().ready());
    for (Integer permission : new Integer[] {0, null}) {
      uncaptured.put("can_select", permission);
      check(
          fixture.check(),
          "source-permissions",
          permission == null ? "UNKNOWN" : "FAIL",
          permission == null ? "MSSQL_CDC_SELECT_UNVERIFIED" : "MSSQL_CDC_SELECT_DENIED",
          true);
    }
    uncaptured.put("can_select", 1);
    assertTrue(fixture.check().ready());
  }

  @Test
  void agentAndJobsMustBelongToTheCurrentLifetimeAndCleanupMustHaveFutureSchedule() {
    for (String field : List.of("last_startup_time", "status")) {
      var fixture = new Fixture();
      fixture.agent.put(field, null);
      assertFalse(fixture.check().ready());
    }
    for (String field : List.of("enabled", "continuous", "start_execution_date")) {
      var fixture = new Fixture();
      fixture.jobs.getFirst().put(field, null);
      assertFalse(fixture.check().ready());
    }
    var fixture = new Fixture();
    fixture.jobs.getFirst().put("start_execution_date", time("2026-11-13 00:00:00"));
    check(fixture.check(), "capture-job", "UNKNOWN", "MSSQL_CAPTURE_JOB_UNVERIFIED", true);
    fixture = new Fixture();
    fixture.jobs.get(1).put("next_scheduled_run_date", time("2026-11-14 01:00:00"));
    check(fixture.check(), "cleanup-retention", "FAIL", "MSSQL_CLEANUP_RETENTION_INVALID", true);
    fixture = new Fixture();
    fixture.agent.put("status", 1);
    check(fixture.check(), "agent-service", "FAIL", "MSSQL_AGENT_NOT_RUNNING", true);
  }

  @Test
  void currentScanErrorsBlockButOlderSupersededErrorsDoNot() {
    var fixture = new Fixture();
    fixture.errors =
        List.of(
            row(
                "session_id",
                99,
                "entry_time",
                time("2026-11-14 09:00:00"),
                "error_number",
                22859));
    assertTrue(fixture.check().ready());
    fixture.errors =
        List.of(
            row(
                "session_id",
                100,
                "entry_time",
                time("2026-11-14 09:00:00"),
                "error_number",
                22859));
    check(fixture.check(), "capture-errors", "FAIL", "MSSQL_CDC_CURRENT_SCAN_ERRORS", true);
    fixture = new Fixture();
    fixture.scan.put("start_time", time("2026-11-13 01:00:00"));
    check(
        fixture.check(), "capture-errors", "UNKNOWN", "MSSQL_CDC_CAPTURE_HEALTH_UNVERIFIED", true);
  }

  @Test
  void capturedComputedColumnsAndDisabledAgentCannotPass() {
    var fixture = new Fixture();
    fixture.captured.get(1).put("is_computed", 1);
    check(fixture.check(), "column-types", "FAIL", "MSSQL_CDC_TYPE_UNSUPPORTED", true);
    fixture = new Fixture();
    fixture.agent.put("startup_type", 4);
    check(fixture.check(), "agent-service", "FAIL", "MSSQL_AGENT_NOT_RUNNING", true);
  }

  @Test
  void metadataDenialsLimitsAndUnsupportedCapturedFieldsNeverLeakOrPass() throws Exception {
    var fixture = new Fixture();
    fixture.denied = "cdc.change_tables";
    var result = fixture.check();
    assertFalse(result.ready());
    assertFalse(new ObjectMapper().writeValueAsString(result).contains("secret SQL password LSN"));
    fixture = new Fixture();
    fixture.columns = Collections.nCopies(1025, fixture.columns.getFirst());
    assertFalse(fixture.check().ready());
    for (String flag :
        List.of(
            "is_computed",
            "is_user_defined",
            "is_assembly_type",
            "is_filestream",
            "is_sparse",
            "is_column_set",
            "generated_always_type",
            "is_hidden")) {
      fixture = new Fixture();
      fixture.columns.get(1).put(flag, 1);
      assertFalse(fixture.check().ready(), flag);
    }
    fixture = new Fixture();
    fixture.columns.get(1).put("base_type", "datetime2");
    fixture.columns.get(1).put("declared_type", "datetime2");
    fixture.columns.get(1).put("scale", 4);
    check(fixture.check(), "column-types", "FAIL", "MSSQL_CDC_TYPE_UNSUPPORTED", true);
    fixture = new Fixture();
    fixture.columns.get(1).put("base_type", "varchar");
    fixture.columns.get(1).put("declared_type", "varchar");
    fixture.columns.get(1).put("collation_name", "Other_CI_AS");
    check(fixture.check(), "column-collation", "FAIL", "MSSQL_CDC_COLLATION_RISK", true);
  }

  @Test
  void taskPreflightRequiresEveryTargetColumnEvenNullableAndDefaulted() {
    var fixture = new Fixture();
    var task = task();
    assertTrue(fixture.preflight().check(task).ready());
    fixture.target =
        new TableDefinition(
            "ods_validation",
            "patient_ods",
            List.of(
                DorisTableMetadata.column("id", "bigint", false, true, false),
                DorisTableMetadata.column("name", "string", true, false, false),
                DorisTableMetadata.column("extra", "string", true, false, true)));
    check(
        fixture.preflight().check(task),
        "target-mapping",
        "FAIL",
        "MSSQL_CDC_TARGET_PROJECTION_INCOMPLETE",
        true);
  }

  @Test
  void targetSchemaScopeIsCheckedBeforeAnySourceMetadataQuery() {
    var fixture = new Fixture();
    var request = task();
    request.targetSchema = "foreign_schema";
    assertEquals(
        "SCHEMA_NOT_ALLOWED",
        assertThrows(IntegrationException.class, () -> fixture.preflight().check(request)).code());
    assertTrue(fixture.queries.isEmpty());
  }

  @Test
  void tableAndSchemaIdentityUseActualCatalogCaseAndRejectDifferentRequestCase() {
    for (String field : List.of("schema_name", "table_name")) {
      var fixture = new Fixture();
      fixture.table.put(field, field.equals("schema_name") ? "DBO" : "Orders");
      var inspection = fixture.preflight().inspect(task());
      check(
          inspection.result(),
          "source-identifiers",
          "FAIL",
          "MSSQL_CDC_IDENTIFIER_UNSUPPORTED",
          true);
      assertEquals(fixture.table.get("schema_name"), inspection.source().schema());
      assertEquals(fixture.table.get("table_name"), inspection.source().name());
    }
  }

  @Test
  void missingPrecisionAndKeyEvidenceRemainUnknownAndInvalidMappingsHaveOneColumnsCheck() {
    var fixture = new Fixture();
    fixture.columns.get(1).put("scale", null);
    check(fixture.check(), "column-types", "UNKNOWN", "MSSQL_CDC_TYPES_UNVERIFIED", true);
    fixture = new Fixture();
    fixture.pk.getFirst().put("is_disabled", null);
    check(fixture.check(), "primary-key", "UNKNOWN", "MSSQL_PRIMARY_KEY_UNVERIFIED", true);
    fixture = new Fixture();
    var result = fixture.check(List.of("ID", "MISSING"));
    check(result, "columns", "FAIL", "CDC_COLUMNS_INVALID", true);
    assertEquals(
        result.checks().size(),
        result.checks().stream().map(check -> check.id()).distinct().count());
  }

  static TaskInput task() {
    var task = IntegrationCdcExecutionTest.input();
    task.sourceSchema = "dbo";
    task.sourceTable = "ORDERS";
    task.primaryKey = "ID";
    task.fieldMappings = SqlServerCdcAdapterTest.MAPPINGS;
    task.cdc = SqlServerCdcAdapterTest.OPTIONS;
    return task;
  }

  static void check(
      CdcPreflightResult result, String id, String status, String code, boolean required) {
    assertTrue(
        result.checks().stream()
            .anyMatch(
                c ->
                    c.id().equals(id)
                        && c.status().equals(status)
                        && c.code().equals(code)
                        && c.required() == required),
        result.checks().toString());
    if (required && !status.equals("PASS")) assertFalse(result.ready());
  }

  static Timestamp time(String value) {
    return Timestamp.valueOf(value);
  }

  static byte[] bytes(int value) {
    byte[] bytes = new byte[10];
    bytes[9] = (byte) value;
    return bytes;
  }

  static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]);
    return row;
  }

  static class Fixture {
    final Map<String, Object> server =
        row(
            "product_version",
            "15.0.4430.1",
            "product_major_version",
            "15",
            "product_level",
            "RTM",
            "edition",
            "Standard Edition (64-bit)",
            "engine_edition",
            2,
            "edition_id",
            123L,
            "is_clustered",
            0,
            "database_name",
            "HisDb");
    final Map<String, Object> database =
        row(
            "database_id",
            7,
            "name",
            "HisDb",
            "create_date",
            time("2020-01-01 00:00:00"),
            "collation_name",
            "Latin1_General_CI_AS",
            "is_cdc_enabled",
            1,
            "state",
            0,
            "is_read_only",
            0,
            "containment",
            0,
            "source_database_id",
            null,
            "is_published",
            0,
            "is_merge_published",
            0,
            "is_subscribed",
            0,
            "is_distributor",
            0,
            "replica_id",
            null,
            "group_database_id",
            null,
            "mirroring_guid",
            null);
    final Map<String, Object> adr = row("is_accelerated_database_recovery_on", 0);
    final Map<String, Object> table =
        row(
            "schema_name",
            "dbo",
            "table_name",
            "ORDERS",
            "object_id",
            100,
            "is_tracked_by_cdc",
            1,
            "is_memory_optimized",
            0,
            "is_filetable",
            0,
            "temporal_type",
            0,
            "is_external",
            0,
            "is_ms_shipped",
            0,
            "has_columnstore",
            0);
    List<Map<String, Object>> columns =
        new ArrayList<>(
            List.of(
                column(1, "ID", "int", 4, 10, 0, 0), column(2, "VALUE", "nvarchar", 200, 0, 0, 1)));
    List<Map<String, Object>> pk =
        new ArrayList<>(
            List.of(
                row(
                    "name",
                    "ID",
                    "column_id",
                    1,
                    "is_nullable",
                    0,
                    "is_unique",
                    1,
                    "is_disabled",
                    0,
                    "is_hypothetical",
                    0,
                    "key_ordinal",
                    1)));
    final Map<String, Object> capture =
        row(
            "object_id",
            200,
            "source_object_id",
            100,
            "capture_instance",
            "dbo_ORDERS",
            "has_drop_pending",
            0,
            "role_name",
            null,
            "index_name",
            "PK_ORDERS",
            "create_date",
            time("2026-11-01 00:00:00"),
            "partition_switch",
            0,
            "start_lsn",
            bytes(1));
    List<Map<String, Object>> captures = List.of(capture);
    List<Map<String, Object>> captured =
        new ArrayList<>(
            List.of(
                row(
                    "column_id",
                    1,
                    "column_name",
                    "ID",
                    "column_ordinal",
                    1,
                    "column_type",
                    "int",
                    "is_computed",
                    0),
                row(
                    "column_id",
                    2,
                    "column_name",
                    "VALUE",
                    "column_ordinal",
                    2,
                    "column_type",
                    "nvarchar",
                    "is_computed",
                    0)));
    final Map<String, Object> permissions =
        row(
            "is_sysadmin",
            0,
            "is_db_owner",
            0,
            "is_gating_role_member",
            null,
            "can_select_changes",
            1,
            "can_view_database_state",
            1,
            "can_view_definition",
            1,
            "can_view_server_state",
            1);
    final Map<String, Object> nativeAccess =
        row("change_tables", 1, "captured_columns", 1, "ddl_history", 1, "lsn_time_mapping", 1);
    final Map<String, Object> lsn = row("min_lsn", bytes(1), "max_lsn", bytes(2));
    final Map<String, Object> agent =
        row(
            "status",
            4,
            "startup_type",
            2,
            "last_startup_time",
            time("2026-11-14 08:00:00"),
            "server_now",
            time("2026-11-14 10:00:00"));
    final List<Map<String, Object>> jobs =
        new ArrayList<>(
            List.of(
                row(
                    "job_type",
                    "capture",
                    "continuous",
                    1,
                    "retention",
                    0,
                    "pollinginterval",
                    5,
                    "enabled",
                    1,
                    "start_execution_date",
                    time("2026-11-14 08:01:00"),
                    "stop_execution_date",
                    null,
                    "next_scheduled_run_date",
                    null,
                    "has_enabled_schedule",
                    0),
                row(
                    "job_type",
                    "cleanup",
                    "continuous",
                    0,
                    "retention",
                    4320,
                    "pollinginterval",
                    0,
                    "enabled",
                    1,
                    "start_execution_date",
                    null,
                    "stop_execution_date",
                    null,
                    "next_scheduled_run_date",
                    time("2026-11-15 02:00:00"),
                    "has_enabled_schedule",
                    1)));
    final Map<String, Object> scan =
        row(
            "session_id",
            100,
            "start_time",
            time("2026-11-14 09:00:00"),
            "end_time",
            time("2026-11-14 09:01:00"),
            "scan_phase",
            "Done",
            "error_count",
            0);
    List<Map<String, Object>> errors = List.of();
    TableDefinition target =
        new TableDefinition(
            "ods_validation",
            "patient_ods",
            List.of(
                DorisTableMetadata.column("id", "bigint", false, true, false),
                DorisTableMetadata.column("name", "string", true, false, false)));
    String denied;
    boolean readOnly;
    final List<String> queries = new ArrayList<>();
    final List<Integer> timeouts = new ArrayList<>();
    final List<Integer> limits = new ArrayList<>();
    final List<List<Object>> bindings = new ArrayList<>();

    IntegrationCdcPreflight preflight() {
      return new IntegrationCdcPreflight(
          id ->
              id.equals("synthetic-source")
                  ? SqlServerCdcAdapterTest.endpoint("VERIFY")
                  : IntegrationCdcExecutionTest.configuration().endpoint(id),
          (endpoint, properties) -> connection(),
          (id, schema, name) -> target,
          plugin -> plugin.equals("SqlServer-CDC"));
    }

    CdcPreflightResult check() {
      return check(List.of("ID", "VALUE"));
    }

    CdcPreflightResult check(List<String> columns) {
      return preflight()
          .check(
              "synthetic-source",
              new CdcPreflightInput(
                  "dbo", "ORDERS", columns, SqlServerCdcAdapterTest.OPTIONS, null, null, null));
    }

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("setReadOnly")) {
                  readOnly = true;
                  return null;
                }
                if (method.getName().equals("prepareStatement")) return statement((String) args[0]);
                return defaultValue(method.getReturnType());
              });
    }

    PreparedStatement statement(String sql) {
      List<Object> parameters = new ArrayList<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setQueryTimeout":
                    timeouts.add((int) args[0]);
                    break;
                  case "setMaxRows":
                    limits.add((int) args[0]);
                    break;
                  case "setString":
                  case "setInt":
                  case "setObject":
                    int index = (int) args[0];
                    while (parameters.size() < index) parameters.add(null);
                    parameters.set(index - 1, args[1]);
                    break;
                  case "executeQuery":
                    queries.add(sql);
                    bindings.add(new ArrayList<>(parameters));
                    if (denied != null && sql.contains(denied))
                      throw new SQLException("secret SQL password LSN");
                    return rows(select(sql));
                  default:
                    break;
                }
                return defaultValue(method.getReturnType());
              });
    }

    List<Map<String, Object>> select(String sql) {
      if (sql.contains("ProductVersion")) return List.of(server);
      if (sql.contains("is_accelerated_database_recovery_on")) return List.of(adr);
      if (sql.contains("FROM sys.databases")) return List.of(database);
      if (sql.contains("FROM sys.tables")) return List.of(table);
      if (sql.contains("FROM sys.columns")) return columns;
      if (sql.contains("FROM sys.indexes")) return pk;
      if (sql.contains("FROM cdc.change_tables")) return captures;
      if (sql.contains("FROM cdc.captured_columns")) return captured;
      if (sql.contains("IS_SRVROLEMEMBER")) return List.of(permissions);
      if (sql.contains("AS change_tables")) return List.of(nativeAccess);
      if (sql.contains("fn_cdc_get_min_lsn")) return List.of(lsn);
      if (sql.contains("dm_server_services")) return List.of(agent);
      if (sql.contains("msdb.dbo.cdc_jobs")) return jobs;
      if (sql.contains("dm_cdc_log_scan_sessions")) return List.of(scan);
      if (sql.contains("dm_cdc_errors")) return errors;
      throw new AssertionError(sql);
    }

    ResultSet rows(List<Map<String, Object>> rows) {
      int[] index = {-1};
      boolean[] wasNull = {false};
      return (ResultSet)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) -> {
                if (method.getName().equals("next")) return ++index[0] < rows.size();
                if (method.getName().equals("wasNull")) return wasNull[0];
                if (method.getName().startsWith("get")) {
                  Object value = rows.get(index[0]).get(args[0]);
                  wasNull[0] = value == null;
                  if (method.getName().equals("getString"))
                    return value == null ? null : value.toString();
                  if (method.getName().equals("getInt"))
                    return value == null ? 0 : ((Number) value).intValue();
                  return value;
                }
                return defaultValue(method.getReturnType());
              });
    }

    static Map<String, Object> column(
        int id, String name, String type, int length, int precision, int scale, int nullable) {
      return row(
          "column_id",
          id,
          "name",
          name,
          "base_type",
          type,
          "declared_type",
          type,
          "is_user_defined",
          0,
          "is_assembly_type",
          0,
          "max_length",
          length,
          "precision",
          precision,
          "scale",
          scale,
          "collation_name",
          type.equals("nvarchar") ? "Latin1_General_CI_AS" : null,
          "is_nullable",
          nullable,
          "is_computed",
          0,
          "is_identity",
          0,
          "is_filestream",
          0,
          "is_sparse",
          0,
          "is_column_set",
          0,
          "generated_always_type",
          0,
          "encryption_type",
          null,
          "is_hidden",
          0,
          "can_select",
          1);
    }

    static Object defaultValue(Class<?> type) {
      if (type == boolean.class) return false;
      if (type == int.class) return 0;
      if (type == long.class) return 0L;
      return null;
    }
  }
}
