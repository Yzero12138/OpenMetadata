package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

final class DorisTableMetadata {
  private static final Pattern UNIQUE_KEY =
      Pattern.compile("(?im)^\\s*UNIQUE\\s+KEY\\s*\\(([^\\r\\n]*)\\)");
  private static final Pattern TYPE =
      Pattern.compile("([a-z][a-z0-9]*)(?:\\(\\s*([0-9]{1,9})\\s*(?:,\\s*([0-9]{1,9})\\s*)?\\))?");

  private DorisTableMetadata() {}

  static TableDefinition read(Connection connection, String schema, String table)
      throws SQLException {
    IntegrationValidator.requireTable(schema, table);
    String qualified = JdbcDialect.Doris.quote(schema) + "." + JdbcDialect.Doris.quote(table);
    Set<String> keys;
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      try (ResultSet result = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
        if (!result.next()) {
          throw new IntegrationException("TABLE_NOT_FOUND", 404);
        }
        keys = uniqueKeys(result.getString(2));
      }
    }
    List<ColumnDefinition> columns = new ArrayList<>();
    Set<String> names = new HashSet<>();
    try (var statement =
        connection.prepareStatement(
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setQueryTimeout(5);
      statement.setMaxRows(129);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String name = result.getString("COLUMN_NAME");
          if (!IntegrationValidator.safeIdentifier(name)
              || !names.add(name.toLowerCase(Locale.ROOT))
              || columns.size() >= 128) {
            throw new IntegrationException("INVALID_CONFIGURATION", 400);
          }
          boolean key = keys.contains(name);
          String defaultValue = result.getString("COLUMN_DEFAULT");
          columns.add(
              column(
                  name,
                  result.getString("COLUMN_TYPE"),
                  !"NO".equalsIgnoreCase(result.getString("IS_NULLABLE")),
                  key,
                  defaultValue != null));
        }
      }
    }
    if (columns.isEmpty()
        || !names.containsAll(keys.stream().map(key -> key.toLowerCase(Locale.ROOT)).toList())
        || columns.stream().filter(ColumnDefinition::primaryKey).count() != keys.size()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return new TableDefinition(schema, table, List.copyOf(columns));
  }

  static Set<String> uniqueKeys(String ddl) {
    if (ddl == null || ddl.length() > 1_048_576) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    Matcher matcher = UNIQUE_KEY.matcher(ddl);
    if (!matcher.find()) {
      return Set.of();
    }
    Set<String> keys = new HashSet<>();
    for (String token : matcher.group(1).split(",", -1)) {
      String identifier = token.trim();
      if (identifier.length() < 3 || !identifier.startsWith("`") || !identifier.endsWith("`")) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      identifier = identifier.substring(1, identifier.length() - 1);
      if (!IntegrationValidator.safeIdentifier(identifier)
          || !keys.add(identifier)
          || keys.size() > 128) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
    }
    if (matcher.find()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return Set.copyOf(keys);
  }

  static ColumnDefinition column(
      String name, String type, boolean nullable, boolean primaryKey, boolean hasDefault) {
    if (type == null || type.length() > 512) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    Matcher matcher = TYPE.matcher(type.toLowerCase(Locale.ROOT).trim());
    int jdbc = Types.OTHER;
    int size = 0;
    int scale = 0;
    boolean unicode = false;
    int capacity = 0;
    if (matcher.matches()) {
      String base = matcher.group(1);
      int first = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
      int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
      switch (base) {
        case "boolean", "bool" -> {
          jdbc = Types.BOOLEAN;
          size = 1;
        }
        case "tinyint" -> {
          jdbc = first == 1 ? Types.BOOLEAN : Types.TINYINT;
          size = first == 1 ? 1 : 3;
        }
        case "smallint" -> {
          jdbc = Types.SMALLINT;
          size = 5;
        }
        case "int", "integer" -> {
          jdbc = Types.INTEGER;
          size = 10;
        }
        case "bigint" -> {
          jdbc = Types.BIGINT;
          size = 19;
        }
        case "decimal", "decimalv2", "decimalv3" -> {
          jdbc = Types.DECIMAL;
          size = first;
          scale = second;
        }
        case "float" -> {
          jdbc = Types.REAL;
          size = 24;
        }
        case "double" -> {
          jdbc = Types.DOUBLE;
          size = 53;
        }
        case "varchar", "char" -> {
          jdbc = base.equals("char") ? Types.CHAR : Types.VARCHAR;
          size = first;
          unicode = true;
          capacity = first / 4;
        }
        case "string", "text" -> {
          jdbc = Types.LONGVARCHAR;
          size = 1_048_576;
          unicode = true;
          capacity = size / 4;
        }
        case "date", "datev2" -> {
          jdbc = Types.DATE;
          size = 10;
        }
        case "datetime", "datetimev2" -> {
          jdbc = Types.TIMESTAMP;
          size = 26;
          scale = first;
        }
        default -> {}
      }
    }
    return new ColumnDefinition(
        name,
        type.toUpperCase(Locale.ROOT),
        nullable,
        primaryKey,
        jdbc,
        size,
        scale,
        hasDefault,
        unicode,
        capacity);
  }
}
