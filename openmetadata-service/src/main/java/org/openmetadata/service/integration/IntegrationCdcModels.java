package org.openmetadata.service.integration;

public final class IntegrationCdcModels {
  private IntegrationCdcModels() {}

  public record CheckpointSummary(
      long triggered,
      long completed,
      long failed,
      long restored,
      Long latestCompletedAt,
      Long latestSavepointAt,
      Long updatedAt) {}
}
