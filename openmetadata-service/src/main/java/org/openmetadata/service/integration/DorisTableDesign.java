package org.openmetadata.service.integration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisAddColumnsInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisColumnInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignPreview;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableStructure;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

final class DorisTableDesign {
  private static final Set<String> TYPES =
      Set.of(
          "BOOLEAN",
          "TINYINT",
          "SMALLINT",
          "INT",
          "BIGINT",
          "FLOAT",
          "DOUBLE",
          "DECIMAL",
          "CHAR",
          "VARCHAR",
          "STRING",
          "DATE",
          "DATETIME");
  private static final Set<String> DEFAULTS =
      Set.of("NONE", "NULL", "LITERAL", "CURRENT_DATE", "CURRENT_TIMESTAMP");

  private DorisTableDesign() {}

  static DorisTableDesignPreview create(
      ConnectionDefinition target,
      JdbcEndpoint endpoint,
      DorisTableDesignInput input,
      int replication) {
    if (input == null) throw invalid();
    requireScope(target, endpoint, input.targetSchema(), input.targetTable());
    if (replication < 1 || replication > 9)
      throw new IntegrationException("INVALID_CONFIGURATION", 503);
    int buckets = input.buckets() == null ? 1 : input.buckets();
    if (buckets < 1 || buckets > 128) throw invalid();
    String comment = text(input.comment(), 1024);
    List<DorisColumnInput> columns = normalize(input.columns(), false);
    if (columns.stream().filter(column -> column.primaryKey()).count() != 1) throw invalid();
    columns =
        columns.stream().sorted(Comparator.comparing(column -> !column.primaryKey())).toList();
    String key = quote(columns.getFirst().name());
    String ddl =
        "CREATE TABLE "
            + quote(input.targetSchema())
            + "."
            + quote(input.targetTable())
            + " (\n"
            + columns.stream()
                .map(column -> "  " + definition(column))
                .collect(Collectors.joining(",\n"))
            + "\n) ENGINE=OLAP\nUNIQUE KEY ("
            + key
            + ")\nCOMMENT "
            + DorisSqlLiterals.tableComment(comment)
            + "\nDISTRIBUTED BY HASH ("
            + key
            + ") BUCKETS "
            + buckets
            + "\nPROPERTIES (\n  \"replication_num\" = \""
            + replication
            + "\",\n  \"enable_unique_key_merge_on_write\" = \"true\"\n)";
    return preview(
        target,
        endpoint,
        "CREATE",
        input.targetSchema(),
        input.targetTable(),
        ddl,
        columns,
        table(input.targetSchema(), input.targetTable(), columns),
        null);
  }

