package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.RuntimeOptions;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;

class IntegrationValidatorTest {
  @Test
  void runtimeOptionsRejectEveryOutOfRangeValueIncludingZeroAndNegative() {
    List<String> fields =
        List.of(
            "fetchSize",
            "splitSize",
            "sinkBatchSize",
            "checkpointIntervalSeconds",
            "checkpointTimeoutSeconds");
    int[] minimums = {100, 1000, 100, 10, 60};
    int[] maximums = {10000, 100000, 50000, 300, 3600};
    for (int field = 0; field < fields.size(); field++) {
      for (int value :
          new int[] {0, -1, minimums[field] - 1, maximums[field] + 1, Integer.MAX_VALUE}) {
        TaskInput task = validInput();
        Integer[] options = new Integer[5];
        options[field] = value;
        task.runtime =
            new RuntimeOptions(options[0], options[1], options[2], options[3], options[4]);
        String expectedField = "runtime." + fields.get(field);
        assertTrue(
            IntegrationValidator.validateStructure(task).errors().stream()
                .anyMatch(error -> error.field().equals(expectedField)),
            expectedField + "=" + value);
      }
    }
  }

  @Test
  void runtimeOptionsAcceptInclusiveBoundsAndIndividuallyNullableSettings() {
    for (String mode : List.of("FULL", "CDC")) {
      TaskInput task = validInput();
      task.mode = mode;
      task.runtime = new RuntimeOptions(100, 1000, 100, 10, 60);
      assertTrue(IntegrationValidator.validateStructure(task).valid(), mode);
      task.runtime = new RuntimeOptions(10000, 100000, 50000, 300, 3600);
      assertTrue(IntegrationValidator.validateStructure(task).valid(), mode);
      task.runtime = new RuntimeOptions(null, null, null, null, null);
      assertTrue(IntegrationValidator.validateStructure(task).valid(), mode);
      task.runtime = null;
      assertTrue(IntegrationValidator.validateStructure(task).valid(), mode);
    }
  }

  @Test
  void runtimeTimeoutMustCoverTwoEffectiveCheckpointIntervals() {
    TaskInput task = validInput();
    task.runtime = new RuntimeOptions(null, null, null, 100, 199);
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task.runtime = new RuntimeOptions(null, null, null, 100, 200);
    assertTrue(IntegrationValidator.validateStructure(task).valid());
    task.runtime = new RuntimeOptions(null, null, null, null, 119);
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task.mode = "CDC";
    task.runtime = new RuntimeOptions(null, null, null, 151, null);
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task.runtime = new RuntimeOptions(null, null, null, 150, null);
    assertTrue(IntegrationValidator.validateStructure(task).valid());
  }

  @Test
  void sqlT1RejectsExplicitSplitSizeWithoutChangingItsQueryContract() {
    TaskInput task = sqlInput();
    task.runtime = new RuntimeOptions(1000, null, 100, 60, 900);
    assertTrue(IntegrationValidator.validateStructure(task).valid());
    task.runtime = new RuntimeOptions(1000, 8192, 100, 60, 900);
    assertTrue(
        IntegrationValidator.validateStructure(task).errors().stream()
            .anyMatch(error -> error.field().equals("runtime.splitSize")));
  }

  @Test
  void initializationConfigurationBelongsOnlyToSqlTasks() {
    TaskInput task = validInput();
    task.initialization = new IntegrationModels.InitializationConfig();
    task.initialization.enabled = true;
    task.initialization.startDate = "2024-02-14";
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task.mode = "CDC";
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task = sqlInput();
    task.initialization = new IntegrationModels.InitializationConfig();
    task.initialization.enabled = true;
    task.initialization.startDate = "2024-02-14";
    assertTrue(IntegrationValidator.validateStructure(task).valid());
  }

  @Test
  void validatesSqlDeclaredKeyWithoutRequiringPhysicalKeyMetadata() {
    TaskInput task = sqlInput();
    var queryResult =
        new TableDefinition(
            "public",
            "sql_result",
            List.of(
                column("id", "int8", true, false, Types.BIGINT, 19),
                column("name", "varchar", true, false, Types.VARCHAR, 100)));
    assertTrue(IntegrationValidator.validateTables(task, queryResult, target()).valid());
    task.primaryKey = "missing";
    assertTrue(
        IntegrationValidator.validateTables(task, queryResult, target()).errors().stream()
            .anyMatch(error -> error.code().equals("INVALID_PRIMARY_KEY")));
    task.primaryKey = "name";
    assertTrue(
        IntegrationValidator.validateTables(task, queryResult, target()).errors().stream()
            .anyMatch(error -> error.code().equals("INVALID_PRIMARY_KEY")));
  }

  @Test
  void rejectsSqlQueryAndScheduleFieldsOnLegacyModesAndWrongVirtualResultName() {
    TaskInput task = sqlInput();
    task.sourceTable = "visits";
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task = validInput();
    task.sourceQuery = "SELECT id FROM public.visits WHERE day = :biz_date";
    assertFalse(IntegrationValidator.validateStructure(task).valid());
    task.sourceQuery = null;
    task.schedule = new IntegrationModels.ScheduleConfig();
    assertFalse(IntegrationValidator.validateStructure(task).valid());
  }

  static TaskInput sqlInput() {
    TaskInput task = validInput();
    task.mode = "SQL_T1";
    task.sourceTable = "sql_result";
    task.sourceQuery = "SELECT id, name FROM public.visits WHERE day = :biz_date";
    return task;
  }

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
