package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

class SqlServerCdcAdapterTest {
  static final CdcOptions OPTIONS = new CdcOptions("HisDb", "Asia/Shanghai", null, null);
  static final List<FieldMapping> MAPPINGS =
      List.of(new FieldMapping("ID", "id"), new FieldMapping("VALUE", "name"));

  @Test
  void nativeContractPinsTrustedSchemaFiltersTlsAndAtLeastOnceInitialProfile() {
    var source = build(endpoint("VERIFY"), column("tinyint", Types.TINYINT, 3, 0));
    assertEquals("SqlServer-CDC", source.get("plugin_name"));
    assertEquals(List.of("HisDb"), source.get("database-names"));
    assertEquals(List.of("HisDb.dbo.ORDERS"), source.get("table-names"));
    assertFalse(source.containsKey("schema-names"));
    assertEquals("initial", source.get("startup.mode"));
    assertEquals(false, source.get("exactly_once"));
    assertEquals(false, source.get("schema-changes.enabled"));
    assertEquals(false, source.get("split.allow-sampling"));
    assertEquals(true, source.get("enable_concurrent_read"));
    assertEquals(1, source.get("incremental.parallelism"));
    assertEquals(1200, source.get("snapshot.fetch.size"));
    assertEquals(9000, source.get("snapshot.split.size"));
    assertEquals(5000, source.get("connect.timeout.ms"));
    assertEquals("Asia/Shanghai", source.get("server-time-zone"));
    var schema = (Map<?, ?>) source.get("schema");
    assertEquals(Map.of("ID", "INT", "VALUE", "SMALLINT"), schema.get("fields"));
    assertEquals("HisDb.dbo.ORDERS", schema.get("table"));
    assertEquals(true, schema.get("schema_first"));
    assertEquals(
        Map.of("name", "hospital_pk", "columnNames", List.of("ID")), schema.get("primaryKey"));
    assertEquals(
        Map.of(
            "time.precision.mode",
            "adaptive",
            "decimal.handling.mode",
            "precise",
            "include.schema.changes",
            "false",
            "database.include.list",
            "\\QHisDb\\E",
            "table.include.list",
            "\\Qdbo\\E\\.\\QORDERS\\E",
            "database.encrypt",
            "true",
            "database.trustServerCertificate",
            "false",
            "database.loginTimeout",
            "5",
            "database.socketTimeout",
            "15000",
            "database.sendTimeAsDatetime",
            "false"),
        source.get("debezium"));
    assertFalse(source.containsKey("skip_analyze"));
    assertFalse(source.containsKey("use_select_count"));
    assertTrue(CdcAdapters.implemented(JdbcDialect.Mssql));
    var definition = definition("DISABLED");
    definition.databaseVersion = "uncertified";
    assertEquals(List.of("FULL", "CDC"), CdcAdapters.supportedModes(definition));
  }

  @Test
  void rejectsAmbiguousEndpointRouteIndependentlyOfConnectionValidation() {
    for (String host :
        List.of(
            "[::1]", "srv\\instance", "host;encrypt=false", "host{bad}", "a..b", "host+other")) {
      var definition = definition("VERIFY");
      definition.host = host;
      assertCode(
          "MSSQL_CDC_ROUTE_UNSUPPORTED",
          () -> build(new JdbcEndpoint(definition, "secret"), column("int", Types.INTEGER, 10, 0)));
    }
    var definition = definition("VERIFY");
    definition.port = 0;
    assertCode(
        "MSSQL_CDC_ROUTE_UNSUPPORTED",
        () -> build(new JdbcEndpoint(definition, "secret"), column("int", Types.INTEGER, 10, 0)));
  }

  @Test
  void requiresExplicitIanaZoneAndRejectsOracleContainerOptions() {
    for (var options :
        List.of(
            new CdcOptions("HisDb", null, null, null),
            new CdcOptions("HisDb", "+08:00", null, null),
            new CdcOptions("HisDb", "No/SuchZone", null, null),
            new CdcOptions("HisDb", "UTC", "PDB", "ROOT"),
            new CdcOptions("bad$name", "UTC", null, null))) {
      assertCode(
          "MSSQL_CDC_OPTIONS_INVALID",
          () ->
              CdcAdapters.require(JdbcDialect.Mssql)
                  .buildSource(
                      "task",
                      endpoint("VERIFY"),
                      table(column("int", Types.INTEGER, 10, 0)),
                      MAPPINGS,
                      options,
                      new IntegrationRuntimeOptions.Resolved(1000, 8192, 1000, 30, 300)));
    }
  }