  static DorisTableDesignPreview append(
      ConnectionDefinition target,
      JdbcEndpoint endpoint,
      DorisTableStructure structure,
      DorisAddColumnsInput input) {
    if (structure == null
        || input == null
        || structure.table() == null
        || structure.columns() == null) throw invalid();
    requireScope(target, endpoint, structure.targetSchema(), structure.targetTable());
    if (!target.id.equals(structure.targetConnectionId())) throw invalid();
    if (!"UNIQUE".equals(structure.model()))
      throw new IntegrationException("TARGET_TABLE_MODEL_UNSUPPORTED", 409);
    if (structure.change() != null
        && !Set.of("FINISHED", "CANCELLED", "CANCELED").contains(structure.change().state()))
      throw new IntegrationException("TARGET_SCHEMA_CHANGE_ACTIVE", 409);
    if (input.columns() == null) throw invalid();
    Set<String> existingNames =
        structure.columns().stream()
            .map(column -> column.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    for (var column : input.columns()) {
      if (column != null
          && column.name() != null
          && existingNames.contains(column.name().toLowerCase(Locale.ROOT)))
        throw new IntegrationException("COLUMN_ALREADY_EXISTS", 409);
    }
    if (structure.columns().size() + input.columns().size() > 128)
      throw new IntegrationException("TABLE_COLUMN_LIMIT_EXCEEDED", 400);
    List<DorisColumnInput> columns = normalize(input.columns(), true);
    String ddl =
        "ALTER TABLE "
            + quote(structure.targetSchema())
            + "."
            + quote(structure.targetTable())
            + " ADD COLUMN (\n"
            + columns.stream()
                .map(column -> "  " + definition(column))
                .collect(Collectors.joining(",\n"))
            + "\n)";
    List<ColumnDefinition> resulting = new ArrayList<>(structure.table().columns());
    resulting.addAll(table(structure.targetSchema(), structure.targetTable(), columns).columns());
    return preview(
        target,
        endpoint,
        "ADD_COLUMNS",
        structure.targetSchema(),
        structure.targetTable(),
        ddl,
        columns,
        new TableDefinition(
            structure.targetSchema(), structure.targetTable(), List.copyOf(resulting)),
        structure);
  }

  static String literal(String value) {
    return DorisSqlLiterals.defaultLiteral(value);
  }

  static List<DorisColumnInput> normalize(List<DorisColumnInput> input, boolean append) {
    if (input == null || input.isEmpty()) throw invalid();
    if (input.size() > 128) throw new IntegrationException("TABLE_COLUMN_LIMIT_EXCEEDED", 400);
    List<DorisColumnInput> columns = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (DorisColumnInput column : input) {
      if (column == null
          || !identifier(column.name())
          || !names.add(column.name().toLowerCase(Locale.ROOT))
          || column.type() == null) throw invalid();
      String type = column.type().trim().toUpperCase(Locale.ROOT);
      if (!TYPES.contains(type)) throw invalid();
      boolean key = Boolean.TRUE.equals(column.primaryKey());
      boolean nullable = column.nullable() == null ? !key : column.nullable();
      if (key && (append || nullable || Set.of("FLOAT", "DOUBLE", "STRING").contains(type)))
        throw invalid();
      Integer length = column.length();
      Integer precision = column.precision();
      Integer scale = column.scale();
      switch (type) {
        case "CHAR", "VARCHAR" -> {
          if (length == null
              || length < 1
              || length > ("CHAR".equals(type) ? 255 : 65533)
              || precision != null
              || scale != null) throw invalid();
        }
        case "DECIMAL" -> {
          if (length != null || precision == null || precision < 1 || precision > 38)
            throw invalid();
          scale = scale == null ? 0 : scale;
          if (scale < 0 || scale > precision) throw invalid();
        }
        case "DATETIME" -> {
          if (length != null || precision != null) throw invalid();
          scale = scale == null ? 0 : scale;
          if (scale < 0 || scale > 6) throw invalid();
        }
        default -> {
          if (length != null || precision != null || scale != null) throw invalid();
        }
      }
      String kind =
          column.defaultKind() == null
              ? "NONE"
              : column.defaultKind().trim().toUpperCase(Locale.ROOT);
      if (!DEFAULTS.contains(kind)
          || (!"LITERAL".equals(kind) && column.defaultValue() != null)
          || ("NULL".equals(kind) && !nullable)
          || ("CURRENT_DATE".equals(kind) && !"DATE".equals(type))
          || ("CURRENT_TIMESTAMP".equals(kind) && !"DATETIME".equals(type))
          || (append && !nullable && Set.of("NONE", "NULL").contains(kind))) throw invalid();
      String value =
          "LITERAL".equals(kind)
              ? scalar(type, column.defaultValue(), length, precision, scale)
              : null;
      String comment = text(column.comment(), 1024);
      if (comment.indexOf('\\') >= 0) throw invalid();
      columns.add(
          new DorisColumnInput(
              column.name(), type, length, precision, scale, nullable, key, kind, value, comment));
    }
    return List.copyOf(columns);
  }

  static String definition(DorisColumnInput column) {
    String type =
        switch (column.type()) {
          case "VARCHAR", "CHAR" -> column.type() + "(" + column.length() + ")";
          case "DECIMAL" -> "DECIMAL(" + column.precision() + "," + column.scale() + ")";
          case "DATE" -> "DATEV2";
          case "DATETIME" -> "DATETIMEV2(" + column.scale() + ")";
          default -> column.type();
        };
    String defaultSql =
        switch (column.defaultKind()) {
          case "NULL" -> " DEFAULT NULL";
          case "LITERAL" -> " DEFAULT " + DorisSqlLiterals.defaultLiteral(column.defaultValue());
          case "CURRENT_DATE" -> " DEFAULT CURRENT_DATE";
          case "CURRENT_TIMESTAMP" -> " DEFAULT CURRENT_TIMESTAMP(" + column.scale() + ")";
          default -> "";
        };
    return quote(column.name())
        + " "
        + type
        + (column.nullable() ? " NULL" : " NOT NULL")
        + defaultSql
        + " COMMENT "
        + DorisSqlLiterals.columnComment(column.comment());
  }

  static TableDefinition table(String schema, String name, List<DorisColumnInput> columns) {
    List<ColumnDefinition> definitions = new ArrayList<>();
    for (var column : columns) {
      String type = definition(column).split(" ", 3)[1];
      definitions.add(
          DorisTableMetadata.column(
              column.name(),
              type,
              column.nullable(),
              column.primaryKey(),
              !Set.of("NONE", "NULL").contains(column.defaultKind())));
    }
    return new TableDefinition(schema, name, List.copyOf(definitions));
  }

  static void requireScope(
      ConnectionDefinition target, JdbcEndpoint endpoint, String schema, String table) {
    if (target == null
        || endpoint == null
        || !"TARGET".equals(target.role)
        || !"Doris".equals(target.databaseType)
        || endpoint.dialect() != JdbcDialect.Doris)
      throw new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
    if (!identifier(schema) || !identifier(table)) throw invalid();
    DorisLoadLabel.requireTablePath(schema, table);
    if (!target.schemas.contains(schema)
        || !target.database.equals(schema)
        || !endpoint.schemas().contains(schema)
        || !endpoint.database().equals(schema))
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
  }

  static boolean identifier(String value) {
    return value != null && value.matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}");
  }

