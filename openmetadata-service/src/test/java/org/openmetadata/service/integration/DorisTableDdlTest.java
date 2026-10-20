package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;

class DorisTableDdlTest {
  @Test
  void derivesSafeUniqueKeyDdlWithKeyFirstAndUnicodeByteCapacity() {
    var input =
        input(
            List.of(new FieldMapping("patient_name", "患者姓名"), new FieldMapping("id", "visit_id")));
    var preview = DorisTableDdl.preview(input, source(), sourceConnection(), targetConnection(), 1);
    assertEquals("doris-ods-validation", preview.targetConnectionId());
    assertTrue(preview.previewHash().matches("[a-f0-9]{64}"));
    assertTrue(
        preview
            .ddl()
            .startsWith(
                "CREATE TABLE `ods_validation`.`new_visits` (\n  `visit_id` BIGINT NOT NULL,"));
    assertTrue(preview.ddl().contains("`患者姓名` VARCHAR(400) NOT NULL"));
    assertTrue(preview.ddl().contains("UNIQUE KEY (`visit_id`)"));
    assertTrue(preview.ddl().contains("DISTRIBUTED BY HASH (`visit_id`)"));
    assertTrue(preview.ddl().contains("\"replication_num\" = \"1\""));
    assertTrue(preview.ddl().contains("\"enable_unique_key_merge_on_write\" = \"true\""));
    for (String destructive : List.of("IF NOT EXISTS", "DROP ", "ALTER ", "TRUNCATE ")) {
      assertFalse(preview.ddl().contains(destructive));
    }
    var task = IntegrationValidatorTest.validInput();
    task.sourceSchema = "public";
    task.sourceTable = "visits";
    task.targetSchema = "ods_validation";
    task.targetTable = "new_visits";
    task.fieldMappings = input.fieldMappings();
    assertTrue(IntegrationValidator.validateTables(task, source(), preview.table()).valid());
  }

  @Test
  void hashesHiddenSourcePrecisionAndBothConnectionVersions() {
    var input = input(List.of(new FieldMapping("id", "id"), new FieldMapping("amount", "amount")));
    var decimal =
        new ColumnDefinition("amount", "numeric", false, false, Types.NUMERIC, 20, 2, false);
    var source =
        new TableDefinition("public", "visits", List.of(source().columns().getFirst(), decimal));
    var first = DorisTableDdl.preview(input, source, sourceConnection(), targetConnection(), 3);
    assertTrue(first.ddl().contains("DECIMAL(20,2)"));
    var changed =
        new TableDefinition(
            "public",
            "visits",
            List.of(
                source.columns().getFirst(),
                new ColumnDefinition(
                    "amount", "numeric", false, false, Types.NUMERIC, 20, 3, false)));
    assertNotEquals(
        first.previewHash(),
        DorisTableDdl.preview(input, changed, sourceConnection(), targetConnection(), 3)
            .previewHash());
    var sourceConnection = sourceConnection();
    sourceConnection.version++;
    assertNotEquals(
        first.previewHash(),
        DorisTableDdl.preview(input, source, sourceConnection, targetConnection(), 3)
            .previewHash());
    var targetConnection = targetConnection();
    targetConnection.version++;
    assertNotEquals(
        first.previewHash(),
        DorisTableDdl.preview(input, source, sourceConnection(), targetConnection, 3)
            .previewHash());
    var unmapped = new ArrayList<>(source.columns());
    unmapped.add(
        new ColumnDefinition("unmapped", "varchar", true, false, Types.VARCHAR, 50, 0, false));
    assertNotEquals(
        first.previewHash(),
        DorisTableDdl.preview(
                input,
                new TableDefinition("public", "visits", unmapped),
                sourceConnection(),
                targetConnection(),
                3)
            .previewHash());
  }

