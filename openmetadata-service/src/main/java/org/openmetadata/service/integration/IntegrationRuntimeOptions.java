package org.openmetadata.service.integration;

import java.util.ArrayList;
import java.util.List;
import org.openmetadata.service.integration.IntegrationCdcModels.RuntimeOptions;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.integration.IntegrationModels.ValidationError;

public final class IntegrationRuntimeOptions {
  private static final Resolved FULL = new Resolved(1000, 8192, 10000, 60, 900);
  private static final Resolved CDC = new Resolved(1000, 8192, 1000, 30, 300);
  // Custom SQL retains its small sink batch and cannot be split safely by a mapped table key.
  private static final Resolved SQL_T1 = new Resolved(1000, null, 100, 60, 900);
  private static final Resolved LEGACY_CDC = new Resolved(1000, null, 100, 5, 60);

  private IntegrationRuntimeOptions() {}

  public record Resolved(
      int fetchSize,
      Integer splitSize,
      int sinkBatchSize,
      int checkpointIntervalSeconds,
      int checkpointTimeoutSeconds) {}

  public static Resolved resolve(TaskInput input) {
    if (input == null || !validate(input).isEmpty()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return effective(input);
  }

  public static Resolved legacyCdc() {
    return LEGACY_CDC;
  }

  static List<ValidationError> validate(TaskInput input) {
    if (input == null) {
      return List.of();
    }
    List<ValidationError> errors = new ArrayList<>();
    RuntimeOptions options = input.runtime;
    if (options != null) {
      range(errors, "fetchSize", options.fetchSize(), 100, 10000);
      range(errors, "splitSize", options.splitSize(), 1000, 100000);
      range(errors, "sinkBatchSize", options.sinkBatchSize(), 100, 50000);
      range(errors, "checkpointIntervalSeconds", options.checkpointIntervalSeconds(), 10, 300);
      range(errors, "checkpointTimeoutSeconds", options.checkpointTimeoutSeconds(), 60, 3600);
      if ("SQL_T1".equals(input.mode) && options.splitSize() != null) {
        errors.add(
            new ValidationError(
                "runtime.splitSize",
                "INVALID_CONFIGURATION",
                "SQL_T1 does not support splitting."));
      }
    }
    if (List.of("FULL", "CDC", "SQL_T1").contains(input.mode == null ? "" : input.mode)) {
      Resolved effective = effective(input);
      if (effective.checkpointTimeoutSeconds() < 2L * effective.checkpointIntervalSeconds()) {
        errors.add(
            new ValidationError(
                "runtime.checkpointTimeoutSeconds",
                "INVALID_CONFIGURATION",
                "Checkpoint timeout must cover at least two checkpoint intervals."));
      }
    }
    return List.copyOf(errors);
  }

  private static Resolved effective(TaskInput input) {
    Resolved defaults =
        switch (input.mode == null ? "" : input.mode) {
          case "FULL" -> FULL;
          case "CDC" -> CDC;
          case "SQL_T1" -> SQL_T1;
          default -> throw new IntegrationException("INVALID_CONFIGURATION", 400);
        };
    RuntimeOptions options = input.runtime;
    if (options == null) {
      return defaults;
    }
    return new Resolved(
        value(options.fetchSize(), defaults.fetchSize()),
        options.splitSize() == null ? defaults.splitSize() : options.splitSize(),
        value(options.sinkBatchSize(), defaults.sinkBatchSize()),
        value(options.checkpointIntervalSeconds(), defaults.checkpointIntervalSeconds()),
        value(options.checkpointTimeoutSeconds(), defaults.checkpointTimeoutSeconds()));
  }

  private static int value(Integer configured, int fallback) {
    return configured == null ? fallback : configured;
  }

  private static void range(
      List<ValidationError> errors, String field, Integer value, int minimum, int maximum) {
    if (value != null && (value < minimum || value > maximum)) {
      errors.add(
          new ValidationError(
              "runtime." + field,
              "INVALID_CONFIGURATION",
              "Use an integer between " + minimum + " and " + maximum + "."));
    }
  }
}
