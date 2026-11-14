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

class CdcAdaptersTest {
  private static final List<FieldMapping> MAPPINGS =
      List.of(new FieldMapping("ID", "id"), new FieldMapping("AT", "at"));
  private static final CdcOptions OPTIONS = new CdcOptions("HISDB", "Asia/Shanghai", null, null);
  private static final IntegrationRuntimeOptions.Resolved RUNTIME =
      new IntegrationRuntimeOptions.Resolved(1200, 9000, 1000, 30, 300);

  @Test
  void oracleRejectsDollarInSchemaAndTableButPreservesDollarColumnNames() {
    for (String[] route :
        List.of(new String[] {"HOS$", "ORDERS"}, new String[] {"HIS", "PAT$ENT"})) {
      assertCode(
          "ORACLE_CDC_IDENTIFIER_UNSUPPORTED",
          () ->
              oracle(
                  endpoint("Oracle", "VERIFY", route[0]),
                  new TableDefinition(route[0], route[1], table(number(18, 0), date()).columns()),
                  MAPPINGS,
                  OPTIONS));
    }
    var dollarColumn =
        new ColumnDefinition(
            "AT$", "DATE", true, false, Types.TIMESTAMP, 7, 0, false, false, 0, JdbcDialect.Oracle);
    var source =
        oracle(
            endpoint("Oracle", "VERIFY"),
            table(number(18, 0), dollarColumn),
            List.of(new FieldMapping("ID", "id"), new FieldMapping("AT$", "at")),
            OPTIONS);
    assertEquals(
        Map.of("ID", "DECIMAL(18,0)", "AT$", "TIMESTAMP"),
        ((Map<?, ?>) source.get("schema")).get("fields"));
  }

  @Test
  void oracleUsesNativeIdentityExplicitTrustedSchemaAndDescriptorTransport() {
    var endpoint = endpoint("Oracle", "VERIFY");
    Map<String, Object> source = oracle(endpoint, table(number(18, 0), date()), MAPPINGS, OPTIONS);
    assertEquals("Oracle-CDC", source.get("plugin_name"));
    assertEquals("source", source.get("plugin_output"));
    assertEquals(endpoint.jdbcUrl(), source.get("url"));
    assertTrue(source.get("url").toString().contains("TCPS"));
    assertTrue(source.get("url").toString().contains("SSL_SERVER_DN_MATCH=yes"));
    assertEquals(List.of("HISDB"), source.get("database-names"));
    assertEquals(List.of("HISDB.HIS.ORDERS"), source.get("table-names"));
    assertEquals("initial", source.get("startup.mode"));
    assertEquals("Asia/Shanghai", source.get("server-time-zone"));
    assertEquals(1200, source.get("snapshot.fetch.size"));
    assertEquals(9000, source.get("snapshot.split.size"));
    assertEquals(
        Map.of(
            "table",
            "HISDB.HIS.ORDERS",
            "schema_first",
            true,
            "fields",
            Map.of("ID", "DECIMAL(18,0)", "AT", "TIMESTAMP"),
            "primaryKey",
            Map.of("name", "hospital_pk", "columnNames", List.of("ID"))),
        source.get("schema"));
  }

  @Test
  void oraclePinsTheReadOnlyProfileAndForwardsDriverPropertiesOnlyToDebezium() {
    var source =
        oracle(endpoint("Oracle", "DISABLED"), table(number(18, 0), date()), MAPPINGS, OPTIONS);
    assertEquals(true, source.get("skip_analyze"));
    assertEquals(true, source.get("use_select_count"));
    assertEquals(false, source.get("schema-changes.enabled"));
    assertFalse(source.containsKey("properties"));
    Map<?, ?> debezium = (Map<?, ?>) source.get("debezium");
    assertEquals("adaptive", debezium.get("time.precision.mode"));
    assertEquals("precise", debezium.get("decimal.handling.mode"));
    assertEquals("true", debezium.get("log.mining.read.only"));
    assertEquals("online_catalog", debezium.get("log.mining.strategy"));
    assertEquals("false", debezium.get("include.schema.changes"));
    assertEquals("5000", debezium.get("database.oracle.net.CONNECT_TIMEOUT"));
    assertEquals("15000", debezium.get("database.oracle.jdbc.ReadTimeout"));
    assertEquals("false", debezium.get("database.oracle.jdbc.timezoneAsRegion"));
    assertFalse(debezium.containsKey("heartbeat.action.query"));
    assertFalse(debezium.containsKey("signal.data.collection"));
  }

  @Test
  void oracleUsesOnlyMappedOriginalFieldsAndExactDecimalPrecision() {
    var source =
        oracle(
            endpoint("Oracle", "DISABLED"), table(number(25, 4), date(), lob()), MAPPINGS, OPTIONS);
    Map<?, ?> schema = (Map<?, ?>) source.get("schema");
    assertEquals(Map.of("ID", "DECIMAL(25,4)", "AT", "TIMESTAMP"), schema.get("fields"));
    assertFalse(schema.toString().contains("BLYY"));
  }

