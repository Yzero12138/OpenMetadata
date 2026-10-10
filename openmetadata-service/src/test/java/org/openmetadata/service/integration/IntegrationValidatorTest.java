package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class IntegrationValidatorTest {
  @Test
  void acceptsRealMappedPrimaryKeyAndCompatibleColumns() {
    TaskInput task = validInput();
    assertTrue(IntegrationValidator.validateTables(task, source(), target()).valid());
  }

  @Test
  void rejectsUnsafeNamesAndNonSyntheticConnections() {
    TaskInput task = validInput();
    task.sourceTable = "patient;drop table patient";
    task.targetConnectionId = "production";
    var result = IntegrationValidator.validateStructure(task);
    assertFalse(result.valid());
    assertTrue(result.errors().stream().anyMatch(error -> error.field().equals("sourceTable")));
    assertTrue(
        result.errors().stream().anyMatch(error -> error.field().equals("targetConnectionId")));
  }

  @Test
  void rejectsRepeatedTargetsAndFalsePrimaryKeys() {
    TaskInput task = validInput();
    task.primaryKey = "name";
    task.fieldMappings = List.of(new FieldMapping("id", "id"), new FieldMapping("name", "id"));
    var result = IntegrationValidator.validateTables(task, source(), target());
    assertFalse(result.valid());
    assertTrue(
        result.errors().stream().anyMatch(error -> error.code().equals("DUPLICATE_MAPPING")));
    assertTrue(
        result.errors().stream().anyMatch(error -> error.code().equals("INVALID_PRIMARY_KEY")));
  }

  @Test
  void rejectsNarrowerTargetTypes() {
    TableDefinition target =
        new TableDefinition(
            "public",
            "patient_ods",
            List.of(
                column("id", "int4", false, true, Types.INTEGER, 10),
                column("name", "varchar", true, false, Types.VARCHAR, 5)));
    var result = IntegrationValidator.validateTables(validInput(), source(), target);
    assertFalse(result.valid());
    assertTrue(
        result.errors().stream().anyMatch(error -> error.code().equals("INCOMPATIBLE_TYPE")));
  }

  @Test
  void rejectsMissingRequiredTargetColumns() {
    TaskInput task = validInput();
    task.fieldMappings = List.of(new FieldMapping("id", "id"));
    TableDefinition target =
        new TableDefinition(
            "public",
            "patient_ods",
            List.of(
                column("id", "int8", false, true, Types.BIGINT, 19),
                column("name", "varchar", false, false, Types.VARCHAR, 100)));
    var result = IntegrationValidator.validateTables(task, source(), target);
    assertFalse(result.valid());
    assertTrue(
        result.errors().stream().anyMatch(error -> error.code().equals("REQUIRED_TARGET_COLUMN")));
  }

  @Test
  void rejectsNationalCharactersIntoAnUnverifiedOrdinaryStringTarget() {
    var from = new ColumnDefinition("name", "nvarchar", true, false, Types.NVARCHAR, 100, 0, false);
    var to = new ColumnDefinition("name", "varchar", true, false, Types.VARCHAR, 100, 0, false);
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(from, true), withValue(to, false))
            .valid());
  }

  @Test
  void rejectsLossOfTemporalFractionalPrecision() {
    var from =
        new ColumnDefinition("name", "timestamp", true, false, Types.TIMESTAMP, 29, 6, false);
    var to = new ColumnDefinition("name", "datetime2", true, false, Types.TIMESTAMP, 19, 0, false);
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(from, true), withValue(to, false))
            .valid());
  }

  @Test
  void verifiedUnicodeReceiversPreserveNationalCharactersAndSupplementaryCapacity() {
    var source =
        new ColumnDefinition("name", "nvarchar", true, false, Types.NVARCHAR, 100, 0, false);
    var postgres =
        new ColumnDefinition(
            "name", "varchar", true, false, Types.VARCHAR, 100, 0, false, true, 100);
    assertTrue(
        IntegrationValidator.validateTables(
                validInput(), withValue(source, true), withValue(postgres, false))
            .valid());
    var tooSmall =
        new ColumnDefinition(
            "name", "nvarchar", true, false, Types.NVARCHAR, 100, 0, false, true, 50);
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(postgres, true), withValue(tooSmall, false))
            .valid());
    var sufficient =
        new ColumnDefinition(
            "name", "nvarchar", true, false, Types.NVARCHAR, 200, 0, false, true, 100);
    assertTrue(
        IntegrationValidator.validateTables(
                validInput(), withValue(postgres, true), withValue(sufficient, false))
            .valid());
  }

  @Test
  void acceptsWiderFractionalPrecision() {
    var from =
        new ColumnDefinition("name", "timestamp", true, false, Types.TIMESTAMP, 29, 6, false);
    var to = new ColumnDefinition("name", "datetime2", true, false, Types.TIMESTAMP, 27, 7, false);
    assertTrue(
        IntegrationValidator.validateTables(
                validInput(), withValue(from, true), withValue(to, false))
            .valid());
  }

  @Test
  void rejectsCoarseSqlServerTemporalReceivers() {
    for (String target : List.of("datetime", "smalldatetime")) {
      int scale = target.equals("datetime") ? 3 : 0;
      var from =
          new ColumnDefinition("name", "timestamp", true, false, Types.TIMESTAMP, 29, scale, false);
      var to = new ColumnDefinition("name", target, true, false, Types.TIMESTAMP, 29, scale, false);
      assertFalse(
          IntegrationValidator.validateTables(
                  validInput(), withValue(from, true), withValue(to, false))
              .valid());
      assertTrue(
          IntegrationValidator.validateTables(
                  validInput(), withValue(to, true), withValue(to, false))
              .valid());
    }
  }

  @Test
  void signedAndUnsignedIntegersRequireFullRangeInclusion() {
    var unsignedInt =
        new ColumnDefinition("name", "INT UNSIGNED", true, false, Types.INTEGER, 10, 0, false);
    var signedInt = new ColumnDefinition("name", "int4", true, false, Types.INTEGER, 10, 0, false);
    var signedBig = new ColumnDefinition("name", "int8", true, false, Types.BIGINT, 19, 0, false);
    var unsignedBig =
        new ColumnDefinition("name", "BIGINT UNSIGNED", true, false, Types.BIGINT, 20, 0, false);
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(unsignedInt, true), withValue(signedInt, false))
            .valid());
    assertTrue(
        IntegrationValidator.validateTables(
                validInput(), withValue(unsignedInt, true), withValue(signedBig, false))
            .valid());
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(signedInt, true), withValue(unsignedBig, false))
            .valid());
    assertFalse(
        IntegrationValidator.validateTables(
                validInput(), withValue(unsignedBig, true), withValue(signedBig, false))
            .valid());
    assertTrue(
        IntegrationValidator.validateTables(
                validInput(), withValue(unsignedInt, true), withValue(unsignedBig, false))
            .valid());
  }

  private TableDefinition withValue(ColumnDefinition value, boolean source) {
    return new TableDefinition(
        "public",
        source ? "patient" : "patient_ods",
        List.of(column("id", "int8", false, true, Types.BIGINT, 19), value));
  }

  static TaskInput validInput() {
    TaskInput input = new TaskInput();
    input.name = "synthetic-patient-copy";
    input.displayName = "Synthetic patient copy";
    input.sourceConnectionId = "synthetic-source";
    input.targetConnectionId = "synthetic-ods";
    input.sourceSchema = "public";
    input.sourceTable = "patient";
    input.targetSchema = "public";
    input.targetTable = "patient_ods";
    input.mode = "FULL";
    input.primaryKey = "id";
    input.fieldMappings = List.of(new FieldMapping("id", "id"), new FieldMapping("name", "name"));
    return input;
  }

  static TableDefinition source() {
    return new TableDefinition(
        "public",
        "patient",
        List.of(
            column("id", "int8", false, true, Types.BIGINT, 19),
            column("name", "varchar", true, false, Types.VARCHAR, 100)));
  }

  static TableDefinition target() {
    return new TableDefinition("public", "patient_ods", source().columns());
  }

  private static ColumnDefinition column(
      String name, String dataType, boolean nullable, boolean primaryKey, int jdbcType, int size) {
    return new ColumnDefinition(
        name, dataType, nullable, primaryKey, jdbcType, size, 0, false, true, size);
  }
}
