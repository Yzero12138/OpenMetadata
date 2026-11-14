package org.openmetadata.service.integration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.openmetadata.service.integration.IntegrationCdcPreflight.Inspection;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

final class IntegrationCdcFingerprint {
  private IntegrationCdcFingerprint() {}

  static String of(
      IntegrationTask task, JdbcEndpoint source, JdbcEndpoint target, Inspection inspection) {
    try {
      var bytes = new ByteArrayOutputStream();
      var data = new DataOutputStream(bytes);
      boolean sqlServer = source.dialect() == JdbcDialect.Mssql;
      SqlServerCaptureIdentity identity =
          sqlServer ? SqlServerCaptureIdentity.require(inspection) : null;
      value(data, sqlServer ? "CDC_GRAPH_MSSQL_V1" : "CDC_GRAPH_V1");
      value(data, task.id);
      route(data, source);
      route(data, target);
      value(data, task.sourceSchema);
      value(data, task.sourceTable);
      value(data, task.targetSchema);
      value(data, task.targetTable);
      value(data, task.primaryKey);
      var options = inspection.resolvedOptions(task.cdc);
      value(data, options.databaseName());
      value(data, options.serverTimeZone());
      value(data, options.pdbName());
      value(data, options.rootServiceName());
      var runtime = IntegrationRuntimeOptions.resolve(task);
      value(data, runtime.fetchSize());
      value(data, runtime.splitSize());
      value(data, runtime.sinkBatchSize());
      value(data, runtime.checkpointIntervalSeconds());
      value(data, runtime.checkpointTimeoutSeconds());
      shape(data, inspection.source());
      shape(data, inspection.target());
      data.writeInt(inspection.mappings().size());
      for (var mapping : inspection.mappings()) {
        value(data, mapping.source());
        value(data, mapping.target());
      }
      if (identity != null) {
        value(data, identity.databaseName());
        value(data, identity.databaseId());
        value(data, identity.databaseCreateDate());
        value(data, identity.schema());
        value(data, identity.table());
        value(data, identity.sourceObjectId());
        value(data, identity.captureObjectId());
        value(data, identity.captureSourceObjectId());
        value(data, identity.captureName());
        value(data, identity.captureCreateDate());
        value(data, identity.roleName());
        value(data, identity.indexName());
        data.writeInt(identity.columns().size());
        for (var column : identity.columns()) {
          value(data, column.id());
          value(data, column.name());
          value(data, column.ordinal());
          value(data, column.type());
          value(data, column.computed());
        }
      }
      data.flush();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("CDC fingerprint could not be computed", e);
    }
  }

  private static void route(DataOutputStream data, JdbcEndpoint endpoint) throws IOException {
    value(data, endpoint.dialect());
    value(data, endpoint.jdbcUrl());
    value(data, endpoint.username());
    value(data, endpoint.dorisHttpPort());
    value(data, endpoint.tlsMode());
  }

  private static void shape(DataOutputStream data, TableDefinition table) throws IOException {
    if (table == null || table.columns() == null) {
      throw new IntegrationException("CDC_PREFLIGHT_FAILED", 409);
    }
    value(data, table.schema());
    value(data, table.name());
    data.writeInt(table.columns().size());
    for (var column : table.columns()) {
      value(data, column.name());
      value(data, column.dataType());
      value(data, column.jdbcType());
      value(data, column.size());
      value(data, column.scale());
      value(data, column.nullable());
      value(data, column.primaryKey());
      value(data, column.hasDefault());
      value(data, column.unicodeSafeTarget());
      value(data, column.unicodeCapacity());
      value(data, column.inspectedDialect());
      value(data, column.oracleDataLengthBytes());
    }
  }

  private static void value(DataOutputStream data, Object value) throws IOException {
    data.writeBoolean(value != null);
    if (value != null) {
      data.writeUTF(value.toString());
    }
  }
}