  @Test
  void oracleRejectsMappedLobUnknownTypesAndUninspectedTypes() {
    var endpoint = endpoint("Oracle", "DISABLED");
    assertCode(
        "ORACLE_CDC_LOB_UNVERIFIED",
        () ->
            oracle(
                endpoint,
                table(number(18, 0), date(), lob()),
                List.of(new FieldMapping("ID", "id"), new FieldMapping("BLYY", "blyy")),
                OPTIONS));
    for (var column :
        List.of(
            number(0, 0),
            number(18, -2),
            new ColumnDefinition("AT", "XMLTYPE", true, false, Types.OTHER, 0, 0, false),
            new ColumnDefinition("AT", "DATE", true, false, Types.TIMESTAMP, 7, 0, false))) {
      var sourceTable =
          column.name().equals("ID") ? table(column, date()) : table(number(18, 0), column);
      assertCode(
          "ORACLE_CDC_TYPE_UNSUPPORTED", () -> oracle(endpoint, sourceTable, MAPPINGS, OPTIONS));
    }
  }

  @Test
  void oracleRejectsMissingOrDuplicateMappingsMissingKeyAndUnsafeOptions() {
    var endpoint = endpoint("Oracle", "DISABLED");
    var table = table(number(18, 0), date());
    for (List<FieldMapping> mappings :
        List.of(
            List.of(new FieldMapping("missing", "missing")),
            List.of(new FieldMapping("ID", "id"), new FieldMapping("ID", "id2")),
            List.of(new FieldMapping("ID", "id"), new FieldMapping("AT", "id")),
            List.of(new FieldMapping("AT", "at")))) {
      assertThrows(IntegrationException.class, () -> oracle(endpoint, table, mappings, OPTIONS));
    }
    for (CdcOptions options :
        List.of(
            new CdcOptions(null, "Asia/Shanghai", null, null),
            new CdcOptions("HISDB", null, null, null),
            new CdcOptions("HISDB", "not a timezone", null, null),
            new CdcOptions("HISDB", "Asia/Shanghai", "PDB1", null))) {
      assertThrows(IntegrationException.class, () -> oracle(endpoint, table, MAPPINGS, options));
    }
  }

  @Test
  void pairedContainerOptionsCannotEmitAnUncertifiedRootOrPdbConfiguration() {
    assertCode(
        "ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED",
        () ->
            oracle(
                endpoint("Oracle", "VERIFY"),
                table(number(18, 0), date()),
                MAPPINGS,
                new CdcOptions("HISDB", "Asia/Shanghai", "PDB1", "root-service")));
  }

  @Test
  void oracleStringSchemaRequiresTrustedByteLengthRatherThanCharacterCapacity() {
    var endpoint = endpoint("Oracle", "DISABLED");
    for (String type : List.of("VARCHAR2", "NVARCHAR2")) {
      for (Integer bytes : new Integer[] {null, 0, -1, 4001, 6000, 32767}) {
        String code =
            bytes != null && bytes > 4000
                ? "ORACLE_CDC_EXTENDED_STRING_UNVERIFIED"
                : "ORACLE_CDC_STRING_BYTES_UNVERIFIED";
        assertCode(
            code,
            () ->
                oracle(
                    endpoint, table(number(18, 0), string(type, 2000, bytes)), MAPPINGS, OPTIONS));
      }
      for (int bytes : List.of(1, 4000)) {
        Map<?, ?> schema =
            (Map<?, ?>)
                oracle(endpoint, table(number(18, 0), string(type, 1000, bytes)), MAPPINGS, OPTIONS)
                    .get("schema");
        assertEquals(Map.of("ID", "DECIMAL(18,0)", "AT", "STRING"), schema.get("fields"));
      }
    }
    var legacy =
        new ColumnDefinition(
            "AT",
            "VARCHAR2",
            true,
            false,
            Types.VARCHAR,
            25,
            0,
            false,
            false,
            0,
            JdbcDialect.Oracle);
    assertCode(
        "ORACLE_CDC_STRING_BYTES_UNVERIFIED",
        () -> oracle(endpoint, table(number(18, 0), legacy), MAPPINGS, OPTIONS));
  }

  @Test
  void oracleBuilderRejectsLongTableSchemaAndEveryColumnNameBeforeProjection() {
    var endpoint = endpoint("Oracle", "DISABLED");
    var source =
        table(
            number(18, 0),
            date(),
            new ColumnDefinition(
                "UNMAPPED_COLUMN_LONGER_THAN_30_CHARS",
                "DATE",
                true,
                false,
                Types.TIMESTAMP,
                7,
                0,
                false,
                false,
                0,
                JdbcDialect.Oracle));
    assertCode(
        "ORACLE_CDC_IDENTIFIER_UNSUPPORTED", () -> oracle(endpoint, source, MAPPINGS, OPTIONS));
    assertCode(
        "ORACLE_CDC_IDENTIFIER_UNSUPPORTED",
        () ->
            oracle(
                endpoint,
                new TableDefinition("HIS", "T".repeat(31), table(number(18, 0), date()).columns()),
                MAPPINGS,
                OPTIONS));
    assertCode(
        "ORACLE_CDC_IDENTIFIER_UNSUPPORTED",
        () ->
            oracle(
                endpoint,
                new TableDefinition(
                    "S".repeat(31), "ORDERS", table(number(18, 0), date()).columns()),
                MAPPINGS,
                OPTIONS));
    assertEquals(
        "Oracle-CDC",
        oracle(
                endpoint,
                new TableDefinition("HIS", "T".repeat(30), table(number(18, 0), date()).columns()),
                MAPPINGS,
                OPTIONS)
            .get("plugin_name"));
  }

