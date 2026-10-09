package org.openmetadata.service.integration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class IntegrationModels {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final int MAX_INPUT_BYTES = 32768;

  static {
    JSON.getFactory()
        .setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).build());
  }

  private IntegrationModels() {}

  public record FieldMapping(String source, String target) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class TaskInput {
    public String name;
    public String displayName;
    public String sourceConnectionId;
    public String targetConnectionId;
    public String sourceSchema;
    public String sourceTable;
    public String targetSchema;
    public String targetTable;
    public String mode;
    public String primaryKey;
    public List<FieldMapping> fieldMappings;

    protected void copyInput(TaskInput input) {
      name = input.name;
      displayName = input.displayName;
      sourceConnectionId = input.sourceConnectionId;
      targetConnectionId = input.targetConnectionId;
      sourceSchema = input.sourceSchema;
      sourceTable = input.sourceTable;
      targetSchema = input.targetSchema;
      targetTable = input.targetTable;
      mode = input.mode;
      primaryKey = input.primaryKey;
      fieldMappings = input.fieldMappings == null ? null : List.copyOf(input.fieldMappings);
    }
  }

  public static final class UpdateInput extends TaskInput {
    public Long version;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class IntegrationTask extends TaskInput {
    public String id;
    public long version;
    public long createdAt;
    public long updatedAt;
    public String updatedBy;
    public RunSummary latestRun;
    public List<RunSummary> runs = new ArrayList<>();
    public CatalogSummary catalog = new CatalogSummary();

    public static IntegrationTask from(TaskInput input) {
      IntegrationTask task = new IntegrationTask();
      task.copyInput(input);
      return task;
    }

    public void replaceInput(TaskInput input) {
      copyInput(input);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class RunSummary {
    public String jobId;
    public String status;
    public long submittedAt;
    public Long finishedAt;
    public Long sourceReceivedCount;
    public Long sinkWriteCount;
    public String errorCode;
    public String errorMessage;
    public Boolean savepointRequested;
    public Boolean canResume;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class CatalogSummary {
    public String status = "PENDING";
    public Long syncedAt;
    public String sourceFqn;
    public String targetFqn;
    public String pipelineFqn;
    public String errorCode;
    public String errorMessage;
  }

  public record ColumnDefinition(
      String name,
      String dataType,
      boolean nullable,
      boolean primaryKey,
      @JsonIgnore int jdbcType,
      @JsonIgnore int size,
      @JsonIgnore int scale,
      @JsonIgnore boolean hasDefault) {}

  public record TableDefinition(String schema, String name, List<ColumnDefinition> columns) {}

  public record ConnectionDefinition(
      String id, String displayName, String role, String databaseType, boolean synthetic) {}

  public record ValidationError(String field, String code, String message) {}

  public record ValidationResult(boolean valid, List<ValidationError> errors) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EngineStatus(
      boolean enabled, boolean reachable, String engineVersion, String errorCode) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ConnectionStatus(boolean connected, String errorCode) {}

  public record RunInput(boolean resume) {}

  public record StopInput(boolean savepoint) {}

  public static TaskInput readInput(JsonNode input, boolean update) {
    if (update
        && (input == null
            || !input.path("version").isIntegralNumber()
            || !input.path("version").canConvertToLong()
            || input.path("version").longValue() < 1)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return update ? read(input, UpdateInput.class) : read(input, TaskInput.class);
  }

  public static JsonNode readBody(InputStream input) {
    if (input == null) {
      return null;
    }
    try {
      byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
      if (bytes.length > MAX_INPUT_BYTES) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      return bytes.length == 0 ? null : JSON.readTree(bytes);
    } catch (IOException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public static <T> T read(JsonNode input, Class<T> type) {
    if (input == null || !input.isObject() || input.toString().length() > MAX_INPUT_BYTES) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    try {
      return JSON.treeToValue(input, type);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public static String serialize(IntegrationTask task) {
    try {
      return JSON.writeValueAsString(task);
    } catch (JsonProcessingException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }

  public static IntegrationTask deserialize(String json) {
    try {
      return JSON.readValue(json, IntegrationTask.class);
    } catch (JsonProcessingException e) {
      throw new IntegrationException("STORAGE_UNAVAILABLE", 503);
    }
  }
}