  @Test
  void nativeDatabaseCannotOverrideEndpointIdentityAndUnicodeBoundsAreProven() {
    var definition = definition("VERIFY");
    definition.database = "OtherDb";
    assertCode(
        "MSSQL_DB_NAME_MISMATCH",
        () -> build(new JdbcEndpoint(definition, "secret"), column("int", Types.INTEGER, 10, 0)));
    assertCode(
        "MSSQL_CDC_TYPE_UNSUPPORTED",
        () -> build(endpoint("VERIFY"), column("nvarchar", Types.NVARCHAR, 4001, 0)));
  }

  @Test
  void mapsOnlyTheCertifiedNativePhysicalTypesWithoutTemporalTruncation() {
    for (var sample :
        List.of(
            new Object[] {"bit", Types.BIT, 1, 0, "BOOLEAN"},
            new Object[] {"smallint", Types.SMALLINT, 5, 0, "SMALLINT"},
            new Object[] {"bigint", Types.BIGINT, 19, 0, "BIGINT"},
            new Object[] {"nvarchar", Types.NVARCHAR, 100, 0, "STRING"},
            new Object[] {"uniqueidentifier", Types.CHAR, 36, 0, "STRING"},
            new Object[] {"money", Types.DECIMAL, 19, 4, "DECIMAL(19,4)"},
            new Object[] {"date", Types.DATE, 10, 0, "DATE"},
            new Object[] {"datetime2", Types.TIMESTAMP, 23, 3, "TIMESTAMP"})) {
      var source =
          build(
              endpoint("DISABLED"),
              column((String) sample[0], (int) sample[1], (int) sample[2], (int) sample[3]));
      assertEquals(
          sample[4], ((Map<?, ?>) ((Map<?, ?>) source.get("schema")).get("fields")).get("VALUE"));
    }
    for (String type :
        List.of(
            "timestamp",
            "rowversion",
            "datetimeoffset",
            "time",
            "binary",
            "varbinary",
            "text",
            "ntext",
            "xml",
            "sql_variant",
            "geometry",
            "hierarchyid",
            "varchar(max)")) {
      assertCode(
          "MSSQL_CDC_TYPE_UNSUPPORTED",
          () -> build(endpoint("DISABLED"), column(type, Types.TIMESTAMP, 100, 0)));
    }
    for (int scale : List.of(-1, 4, 5, 6, 7)) {
      assertCode(
          "MSSQL_CDC_TYPE_UNSUPPORTED",
          () -> build(endpoint("DISABLED"), column("datetime2", Types.TIMESTAMP, 27, scale)));
    }
    assertCode(
        "MSSQL_CDC_TYPE_UNSUPPORTED",
        () -> build(endpoint("DISABLED"), column("varchar", Types.VARCHAR, -1, 0)));
  }

  static Map<String, Object> build(JdbcEndpoint endpoint, ColumnDefinition column) {
    return CdcAdapters.require(JdbcDialect.Mssql)
        .buildSource(
            "task",
            endpoint,
            table(column),
            MAPPINGS,
            OPTIONS,
            new IntegrationRuntimeOptions.Resolved(1200, 9000, 1000, 30, 300));
  }

  static TableDefinition table(ColumnDefinition column) {
    return new TableDefinition(
        "dbo",
        "ORDERS",
        List.of(
            new ColumnDefinition(
                "ID", "int", false, true, Types.INTEGER, 10, 0, false, false, 0, JdbcDialect.Mssql),
            column));
  }

  static ColumnDefinition column(String type, int jdbc, int size, int scale) {
    return new ColumnDefinition(
        "VALUE", type, true, false, jdbc, size, scale, false, false, 0, JdbcDialect.Mssql);
  }

  static ConnectionDefinition definition(String tls) {
    var definition = new ConnectionDefinition();
    definition.id = "synthetic-source";
    definition.databaseType = "Mssql";
    definition.role = "SOURCE";
    definition.host = "sql.example.internal";
    definition.port = 1433;
    definition.database = "HisDb";
    definition.username = "reader";
    definition.schemas = List.of("dbo");
    definition.tlsMode = tls;
    return definition;
  }

  static JdbcEndpoint endpoint(String tls) {
    return new JdbcEndpoint(definition(tls), "secret");
  }

  static void assertCode(String code, Runnable call) {
    assertEquals(code, assertThrows(IntegrationException.class, call::run).code());
  }
}