  @Test
  void rejectsUnsafeMappingsUnknownColumnsMissingRealNonnullableKeyAndUnsupportedTypes() {
    var valid =
        input(List.of(new FieldMapping("id", "id"), new FieldMapping("patient_name", "name")));
    for (var mappings :
        List.of(
            List.of(new FieldMapping("patient_name", "name")),
            List.of(new FieldMapping("id", "id"), new FieldMapping("patient_name", "ID")),
            List.of(new FieldMapping("id", "id"), new FieldMapping("id", "other")),
            List.of(new FieldMapping("id", "id"), new FieldMapping("unknown", "other")),
            List.of(new FieldMapping("id", "id`; DROP TABLE patients")))) {
      assertEquals(
          "INVALID_CONFIGURATION",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      DorisTableDdl.preview(
                          input(mappings), source(), sourceConnection(), targetConnection(), 1))
              .code());
    }
    var nullableKey =
        new TableDefinition(
            "public",
            "visits",
            List.of(
                new ColumnDefinition("id", "bigint", true, true, Types.BIGINT, 19, 0, false),
                source().columns().get(1)));
    assertThrows(
        IntegrationException.class,
        () -> DorisTableDdl.preview(valid, nullableKey, sourceConnection(), targetConnection(), 1));
    for (var unsupported :
        List.of(
            new ColumnDefinition(
                "patient_name", "numeric", false, false, Types.NUMERIC, 39, 2, false),
            new ColumnDefinition(
                "patient_name", "number", false, false, Types.NUMERIC, 0, -127, false),
            new ColumnDefinition(
                "patient_name",
                "varchar",
                false,
                false,
                Types.VARCHAR,
                Integer.MAX_VALUE,
                0,
                false),
            new ColumnDefinition(
                "patient_name", "timestamp", false, false, Types.TIMESTAMP, 30, 7, false),
            new ColumnDefinition(
                "patient_name",
                "timestamptz",
                false,
                false,
                Types.TIMESTAMP_WITH_TIMEZONE,
                30,
                6,
                false),
            new ColumnDefinition(
                "patient_name", "timestamptz", false, false, Types.TIMESTAMP, 30, 6, false),
            new ColumnDefinition(
                "patient_name", "blob", false, false, Types.BLOB, 100, 0, false))) {
      var source =
          new TableDefinition(
              "public", "visits", List.of(source().columns().getFirst(), unsupported));
      assertEquals(
          "UNSUPPORTED_TARGET_TYPE",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      DorisTableDdl.preview(
                          valid, source, sourceConnection(), targetConnection(), 1))
              .code());
    }
  }

  @Test
  void mapsBoundedBooleansDatesDecimalsAndMicrosecondTimestamps() {
    var mappings =
        List.of(
            new FieldMapping("id", "id"),
            new FieldMapping("active", "active"),
            new FieldMapping("day", "day"),
            new FieldMapping("updated_at", "updated_at"));
    var columns = new ArrayList<>(List.of(source().columns().getFirst()));
    columns.add(new ColumnDefinition("active", "bool", true, false, Types.BOOLEAN, 1, 0, false));
    columns.add(new ColumnDefinition("day", "date", true, false, Types.DATE, 10, 0, false));
    columns.add(
        new ColumnDefinition(
            "updated_at", "timestamp", true, false, Types.TIMESTAMP, 29, 6, false));
    var preview =
        DorisTableDdl.preview(
            input(mappings),
            new TableDefinition("public", "visits", columns),
            sourceConnection(),
            targetConnection(),
            1);
    assertTrue(preview.ddl().contains("`active` BOOLEAN NULL"));
    assertTrue(preview.ddl().contains("`day` DATEV2 NULL"));
    assertTrue(preview.ddl().contains("`updated_at` DATETIMEV2(6) NULL"));
  }

  static TargetTableInput input(List<FieldMapping> mappings) {
    return new TargetTableInput(
        "synthetic-source", "public", "visits", "ods_validation", "new_visits", mappings, null);
  }

  static TableDefinition source() {
    return new TableDefinition(
        "public",
        "visits",
        List.of(
            new ColumnDefinition("id", "bigint", false, true, Types.BIGINT, 19, 0, false),
            new ColumnDefinition(
                "patient_name", "varchar", false, false, Types.VARCHAR, 100, 0, false)));
  }

  static ConnectionDefinition sourceConnection() {
    var value = new ConnectionDefinition();
    value.copyInput(IntegrationConnectionsTest.input("Postgres"));
    value.id = "synthetic-source";
    value.version = 1;
    return value;
  }

  static ConnectionDefinition targetConnection() {
    var value = new ConnectionDefinition();
    value.id = "doris-ods-validation";
    value.role = "TARGET";
    value.databaseType = "Doris";
    value.database = "ods_validation";
    value.schemas = List.of("ods_validation");
    value.enabled = true;
    value.version = 1;
    return value;
  }
}