  static void requireHash(String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IntegrationException("INVALID_TABLE_DESIGN", 400);
  }

  static String digest(String... values) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(bytes)) {
        for (String value : values) {
          byte[] content = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
          data.writeInt(value == null ? -1 : content.length);
          data.write(content);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IntegrationException("TARGET_TABLE_STRUCTURE_UNAVAILABLE", 503);
    }
  }

  private static DorisTableDesignPreview preview(
      ConnectionDefinition target,
      JdbcEndpoint endpoint,
      String action,
      String schema,
      String name,
      String ddl,
      List<DorisColumnInput> columns,
      TableDefinition table,
      DorisTableStructure existing) {
    String hash =
        digest(
            "hospital-doris-designer-v1",
            action,
            target.id,
            Long.toString(target.version),
            target.username,
            IntegrationConnectionFingerprint.of(endpoint),
            ddl,
            existing == null ? null : existing.structureHash(),
            existing == null ? null : existing.table().toString(),
            existing == null ? null : existing.columns().toString(),
            existing == null ? null : existing.comment(),
            existing == null ? null : String.valueOf(existing.buckets()),
            existing == null ? null : String.valueOf(existing.replicationNum()),
            existing == null ? null : existing.model());
    return new DorisTableDesignPreview(target.id, action, schema, name, hash, ddl, columns, table);
  }

  private static String scalar(
      String type, String input, Integer length, Integer precision, Integer scale) {
    if (input == null) throw invalid();
    String value = text(input, 4096);
    try {
      return switch (type) {
        case "CHAR", "VARCHAR", "STRING" -> {
          if (length != null
              && (value.getBytes(StandardCharsets.UTF_8).length > length
                  || DorisSqlLiterals.defaultLiteral(value).length() - 2 > length)) throw invalid();
          yield value;
        }
        case "BOOLEAN" -> {
          if (Set.of("true", "1").contains(value.toLowerCase(Locale.ROOT))) yield "1";
          if (Set.of("false", "0").contains(value.toLowerCase(Locale.ROOT))) yield "0";
          throw invalid();
        }
        case "TINYINT", "SMALLINT", "INT", "BIGINT" -> {
          if (!value.matches("[+-]?[0-9]+")) throw invalid();
          int bits =
              switch (type) {
                case "TINYINT" -> 8;
                case "SMALLINT" -> 16;
                case "INT" -> 32;
                default -> 64;
              };
          BigInteger number = new BigInteger(value);
          if (number.compareTo(BigInteger.ONE.shiftLeft(bits - 1).negate()) < 0
              || number.compareTo(BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)) > 0)
            throw invalid();
          yield number.toString();
        }
        case "DECIMAL" -> {
          if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) throw invalid();
          BigDecimal number = new BigDecimal(value).setScale(scale);
          if (number.precision() > precision) throw invalid();
          yield number.toPlainString();
        }
        case "FLOAT", "DOUBLE" -> {
          if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
            throw invalid();
          double number = Double.parseDouble(value);
          if (!Double.isFinite(number)
              || ("FLOAT".equals(type) && !Float.isFinite(Float.parseFloat(value))))
            throw invalid();
          if (number == 0 && new BigDecimal(value).signum() != 0) throw invalid();
          if ("FLOAT".equals(type)
              && Float.parseFloat(value) == 0
              && new BigDecimal(value).signum() != 0) throw invalid();
          yield "FLOAT".equals(type)
              ? Float.toString(Float.parseFloat(value))
              : Double.toString(number);
        }
        case "DATE" -> {
          if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || LocalDate.parse(value).getYear() < 1)
            throw invalid();
          yield value;
        }
        case "DATETIME" -> {
          if (!value.matches(
              "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?"))
            throw invalid();
          int fractional = value.indexOf('.') < 0 ? 0 : value.length() - value.indexOf('.') - 1;
          if (fractional > scale
              || LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                      .getYear()
                  < 1) throw invalid();
          String seconds = value.substring(0, 19);
          yield scale == 0
              ? seconds
              : seconds
                  + "."
                  + (fractional == 0 ? "" : value.substring(20))
                  + "0".repeat(scale - fractional);
        }
        default -> throw invalid();
      };
    } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
      throw invalid();
    }
  }

  private static String text(String value, int maxBytes) {
    if (value == null) return "";
    if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || (code >= 0xD800 && code <= 0xDFFF)))
      throw invalid();
    return value;
  }

  private static String quote(String value) {
    return JdbcDialect.Doris.quote(value);
  }

  private static IntegrationException invalid() {
    return new IntegrationException("INVALID_TABLE_DESIGN", 400);
  }
}
