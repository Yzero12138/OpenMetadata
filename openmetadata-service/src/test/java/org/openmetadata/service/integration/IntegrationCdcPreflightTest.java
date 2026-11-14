package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcCheck;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightInput;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightResult;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class IntegrationCdcPreflightTest {
  private static final CdcOptions OPTIONS = new CdcOptions("HISDB", "Asia/Shanghai", null, null);

  @Test
  void sourceAndTaskPreflightRejectDollarSchemaAndTableNamesButAcceptDollarColumns() {
    for (String[] route :
        List.of(new String[] {"HOS$", "ORDERS"}, new String[] {"HIS", "PAT$ENT"})) {
      var fixture = new OracleFixture();
      fixture.source = CdcAdaptersTest.endpoint("Oracle", "VERIFY", route[0]);
      fixture.target = target(false);
      var request =
          new CdcPreflightInput(route[0], route[1], List.of("ID", "AT"), OPTIONS, null, null, null);
      var sourceResult = fixture.preflight().check("synthetic-source", request);
      assertFalse(sourceResult.ready());
      assertCheck(
          sourceResult, "logminer-identifiers", "FAIL", "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", true);
      var task = task();
      task.sourceSchema = route[0];
      task.sourceTable = route[1];
      var taskResult = fixture.preflight().check(task);
      assertFalse(taskResult.ready());
      assertCheck(
          taskResult, "logminer-identifiers", "FAIL", "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", true);
    }
    var fixture = new OracleFixture();
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.column("AT$", "DATE", "Y", null, null));
    fixture.target = target(false);
    var task = task();
    task.fieldMappings = List.of(new FieldMapping("ID", "id"), new FieldMapping("AT$", "at"));
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT$"))).ready());
    assertTrue(fixture.preflight().check(task).ready());
  }

  @Test
  void selectOnlyPreflightProvesKnownNonCdbPrerequisitesWithoutClaimingRuntimeAcceptance() {
    var fixture = new OracleFixture();
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID", "AT")));
    assertTrue(result.implemented());
    assertTrue(result.ready());
    assertEquals("Oracle", result.databaseType());
    assertEquals("11.2.0.1.0", result.databaseVersion());
    assertEquals("HISDB", result.databaseName());
    assertCheck(result, "database-version", "UNKNOWN", "ORACLE_CDC_VERSION_UNCERTIFIED", false);
    assertCheck(result, "engine-network", "UNKNOWN", "ENGINE_SOURCE_NETWORK_UNVERIFIED", false);
    assertEquals(List.of("Oracle-CDC"), fixture.plugins);
    assertTrue(fixture.readOnly);
    assertTrue(fixture.queries.stream().allMatch(sql -> sql.startsWith("SELECT ")));
    assertTrue(
        fixture.queries.stream()
            .noneMatch(
                sql ->
                    sql.matches(
                        "(?is).*\\b(ANALYZE|CREATE|ALTER|BEGIN|CALL|LOCK|INSERT|UPDATE|DELETE)\\b.*")));
    assertTrue(
        fixture.bindings.stream().anyMatch(values -> values.equals(List.of("HIS", "ORDERS"))));
    assertTrue(fixture.timeouts.stream().allMatch(value -> value > 0 && value <= 5));
    assertTrue(fixture.maxRows.stream().allMatch(value -> value > 0 && value <= 513));
  }

  @Test
  void reportsOnlyStatusesSupportedByThePreflightContract() {
    var fixture = new OracleFixture();
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID", "AT")));
    assertTrue(
        result.checks().stream()
            .allMatch(
                check ->
                    Set.of("PASS", "FAIL", "UNKNOWN", "NOT_APPLICABLE").contains(check.status())),
        result.checks().toString());
  }

  @Test
  void actualNoArchiveAndMinimumNoBlockIndependentlyOfPrivilegePasses() {
    var fixture = new OracleFixture();
    fixture.logMode = "NOARCHIVELOG";
    fixture.minimum = "NO";
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID", "AT")));
    assertFalse(result.ready());
    assertCheck(result, "archive-log", "FAIL", "ORACLE_ARCHIVELOG_REQUIRED", true);
    assertCheck(
        result, "minimum-supplemental-logging", "FAIL", "ORACLE_MIN_SUPPLEMENTAL_REQUIRED", true);
    assertCheck(result, "package-execute", "PASS", "EXECUTE_GRANT_PROVEN", true);
  }

  @Test
  void unconditionalGroupMustCoverEverySelectedColumnByName() {
    var fixture = new OracleFixture();
    fixture.globalAll = "NO";
    fixture.members = List.of(fixture.member("G1", "ID", "LOG"));
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID", "AT")));
    assertFalse(result.ready());
    assertCheck(
        result,
        "table-supplemental-logging",
        "FAIL",
        "TABLE_SUPPLEMENTAL_LOGGING_INCOMPLETE",
        true);
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
  }

  @Test
  void loggingCoverageUsesExactJoinsAndIgnoresConditionalOrNoLogMembers() {
    for (String[] change :
        List.of(
            new String[] {"CONDITIONAL", "LOG"}, new String[] {"ALWAYS", "NO LOG"},
            new String[] {"N", "LOG"}, new String[] {"ALWAYS", "NO_LOG"})) {
      var fixture = new OracleFixture();
      fixture.globalAll = "NO";
      fixture.groups =
          List.of(
              fixture.group("G1", "USER LOG GROUP", "ALWAYS"),
              fixture.group("G2", "USER LOG GROUP", change[0]));
      fixture.members =
          List.of(fixture.member("G1", "ID", "LOG"), fixture.member("G2", "AT", change[1]));
      assertFalse(
          fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
    }
    var fixture = new OracleFixture();
    fixture.globalAll = "NO";
    fixture.members =
        List.of(
            fixture.member("G1", "ID", "LOG"),
            row(
                "OWNER",
                "OTHER",
                "TABLE_NAME",
                "ORDERS",
                "LOG_GROUP_NAME",
                "G1",
                "COLUMN_NAME",
                "AT",
                "LOGGING_PROPERTY",
                "LOG"));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
  }

  @Test
  void unionOfVerifiedUnconditionalGroupsAndGlobalOrTableAllCanProveCoverage() {
    var fixture = new OracleFixture();
    fixture.globalAll = "NO";
    fixture.groups =
        List.of(
            fixture.group("G1", "USER LOG GROUP", "Y"),
            fixture.group("G2", "USER LOG GROUP", "ALWAYS"));
    fixture.members = List.of(fixture.member("G1", "ID", "LOG"), fixture.member("G2", "AT", "LOG"));
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
    fixture.groups = List.of(fixture.group("G1", "ALL COLUMN LOGGING", "ALWAYS"));
    fixture.members = List.of();
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
    fixture.groups = List.of(fixture.group("G1", "PRIMARY KEY LOGGING", "ALWAYS"));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
    fixture.globalAll = "YES";
    fixture.fail = sql -> sql.contains("ALL_LOG_GROUP");
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
  }

  @Test
  void inaccessibleTruncatedOrUnknownLoggingEvidenceIsRequiredUnknown() {
    for (String shape : List.of("ALL_LOG_GROUPS", "ALL_LOG_GROUP_COLUMNS", "V$DATABASE")) {
      var fixture = new OracleFixture();
      fixture.globalAll = "NO";
      fixture.fail = sql -> sql.contains(shape);
      var result = fixture.preflight().check("synthetic-source", input(List.of("ID", "AT")));
      assertFalse(result.ready());
      assertTrue(
          result.checks().stream()
              .anyMatch(check -> check.required() && check.status().equals("UNKNOWN")));
    }
    var fixture = new OracleFixture();
    fixture.globalAll = "NO";
    fixture.groups = List.of(fixture.group("G1", "USER LOG GROUP", "unfamiliar"));
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "table-supplemental-logging",
        "UNKNOWN",
        "TABLE_SUPPLEMENTAL_LOGGING_UNVERIFIED",
        true);
    fixture.groups =
        java.util.Collections.nCopies(513, fixture.group("G1", "USER LOG GROUP", "ALWAYS"));
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "table-supplemental-logging",
        "UNKNOWN",
        "TABLE_SUPPLEMENTAL_LOGGING_UNVERIFIED",
        true);
  }

  @Test
  void packageProofUsesCorrectDictionaryColumnsAndEnabledRolesWithoutExecutingPackages() {
    var fixture = new OracleFixture();
    fixture.fail = sql -> sql.contains("ALL_TAB_PRIVS");
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertTrue(result.ready());
    assertTrue(
        fixture.queries.stream()
            .anyMatch(
                sql ->
                    sql.contains("P.TABLE_SCHEMA='SYS'")
                        && sql.contains("P.GRANTEE=USER")
                        && sql.contains("P.GRANTEE='PUBLIC'")
                        && sql.contains("SESSION_ROLES")));
    assertTrue(
        fixture.queries.stream()
            .anyMatch(sql -> sql.contains("P.OWNER='SYS'") && sql.contains("JOIN SESSION_ROLES")));
    fixture.roleGrants = List.of();
    result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertCheck(result, "package-execute", "UNKNOWN", "EXECUTE_GRANT_UNVERIFIED", true);
    assertFalse(result.ready());
  }

  @Test
  void incompleteOrUnrecognizedGrantRowsDoNotPassEvenWithBroadExecutePrivilege() {
    var fixture = new OracleFixture();
    fixture.packageGrants =
        List.of(row("TABLE_NAME", "DBMS_LOGMNR", "GRANT_PATH", "DISABLED_ROLE", "GRANT_COUNT", 1));
    fixture.roleGrants = List.of();
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "package-execute",
        "UNKNOWN",
        "EXECUTE_GRANT_UNVERIFIED",
        true);
    fixture.packageGrants =
        java.util.Collections.nCopies(
            513, row("TABLE_NAME", "DBMS_LOGMNR", "GRANT_PATH", "DIRECT", "GRANT_COUNT", 1));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
  }

  @Test
  void nullOrOversizedMetadataCannotEscapeAsAnExceptionOrPass() {
    var fixture = new OracleFixture();
    fixture.packageGrants =
        List.of(row("TABLE_NAME", null, "GRANT_PATH", "DIRECT", "GRANT_COUNT", 1));
    fixture.roleGrants = List.of();
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "package-execute",
        "UNKNOWN",
        "EXECUTE_GRANT_UNVERIFIED",
        true);
    fixture.columns =
        java.util.Collections.nCopies(129, fixture.column("ID", "NUMBER", "N", 18, 0));
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "table-metadata",
        "UNKNOWN",
        "CDC_TABLE_METADATA_UNVERIFIED",
        true);
  }

  @Test
  void exposesDiscoveredIdentityForBuilderAndKeepsRuntimeCertificationExplicit() {
    var fixture = new OracleFixture();
    var task = task();
    task.cdc = new CdcOptions(null, "Asia/Shanghai", null, null);
    fixture.target = target(false);
    var inspected = fixture.preflight().inspect(task);
    assertEquals(OPTIONS, inspected.resolvedOptions(task.cdc));
    assertTrue(inspected.result().ready());
    assertCheck(
        inspected.result(),
        "runtime-certification",
        "UNKNOWN",
        "ORACLE_CDC_RUNTIME_UNVERIFIED",
        false);
  }

  @Test
  void pairedPdbOptionsRequireSourceContainerAndRootProofAndStayRuntimeBlocked() {
    var fixture = new OracleFixture();
    fixture.version = "19.24.0.0.0";
    fixture.cdb = "YES";
    var request =
        new CdcPreflightInput(
            "HIS",
            "ORDERS",
            List.of("ID"),
            new CdcOptions("HISDB", "Asia/Shanghai", "PDB1", "root-service"),
            null,
            null,
            null);
    var result = fixture.preflight().check("synthetic-source", request);
    assertFalse(result.ready());
    assertCheck(result, "container", "PASS", "ORACLE_PDB_SOURCE_PROVEN", true);
    assertCheck(result, "root-container", "UNKNOWN", "ORACLE_CDC_ROOT_ROUTE_UNVERIFIED", true);
    assertCheck(
        result, "container-runtime", "UNKNOWN", "ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED", true);
    assertEquals(1, fixture.opens);
    assertTrue(fixture.queries.stream().anyMatch(sql -> sql.contains("'CON_NAME'")));
  }

  @Test
  void inaccessibleVersionAndConnectionAreRequiredUnknownWithNoRawDetails() {
    var fixture = new OracleFixture();
    fixture.fail = sql -> sql.contains("PRODUCT_COMPONENT_VERSION");
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "database-version",
        "UNKNOWN",
        "ORACLE_DATABASE_VERSION_UNVERIFIED",
        true);
    fixture.connectionFailure = true;
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertFalse(result.ready());
    assertCheck(result, "source-connection", "UNKNOWN", "CDC_SOURCE_METADATA_UNVERIFIED", true);
    fixture.connectionFailure = false;
    fixture.readOnlyUnsupported = true;
    fixture.fail = sql -> false;
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
  }

  @Test
  void viewAndRequiredSessionPrivilegeFailuresStayUnknownWithoutMining() {
    var fixture = new OracleFixture();
    fixture.fail = sql -> sql.equals("SELECT 1 FROM V$LOGMNR_CONTENTS WHERE 1=0");
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertCheck(result, "mining-views", "UNKNOWN", "ORACLE_MINING_VIEW_ACCESS_UNVERIFIED", true);
    fixture.fail = sql -> false;
    fixture.privileges = List.of();
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "mining-privileges",
        "UNKNOWN",
        "ORACLE_MINING_PRIVILEGES_UNVERIFIED",
        true);
    fixture.version = "19.24.0.0.0";
    fixture.cdb = "NO";
    fixture.privileges = List.of(row("PRIVILEGE", "SELECT ANY TRANSACTION"));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
    fixture.privileges =
        List.of(row("PRIVILEGE", "SELECT ANY TRANSACTION"), row("PRIVILEGE", "LOGMINING"));
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
  }

  @Test
  void nativeDatabaseMismatchInvalidTimezoneAndUnpairedContainerOptionsBlock() {
    for (CdcOptions options :
        List.of(
            new CdcOptions("HISDB", "wrong/timezone", null, null),
            new CdcOptions("HISDB", "Asia/Shanghai", "PDB1", null),
            new CdcOptions("HISDB", "Asia/Shanghai", null, "root-service"))) {
      var fixture = new OracleFixture();
      var request =
          new CdcPreflightInput("HIS", "ORDERS", List.of("ID"), options, null, null, null);
      assertThrows(
          IntegrationException.class, () -> fixture.preflight().check("synthetic-source", request));
    }
    var fixture = new OracleFixture();
    assertFalse(
        fixture
            .preflight()
            .check(
                "synthetic-source",
                new CdcPreflightInput(
                    "HIS",
                    "ORDERS",
                    List.of("ID"),
                    new CdcOptions("SERVICE_ALIAS", "Asia/Shanghai", null, null),
                    null,
                    null,
                    null))
            .ready());
    assertFalse(
        fixture
            .preflight()
            .check(
                "synthetic-source",
                new CdcPreflightInput(
                    "HIS",
                    "ORDERS",
                    List.of("ID"),
                    new CdcOptions("HISDB", null, null, null),
                    null,
                    null,
                    null))
            .ready());
  }

  @Test
  void nonCdb19IsProvenButUnknownContainerDoesNotBecomeNonCdb() {
    var fixture = new OracleFixture();
    fixture.version = "19.24.0.0.0";
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
    fixture.fail = sql -> sql.contains("SELECT CDB");
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "container",
        "UNKNOWN",
        "ORACLE_CDC_CONTAINER_UNVERIFIED",
        true);
    fixture.fail = sql -> false;
    fixture.cdb = "YES";
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
  }

  @Test
  void mappedLobIsBlockedButAnUnmappedLobCanBeExcludedForCdc() {
    var fixture = new OracleFixture();
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.column("BLYY", "CLOB", "Y", null, null));
    assertTrue(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
    var result = fixture.preflight().check("synthetic-source", input(null));
    assertCheck(result, "column-types", "FAIL", "ORACLE_CDC_LOB_UNVERIFIED", true);
    assertFalse(result.ready());
  }

  @Test
  void mappedExtendedCharacterColumnsAreNotReadyEvenWhenTheCharacterCountIsSmall() {
    for (String type : List.of("VARCHAR2", "NVARCHAR2")) {
      for (int[] lengths :
          List.of(new int[] {32767, 32767}, new int[] {3000, 6000}, new int[] {2000, 8000})) {
        var fixture = new OracleFixture();
        fixture.version = "19.24.0.0.0";
        fixture.columns =
            List.of(
                fixture.column("ID", "NUMBER", "N", 18, 0),
                fixture.stringColumn("AT", type, lengths[0], lengths[1]));
        fixture.target = stringTarget();
        var result = fixture.preflight().check(task());
        assertFalse(result.ready(), result.checks().toString());
        assertCheck(result, "column-types", "FAIL", "ORACLE_CDC_EXTENDED_STRING_UNVERIFIED", true);
      }
    }
  }

  @Test
  void mappedCharacterColumnsNeedKnownActualByteLength() {
    for (Object length : new Object[] {null, 0, -1}) {
      var fixture = new OracleFixture();
      fixture.columns =
          List.of(
              fixture.column("ID", "NUMBER", "N", 18, 0),
              fixture.stringColumn("AT", "NVARCHAR2", 2000, length));
      fixture.target = stringTarget();
      var result = fixture.preflight().check(task());
      assertFalse(result.ready());
      assertCheck(result, "column-types", "UNKNOWN", "ORACLE_CDC_STRING_BYTES_UNVERIFIED", true);
    }
    for (Object length : new Object[] {"not-a-number", "99999999999999999999", "1".repeat(257)}) {
      var fixture = new OracleFixture();
      fixture.columns =
          List.of(
              fixture.column("ID", "NUMBER", "N", 18, 0),
              fixture.stringColumn("AT", "VARCHAR2", 25, length));
      fixture.target = stringTarget();
      var result = fixture.preflight().check(task());
      assertFalse(result.ready());
      assertCheck(result, "table-metadata", "UNKNOWN", "CDC_TABLE_METADATA_UNVERIFIED", true);
    }
  }

  @Test
  void ordinaryCharacterBytesCanBeProvenAndUnmappedExtendedColumnsCanBeExcluded() {
    for (String type : List.of("VARCHAR2", "NVARCHAR2")) {
      var fixture = new OracleFixture();
      fixture.columns =
          List.of(
              fixture.column("ID", "NUMBER", "N", 18, 0),
              fixture.stringColumn("AT", type, 2000, 4000),
              fixture.stringColumn("UNMAPPED", "VARCHAR2", 32767, 32767));
      fixture.target = stringTarget();
      var inspected = fixture.preflight().inspect(task());
      assertTrue(inspected.result().ready());
      assertEquals(4000, inspected.source().columns().get(1).oracleDataLengthBytes());
      assertEquals(32767, inspected.source().columns().get(2).oracleDataLengthBytes());
    }
  }

  @Test
  void logMinerBlocksLongTableAndEveryDiscoveredColumnNameIncludingUnmappedColumns() {
    var fixture = new OracleFixture();
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.column("AT", "DATE", "Y", null, null),
            fixture.column("COLUMN_NAME_LONGER_THAN_30_CHARS_01", "DATE", "Y", null, null));
    fixture.target = target(false);
    var result = fixture.preflight().check(task());
    assertFalse(result.ready());
    assertCheck(result, "logminer-identifiers", "FAIL", "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", true);
    fixture = new OracleFixture();
    fixture.target = target(false);
    var task = task();
    task.sourceTable = "T".repeat(31);
    result = fixture.preflight().check(task);
    assertFalse(result.ready());
    assertCheck(result, "logminer-identifiers", "FAIL", "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", true);
    task.sourceTable = "T".repeat(30);
    assertTrue(fixture.preflight().check(task).ready());
    fixture.columns =
        List.of(
            fixture.column("ID", "NUMBER", "N", 18, 0),
            fixture.column("C".repeat(30), "DATE", "Y", null, null));
    task.fieldMappings =
        List.of(new FieldMapping("ID", "id"), new FieldMapping("C".repeat(30), "at"));
    assertTrue(fixture.preflight().check(task).ready());
  }

  @Test
  void missingDuplicateColumnsAndInvalidActualPrimaryKeyFailClosed() {
    for (List<String> columns : List.of(List.of("MISSING"), List.of("AT"))) {
      assertFalse(
          new OracleFixture().preflight().check("synthetic-source", input(columns)).ready());
    }
    for (List<String> columns : List.of(List.of("ID", "ID"), List.<String>of())) {
      assertThrows(
          IntegrationException.class,
          () -> new OracleFixture().preflight().check("synthetic-source", input(columns)));
    }
    var fixture = new OracleFixture();
    fixture.keys = List.of();
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
    fixture.keys = List.of(fixture.key("ID", "DISABLED"));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID"))).ready());
    fixture.keys = List.of(fixture.key("ID", "ENABLED"), fixture.key("AT", "ENABLED"));
    assertFalse(fixture.preflight().check("synthetic-source", input(List.of("ID", "AT"))).ready());
  }

  @Test
  void targetMustBeUniqueKeyCompatibleAndFullyMappedIncludingNullableDefaultColumns() {
    var fixture = new OracleFixture();
    var task = task();
    fixture.target = target(false);
    var inspected = fixture.preflight().inspect(task);
    assertTrue(inspected.result().ready());
    assertNotNull(inspected.source());
    assertEquals(task.fieldMappings, inspected.mappings());
    fixture.target = target(true);
    var result = fixture.preflight().check(task);
    assertCheck(result, "target-mapping", "FAIL", "ORACLE_CDC_TARGET_PROJECTION_INCOMPLETE", true);
    fixture.target =
        new TableDefinition(
            "hospital",
            "orders",
            List.of(
                new ColumnDefinition(
                    "id", "DECIMAL(18,0)", false, false, Types.DECIMAL, 18, 0, false)));
    assertFalse(fixture.preflight().check(task).ready());
    fixture.targetFailure = true;
    assertCheck(
        fixture.preflight().check(task),
        "target-mapping",
        "UNKNOWN",
        "CDC_TARGET_METADATA_UNVERIFIED",
        true);
  }

  @Test
  void forbiddenRegisteredTargetSchemaIsABadRequestRatherThanUnknownMetadata() {
    var fixture = new OracleFixture();
    var task = task();
    task.targetSchema = "forbidden";
    var failure = assertThrows(IntegrationException.class, () -> fixture.preflight().check(task));
    assertEquals("SCHEMA_NOT_ALLOWED", failure.code());
    assertEquals(400, failure.status());
  }

  @Test
  void missingEnginePluginOrUnavailableEngineCannotBeReadyAndExceptionsAreRedacted()
      throws Exception {
    var fixture = new OracleFixture();
    fixture.pluginPresent = false;
    assertCheck(
        fixture.preflight().check("synthetic-source", input(List.of("ID"))),
        "engine-plugin",
        "FAIL",
        "CDC_ENGINE_PLUGIN_MISSING",
        true);
    fixture.engineFailure = true;
    fixture.fail = sql -> true;
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertFalse(result.ready());
    assertCheck(result, "engine-plugin", "UNKNOWN", "CDC_ENGINE_PLUGIN_UNVERIFIED", true);
    String serialized = new ObjectMapper().writeValueAsString(result);
    for (String sensitive :
        List.of(
            "password-secret-never-expose",
            "jdbc:",
            "ORA-",
            "source.example",
            "synthetic_user",
            "secret-row")) {
      assertFalse(serialized.contains(sensitive), serialized);
    }
  }

  @Test
  void invalidRequestScopesDoNotOpenAnySourceConnection() {
    for (CdcPreflightInput request :
        List.of(
            new CdcPreflightInput("HIS", null, null, OPTIONS, null, null, null),
            new CdcPreflightInput(null, "ORDERS", null, OPTIONS, null, null, null),
            new CdcPreflightInput("OTHER", "ORDERS", null, OPTIONS, null, null, null),
            new CdcPreflightInput("HIS", "ORDERS", List.of("ID"), OPTIONS, "target", null, null))) {
      var fixture = new OracleFixture();
      assertThrows(
          IntegrationException.class, () -> fixture.preflight().check("synthetic-source", request));
      assertEquals(0, fixture.opens);
    }
    var fixture = new OracleFixture();
    var result =
        fixture
            .preflight()
            .check(
                "synthetic-source",
                new CdcPreflightInput(null, null, null, OPTIONS, null, null, null));
    assertFalse(result.ready());
    assertEquals("HISDB", result.databaseName());
    assertCheck(result, "table-metadata", "UNKNOWN", "CDC_TABLE_REQUIRED", true);
  }

  @Test
  void mysqlRemainsFullOnlyWithoutSourceQueries() {
    var fixture = new OracleFixture();
    fixture.source = endpoint("Mysql");
    var result = fixture.preflight().check("synthetic-source", input(List.of("ID")));
    assertFalse(result.implemented());
    assertFalse(result.ready());
    assertEquals(0, fixture.opens);
  }

  @Test
  void postgresUsesExistingTableValidationWithoutIntroducingOraclePrerequisites() {
    var fixture = new OracleFixture();
    fixture.source = CdcAdaptersTest.endpoint("Postgres", "VERIFY");
    fixture.sourceTable =
        new TableDefinition(
            "public",
            "orders",
            List.of(
                new ColumnDefinition("ID", "NUMERIC", false, true, Types.NUMERIC, 18, 0, false)));
    var result =
        fixture
            .preflight()
            .check(
                "synthetic-source",
                new CdcPreflightInput("public", "orders", List.of("ID"), null, null, null, null));
    assertTrue(result.ready());
    assertEquals(List.of("Postgres-CDC"), fixture.plugins);
    assertEquals(0, fixture.opens);
    assertTrue(result.checks().stream().noneMatch(check -> check.code().startsWith("ORACLE")));
  }

  private static CdcPreflightInput input(List<String> columns) {
    return new CdcPreflightInput("HIS", "ORDERS", columns, OPTIONS, null, null, null);
  }

  private static TaskInput task() {
    var task = new TaskInput();
    task.name = "oracle_cdc";
    task.sourceConnectionId = "synthetic-source";
    task.targetConnectionId = "doris-ods-validation";
    task.sourceSchema = "HIS";
    task.sourceTable = "ORDERS";
    task.targetSchema = "hospital";
    task.targetTable = "orders";
    task.mode = "CDC";
    task.primaryKey = "ID";
    task.cdc = OPTIONS;
    task.fieldMappings = List.of(new FieldMapping("ID", "id"), new FieldMapping("AT", "at"));
    return task;
  }

  private static TableDefinition target(boolean extra) {
    List<ColumnDefinition> columns = new ArrayList<>();
    columns.add(DorisTableMetadata.column("id", "decimal(18,0)", false, true, false));
    columns.add(DorisTableMetadata.column("at", "datetime", true, false, false));
    if (extra) {
      columns.add(DorisTableMetadata.column("blyy", "string", true, false, true));
    }
    return new TableDefinition("hospital", "orders", List.copyOf(columns));
  }

  private static TableDefinition stringTarget() {
    return new TableDefinition(
        "hospital",
        "orders",
        List.of(
            DorisTableMetadata.column("id", "decimal(18,0)", false, true, false),
            DorisTableMetadata.column("at", "string", true, false, false)));
  }

  private static JdbcEndpoint endpoint(String type) {
    var definition = new ConnectionDefinition();
    definition.id = "doris-ods-validation";
    definition.role = type.equals("Doris") ? "TARGET" : "SOURCE";
    definition.databaseType = type;
    definition.host = "target.example.internal";
    definition.port = type.equals("Doris") ? 9030 : 1433;
    definition.dorisHttpPort = type.equals("Doris") ? 8030 : null;
    definition.database = "hospital";
    definition.username = "synthetic_target";
    definition.schemas = List.of(type.equals("Doris") ? "hospital" : "HIS");
    definition.tlsMode = "DISABLED";
    return new JdbcEndpoint(definition, "password-secret-never-expose");
  }

  private static void assertCheck(
      CdcPreflightResult result, String id, String status, String code, boolean required) {
    assertTrue(
        result.checks().contains(new CdcCheck(id, status, code, required)),
        result.checks().toString());
  }

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      row.put(pairs[i].toString(), pairs[i + 1]);
    }
    return row;
  }

  static final class OracleFixture {
    JdbcEndpoint source = CdcAdaptersTest.endpoint("Oracle", "VERIFY");
    String targetConnectionId = "doris-ods-validation";
    JdbcEndpoint targetEndpoint = endpoint("Doris");
    String version = "11.2.0.1.0";
    String logMode = "ARCHIVELOG";
    String minimum = "IMPLICIT";
    String globalAll = "YES";
    String cdb = "NO";
    List<Map<String, Object>> groups = List.of(group("G1", "USER LOG GROUP", "ALWAYS"));
    List<Map<String, Object>> members =
        List.of(member("G1", "ID", "LOG"), member("G1", "AT", "LOG"));
    List<Map<String, Object>> columns =
        List.of(column("ID", "NUMBER", "N", 18, 0), column("AT", "DATE", "Y", null, null));
    List<Map<String, Object>> keys = List.of(key("ID", "ENABLED"));
    List<Map<String, Object>> privileges =
        List.of(row("PRIVILEGE", "SELECT ANY TRANSACTION"), row("PRIVILEGE", "LOGMINING"));
    List<Map<String, Object>> packageGrants =
        List.of(
            row("TABLE_NAME", "DBMS_LOGMNR", "GRANT_PATH", "ENABLED_ROLE", "GRANT_COUNT", 1),
            row("TABLE_NAME", "DBMS_LOGMNR_D", "GRANT_PATH", "PUBLIC", "GRANT_COUNT", 1));
    List<Map<String, Object>> roleGrants =
        List.of(
            row("TABLE_NAME", "DBMS_LOGMNR", "ENABLED_ROLE_GRANT_COUNT", 1),
            row("TABLE_NAME", "DBMS_LOGMNR_D", "ENABLED_ROLE_GRANT_COUNT", 1));
    Predicate<String> fail = sql -> false;
    boolean pluginPresent = true;
    boolean engineFailure;
    boolean targetFailure;
    boolean connectionFailure;
    boolean readOnlyUnsupported;
    boolean readOnly;
    int opens;
    TableDefinition target;
    TableDefinition sourceTable;
    final List<String> queries = new ArrayList<>();
    final List<List<String>> bindings = new ArrayList<>();
    final List<Integer> timeouts = new ArrayList<>();
    final List<Integer> maxRows = new ArrayList<>();
    final List<String> plugins = new ArrayList<>();

    IntegrationCdcPreflight preflight() {
      return new IntegrationCdcPreflight(
          id -> targetConnectionId.equals(id) ? targetEndpoint : source,
          (endpoint, properties) -> connection(),
          (id, schema, table) -> {
            if (targetFailure) {
              throw new IntegrationException("jdbc:secret password-secret-never-expose", 503);
            }
            return targetConnectionId.equals(id) ? target : sourceTable;
          },
          plugin -> {
            plugins.add(plugin);
            if (engineFailure) {
              throw new IntegrationException("ORA-secret jdbc: password-secret-never-expose", 503);
            }
            return pluginPresent;
          });
    }

    private Connection connection() throws SQLException {
      opens++;
      if (connectionFailure) {
        throw new SQLException(
            "ORA-secret jdbc:oracle:thin:source.example password-secret-never-expose");
      }
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "setReadOnly" -> {
                      if (readOnlyUnsupported)
                        throw new SQLFeatureNotSupportedException("Oracle readOnly unsupported");
                      readOnly = (boolean) args[0];
                      yield null;
                    }
                    case "prepareStatement" -> statement((String) args[0]);
                    case "close", "setAutoCommit", "rollback" -> null;
                    case "isClosed" -> false;
                    default -> throw new AssertionError("Unexpected JDBC call " + method.getName());
                  });
    }

    private PreparedStatement statement(String sql) {
      assertTrue(sql.startsWith("SELECT "), sql);
      queries.add(sql);
      Map<Integer, String> values = new HashMap<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "setString" -> {
                      values.put((Integer) args[0], (String) args[1]);
                      yield null;
                    }
                    case "setQueryTimeout" -> {
                      timeouts.add((Integer) args[0]);
                      yield null;
                    }
                    case "setMaxRows" -> {
                      maxRows.add((Integer) args[0]);
                      yield null;
                    }
                    case "executeQuery" -> {
                      bindings.add(
                          values.entrySet().stream()
                              .sorted(Map.Entry.comparingByKey())
                              .map(Map.Entry::getValue)
                              .toList());
                      if (fail.test(sql)) {
                        throw new SQLException(
                            "ORA-00942 jdbc:oracle:thin:source.example password-secret-never-expose secret-row");
                      }
                      yield result(rows(sql));
                    }
                    case "close" -> null;
                    default -> throw new AssertionError(
                        "Unexpected statement call " + method.getName());
                  });
    }

    private List<Map<String, Object>> rows(String sql) {
      if (sql.contains("'CON_NAME'")) return List.of(row("CON_NAME", "PDB1"));
      if (sql.contains("PRODUCT_COMPONENT_VERSION")) return List.of(row("VERSION", version));
      if (sql.contains("SELECT CDB")) return List.of(row("CDB", cdb));
      if (sql.contains("FROM V$DATABASE"))
        return List.of(
            row(
                "NAME",
                "HISDB",
                "LOG_MODE",
                logMode,
                "SUPPLEMENTAL_LOG_DATA_MIN",
                minimum,
                "SUPPLEMENTAL_LOG_DATA_ALL",
                globalAll));
      if (sql.contains("ALL_TAB_COLUMNS")) return columns;
      if (sql.contains("ALL_CONSTRAINTS")) return keys;
      if (sql.contains("ALL_LOG_GROUP_COLUMNS")) return members;
      if (sql.contains("ALL_LOG_GROUPS")) return groups;
      if (sql.contains("ALL_TAB_PRIVS")) return packageGrants;
      if (sql.contains("ROLE_TAB_PRIVS")) return roleGrants;
      if (sql.contains("SESSION_PRIVS")) return privileges;
      if (sql.matches("SELECT 1 FROM V\\$[A-Z_]+ WHERE 1=0")) return List.of();
      throw new AssertionError("Unrecognized SQL: " + sql);
    }

    private ResultSet result(List<Map<String, Object>> rows) {
      int[] index = {-1};
      Object[] last = {null};
      return (ResultSet)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "next" -> ++index[0] < rows.size();
                    case "getString", "getInt", "getLong", "getObject" -> {
                      Object key = args[0];
                      Object value =
                          key instanceof Integer number
                              ? new ArrayList<>(rows.get(index[0]).values()).get(number - 1)
                              : rows.get(index[0]).get(key);
                      last[0] = value;
                      yield switch (method.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getInt" -> value == null ? 0 : Integer.parseInt(value.toString());
                        case "getLong" -> value == null ? 0L : Long.parseLong(value.toString());
                        default -> value;
                      };
                    }
                    case "wasNull" -> last[0] == null;
                    case "close" -> null;
                    default -> throw new AssertionError(
                        "Unexpected result call " + method.getName());
                  });
    }

    Map<String, Object> column(
        String name, String type, String nullable, Integer precision, Integer scale) {
      return row(
          "COLUMN_NAME",
          name,
          "DATA_TYPE",
          type,
          "NULLABLE",
          nullable,
          "DATA_PRECISION",
          precision,
          "DATA_SCALE",
          scale,
          "DATA_LENGTH",
          100,
          "CHAR_LENGTH",
          25);
    }

    Map<String, Object> stringColumn(String name, String type, int chars, Object bytes) {
      Map<String, Object> row = column(name, type, "Y", null, null);
      row.put("CHAR_LENGTH", chars);
      row.put("DATA_LENGTH", bytes);
      return row;
    }

    Map<String, Object> key(String name, String status) {
      return row(
          "COLUMN_NAME",
          name,
          "CONSTRAINT_NAME",
          "PK_ORDERS",
          "STATUS",
          status,
          "VALIDATED",
          "VALIDATED",
          "POSITION",
          1);
    }

    Map<String, Object> group(String name, String type, String always) {
      return row(
          "OWNER",
          "HIS",
          "TABLE_NAME",
          "ORDERS",
          "LOG_GROUP_NAME",
          name,
          "LOG_GROUP_TYPE",
          type,
          "ALWAYS",
          always);
    }

    Map<String, Object> member(String group, String column, String property) {
      return row(
          "OWNER",
          "HIS",
          "TABLE_NAME",
          "ORDERS",
          "LOG_GROUP_NAME",
          group,
          "COLUMN_NAME",
          column,
          "LOGGING_PROPERTY",
          property);
    }
  }
}
