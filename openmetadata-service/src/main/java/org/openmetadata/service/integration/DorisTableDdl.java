package org.openmetadata.service.integration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;
import org.openmetadata.service.integration.IntegrationModels.TargetTablePreview;

final class DorisTableDdl {
  private static final int MAX_VARCHAR_BYTES = 65533;

  private DorisTableDdl() {}

  static TargetTablePreview preview(
      TargetTableInput input,
      TableDefinition source,
      ConnectionDefinition sourceConnection,
      ConnectionDefinition targetConnection,
      int replication) {
    requireInput(input);
    if (!"Doris".equals(targetConnection.databaseType)) {
      throw new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
    }
    if (!"SOURCE".equals(sourceConnection.role)
        || !"TARGET".equals(targetConnection.role)
        || !input.sourceConnectionId().equals(sourceConnection.id)
        || replication < 1
        || replication > 9) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    if (!sourceConnection.schemas.contains(input.sourceSchema())
        || !targetConnection.schemas.contains(input.targetSchema())
        || !targetConnection.database.equals(input.targetSchema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    if (source == null) {
      throw new IntegrationException("TABLE_NOT_FOUND", 404);
    }
    if (!source.schema().equals(input.sourceSchema())
        || !source.name().equals(input.sourceTable())
        || source.columns() == null
        || source.columns().isEmpty()
        || source.columns().size() > 128) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    Map<String, ColumnDefinition> sourceColumns = new HashMap<>();
    for (ColumnDefinition column : source.columns()) {
      if (!IntegrationValidator.safeIdentifier(column.name())
          || sourceColumns.put(column.name(), column) != null) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
    }
    List<ColumnDefinition> keys =
        source.columns().stream().filter(ColumnDefinition::primaryKey).toList();
    if (keys.size() != 1 || keys.getFirst().nullable()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    String key = keys.getFirst().name();
    Set<String> from = new HashSet<>();
    Set<String> to = new HashSet<>();
    List<ColumnDefinition> columns = new ArrayList<>();
    for (FieldMapping mapping : input.fieldMappings()) {
      if (!from.add(mapping.source()) || !to.add(mapping.target().toLowerCase(Locale.ROOT))) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      ColumnDefinition column = sourceColumns.get(mapping.source());
      if (column == null) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      columns.add(targetColumn(column, mapping.target(), sourceConnection.databaseType));
    }
    if (!from.contains(key)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    columns.sort(Comparator.comparing(column -> !column.primaryKey()));
    String targetKey = JdbcDialect.Doris.quote(columns.getFirst().name());
    String ddl =
        "CREATE TABLE "
            + JdbcDialect.Doris.quote(input.targetSchema())
            + "."
            + JdbcDialect.Doris.quote(input.targetTable())
            + " (\n"
            + columns.stream()
                .map(
                    column ->
                        "  "
                            + JdbcDialect.Doris.quote(column.name())
                            + " "
                            + column.dataType()
                            + (column.nullable() ? " NULL" : " NOT NULL"))
                .collect(Collectors.joining(",\n"))
            + "\n) ENGINE=OLAP\nUNIQUE KEY ("
            + targetKey
            + ")\nDISTRIBUTED BY HASH ("
            + targetKey
            + ") BUCKETS 1\n"
            + "PROPERTIES (\n  \"replication_num\" = \""
            + replication
            + "\",\n  \"enable_unique_key_merge_on_write\" = \"true\"\n)";
    return new TargetTablePreview(
        targetConnection.id,
        hash(input, source, sourceConnection, targetConnection, ddl),
        ddl,
        new TableDefinition(input.targetSchema(), input.targetTable(), List.copyOf(columns)),
        List.copyOf(input.fieldMappings()));
  }

  static void requireInput(TargetTableInput input) {
    if (input == null
        || input.sourceConnectionId() == null
        || input.sourceConnectionId().isBlank()
        || input.sourceConnectionId().length() > 64
        || input.fieldMappings() == null
        || input.fieldMappings().isEmpty()
        || input.fieldMappings().size() > 128) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    IntegrationValidator.requireTable(input.sourceSchema(), input.sourceTable());
    IntegrationValidator.requireTable(input.targetSchema(), input.targetTable());
    DorisLoadLabel.requireTablePath(input.targetSchema(), input.targetTable());
    Set<String> sources = new HashSet<>();
    Set<String> targets = new HashSet<>();
    for (FieldMapping mapping : input.fieldMappings()) {
      if (mapping == null
          || !IntegrationValidator.safeIdentifier(mapping.source())
          || !IntegrationValidator.safeIdentifier(mapping.target())
          || !sources.add(mapping.source())
          || !targets.add(mapping.target().toLowerCase(Locale.ROOT))) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
    }
  }

  private static ColumnDefinition targetColumn(
      ColumnDefinition source, String name, String family) {
    int jdbc = source.jdbcType();
    int size = source.size();
    int scale = source.scale();
    String type;
    boolean unicode = false;
    int capacity = 0;
    switch (jdbc) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
        if (jdbc == Types.BIGINT
            && source.dataType().toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
          throw unsupported();
        }
        type = "BIGINT";
        jdbc = Types.BIGINT;
        size = 19;
        scale = 0;
      }
      case Types.NUMERIC, Types.DECIMAL -> {
        if (size < 1 || size > 38 || scale < 0 || scale > size) {
          throw unsupported();
        }
        type = "DECIMAL(" + size + "," + scale + ")";
        jdbc = Types.DECIMAL;
      }
      case Types.CHAR,
          Types.NCHAR,
          Types.VARCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR -> {
        if (size < 1 || size > MAX_VARCHAR_BYTES / 4) {
          throw unsupported();
        }
        capacity = size;
        size *= 4;
        type = "VARCHAR(" + size + ")";
        jdbc = Types.VARCHAR;
        unicode = true;
        scale = 0;
      }
      case Types.BOOLEAN, Types.BIT -> {
        if (jdbc == Types.BIT && size != 1) {
          throw unsupported();
        }
        type = "BOOLEAN";
        jdbc = Types.BOOLEAN;
        size = 1;
        scale = 0;
      }
      case Types.DATE -> {
        if ("Oracle".equals(family)) {
          throw unsupported();
        }
        type = "DATEV2";
        size = 10;
        scale = 0;
      }
      case Types.TIMESTAMP -> {
        String sourceType = source.dataType().toUpperCase(Locale.ROOT).replace('_', ' ');
        if (scale < 0
            || scale > 6
            || sourceType.contains("TIME ZONE")
            || sourceType.contains("TIMESTAMPTZ")
            || sourceType.contains("DATETIMEOFFSET")) {
          throw unsupported();
        }
        type = "DATETIMEV2(" + scale + ")";
        size = 26;
      }
      default -> throw unsupported();
    }
    return new ColumnDefinition(
        name,
        type,
        source.nullable(),
        source.primaryKey(),
        jdbc,
        size,
        scale,
        false,
        unicode,
        capacity);
  }

  private static IntegrationException unsupported() {
    return new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
  }

  private static String hash(
      TargetTableInput input,
      TableDefinition source,
      ConnectionDefinition sourceConnection,
      ConnectionDefinition targetConnection,
      String ddl) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(bytes)) {
        string(data, "hospital-doris-table-v1");
        string(data, ddl);
        connection(data, sourceConnection);
        connection(data, targetConnection);
        string(data, source.schema());
        string(data, source.name());
        data.writeInt(source.columns().size());
        for (ColumnDefinition column : source.columns()) {
          string(data, column.name());
          string(data, column.dataType());
          data.writeBoolean(column.nullable());
          data.writeBoolean(column.primaryKey());
          data.writeInt(column.jdbcType());
          data.writeInt(column.size());
          data.writeInt(column.scale());
          data.writeBoolean(column.hasDefault());
          data.writeBoolean(column.unicodeSafeTarget());
          data.writeInt(column.unicodeCapacity());
        }
        data.writeInt(input.fieldMappings().size());
        for (FieldMapping mapping : input.fieldMappings()) {
          string(data, mapping.source());
          string(data, mapping.target());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IntegrationException("TARGET_TABLE_CREATE_FAILED", 503);
    }
  }

  private static void connection(DataOutputStream data, ConnectionDefinition connection)
      throws IOException {
    string(data, connection.id);
    data.writeLong(connection.version);
    string(data, connection.databaseType);
    string(data, connection.databaseVersion);
    string(data, connection.host);
    string(data, String.valueOf(connection.port));
    string(data, String.valueOf(connection.dorisHttpPort));
    string(data, connection.database);
    string(data, connection.username);
    string(data, connection.tlsMode);
    data.writeInt(connection.schemas.size());
    for (String schema : connection.schemas) {
      string(data, schema);
    }
  }

  private static void string(DataOutputStream data, String value) throws IOException {
    byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    data.writeInt(value == null ? -1 : bytes.length);
    data.write(bytes);
  }
}
