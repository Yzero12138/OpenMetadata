package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DorisTableMetadataTest {
  @Test
  void taskInspectorNormalizesNativeBooleanAndBoundedStringReceivers() {
    var bool = DorisTableMetadata.column("active", "tinyint(1)", true, false, false);
    assertEquals(Types.BOOLEAN, bool.jdbcType());
    for (String type : List.of("string", "text")) {
      var string = DorisTableMetadata.column("name", type, true, false, false);
      assertEquals(Types.LONGVARCHAR, string.jdbcType());
      assertTrue(string.unicodeSafeTarget());
      assertEquals(1_048_576, string.size());
      assertEquals(262_144, string.unicodeCapacity());
      var task = IntegrationValidatorTest.validInput();
      var target =
          new IntegrationModels.TableDefinition(
              "public",
              "patient_ods",
              List.of(DorisTableMetadata.column("id", "bigint", false, true, false), string));
      assertTrue(
          IntegrationValidator.validateTables(task, IntegrationValidatorTest.source(), target)
              .valid());
    }
  }

  @Test
  void literalNullDefaultRemainsUsableWhenColumnIsUnmapped() throws Exception {
    var rows =
        List.of(
            metadataRow("id", "bigint", false, true, null),
            metadataRow("name", "varchar(400)", true, false, null),
            metadataRow("extra", "varchar(16)", false, false, "NULL"));
    var inspected = DorisTableMetadata.read(metadataConnection(rows), "public", "patient_ods");
    assertTrue(inspected.columns().get(2).hasDefault());
    assertFalse(inspected.columns().getFirst().hasDefault());
    assertFalse(inspected.columns().get(1).hasDefault());
    assertTrue(
        IntegrationValidator.validateTables(
                IntegrationValidatorTest.validInput(), IntegrationValidatorTest.source(), inspected)
            .valid());
  }

  @Test
  void identifiesLogicalUniqueKeysWithoutTreatingDuplicateOrAggregateKeysAsPrimary() {
    assertEquals(
        Set.of("id"),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nUNIQUE KEY(`id`)\nDISTRIBUTED BY HASH(`id`)"));
    assertEquals(
        Set.of(),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nDUPLICATE KEY(`id`)"));
    assertEquals(
        Set.of(),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nAGGREGATE KEY(`id`)"));
  }

  @Test
  void keepsDorisByteLengthsSeparateFromUnicodeCharacterCapacity() {
    var column = DorisTableMetadata.column("患者姓名", "varchar(400)", false, false, false);
    assertEquals(Types.VARCHAR, column.jdbcType());
    assertEquals(400, column.size());
    assertTrue(column.unicodeSafeTarget());
    assertEquals(100, column.unicodeCapacity());
    var key = DorisTableMetadata.column("id", "bigint", false, true, false);
    assertTrue(key.primaryKey());
    assertFalse(key.nullable());
    assertEquals(Types.BIGINT, key.jdbcType());
    var timestamp = DorisTableMetadata.column("updated_at", "datetimev2(6)", true, false, false);
    assertEquals(Types.TIMESTAMP, timestamp.jdbcType());
    assertEquals(6, timestamp.scale());
    var decimal = DorisTableMetadata.column("amount", "decimal(20, 4)", true, false, false);
    assertEquals(20, decimal.size());
    assertEquals(4, decimal.scale());
  }

  private Connection metadataConnection(List<Map<String, String>> columns) {
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("createStatement")) {
                return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Statement.class},
                    (statement, operation, parameters) -> {
                      if (!operation.getName().equals("executeQuery")) return null;
                      String sql = (String) parameters[0];
                      return sql.startsWith("SHOW CREATE")
                          ? result(
                              List.of(
                                  Map.of(
                                      "2",
                                      "CREATE TABLE `patient_ods` (\n`id` bigint\n) ENGINE=OLAP\nUNIQUE KEY (`id`)")))
                          : result(columns);
                    });
              }
              if (method.getName().equals("prepareStatement")) {
                return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (statement, operation, parameters) ->
                        operation.getName().equals("executeQuery") ? result(columns) : null);
              }
              return null;
            });
  }

  private ResultSet result(List<Map<String, String>> rows) {
    int[] index = {-1};
    return (ResultSet)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "next" -> ++index[0] < rows.size();
                  case "getString" -> rows.get(index[0]).get(String.valueOf(args[0]));
                  default -> null;
                });
  }

  private Map<String, String> metadataRow(
      String name, String type, boolean nullable, boolean key, String value) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("Field", name);
    row.put("Type", type);
    row.put("Null", nullable ? "YES" : "NO");
    row.put("Key", key ? "true" : "false");
    row.put("Default", value);
    row.put("COLUMN_NAME", name);
    row.put("COLUMN_TYPE", type);
    row.put("IS_NULLABLE", nullable ? "YES" : "NO");
    row.put("COLUMN_DEFAULT", value);
    return row;
  }
}
