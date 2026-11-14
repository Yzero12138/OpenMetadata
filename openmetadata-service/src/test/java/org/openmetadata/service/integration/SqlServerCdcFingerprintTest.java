package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcPreflight.Inspection;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

class SqlServerCdcFingerprintTest {
  @Test
  void missingCaptureIdentityFailsBothFingerprintAndDirectBuilderEvenWhenResultClaimsReady() {
    var fixture = new SqlServerCdcPreflightTest.Fixture();
    var task = task();
    var inspected = fixture.preflight().inspect(task);
    var spoofed =
        new Inspection(
            inspected.result(), inspected.source(), inspected.target(), inspected.mappings());
    assertEquals(
        "CDC_TRUSTED_INSPECTION_REQUIRED",
        assertThrows(IntegrationException.class, () -> fingerprint(task, spoofed)).code());
    assertEquals(
        "CDC_TRUSTED_INSPECTION_REQUIRED",
        assertThrows(IntegrationException.class, () -> jobs().build(task, null, spoofed)).code());
  }

  @Test
  void replacementCaptureAndDatabaseTableIdentityInvalidateResumeWhileMovingEvidenceIsStable() {
    var fixture = new SqlServerCdcPreflightTest.Fixture();
    var task = task();
    var inspected = fixture.preflight().inspect(task);
    assertNotNull(inspected.sqlServerIdentity());
    String original = fingerprint(task, inspected);
    fixture.lsn.put("min_lsn", SqlServerCdcPreflightTest.bytes(2));
    fixture.lsn.put("max_lsn", SqlServerCdcPreflightTest.bytes(9));
    fixture.capture.put("start_lsn", SqlServerCdcPreflightTest.bytes(2));
    fixture.jobs.get(1).put("retention", 5000);
    assertEquals(original, fingerprint(task, fixture.preflight().inspect(task)));
    for (String field :
        List.of("object_id", "capture_instance", "create_date", "role_name", "index_name")) {
      Object old = fixture.capture.get(field);
      fixture.capture.put(
          field,
          field.equals("object_id")
              ? 201
              : field.equals("create_date")
                  ? SqlServerCdcPreflightTest.time("2026-11-02 00:00:00")
                  : "different");
      assertNotEquals(original, fingerprint(task, fixture.preflight().inspect(task)), field);
      fixture.capture.put(field, old);
    }
    fixture.database.put("database_id", 8);
    assertNotEquals(original, fingerprint(task, fixture.preflight().inspect(task)));
  }

  @Test
  void sqlServerUnsignedTinyintCannotUseSignedDorisTinyintButFullSemanticsStayUnchanged() {
    var task = task();
    var source =
        new TableDefinition(
            "dbo",
            "ORDERS",
            List.of(
                new ColumnDefinition(
                    "ID",
                    "tinyint",
                    false,
                    true,
                    Types.TINYINT,
                    3,
                    0,
                    false,
                    false,
                    0,
                    JdbcDialect.Mssql),
                SqlServerCdcAdapterTest.column("nvarchar", Types.NVARCHAR, 100, 0)));
    var target =
        new TableDefinition(
            "ods_validation",
            "patient_ods",
            List.of(
                DorisTableMetadata.column("id", "tinyint", false, true, false),
                DorisTableMetadata.column("name", "string", true, false, false)));
    assertEquals(false, IntegrationValidator.validateTables(task, source, target).valid());
    task.mode = "FULL";
    task.cdc = null;
    assertEquals(true, IntegrationValidator.validateTables(task, source, target).valid());
  }

  static IntegrationTask task() {
    var task = IntegrationTask.from(SqlServerCdcPreflightTest.task());
    task.id = "12345678-1234-1234-1234-123456789012";
    return task;
  }

  static String fingerprint(IntegrationTask task, Inspection inspection) {
    return IntegrationCdcFingerprint.of(
        task,
        SqlServerCdcAdapterTest.endpoint("VERIFY"),
        IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation"),
        inspection);
  }

  static SeaTunnelJobConfig jobs() {
    return new SeaTunnelJobConfig(
        id ->
            id.equals("synthetic-source")
                ? SqlServerCdcAdapterTest.endpoint("VERIFY")
                : IntegrationCdcExecutionTest.configuration().endpoint(id));
  }
}
