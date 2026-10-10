package org.openmetadata.service.integration;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.integration.IntegrationModels.ValidationError;
import org.openmetadata.service.integration.IntegrationModels.ValidationResult;

public final class IntegrationValidator {
  private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]{0,127}");
  private static final Pattern TASK_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,62}");
  private static final List<Integer> INTEGERS =
      List.of(Types.SMALLINT, Types.INTEGER, Types.BIGINT);
  private static final Set<Integer> STRINGS =
      Set.of(
          Types.CHAR,
          Types.VARCHAR,
          Types.LONGVARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGNVARCHAR);
  private static final Set<Integer> DECIMALS = Set.of(Types.DECIMAL, Types.NUMERIC);

  private IntegrationValidator() {}

  public static boolean safeIdentifier(String value) {
    return value != null && IDENTIFIER.matcher(value).matches();
  }

  public static String quote(String value) {
    if (!safeIdentifier(value)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return "\"" + value + "\"";
  }

  public static void requireTable(String schema, String table) {
    if (!safeIdentifier(schema) || !safeIdentifier(table)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  public static ValidationResult validateStructure(TaskInput input) {
    List<ValidationError> errors = new ArrayList<>();
    if (input == null) {
      error(errors, "task", "REQUIRED", "A task is required.");
      return result(errors);
    }
    if (input.name == null || !TASK_NAME.matcher(input.name).matches()) {
      error(
          errors,
          "name",
          "INVALID_NAME",
          "Use a lowercase technical name of at most 63 characters.");
    }
    if (input.displayName != null
        && (input.displayName.length() > 120
            || input.displayName.chars().anyMatch(Character::isISOControl))) {
      error(
          errors,
          "displayName",
          "INVALID_NAME",
          "The display name must be at most 120 characters without control characters.");
    }
    if (!connectionId(input.sourceConnectionId)) {
      error(
          errors,
          "sourceConnectionId",
          "CONNECTION_NOT_ALLOWED",
          "Select a registered source connection.");
    }
    if (!connectionId(input.targetConnectionId)) {
      error(
          errors,
          "targetConnectionId",
          "CONNECTION_NOT_ALLOWED",
          "Select a registered target connection.");
    }
    identifier(errors, "sourceSchema", input.sourceSchema);
    identifier(errors, "targetSchema", input.targetSchema);
    identifier(errors, "sourceTable", input.sourceTable);
    identifier(errors, "targetTable", input.targetTable);
    identifier(errors, "primaryKey", input.primaryKey);
    if (!Set.of("FULL", "CDC").contains(input.mode == null ? "" : input.mode)) {
      error(errors, "mode", "INVALID_MODE", "Select FULL or CDC.");
    }
    if (input.fieldMappings == null
        || input.fieldMappings.isEmpty()
        || input.fieldMappings.size() > 128) {
      error(
          errors, "fieldMappings", "INVALID_MAPPING", "Provide between 1 and 128 field mappings.");
    } else {
      for (FieldMapping mapping : input.fieldMappings) {
        if (mapping == null
            || !safeIdentifier(mapping.source())
            || !safeIdentifier(mapping.target())) {
          error(
              errors,
              "fieldMappings",
              "INVALID_IDENTIFIER",
              "Field mappings must use safe column names.");
        }
      }
    }
    return result(errors);
  }

  public static ValidationResult validateTables(
      TaskInput input, TableDefinition source, TableDefinition target) {
    List<ValidationError> errors = new ArrayList<>(validateStructure(input).errors());
    if (!errors.isEmpty()) {
      return result(errors);
    }
    if (source == null || target == null) {
      error(errors, "table", "TABLE_NOT_FOUND", "Both selected tables must exist.");
      return result(errors);
    }
    Map<String, ColumnDefinition> sourceColumns = columns(source);
    Map<String, ColumnDefinition> targetColumns = columns(target);
    Set<String> sources = new HashSet<>();
    Set<String> targets = new HashSet<>();
    for (FieldMapping mapping : input.fieldMappings) {
      if (!sources.add(mapping.source()) || !targets.add(mapping.target())) {
        error(
            errors,
            "fieldMappings",
            "DUPLICATE_MAPPING",
            "Map every source and target column at most once.");
      }
      ColumnDefinition from = sourceColumns.get(mapping.source());
      ColumnDefinition to = targetColumns.get(mapping.target());
      if (from == null || to == null) {
        error(
            errors,
            "fieldMappings",
            "COLUMN_NOT_FOUND",
            "Each mapped column must exist in its selected table.");
      } else if (!compatible(from, to) || (from.nullable() && !to.nullable())) {
        error(
            errors,
            "fieldMappings",
            "INCOMPATIBLE_TYPE",
            "The target must accept the source type, size and nullability.");
      }
    }
    List<ColumnDefinition> sourceKeys =
        source.columns().stream().filter(ColumnDefinition::primaryKey).toList();
    List<ColumnDefinition> targetKeys =
        target.columns().stream().filter(ColumnDefinition::primaryKey).toList();
    String mappedKey =
        input.fieldMappings.stream()
            .filter(mapping -> mapping.source().equals(input.primaryKey))
            .map(FieldMapping::target)
            .findFirst()
            .orElse(null);
    if (sourceKeys.size() != 1
        || targetKeys.size() != 1
        || !sourceKeys.getFirst().name().equals(input.primaryKey)
        || !targetKeys.getFirst().name().equals(mappedKey)) {
      error(
          errors,
          "primaryKey",
          "INVALID_PRIMARY_KEY",
          "Map the actual single-column source primary key to the target primary key.");
    }
    for (ColumnDefinition column : target.columns()) {
      if (!column.nullable() && !column.hasDefault() && !targets.contains(column.name())) {
        error(
            errors,
            "fieldMappings",
            "REQUIRED_TARGET_COLUMN",
            "Map every required target column without a default.");
      }
    }
    return result(errors);
  }

  private static boolean compatible(ColumnDefinition source, ColumnDefinition target) {
    int from = source.jdbcType();
    int to = target.jdbcType();
    if (INTEGERS.contains(from) && INTEGERS.contains(to)) {
      boolean sourceUnsigned = source.dataType().toUpperCase(Locale.ROOT).contains("UNSIGNED");
      boolean targetUnsigned = target.dataType().toUpperCase(Locale.ROOT).contains("UNSIGNED");
      if (!sourceUnsigned && targetUnsigned) {
        return false;
      }
      return sourceUnsigned && !targetUnsigned
          ? INTEGERS.indexOf(from) < INTEGERS.indexOf(to)
          : INTEGERS.indexOf(from) <= INTEGERS.indexOf(to);
    }
    if (STRINGS.contains(from) && STRINGS.contains(to)) {
      return target.unicodeSafeTarget() && target.unicodeCapacity() >= source.size();
    }
    if (DECIMALS.contains(from) && DECIMALS.contains(to)) {
      return target.scale() >= source.scale()
          && target.size() - target.scale() >= source.size() - source.scale();
    }
    if (from == Types.REAL && (to == Types.FLOAT || to == Types.DOUBLE)) {
      return true;
    }
    if (DECIMALS.contains(from) && INTEGERS.contains(to) && source.scale() == 0) {
      return source.size()
          <= switch (to) {
            case Types.SMALLINT -> 4;
            case Types.INTEGER -> 9;
            default -> 18;
          };
    }
    if (from == to
        && Set.of(Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE).contains(from)) {
      if (Set.of("datetime", "smalldatetime").contains(target.dataType().toLowerCase(Locale.ROOT))
          && !source.dataType().equalsIgnoreCase(target.dataType())) {
        return false;
      }
      return source.scale() <= target.scale();
    }
    if (from == to
        && Set.of(
                Types.DATE,
                Types.TIME,
                Types.TIMESTAMP,
                Types.TIMESTAMP_WITH_TIMEZONE,
                Types.BOOLEAN,
                Types.BIT,
                Types.BINARY,
                Types.VARBINARY)
            .contains(from)) {
      return !Set.of(Types.BINARY, Types.VARBINARY).contains(from)
          || target.size() >= source.size();
    }
    return from == to && source.dataType().equalsIgnoreCase(target.dataType());
  }

  private static boolean connectionId(String id) {
    if (IntegrationConfiguration.SOURCE_ID.equals(id)
        || IntegrationConfiguration.TARGET_ID.equals(id)) {
      return true;
    }
    try {
      return java.util.UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      return false;
    }
  }

  private static Map<String, ColumnDefinition> columns(TableDefinition table) {
    return table.columns().stream()
        .collect(Collectors.toMap(ColumnDefinition::name, Function.identity()));
  }

  private static void identifier(List<ValidationError> errors, String field, String value) {
    if (!safeIdentifier(value)) {
      error(
          errors,
          field,
          "INVALID_IDENTIFIER",
          "Use an existing column or table name of at most 128 safe characters.");
    }
  }

  private static void error(
      List<ValidationError> errors, String field, String code, String message) {
    errors.add(new ValidationError(field, code, message));
  }

  private static ValidationResult result(List<ValidationError> errors) {
    return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
  }
}
