package org.openmetadata.service.integration;

import java.util.List;

/** Stable, session-proven identity. Moving LSN, job, and health evidence is deliberately excluded. */
record SqlServerCaptureIdentity(
    String databaseName,
    int databaseId,
    String databaseCreateDate,
    String schema,
    String table,
    int sourceObjectId,
    int captureObjectId,
    int captureSourceObjectId,
    String captureName,
    String captureCreateDate,
    String roleName,
    String indexName,
    List<CapturedColumn> columns) {
  SqlServerCaptureIdentity {
    columns = List.copyOf(columns);
  }

  record CapturedColumn(int id, String name, int ordinal, String type, boolean computed) {}

  static SqlServerCaptureIdentity require(IntegrationCdcPreflight.Inspection inspection) {
    var identity = inspection == null ? null : inspection.sqlServerIdentity();
    if (identity == null
        || inspection.source() == null
        || !"Mssql".equals(inspection.result().databaseType())
        || !SqlServerCdcAdapter.identifier(identity.databaseName())
        || !SqlServerCdcAdapter.identifier(identity.schema())
        || !SqlServerCdcAdapter.identifier(identity.table())
        || !identity.databaseName().equals(inspection.result().databaseName())
        || !identity.schema().equals(inspection.source().schema())
        || !identity.table().equals(inspection.source().name())
        || identity.databaseId() <= 0
        || identity.sourceObjectId() <= 0
        || identity.captureObjectId() <= 0
        || identity.sourceObjectId() != identity.captureSourceObjectId()
        || identity.databaseCreateDate() == null
        || identity.captureCreateDate() == null
        || !SqlServerCdcAdapter.identifier(identity.captureName())
        || identity.columns().isEmpty()) {
      throw new IntegrationException("CDC_TRUSTED_INSPECTION_REQUIRED", 400);
    }
    return identity;
  }
}
