package org.openmetadata.service.integration;

import java.util.List;

public final class IntegrationCdcModels {
  private IntegrationCdcModels() {}

  public record CdcOptions(
      String databaseName, String serverTimeZone, String pdbName, String rootServiceName) {}

  public record CdcCheck(String id, String status, String code, boolean required) {}

  public record CdcPreflightInput(
      String schema,
      String table,
      List<String> columns,
      CdcOptions cdc,
      String targetConnectionId,
      String targetSchema,
      String targetTable) {
    public CdcPreflightInput {
      columns = columns == null ? null : List.copyOf(columns);
    }
  }

  public record CdcPreflightResult(
      String connectionId,
      String databaseType,
      String databaseVersion,
      String databaseName,
      boolean implemented,
      boolean ready,
      List<CdcCheck> checks) {
    public CdcPreflightResult {
      checks = List.copyOf(checks);
    }
  }

  public record TargetRowCount(long count, long checkedAt) {}

  public record RuntimeOptions(
      Integer fetchSize,
      Integer splitSize,
      Integer sinkBatchSize,
      Integer checkpointIntervalSeconds,
      Integer checkpointTimeoutSeconds) {}

  public record CheckpointSummary(
      long triggered,
      long completed,
      long failed,
      long restored,
      Long latestCompletedAt,
      Long latestSavepointAt,
      Long updatedAt) {}
}
