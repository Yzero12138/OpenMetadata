package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightResult;
import org.openmetadata.service.integration.IntegrationCdcModels.RuntimeOptions;
import org.openmetadata.service.integration.IntegrationCdcPreflight.Inspection;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

class IntegrationCdcFingerprintTest {
  private final ConnectionDefinition definition = definition();
  private final JdbcEndpoint source = new JdbcEndpoint(definition, "old-secret");
  private final JdbcEndpoint target =
      IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation");
  private final IntegrationTask task = task();
  private final Inspection inspection = inspection("HISDB", columns(), targetColumns());

  @Test
  void secretAndConnectionVersionRotationAreStableButUsernameAndRoutesAreBound() {
    String fingerprint = fingerprint(inspection);
    definition.version = 2;
    assertEquals(
        fingerprint,
        IntegrationCdcFingerprint.of(
            task, new JdbcEndpoint(definition, "new-secret"), target, inspection));
    definition.username = "different_reader";
    assertNotEquals(
        fingerprint,
        IntegrationCdcFingerprint.of(
            task, new JdbcEndpoint(definition, "old-secret"), target, inspection));
    definition.username = source.username();
    definition.host = "other-host";
    assertNotEquals(
        fingerprint,
        IntegrationCdcFingerprint.of(
            task, new JdbcEndpoint(definition, "old-secret"), target, inspection));
  }

  @Test
  void usesResolvedNativeDatabaseAndResolvedRuntimeInsteadOfUnresolvedInputDefaults() {
    String fingerprint = fingerprint(inspection);
    task.cdc = new CdcOptions(null, "Asia/Shanghai", null, null);
    task.runtime = new RuntimeOptions(1000, 8192, 1000, 30, 300);
    assertEquals(fingerprint, fingerprint(inspection));
    assertNotEquals(fingerprint, fingerprint(inspection("OTHERDB", columns(), targetColumns())));
    task.runtime = new RuntimeOptions(1000, 16000, 1000, 30, 300);
    assertNotEquals(fingerprint, fingerprint(inspection));
    task.runtime = null;
    task.cdc = new CdcOptions(null, "UTC", null, null);
    assertNotEquals(fingerprint, fingerprint(inspection));
  }

  @Test
  void explicitlyBindsOracleByteMetadataAndEveryInspectedShapeField() {
    var original = columns().get(1);
    List<ColumnDefinition> variations =
        List.of(
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                201),
            column(
                "VALUE",
                "NVARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.NVARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                101,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                1,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                false,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                true,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                true,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                false,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                101,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Postgres,
                200),
            column(
                "RENAMED",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                200),
            column(
                "VALUE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                100,
                0,
                false,
                true,
                100,
                JdbcDialect.Oracle,
                null));
    String fingerprint = fingerprint(inspection);
    for (var changed : variations) {
      var columns = new ArrayList<>(columns());
      columns.set(1, changed);
      assertNotEquals(
          fingerprint,
          fingerprint(inspection("HISDB", columns, targetColumns())),
          changed.toString());
    }
    assertEquals(Integer.valueOf(200), original.oracleDataLengthBytes());
  }

  @Test
  void unmappedDdlAndTargetShapeAndMappingOrderAndKeyInvalidateResume() {
    String fingerprint = fingerprint(inspection);
    var columns = new ArrayList<>(columns());
    columns.add(
        column(
            "UNMAPPED",
            "DATE",
            true,
            false,
            Types.TIMESTAMP,
            0,
            0,
            false,
            false,
            0,
            JdbcDialect.Oracle,
            null));
    assertNotEquals(fingerprint, fingerprint(inspection("HISDB", columns, targetColumns())));
    var targetColumns = new ArrayList<>(targetColumns());
    targetColumns.add(DorisTableMetadata.column("another", "string", true, false, true));
    assertNotEquals(fingerprint, fingerprint(inspection("HISDB", columns(), targetColumns)));
    task.fieldMappings = task.fieldMappings.reversed();
    assertNotEquals(fingerprint, fingerprint(inspection("HISDB", columns(), targetColumns())));
    task.fieldMappings = task.fieldMappings.reversed();
    task.primaryKey = "VALUE";
    assertNotEquals(fingerprint, fingerprint(inspection));
  }

  private String fingerprint(Inspection inspection) {
    return IntegrationCdcFingerprint.of(task, source, target, inspection);
  }

  private Inspection inspection(
      String database, List<ColumnDefinition> source, List<ColumnDefinition> target) {
    return new Inspection(
        new CdcPreflightResult(
            task.sourceConnectionId, "Oracle", "11.2.0.1", database, true, true, List.of()),
        new TableDefinition("HIS", "ORDERS", source),
        new TableDefinition("ods_validation", "patient_ods", target),
        task.fieldMappings);
  }

  private static List<ColumnDefinition> columns() {
    return List.of(
        column(
            "ID",
            "NUMBER",
            false,
            true,
            Types.NUMERIC,
            18,
            0,
            false,
            false,
            0,
            JdbcDialect.Oracle,
            null),
        column(
            "VALUE",
            "VARCHAR2",
            true,
            false,
            Types.VARCHAR,
            100,
            0,
            false,
            true,
            100,
            JdbcDialect.Oracle,
            200));
  }

  private static List<ColumnDefinition> targetColumns() {
    return List.of(
        DorisTableMetadata.column("id", "decimal(18,0)", false, true, false),
        DorisTableMetadata.column("name", "string", true, false, false));
  }

  private static ColumnDefinition column(
      String name,
      String type,
      boolean nullable,
      boolean primaryKey,
      int jdbc,
      int size,
      int scale,
      boolean hasDefault,
      boolean unicode,
      int capacity,
      JdbcDialect dialect,
      Integer bytes) {
    return new ColumnDefinition(
        name,
        type,
        nullable,
        primaryKey,
        jdbc,
        size,
        scale,
        hasDefault,
        unicode,
        capacity,
        dialect,
        bytes);
  }

  private static ConnectionDefinition definition() {
    var definition = new ConnectionDefinition();
    definition.copyInput(IntegrationConnectionsTest.input("Oracle"));
    definition.id = "01234567-1234-1234-1234-123456789012";
    return definition;
  }

  private static IntegrationTask task() {
    var task = IntegrationTask.from(IntegrationCdcExecutionTest.input());
    task.id = "12345678-1234-1234-1234-123456789012";
    task.sourceConnectionId = "01234567-1234-1234-1234-123456789012";
    task.sourceSchema = "HIS";
    task.sourceTable = "ORDERS";
    task.primaryKey = "ID";
    task.cdc = new CdcOptions("HISDB", "Asia/Shanghai", null, null);
    task.fieldMappings = List.of(new FieldMapping("ID", "id"), new FieldMapping("VALUE", "name"));
    return task;
  }
}
