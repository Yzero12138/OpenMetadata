package org.openmetadata.service.integration;

public final class IntegrationCdcModels {
  private IntegrationCdcModels() {}

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
