package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisAddColumnsInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisColumnInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisSchemaChange;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignPreview;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableMutationResult;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableStructure;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

final class DorisTableDesignService {
  private static final Set<String> TERMINAL =
      Set.of("FINISHED", "FAILED", "CANCELED", "CANCELLED", "STOPPED", "SAVEPOINT_DONE");
  private static final Pattern MODEL =
      Pattern.compile("(?im)^\\s*(UNIQUE|DUPLICATE|AGGREGATE)\\s+KEY\\s*\\(([^\\r\\n]*)\\)");
  private static final Pattern BUCKETS = Pattern.compile("(?i)\\bBUCKETS\\s+([0-9]{1,9})\\b");
  private static final Pattern TYPE =
      Pattern.compile("([A-Z][A-Z0-9]*)(?:\\(\\s*([0-9]{1,9})\\s*(?:,\\s*([0-9]{1,9})\\s*)?\\))?");
  private final Function<String, ConnectionDefinition> definitions;
  private final Function<String, JdbcEndpoint> endpoints;
  private final Supplier<List<IntegrationTask>> tasks;
  private final IntSupplier replication;
  private final ConnectionFactory connections;

  @FunctionalInterface
  interface ConnectionFactory {
    Connection open(JdbcEndpoint endpoint) throws SQLException;
  }