  @Test
  void postgresPreservesReleasedSourceConfigurationAndReplicationNames() {
    var endpoint = endpoint("Postgres", "VERIFY");
    var table = new TableDefinition("public", "orders", List.of());
    var source =
        CdcAdapters.require(JdbcDialect.Postgres)
            .buildSource(
                "01234567-89ab-cdef-0123-456789abcdef", endpoint, table, List.of(), null, RUNTIME);
    assertEquals("Postgres-CDC", source.get("plugin_name"));
    assertEquals(endpoint.jdbcUrl(), source.get("url"));
    assertEquals("hospital_0123456789abcdef0123456789abcdef", source.get("slot.name"));
    assertEquals(List.of("service_alias.public.orders"), source.get("table-names"));
    assertEquals(
        Map.of(
            "publication.name",
            "hospital_0123456789abcdef0123456789abcdef",
            "publication.autocreate.mode",
            "filtered",
            "slot.drop.on.stop",
            "false",
            "database.sslmode",
            "verify-full",
            "schema.include.list",
            "\\Qpublic\\E",
            "table.include.list",
            "\\Qpublic\\E\\.\\Qorders\\E"),
        source.get("debezium"));
    assertFalse(source.containsKey("schema"));
    assertEquals(1200, source.get("snapshot.fetch.size"));
    assertEquals(9000, source.get("snapshot.split.size"));
    assertCode("MODE_UNSUPPORTED", () -> CdcAdapters.require(JdbcDialect.Mysql));
    assertEquals(List.of("FULL"), JdbcDialect.Mysql.supportedModes());
    assertEquals(List.of("FULL", "CDC"), JdbcDialect.Oracle.supportedModes());
  }

  private static Map<String, Object> oracle(
      JdbcEndpoint endpoint,
      TableDefinition table,
      List<FieldMapping> mappings,
      CdcOptions options) {
    return CdcAdapters.require(JdbcDialect.Oracle)
        .buildSource(
            "01234567-89ab-cdef-0123-456789abcdef", endpoint, table, mappings, options, RUNTIME);
  }

  static JdbcEndpoint endpoint(String type, String tls) {
    return endpoint(type, tls, "HIS");
  }

  static JdbcEndpoint endpoint(String type, String tls, String oracleSchema) {
    var definition = new ConnectionDefinition();
    definition.id = "synthetic-source";
    definition.databaseType = type;
    definition.role = "SOURCE";
    definition.host = "source.example.internal";
    definition.port = type.equals("Oracle") ? 1521 : 5432;
    definition.database = "service_alias";
    definition.username = "synthetic_user";
    definition.schemas = List.of(type.equals("Oracle") ? oracleSchema : "public");
    definition.oracleConnectionType = type.equals("Oracle") ? "SERVICE_NAME" : null;
    definition.tlsMode = tls;
    return new JdbcEndpoint(definition, "password-secret-never-expose");
  }

  static ColumnDefinition number(int precision, int scale) {
    return new ColumnDefinition(
        "ID",
        "NUMBER",
        false,
        true,
        Types.NUMERIC,
        precision,
        scale,
        false,
        false,
        0,
        JdbcDialect.Oracle);
  }

  static ColumnDefinition date() {
    return new ColumnDefinition(
        "AT", "DATE", true, false, Types.TIMESTAMP, 7, 0, false, false, 0, JdbcDialect.Oracle);
  }

  static ColumnDefinition lob() {
    return new ColumnDefinition(
        "BLYY", "CLOB", true, false, Types.CLOB, 0, 0, false, false, 0, JdbcDialect.Oracle);
  }

  private static ColumnDefinition string(String type, int chars, Integer bytes) {
    return new ColumnDefinition(
        "AT",
        type,
        true,
        false,
        "NVARCHAR2".equals(type) ? Types.NVARCHAR : Types.VARCHAR,
        chars,
        0,
        false,
        false,
        0,
        JdbcDialect.Oracle,
        bytes);
  }

  static TableDefinition table(ColumnDefinition... columns) {
    return new TableDefinition("HIS", "ORDERS", List.of(columns));
  }

  private static void assertCode(String code, Runnable operation) {
    assertEquals(code, assertThrows(IntegrationException.class, operation::run).code());
  }
}
