package org.openmetadata.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class DorisTableDesignModels {
  private DorisTableDesignModels() {}

  static void requireJsonTypes(JsonNode input) {
    requireFields(
        input,
        List.of("targetSchema", "targetTable", "comment", "previewHash"),
        List.of("buckets"),
        List.of());
    JsonNode columns = input.get("columns");
    if (columns == null || columns.isNull()) return;
    if (!columns.isArray() || columns.size() > 128)
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    for (JsonNode column : columns) {
      if (!column.isObject()) throw new IntegrationException("INVALID_CONFIGURATION", 400);
      requireFields(
          column,
          List.of("name", "type", "defaultKind", "defaultValue", "comment"),
          List.of("length", "precision", "scale"),
          List.of("nullable", "primaryKey"));
    }
  }

  private static void requireFields(
      JsonNode input, List<String> strings, List<String> integers, List<String> booleans) {
    for (String name : strings) {
      JsonNode value = input.get(name);
      if (value != null && !value.isNull() && !value.isTextual())
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    for (String name : integers) {
      JsonNode value = input.get(name);
      if (value != null
          && !value.isNull()
          && (!value.isIntegralNumber() || !value.canConvertToInt()))
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    for (String name : booleans) {
      JsonNode value = input.get(name);
      if (value != null && !value.isNull() && !value.isBoolean())
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public record DorisColumnInput(
      String name,
      String type,
      Integer length,
      Integer precision,
      Integer scale,
      Boolean nullable,
      Boolean primaryKey,
      String defaultKind,
      String defaultValue,
      String comment) {}

  public record DorisTableDesignInput(
      String targetSchema,
      String targetTable,
      String comment,
      Integer buckets,
      List<DorisColumnInput> columns,
      String previewHash) {}

  public record DorisAddColumnsInput(List<DorisColumnInput> columns, String previewHash) {}

  public record DorisSchemaChange(String jobId, String state) {}

  public record DorisTableStructure(
      String targetConnectionId,
      String targetSchema,
      String targetTable,
      String comment,
      String model,
      Integer buckets,
      Integer replicationNum,
      String structureHash,
      List<DorisColumnInput> columns,
      TableDefinition table,
      String ddl,
      DorisSchemaChange change) {}

  public record DorisTableDesignPreview(
      String targetConnectionId,
      String action,
      String targetSchema,
      String targetTable,
      String previewHash,
      String ddl,
      List<DorisColumnInput> columns,
      TableDefinition table) {}

  public record DorisTableMutationResult(String status, DorisTableStructure structure) {}
}