  DorisTableDesignService(
      IntegrationConfiguration configuration,
      IntegrationConnections connections,
      IntegrationTaskStore tasks) {
    this(
        connections::connection,
        connections::endpoint,
        tasks::list,
        configuration::dorisReplicationNum,
        endpoint ->
            DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties()));
  }

  DorisTableDesignService(
      Function<String, ConnectionDefinition> definitions,
      Function<String, JdbcEndpoint> endpoints,
      Supplier<List<IntegrationTask>> tasks,
      IntSupplier replication,
      ConnectionFactory connections) {
    this.definitions = definitions;
    this.endpoints = endpoints;
    this.tasks = tasks;
    this.replication = replication;
    this.connections = connections;
  }

  DorisTableStructure structure(String id, String schema, String table) {
    JdbcEndpoint endpoint = endpoint(id, schema, table);
    try (Connection connection = open(endpoint)) {
      return read(connection, id, schema, table);
    } catch (SQLException e) {
      throw failure(e, "TARGET_TABLE_STRUCTURE_UNAVAILABLE");
    }
  }

  DorisTableDesignPreview previewCreate(String id, DorisTableDesignInput input) {
    if (input == null) throw new IntegrationException("INVALID_TABLE_DESIGN", 400);
    JdbcEndpoint endpoint = endpoint(id, input.targetSchema(), input.targetTable());
    var preview =
        DorisTableDesign.create(definitions.apply(id), endpoint, input, replication.getAsInt());
    try (Connection connection = open(endpoint)) {
      requireAbsent(connection, input.targetSchema(), input.targetTable());
      return preview;
    } catch (SQLException e) {
      throw failure(e, "CONNECTION_UNAVAILABLE");
    }
  }

  DorisTableMutationResult create(String id, DorisTableDesignInput input, String actor) {
    if (input == null) throw new IntegrationException("INVALID_TABLE_DESIGN", 400);
    DorisTableDesign.requireHash(input.previewHash());
    try {
      JdbcEndpoint endpoint = endpoint(id, input.targetSchema(), input.targetTable());
      int replicas = replication.getAsInt();
      var preview = DorisTableDesign.create(definitions.apply(id), endpoint, input, replicas);
      requireMatchingHash(input.previewHash(), preview);
      try (Connection connection = open(endpoint)) {
        requireAbsent(connection, input.targetSchema(), input.targetTable());
        execute(connection, preview.ddl());
        var actual = read(connection, id, preview.targetSchema(), preview.targetTable());
        boolean applied =
            visible(actual, preview.columns())
                && "UNIQUE".equals(actual.model())
                && Objects.equals(actual.comment(), input.comment() == null ? "" : input.comment())
                && Objects.equals(actual.buckets(), input.buckets() == null ? 1 : input.buckets())
                && Objects.equals(actual.replicationNum(), replicas);
        return new DorisTableMutationResult(applied ? "APPLIED" : "SUBMITTED", actual);
      } catch (SQLException e) {
        if (e.getErrorCode() == 1050 || "42S01".equals(e.getSQLState()))
          throw new IntegrationException("TABLE_ALREADY_EXISTS", 409);
        throw failure(e, "TARGET_TABLE_CREATE_FAILED");
      }
    } catch (IntegrationException e) {
      throw staleScope(e);
    }
  }

  DorisTableDesignPreview previewColumns(
      String id, String schema, String table, DorisAddColumnsInput input) {
    JdbcEndpoint endpoint = endpoint(id, schema, table);
    requireIdle(id, endpoint, schema, table);
    try (Connection connection = open(endpoint)) {
      return DorisTableDesign.append(
          definitions.apply(id), endpoint, read(connection, id, schema, table), input);
    } catch (SQLException e) {
      throw failure(e, "TARGET_TABLE_STRUCTURE_UNAVAILABLE");
    }
  }

  DorisTableMutationResult addColumns(
      String id, String schema, String table, DorisAddColumnsInput input, String actor) {
    if (input == null) throw new IntegrationException("INVALID_TABLE_DESIGN", 400);
    DorisTableDesign.requireHash(input.previewHash());
    try {
      JdbcEndpoint endpoint = endpoint(id, schema, table);
      requireIdle(id, endpoint, schema, table);
      try (Connection connection = open(endpoint)) {
        var current = read(connection, id, schema, table);
        var preview = DorisTableDesign.append(definitions.apply(id), endpoint, current, input);
        requireMatchingHash(input.previewHash(), preview);
        execute(connection, preview.ddl());
        var actual = read(connection, id, schema, table);
        return new DorisTableMutationResult(
            visible(actual, preview.columns()) ? "APPLIED" : "SUBMITTED", actual);
      } catch (SQLException e) {
        throw failure(e, "TARGET_TABLE_ALTER_FAILED");
      }
    } catch (IntegrationException e) {
      throw staleScope(e);
    }
  }

  private JdbcEndpoint endpoint(String id, String schema, String table) {
    ConnectionDefinition definition = definitions.apply(id);
    if (!"TARGET".equals(definition.role) || !"Doris".equals(definition.databaseType))
      throw new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
    if (Boolean.FALSE.equals(definition.enabled))
      throw new IntegrationException("CONNECTION_DISABLED", 409);
    JdbcEndpoint endpoint = endpoints.apply(id);
    DorisTableDesign.requireScope(definition, endpoint, schema, table);
    return endpoint;
  }

  private Connection open(JdbcEndpoint endpoint) throws SQLException {
    Connection connection = connections.open(endpoint);
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute("SET SESSION sql_mode = ''");
      return connection;
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException ignored) {
      }
      throw e;
    }
  }

  private void requireAbsent(Connection connection, String schema, String table)
      throws SQLException {
    if (header(connection, schema, table) != null)
      throw new IntegrationException("TABLE_ALREADY_EXISTS", 409);
  }

  private Header header(Connection connection, String schema, String table) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT TABLE_TYPE, ENGINE, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setQueryTimeout(5);
      statement.setMaxRows(2);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        Header result =
            new Header(
                rows.getString("TABLE_TYPE"),
                rows.getString("ENGINE"),
                bounded(rows.getString("TABLE_COMMENT"), 16384));
        if (rows.next()) throw unavailable();
        return result;
      }
    }
  }

  private DorisTableStructure read(Connection connection, String id, String schema, String table)
      throws SQLException {
    Header header = header(connection, schema, table);
    if (header == null) throw new IntegrationException("TABLE_NOT_FOUND", 404);
    if (!"BASE TABLE".equalsIgnoreCase(header.type())
        || !("OLAP".equalsIgnoreCase(header.engine()) || "Doris".equalsIgnoreCase(header.engine())))
      throw new IntegrationException("TARGET_TABLE_MODEL_UNSUPPORTED", 409);
    String qualified = JdbcDialect.Doris.quote(schema) + "." + JdbcDialect.Doris.quote(table);
    String rawDdl;
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      try (ResultSet rows = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
        if (!rows.next()) throw new IntegrationException("TABLE_NOT_FOUND", 404);
        rawDdl = bounded(rows.getString(2), 1_048_576);
        if (rawDdl.isEmpty()) throw unavailable();
      }
    }
    String masked = maskLiterals(rawDdl);
    if (!Pattern.compile("(?im)^\\s*\\)\\s*ENGINE\\s*=\\s*OLAP\\b").matcher(masked).find())
      throw new IntegrationException("TARGET_TABLE_MODEL_UNSUPPORTED", 409);
    Matcher modelMatcher = MODEL.matcher(masked);
    String model = "UNKNOWN";
    Set<String> keyNames = Set.of();
    if (modelMatcher.find()) {
      model = modelMatcher.group(1).toUpperCase(Locale.ROOT);
      keyNames = DorisTableMetadata.uniqueKeys("UNIQUE KEY (" + modelMatcher.group(2) + ")");
      if (modelMatcher.find()) throw unavailable();
    }
    Matcher bucketMatcher = BUCKETS.matcher(masked);
    Integer buckets = bucketMatcher.find() ? Integer.valueOf(bucketMatcher.group(1)) : null;
    Integer replicas = replicas(rawDdl);
    List<DorisColumnInput> columns = new ArrayList<>();
    List<ColumnDefinition> definitions = new ArrayList<>();
    Set<String> names = new HashSet<>();
    try (var statement =
        connection.prepareStatement(
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, ORDINAL_POSITION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setQueryTimeout(5);
      statement.setMaxRows(129);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String name = rows.getString("COLUMN_NAME");
          if (!IntegrationValidator.safeIdentifier(name)
              || !names.add(name.toLowerCase(Locale.ROOT))) throw unavailable();
          if (columns.size() >= 128)
            throw new IntegrationException("TABLE_COLUMN_LIMIT_EXCEEDED", 400);
          String type = bounded(rows.getString("COLUMN_TYPE"), 512).toUpperCase(Locale.ROOT).trim();
          if (type.isEmpty()) throw unavailable();
          boolean nullable = "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE"));
          boolean key = "UNIQUE".equals(model) && keyNames.contains(name);
          String value = rows.getString("COLUMN_DEFAULT");
          var column =
              column(
                  name,
                  type,
                  nullable,
                  key,
                  value,
                  bounded(rows.getString("COLUMN_COMMENT"), 16384));
          columns.add(column);
          definitions.add(DorisTableMetadata.column(name, type, nullable, key, value != null));
        }
      }
    }
    if (columns.isEmpty()
        || !names.containsAll(keyNames.stream().map(key -> key.toLowerCase(Locale.ROOT)).toList()))
      throw unavailable();
    var definition = new TableDefinition(schema, table, List.copyOf(definitions));
    String safeDdl =
        displayDdl(qualified, header.comment(), model, keyNames, buckets, replicas, columns);
    String hash =
        DorisTableDesign.digest(
            "hospital-doris-structure-v1",
            rawDdl,
            header.type(),
            header.engine(),
            header.comment(),
            columns.toString(),
            definitions.toString());
    return new DorisTableStructure(
        id,
        schema,
        table,
        header.comment(),
        model,
        buckets,
        replicas,
        hash,
        List.copyOf(columns),
        definition,
        safeDdl,
        change(connection, schema, table));
  }

  private DorisSchemaChange change(Connection connection, String schema, String table)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      try (ResultSet rows =
          statement.executeQuery(
              "SHOW ALTER TABLE COLUMN FROM "
                  + JdbcDialect.Doris.quote(schema)
                  + " WHERE TableName = "
                  + DorisSqlLiterals.queryLiteral(table)
                  + " ORDER BY CreateTime DESC LIMIT 1")) {
        if (!rows.next()) return null;
        String job = rows.getString("JobId");
        String state = rows.getString("State");
        if (job == null || !job.matches("[0-9]{1,32}")) job = null;
        state = state == null ? "UNKNOWN" : state.toUpperCase(Locale.ROOT);
        if (!state.matches("[A-Z_]{1,32}")) state = "UNKNOWN";
        return new DorisSchemaChange(job, state);
      }
    }
  }

  private DorisColumnInput column(
      String name, String type, boolean nullable, boolean key, String value, String comment) {
    String base = type;
    Integer length = null;
    Integer precision = null;
    Integer scale = null;
    Matcher matcher = TYPE.matcher(type);
    if (matcher.matches()) {
      base =
          switch (matcher.group(1)) {
            case "BOOL" -> "BOOLEAN";
            case "TEXT" -> "STRING";
            case "INTEGER" -> "INT";
            case "DECIMALV2", "DECIMALV3" -> "DECIMAL";
            case "DATEV2" -> "DATE";
            case "DATETIMEV2" -> "DATETIME";
            default -> matcher.group(1);
          };
      int first = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
      if ("TINYINT".equals(base) && first == 1) base = "BOOLEAN";
      if (Set.of("CHAR", "VARCHAR").contains(base)) length = first;
      if ("DECIMAL".equals(base)) {
        precision = first;
        scale = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
      }
      if ("DATETIME".equals(base)) scale = first;
    }
    String kind = value == null ? (nullable ? "NULL" : "NONE") : "LITERAL";
    if (value != null) {
      value = bounded(value, 16384);
      if (Set.of("CHAR", "VARCHAR", "STRING").contains(base))
        value = DorisSqlLiterals.decodeDefault(value);
      if ("DATE".equals(base) && value.matches("(?i)CURRENT_DATE(?:\\(\\))?")) {
        kind = "CURRENT_DATE";
        value = null;
      }
      if ("DATETIME".equals(base) && value.matches("(?i)CURRENT_TIMESTAMP(?:\\([0-6]?\\))?")) {
        kind = "CURRENT_TIMESTAMP";
        value = null;
      }
    }
    return new DorisColumnInput(
        name, base, length, precision, scale, nullable, key, kind, value, comment);
  }

  private String displayDdl(
      String qualified,
      String comment,
      String model,
      Set<String> keys,
      Integer buckets,
      Integer replicas,
      List<DorisColumnInput> columns) {
    String fields =
        columns.stream()
            .map(column -> "  " + DorisTableDesign.definition(column))
            .collect(Collectors.joining(",\n"));
    String key =
        keys.isEmpty()
            ? ""
            : "\n"
                + model
                + " KEY ("
                + columns.stream()
                    .filter(column -> keys.contains(column.name()))
                    .map(column -> JdbcDialect.Doris.quote(column.name()))
                    .collect(Collectors.joining(", "))
                + ")";
    String distribution =
        buckets == null
            ? ""
            : "\nDISTRIBUTED BY HASH ("
                + columns.stream()
                    .filter(column -> keys.contains(column.name()))
                    .map(column -> JdbcDialect.Doris.quote(column.name()))
                    .collect(Collectors.joining(", "))
                + ") BUCKETS "
                + buckets;
    return "CREATE TABLE "
        + qualified
        + " (\n"
        + fields
        + "\n) ENGINE=OLAP"
        + key
        + "\nCOMMENT "
        + DorisSqlLiterals.tableComment(comment)
        + distribution
        + (replicas == null ? "" : "\nPROPERTIES (\"replication_num\" = \"" + replicas + "\")");
  }

  private void requireIdle(String id, JdbcEndpoint endpoint, String schema, String table) {
    for (IntegrationTask task : tasks.get()) {
      if (task.latestRun == null
          || (task.latestRun.status != null && TERMINAL.contains(task.latestRun.status))
          || !schema.equalsIgnoreCase(task.targetSchema)
          || !table.equalsIgnoreCase(task.targetTable)) continue;
      if (id.equals(task.targetConnectionId))
        throw new IntegrationException("TARGET_TABLE_ACTIVE", 409);
      try {
        JdbcEndpoint other = endpoints.apply(task.targetConnectionId);
        // Different FE addresses can belong to the same cluster; no trusted cluster ID is
        // available.
        boolean mysqlAlias =
            other.dialect() == JdbcDialect.Mysql
                && endpoint.host().equalsIgnoreCase(other.host())
                && endpoint.port() == other.port();
        if ((other.dialect() == JdbcDialect.Doris || mysqlAlias)
            && endpoint.database().equalsIgnoreCase(other.database()))
          throw new IntegrationException("TARGET_TABLE_ACTIVE", 409);
      } catch (IntegrationException e) {
        throw new IntegrationException("TARGET_TABLE_ACTIVE", 409);
      }
    }
  }

  private boolean visible(DorisTableStructure actual, List<DorisColumnInput> expected) {
    if (actual.change() != null
        && !Set.of("FINISHED", "CANCELLED", "CANCELED").contains(actual.change().state()))
      return false;
    for (DorisColumnInput column : expected) {
      var found =
          actual.columns().stream().filter(value -> value.name().equals(column.name())).findFirst();
      if (found.isEmpty()) return false;
      var observed = found.get();
      if (!Objects.equals(column.type(), observed.type())
          || !Objects.equals(column.length(), observed.length())
          || !Objects.equals(column.precision(), observed.precision())
          || !Objects.equals(column.scale(), observed.scale())
          || !Objects.equals(column.nullable(), observed.nullable())
          || !Objects.equals(column.primaryKey(), observed.primaryKey())
          || !Objects.equals(column.comment(), observed.comment())) return false;
      if ("NONE".equals(column.defaultKind())
          && column.nullable()
          && "NULL".equals(observed.defaultKind())) continue;
      try {
        observed = DorisTableDesign.normalize(List.of(observed), false).getFirst();
      } catch (IntegrationException e) {
        return false;
      }
      if (!Objects.equals(column.defaultKind(), observed.defaultKind())
          || !Objects.equals(column.defaultValue(), observed.defaultValue())) return false;
    }
    return true;
  }

  private void execute(Connection connection, String ddl) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(15);
      statement.executeUpdate(ddl);
    }
  }

  private static void requireMatchingHash(String hash, DorisTableDesignPreview preview) {
    if (!preview.previewHash().equals(hash))
      throw new IntegrationException("TABLE_PREVIEW_STALE", 409);
  }

  private IntegrationException staleScope(IntegrationException failure) {
    return Set.of(
                "SCHEMA_NOT_ALLOWED",
                "TABLE_NOT_FOUND",
                "UNSUPPORTED_TARGET_TYPE",
                "COLUMN_ALREADY_EXISTS",
                "TABLE_COLUMN_LIMIT_EXCEEDED",
                "TARGET_TABLE_MODEL_UNSUPPORTED")
            .contains(failure.code())
        ? new IntegrationException("TABLE_PREVIEW_STALE", 409)
        : failure;
  }

  private IntegrationException failure(SQLException failure, String fallback) {
    if (Set.of(1044, 1045, 1142, 1143, 1227).contains(failure.getErrorCode())
        || "28000".equals(failure.getSQLState()))
      return new IntegrationException("TARGET_TABLE_PERMISSION_DENIED", 403);
    return new IntegrationException(fallback, 503);
  }

  private static String bounded(String value, int limit) {
    if (value == null) return "";
    if (value.length() > limit) throw unavailable();
    return value;
  }

  private static Integer replicas(String ddl) {
    Matcher count =
        Pattern.compile("(?im)^\\s*\"replication_num\"\\s*=\\s*\"([1-9])\"").matcher(ddl);
    if (count.find()) return Integer.valueOf(count.group(1));
    Matcher allocation =
        Pattern.compile(
                "\"replication_allocation\"\\s*=\\s*\"tag\\.location\\.default:\\s*([1-9])\"")
            .matcher(ddl);
    return allocation.find() ? Integer.valueOf(allocation.group(1)) : null;
  }

  private static String maskLiterals(String ddl) {
    // SHOW CREATE does not escape apostrophes in its standalone table COMMENT line.
    String structural = ddl.replaceAll("(?im)^[ \\t]*COMMENT\\b[^\\r\\n]*", "");
    StringBuilder masked = new StringBuilder(structural.length());
    char quote = 0;
    for (int i = 0; i < structural.length(); i++) {
      char current = structural.charAt(i);
      if (quote == 0 && (current == '\'' || current == '"')) {
        quote = current;
        masked.append(' ');
      } else if (quote != 0) {
        masked.append(current == '\n' ? '\n' : ' ');
        if (current == '\\' && i + 1 < structural.length()) {
          masked.append(structural.charAt(++i) == '\n' ? '\n' : ' ');
          continue;
        }
        if (current == quote) {
          if (i + 1 < structural.length() && structural.charAt(i + 1) == quote) {
            masked.append(' ');
            i++;
          } else quote = 0;
        }
      } else masked.append(current);
    }
    if (quote != 0) throw unavailable();
    return masked.toString();
  }

  private static IntegrationException unavailable() {
    return new IntegrationException("TARGET_TABLE_STRUCTURE_UNAVAILABLE", 503);
  }

  private record Header(String type, String engine, String comment) {}
}
