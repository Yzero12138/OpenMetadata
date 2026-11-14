package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class OracleTypeFoundationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOURCE_SCHEMA = "SYNTHETIC";
  private static final String SOURCE_TABLE = "SOURCE_FIXTURE";

  @Test
  void acceptsTruthfulOracleDateTimestampMetadataIntoNativeDorisDatetimeZero() {
    var source = inspectSource(List.of(key(), value("VALUE", "DATE", Types.TIMESTAMP, 19, 0)));
    var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "datetime", true)));
    assertEquals("DATE", source.columns().get(1).dataType());
    assertEquals(Types.TIMESTAMP, source.columns().get(1).jdbcType());
    assertEquals(JdbcDialect.Oracle, source.columns().get(1).inspectedDialect());
    assertEquals("DATETIME", target.columns().get(1).dataType());
    assertEquals(0, target.columns().get(1).scale());
    assertEquals(JdbcDialect.Doris, target.columns().get(1).inspectedDialect());
    var result = IntegrationValidator.validateTables(task(source), source, target);
    assertTrue(result.valid(), () -> result.errors().toString());
  }

  @Test
  void retainsSqlServerDatetimeAndSmalldatetimeNarrowingGuardsWithInspectedMetadata() {
    var source = inspectSource(List.of(key(), value("VALUE", "DATE", Types.TIMESTAMP, 19, 0)));
    for (String type : List.of("datetime", "smalldatetime")) {
      var definition = sourceConnection();
      definition.databaseType = "Mssql";
      definition.databaseVersion = "2019";
      var target =
          inspectSource(definition, List.of(key(), value("VALUE", type, Types.TIMESTAMP, 19, 0)));
      assertFalse(IntegrationValidator.validateTables(task(source), source, target).valid());
    }
    var fractional =
        inspectSource(List.of(key(), value("VALUE", "TIMESTAMP", Types.TIMESTAMP, 26, 6)));
    var doris = inspectTarget(List.of(targetKey(), targetValue("VALUE", "datetime", true)));
    assertFalse(IntegrationValidator.validateTables(task(fractional), fractional, doris).valid());
  }

  @Test
  void temporalTypeNamesAloneCannotAssertOracleOrDorisProvenance() {
    var source = inspectSource(List.of(key(), value("VALUE", "DATE", Types.TIMESTAMP, 19, 0)));
    var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "datetime", true)));
    var unverifiedSource =
        new TableDefinition(
            source.schema(),
            source.name(),
            List.of(
                source.columns().getFirst(),
                new ColumnDefinition("VALUE", "DATE", true, false, Types.TIMESTAMP, 19, 0, false)));
    assertFalse(
        IntegrationValidator.validateTables(task(source), unverifiedSource, target).valid());
    var unverifiedTarget =
        new TableDefinition(
            target.schema(),
            target.name(),
            List.of(
                target.columns().getFirst(),
                new ColumnDefinition(
                    "VALUE", "DATETIME", true, false, Types.TIMESTAMP, 26, 0, false)));
    assertFalse(
        IntegrationValidator.validateTables(task(source), source, unverifiedTarget).valid());
  }

  @Test
  void mapsOracleClobAndNclobToStringWithoutInventingABoundedLength() {
    for (int jdbc : List.of(Types.CLOB, Types.NCLOB)) {
      for (int size : List.of(0, -1, 4000, Integer.MAX_VALUE)) {
        String type = jdbc == Types.CLOB ? "CLOB" : "NCLOB";
        var source = inspectSource(List.of(key(), value("VALUE", type, jdbc, size, 0)));
        var preview = preview(source);
        assertTrue(preview.ddl().contains("`VALUE` STRING NULL"));
        assertFalse(preview.ddl().contains("VARCHAR"));
        assertFalse(preview.ddl().contains("SUBSTR"));
        assertEquals(size, source.columns().get(1).size());
        assertEquals(type, source.columns().get(1).dataType());
        var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "string", true)));
        assertTrue(IntegrationValidator.validateTables(task(source), source, target).valid());
      }
    }
  }

  @Test
  void preservesExistingFullOracleClobToIdenticalClobCompatibility() {
    assertIdenticalOracleLobFullCompatibility("CLOB", Types.CLOB);
  }

  @Test
  void preservesExistingFullOracleNclobToIdenticalNclobCompatibility() {
    assertIdenticalOracleLobFullCompatibility("NCLOB", Types.NCLOB);
  }

  @Test
  void lobAcceptanceRequiresTrustedOracleSourceAndNativeDorisStringReceiver() {
    for (int jdbc : List.of(Types.CLOB, Types.NCLOB)) {
      String type = jdbc == Types.CLOB ? "CLOB" : "NCLOB";
      var source = inspectSource(List.of(key(), value("VALUE", type, jdbc, 0, 0)));
      for (String targetType : List.of("varchar(65532)", "char(255)", "text")) {
        var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", targetType, true)));
        assertFalse(IntegrationValidator.validateTables(task(source), source, target).valid());
      }
      var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "string", true)));
      var unverified =
          new TableDefinition(
              SOURCE_SCHEMA,
              SOURCE_TABLE,
              List.of(
                  source.columns().getFirst(),
                  new ColumnDefinition("VALUE", type, true, false, jdbc, 0, 0, false)));
      assertFalse(IntegrationValidator.validateTables(task(source), unverified, target).valid());
      assertEquals(
          "UNSUPPORTED_TARGET_TYPE",
          assertThrows(IntegrationException.class, () -> preview(unverified)).code());
      var foreignConnection = sourceConnection();
      foreignConnection.databaseType = "Mssql";
      foreignConnection.databaseVersion = "2019";
      var foreign =
          inspectSource(foreignConnection, List.of(key(), value("VALUE", type, jdbc, 0, 0)));
      assertFalse(IntegrationValidator.validateTables(task(source), foreign, target).valid());
      assertEquals(
          "UNSUPPORTED_TARGET_TYPE",
          assertThrows(IntegrationException.class, () -> preview(foreign)).code());
      var unverifiedTarget =
          new TableDefinition(
              target.schema(),
              target.name(),
              List.of(
                  target.columns().getFirst(),
                  new ColumnDefinition(
                      "VALUE",
                      "STRING",
                      true,
                      false,
                      Types.LONGVARCHAR,
                      1_048_576,
                      0,
                      false,
                      true,
                      262_144)));
      assertFalse(
          IntegrationValidator.validateTables(task(source), source, unverifiedTarget).valid());
    }
  }

  @Test
  void rejectsLobKeysAndNullableLobIntoRequiredString() {
    for (int jdbc : List.of(Types.CLOB, Types.NCLOB)) {
      String type = jdbc == Types.CLOB ? "CLOB" : "NCLOB";
      var lobKey = value("ID", type, jdbc, 0, 0);
      lobKey.put("NULLABLE", DatabaseMetaData.columnNoNulls);
      var sourceKey = inspectSource(List.of(lobKey));
      assertEquals(
          "UNSUPPORTED_TARGET_TYPE",
          assertThrows(IntegrationException.class, () -> preview(sourceKey)).code());
      var targetKey = targetValue("ID", "string", false);
      assertFalse(
          IntegrationValidator.validateTables(
                  task(sourceKey), sourceKey, inspectTarget(List.of(targetKey)))
              .valid());
      var fullKey = task(sourceKey);
      fullKey.mode = "FULL";
      assertFalse(
          IntegrationValidator.validateTables(fullKey, sourceKey, inspectSource(List.of(lobKey)))
              .valid());
      var source = inspectSource(List.of(key(), value("VALUE", type, jdbc, 0, 0)));
      var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "string", false)));
      assertFalse(IntegrationValidator.validateTables(task(source), source, target).valid());
    }
  }

  @Test
  void nativeValidationRequiresNonnullableSourceAndTargetPrimaryKeys() {
    var nullableKey = key();
    nullableKey.put("NULLABLE", DatabaseMetaData.columnNullable);
    var nullableSource = inspectSource(List.of(nullableKey));
    var target = inspectTarget(List.of(targetKey()));
    var nullableSourceResult =
        IntegrationValidator.validateTables(task(nullableSource), nullableSource, target);
    assertFalse(nullableSourceResult.valid());
    assertTrue(
        nullableSourceResult.errors().stream()
            .anyMatch(error -> error.code().equals("INVALID_PRIMARY_KEY")));
    assertThrows(IntegrationException.class, () -> preview(nullableSource));
    var source = inspectSource(List.of(key()));
    var nullableTargetKey = targetKey();
    nullableTargetKey.put("IS_NULLABLE", "YES");
    var nullableTarget = inspectTarget(List.of(nullableTargetKey));
    assertFalse(IntegrationValidator.validateTables(task(source), source, nullableTarget).valid());
    var nullableBoth =
        IntegrationValidator.validateTables(task(nullableSource), nullableSource, nullableTarget);
    assertFalse(nullableBoth.valid());
    assertTrue(
        nullableBoth.errors().stream()
            .anyMatch(error -> error.code().equals("INVALID_PRIMARY_KEY")));
  }

  @Test
  void keepsNumericAndUnicodeCapacityChecksAfterNativeMetadataInspection() {
    var decimal = inspectSource(List.of(key(), value("VALUE", "NUMBER", Types.NUMERIC, 12, 2)));
    for (String targetType : List.of("decimal(11,2)", "decimal(12,1)")) {
      var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", targetType, true)));
      assertFalse(IntegrationValidator.validateTables(task(decimal), decimal, target).valid());
    }
    var text = inspectSource(List.of(key(), value("VALUE", "VARCHAR2", Types.VARCHAR, 100, 0)));
    for (String targetType : List.of("varchar(399)", "varchar(400)")) {
      var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", targetType, true)));
      assertEquals(
          targetType.equals("varchar(400)"),
          IntegrationValidator.validateTables(task(text), text, target).valid());
    }
    var oversized =
        inspectSource(List.of(key(), value("VALUE", "VARCHAR2", Types.VARCHAR, 16384, 0)));
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(IntegrationException.class, () -> preview(oversized)).code());
  }

  @Test
  void clientJsonCannotClaimDialectOrOtherInspectedCapabilities() throws Exception {
    var source = inspectSource(List.of(key(), value("VALUE", "CLOB", Types.CLOB, 0, 0)));
    ObjectNode serialized = JSON.valueToTree(source.columns().get(1));
    assertEquals(4, serialized.size());
    assertFalse(serialized.has("inspectedDialect"));
    var legacy = IntegrationModels.read(serialized, ColumnDefinition.class);
    assertEquals("CLOB", legacy.dataType());
    assertEquals(0, legacy.jdbcType());
    assertNull(legacy.inspectedDialect());
    serialized.put("inspectedDialect", "Oracle");
    serialized.put("jdbcType", Types.CLOB);
    serialized.put("size", 4000);
    serialized.put("unicodeSafeTarget", true);
    serialized.put("unicodeCapacity", Integer.MAX_VALUE);
    var claimed = IntegrationModels.read(serialized, ColumnDefinition.class);
    assertNull(claimed.inspectedDialect());
    assertEquals(0, claimed.jdbcType());
    assertEquals(0, claimed.size());
    assertFalse(claimed.unicodeSafeTarget());
    var untrusted =
        new TableDefinition(
            SOURCE_SCHEMA, SOURCE_TABLE, List.of(source.columns().getFirst(), claimed));
    var target = inspectTarget(List.of(targetKey(), targetValue("VALUE", "string", true)));
    assertFalse(IntegrationValidator.validateTables(task(source), untrusted, target).valid());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(IntegrationException.class, () -> preview(untrusted)).code());
    ObjectNode targetJson = JSON.valueToTree(target.columns().get(1));
    targetJson.put("inspectedDialect", "Doris");
    targetJson.put("jdbcType", Types.LONGVARCHAR);
    targetJson.put("unicodeSafeTarget", true);
    var claimedTarget = IntegrationModels.read(targetJson, ColumnDefinition.class);
    assertNull(claimedTarget.inspectedDialect());
    assertEquals(0, claimedTarget.jdbcType());
    assertFalse(claimedTarget.unicodeSafeTarget());
  }

  @Test
  void legacyColumnConstructorsRemainUnverified() {
    var eight = new ColumnDefinition("VALUE", "DATE", true, false, Types.TIMESTAMP, 19, 0, false);
    var ten =
        new ColumnDefinition(
            "VALUE", "STRING", true, false, Types.LONGVARCHAR, 4000, 0, false, true, 1000);
    assertNull(eight.inspectedDialect());
    assertNull(ten.inspectedDialect());
    assertFalse(eight.unicodeSafeTarget());
    assertTrue(ten.unicodeSafeTarget());
  }

  @Test
  void synthetic102ColumnOracleShapeRoundTripsPreviewAndActualDorisMetadata() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>(List.of(key()));
    for (int index = 1; index <= 14; index++) {
      rows.add(value("DATE_" + index, "DATE", Types.TIMESTAMP, 19, 0));
    }
    rows.add(value("LOB_VALUE", "CLOB", Types.CLOB, 0, 0));
    for (int index = 1; index <= 10; index++) {
      rows.add(value("AMOUNT_" + index, "NUMBER", Types.NUMERIC, 12, 2));
    }
    for (int index = 1; index <= 76; index++) {
      rows.add(value("TEXT_" + index, "VARCHAR2", Types.VARCHAR, 100, 0));
    }
    var source = inspectSource(rows);
    assertEquals(102, source.columns().size());
    assertEquals(14, source.columns().stream().filter(c -> c.dataType().equals("DATE")).count());
    assertEquals(1, source.columns().stream().filter(ColumnDefinition::primaryKey).count());
    assertEquals(18, source.columns().getFirst().size());
    var preview = preview(source);
    assertEquals(102, preview.fieldMappings().size());
    assertTrue(preview.ddl().contains("`ID` DECIMAL(18,0) NOT NULL"));
    assertTrue(preview.ddl().contains("`LOB_VALUE` STRING NULL"));
    assertTrue(preview.ddl().contains("`TEXT_76` VARCHAR(400) NULL"));
    assertEquals(
        14,
        preview.table().columns().stream()
            .filter(c -> c.dataType().equals("DATETIMEV2(0)"))
            .count());
    assertTrue(IntegrationValidator.validateTables(task(source), source, preview.table()).valid());
    List<Map<String, Object>> actualRows =
        preview.table().columns().stream()
            .map(
                column ->
                    targetValue(
                        column.name(),
                        column.dataType().equals("DATETIMEV2(0)")
                            ? "datetime"
                            : column.dataType().toLowerCase(Locale.ROOT),
                        column.nullable()))
            .toList();
    var actual = inspectTarget(actualRows);
    assertEquals(102, actual.columns().size());
    assertEquals(
        14, actual.columns().stream().filter(c -> c.dataType().equals("DATETIME")).count());
    assertEquals(1, actual.columns().stream().filter(ColumnDefinition::primaryKey).count());
    var result = IntegrationValidator.validateTables(task(source), source, actual);
    assertTrue(result.valid(), () -> result.errors().toString());
    var json = JSON.readTree(JSON.writeValueAsString(source));
    assertEquals(102, json.path("columns").size());
    assertEquals(4, json.path("columns").get(0).size());
    assertEquals("NUMBER", json.path("columns").get(0).path("dataType").asText());
  }

  private IntegrationModels.TargetTablePreview preview(TableDefinition source) {
    var input =
        new TargetTableInput(
            "synthetic-source",
            SOURCE_SCHEMA,
            SOURCE_TABLE,
            "ods_validation",
            "oracle_fixture",
            mappings(source),
            null,
            "CDC",
            null,
            null);
    return DorisTableDdl.preview(
        input, source, sourceConnection(), DorisTableDdlTest.targetConnection(), 1);
  }

  private void assertIdenticalOracleLobFullCompatibility(String type, int jdbc) {
    var source = inspectSource(List.of(key(), value("VALUE", type, jdbc, 0, 0)));
    var target =
        inspectSource(List.of(key(), value("VALUE", type.toLowerCase(Locale.ROOT), jdbc, 0, 0)));
    var input = task(source);
    input.mode = "FULL";
    input.targetConnectionId = IntegrationConfiguration.TARGET_ID;
    input.targetSchema = SOURCE_SCHEMA;
    input.targetTable = SOURCE_TABLE;
    var result = IntegrationValidator.validateTables(input, source, target);
    assertTrue(result.valid(), () -> result.errors().toString());
    var legacyColumns =
        source.columns().stream()
            .map(
                column ->
                    new ColumnDefinition(
                        column.name(),
                        column.dataType(),
                        column.nullable(),
                        column.primaryKey(),
                        column.jdbcType(),
                        column.size(),
                        column.scale(),
                        column.hasDefault()))
            .toList();
    var legacy = new TableDefinition(SOURCE_SCHEMA, SOURCE_TABLE, legacyColumns);
    assertTrue(IntegrationValidator.validateTables(input, legacy, legacy).valid());
    var requiredLob = value("VALUE", type, jdbc, 0, 0);
    requiredLob.put("NULLABLE", DatabaseMetaData.columnNoNulls);
    assertFalse(
        IntegrationValidator.validateTables(
                input, source, inspectSource(List.of(key(), requiredLob)))
            .valid());
  }

  private TaskInput task(TableDefinition source) {
    var task = IntegrationValidatorTest.validInput();
    task.sourceSchema = SOURCE_SCHEMA;
    task.sourceTable = SOURCE_TABLE;
    task.targetConnectionId = "doris-ods-validation";
    task.targetSchema = "ods_validation";
    task.targetTable = "oracle_fixture";
    task.mode = "CDC";
    task.primaryKey = "ID";
    task.fieldMappings = mappings(source);
    return task;
  }

  private List<FieldMapping> mappings(TableDefinition source) {
    return source.columns().stream()
        .map(column -> new FieldMapping(column.name(), column.name()))
        .toList();
  }

  private ConnectionDefinition sourceConnection() {
    var definition = new ConnectionDefinition();
    definition.copyInput(IntegrationConnectionsTest.input("Oracle"));
    definition.id = "synthetic-source";
    definition.databaseVersion = "11g";
    definition.schemas = List.of(SOURCE_SCHEMA);
    return definition;
  }

  private TableDefinition inspectSource(List<Map<String, Object>> columns) {
    return inspectSource(sourceConnection(), columns);
  }

  private TableDefinition inspectSource(
      ConnectionDefinition definition, List<Map<String, Object>> columns) {
    var endpoint = new JdbcEndpoint(definition, "synthetic-fixture-password");
    var connection = sourceMetadata(columns);
    var inspector = new JdbcInspector(id -> endpoint, (ignored, properties) -> connection);
    var table = inspector.table(definition.id, SOURCE_SCHEMA, SOURCE_TABLE);
    assertNotNull(table);
    return table;
  }

  private TableDefinition inspectTarget(List<Map<String, Object>> columns) {
    var definition = DorisTableDdlTest.targetConnection();
    definition.host = "synthetic-doris.invalid";
    definition.port = 9030;
    definition.dorisHttpPort = 8030;
    definition.username = "synthetic_reader";
    definition.tlsMode = "DISABLED";
    var endpoint = new JdbcEndpoint(definition, "synthetic-fixture-password");
    var metadata =
        proxy(
            DatabaseMetaData.class,
            (instance, method, args) ->
                switch (method.getName()) {
                  case "getTables" -> result(
                      List.of(
                          Map.of("TABLE_CAT", "ods_validation", "TABLE_NAME", "oracle_fixture")));
                  case "getSearchStringEscape" -> "\\";
                  default -> null;
                });
    var connection =
        proxy(
            Connection.class,
            (instance, method, args) ->
                switch (method.getName()) {
                  case "getMetaData" -> metadata;
                  case "createStatement" -> proxy(
                      Statement.class,
                      (statement, operation, parameters) ->
                          operation.getName().equals("executeQuery")
                              ? result(
                                  List.of(
                                      Map.of(
                                          "2",
                                          "CREATE TABLE `oracle_fixture` (\n`ID` decimal(18,0)\n) ENGINE=OLAP\nUNIQUE KEY (`ID`)")))
                              : null);
                  case "prepareStatement" -> proxy(
                      PreparedStatement.class,
                      (statement, operation, parameters) ->
                          operation.getName().equals("executeQuery") ? result(columns) : null);
                  default -> null;
                });
    return new JdbcInspector(id -> endpoint, (ignored, properties) -> connection)
        .table(definition.id, "ods_validation", "oracle_fixture");
  }

  private Connection sourceMetadata(List<Map<String, Object>> columns) {
    var metadata =
        proxy(
            DatabaseMetaData.class,
            (instance, method, args) ->
                switch (method.getName()) {
                  case "getTables" -> result(
                      List.of(Map.of("TABLE_SCHEM", SOURCE_SCHEMA, "TABLE_NAME", SOURCE_TABLE)));
                  case "getPrimaryKeys" -> result(List.of(Map.of("COLUMN_NAME", "ID")));
                  case "getColumns" -> result(columns);
                  case "getSearchStringEscape" -> "\\";
                  default -> null;
                });
    return proxy(
        Connection.class,
        (instance, method, args) ->
            switch (method.getName()) {
              case "getMetaData" -> metadata;
              case "prepareStatement", "createStatement" -> throw new AssertionError(
                  "Source fixture allows metadata inspection only");
              default -> null;
            });
  }

  private Map<String, Object> key() {
    var column = value("ID", "NUMBER", Types.NUMERIC, 18, 0);
    column.put("NULLABLE", DatabaseMetaData.columnNoNulls);
    return column;
  }

  private Map<String, Object> value(String name, String type, int jdbc, int size, int scale) {
    Map<String, Object> column = new LinkedHashMap<>();
    column.put("TABLE_SCHEM", SOURCE_SCHEMA);
    column.put("TABLE_NAME", SOURCE_TABLE);
    column.put("COLUMN_NAME", name);
    column.put("TYPE_NAME", type);
    column.put("DATA_TYPE", jdbc);
    column.put("COLUMN_SIZE", size);
    column.put("DECIMAL_DIGITS", scale);
    column.put("NULLABLE", DatabaseMetaData.columnNullable);
    column.put("COLUMN_DEF", null);
    return column;
  }

  private Map<String, Object> targetKey() {
    return targetValue("ID", "decimal(18,0)", false);
  }

  private Map<String, Object> targetValue(String name, String type, boolean nullable) {
    Map<String, Object> column = new LinkedHashMap<>();
    column.put("COLUMN_NAME", name);
    column.put("COLUMN_TYPE", type);
    column.put("IS_NULLABLE", nullable ? "YES" : "NO");
    column.put("COLUMN_DEFAULT", null);
    return column;
  }

  private ResultSet result(List<? extends Map<String, ?>> rows) {
    int[] index = {-1};
    return proxy(
        ResultSet.class,
        (instance, method, args) ->
            switch (method.getName()) {
              case "next" -> ++index[0] < rows.size();
              case "getString" -> rows.get(index[0]).get(String.valueOf(args[0]));
              case "getInt" -> {
                Object value = rows.get(index[0]).get(String.valueOf(args[0]));
                yield value == null ? 0 : ((Number) value).intValue();
              }
              default -> null;
            });
  }

  private <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, handler));
  }
}
