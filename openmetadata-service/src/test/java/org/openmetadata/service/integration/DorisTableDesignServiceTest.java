package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisAddColumnsInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisColumnInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignInput;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;

class DorisTableDesignServiceTest {
  @Test
  void readsSafeOlapStructureWithoutReturningArbitraryProperties() {
    var database = new Database();
    database.engine = "Doris";
    database.rawDdl +=
        "\nPROPERTIES (\"replication_allocation\" = \"tag.location.default: 1\", \"secret_token\" = \"do-not-expose\")";
    var structure =
        service(database, new ArrayList<>(), new AtomicReference<>(DorisTableDesignTest.endpoint()))
            .structure("target", "warehouse", "visits");
    assertEquals("UNIQUE", structure.model());
    assertEquals(4, structure.buckets());
    assertEquals(1, structure.replicationNum());
    assertEquals("BIGINT", structure.columns().getFirst().type());
    assertTrue(structure.table().columns().getFirst().primaryKey());
    assertFalse(structure.ddl().contains("secret_token"));
    assertFalse(structure.ddl().contains("do-not-expose"));
    assertTrue(structure.structureHash().matches("[a-f0-9]{64}"));
  }

  @Test
  void nativeEngineNameStillRequiresActualOlapDdl() {
    var database = new Database();
    database.engine = "Doris";
    database.rawDdl = database.rawDdl.replace("ENGINE=OLAP", "ENGINE=MYSQL");
    assertEquals(
        "TARGET_TABLE_MODEL_UNSUPPORTED",
        failure(() -> service(database).structure("target", "warehouse", "visits")).code());
  }

  @Test
  void normalizesPinnedNativeBooleanDateDatetimeAndDecimalShapes() {
    var database = new Database();
    database.nativeScalars = true;
    var structure = service(database).structure("target", "warehouse", "visits");
    assertEquals("BOOLEAN", structure.columns().get(1).type());
    assertEquals(java.sql.Types.BOOLEAN, structure.table().columns().get(1).jdbcType());
    assertEquals("DATE", structure.columns().get(2).type());
    assertEquals("DATETIME", structure.columns().get(3).type());
    assertEquals(6, structure.columns().get(3).scale());
    assertEquals("DECIMAL", structure.columns().get(4).type());
    assertEquals(18, structure.columns().get(4).precision());
    assertEquals(2, structure.columns().get(4).scale());
  }

  @Test
  void concurrentlyAddedColumnMakesReviewedPreviewStale() {
    var database = new Database();
    var service = service(database);
    var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
    database.addVisible = true;
    assertEquals(
        "TABLE_PREVIEW_STALE",
        failure(
                () ->
                    service.addColumns(
                        "target", "warehouse", "visits", addition(preview.previewHash()), "admin"))
            .code());
    assertTrue(database.mutations.isEmpty());
  }

  @Test
  void refusesExternalTableBeforeRequestingShowCreateCredentials() {
    var database = new Database();
    database.engine = "MYSQL";
    assertEquals(
        "TARGET_TABLE_MODEL_UNSUPPORTED",
        failure(() -> service(database).structure("target", "warehouse", "visits")).code());
    assertFalse(database.queries.stream().anyMatch(query -> query.startsWith("SHOW CREATE")));
  }

  @Test
  void creationExecutesOnlyReviewedDdlInPrivateDeterministicSession() {
    var database = new Database();
    database.exists = false;
    var service = service(database);
    var input = DorisTableDesignTest.input(List.of(DorisTableDesignTest.key()));
    var preview = service.previewCreate("target", input);
    var result = service.create("target", withHash(input, preview.previewHash()), "admin");
    assertEquals("APPLIED", result.status());
    assertEquals(List.of(preview.ddl()), database.mutations);
    assertTrue(
        database.executions.stream().allMatch(sql -> sql.equals("SET SESSION sql_mode = ''")));
    assertTrue(database.timeoutCount > 0);
    assertTrue(database.maxRowsCount > 0);
  }

  @Test
  void creationDoesNotClaimAppliedWhenActualTableCommentDiffers() {
    var database = new Database();
    database.exists = false;
    database.tableComment = "different";
    var service = service(database);
    var input = DorisTableDesignTest.input(List.of(DorisTableDesignTest.key()));
    var preview = service.previewCreate("target", input);
    assertEquals(
        "SUBMITTED",
        service.create("target", withHash(input, preview.previewHash()), "admin").status());
  }

  @Test
  void addReturnsSubmittedUntilExpectedColumnsAreActuallyVisible() {
    var database = new Database();
    var service = service(database);
    var input = addition(null);
    var preview = service.previewColumns("target", "warehouse", "visits", input);
    var submitted =
        service.addColumns(
            "target", "warehouse", "visits", addition(preview.previewHash()), "admin");
    assertEquals("SUBMITTED", submitted.status());
    assertEquals("RUNNING", submitted.structure().change().state());
    assertEquals(1, submitted.structure().columns().size());
    database.addVisible = true;
    database.change = "FINISHED";
    var structure = service.structure("target", "warehouse", "visits");
    assertEquals(2, structure.columns().size());
    assertEquals("FINISHED", structure.change().state());
    assertEquals("id", structure.columns().getFirst().name());
  }

  @Test
  void lightSchemaChangeReturnsAppliedWhenExactColumnDefinitionIsVisible() {
    var database = new Database();
    database.applyImmediately = true;
    var service = service(database);
    var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
    assertEquals(
        "APPLIED",
        service
            .addColumns("target", "warehouse", "visits", addition(preview.previewHash()), "admin")
            .status());
    assertTrue(database.mutations.getFirst().startsWith("ALTER TABLE"));
    assertFalse(database.mutations.getFirst().contains("MODIFY"));
    assertFalse(database.mutations.getFirst().contains("DROP"));
  }

  @Test
  void visibleColumnsDuringAnActiveSchemaChangeRemainSubmitted() {
    var database = new Database();
    database.visibleWhileRunning = true;
    var service = service(database);
    var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
    assertEquals(
        "SUBMITTED",
        service
            .addColumns("target", "warehouse", "visits", addition(preview.previewHash()), "admin")
            .status());
  }

  @Test
  void escapedMetadataDefaultsExposeSemanticValuesAndReachApplied() {
    var database = new Database();
    database.applyImmediately = true;
    database.addedType = "varchar(256)";
    database.addedDefault = "患者'a\\\\b\\\"c\\\\";
    String payload = "患者'a\\b\"c\\";
    var service = service(database);
    var input =
        new DorisAddColumnsInput(
            List.of(
                DorisTableDesignTest.column(
                    "extra", "VARCHAR", 256, null, null, false, false, "LITERAL", payload, "新增")),
            null);
    var preview = service.previewColumns("target", "warehouse", "visits", input);
    var applied =
        service.addColumns(
            "target",
            "warehouse",
            "visits",
            new DorisAddColumnsInput(input.columns(), preview.previewHash()),
            "admin");
    assertEquals(payload, applied.structure().columns().getLast().defaultValue());
    assertEquals("APPLIED", applied.status());
  }

  @Test
  void showCreateMetadataLexerHandlesMysqlEscapedDoubleQuotesAndBackslashes() {
    var database = new Database();
    database.rawDdl =
        "CREATE TABLE `visits` (\n`id` BIGINT NOT NULL COMMENT '主键',\n`note` VARCHAR(256) DEFAULT \"患者'a\\\\b\\\"c\\\\\"\n) ENGINE=OLAP\nUNIQUE KEY (`id`)\nDISTRIBUTED BY HASH (`id`) BUCKETS 4";
    assertEquals("UNIQUE", service(database).structure("target", "warehouse", "visits").model());
  }

  @Test
  void standaloneNativeTableCommentsDoNotPoisonStructuralParsing() {
    var database = new Database();
    database.rawDdl =
        "CREATE TABLE `visits` (\n`id` BIGINT NOT NULL\n) ENGINE=OLAP\nUNIQUE KEY (`id`)\nCOMMENT '患者'a\\b\\\"c\\'\nDISTRIBUTED BY HASH (`id`) BUCKETS 4";
    var structure = service(database).structure("target", "warehouse", "visits");
    assertEquals("UNIQUE", structure.model());
    assertEquals(4, structure.buckets());
  }

  @Test
  void refusesStaleActualEndpointAndFullStructureBeforeMutation() {
    var database = new Database();
    var endpoint = new AtomicReference<>(DorisTableDesignTest.endpoint());
    var service = service(database, new ArrayList<>(), endpoint);
    var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
    var changed = DorisTableDesignTest.target();
    changed.host = "another-fe";
    endpoint.set(new JdbcEndpoint(changed, "not-returned"));
    assertEquals(
        "TABLE_PREVIEW_STALE",
        failure(
                () ->
                    service.addColumns(
                        "target", "warehouse", "visits", addition(preview.previewHash()), "admin"))
            .code());
    endpoint.set(DorisTableDesignTest.endpoint());
    database.rawDdl += "\nPROPERTIES (\"compression\" = \"zstd\")";
    assertEquals(
        "TABLE_PREVIEW_STALE",
        failure(
                () ->
                    service.addColumns(
                        "target", "warehouse", "visits", addition(preview.previewHash()), "admin"))
            .code());
    changed = DorisTableDesignTest.target();
    changed.database = "moved";
    changed.schemas = List.of("moved");
    endpoint.set(new JdbcEndpoint(changed, "not-returned"));
    assertEquals(
        "TABLE_PREVIEW_STALE",
        failure(
                () ->
                    service.addColumns(
                        "target", "warehouse", "visits", addition(preview.previewHash()), "admin"))
            .code());
    assertTrue(database.mutations.isEmpty());
  }

  @Test
  void blocksActiveAndUnknownJobsIncludingAliasesButAllowsIdleScheduledTasks() {
    for (String state :
        List.of("SUBMITTED", "RUNNING", "STOPPING", "SUBMISSION_UNKNOWN", "SOMETHING_NEW")) {
      var database = new Database();
      var tasks = new ArrayList<IntegrationTask>();
      var task = new IntegrationTask();
      task.targetConnectionId = "alias";
      task.targetSchema = "warehouse";
      task.targetTable = "visits";
      task.latestRun = new RunSummary();
      task.latestRun.status = state;
      tasks.add(task);
      var service =
          service(database, tasks, new AtomicReference<>(DorisTableDesignTest.endpoint()));
      assertEquals(
          "TARGET_TABLE_ACTIVE",
          failure(() -> service.previewColumns("target", "warehouse", "visits", addition(null)))
              .code(),
          state);
      assertEquals(0, database.opens);
      task.latestRun.status = "FINISHED";
      service.previewColumns("target", "warehouse", "visits", addition(null));
      task.latestRun = null;
      service.previewColumns("target", "warehouse", "visits", addition(null));
    }
  }

  @Test
  void alternativeFeAndDnsAliasesCannotBypassActiveTableGuard() {
    var database = new Database();
    var task = new IntegrationTask();
    task.targetConnectionId = "alias";
    task.targetSchema = "warehouse";
    task.targetTable = "visits";
    task.latestRun = new RunSummary();
    task.latestRun.status = "RUNNING";
    var alias = DorisTableDesignTest.target();
    alias.host = "another-fe";
    alias.port = 19030;
    var service =
        new DorisTableDesignService(
            id -> DorisTableDesignTest.target(),
            id ->
                id.equals("alias")
                    ? new JdbcEndpoint(alias, "unused")
                    : DorisTableDesignTest.endpoint(),
            () -> List.of(task),
            () -> 1,
            database::open);
    assertEquals(
        "TARGET_TABLE_ACTIVE",
        failure(() -> service.previewColumns("target", "warehouse", "visits", addition(null)))
            .code());
    assertEquals(0, database.opens);
    task.targetTable = "another_table";
    service.previewColumns("target", "warehouse", "visits", addition(null));
  }

  @Test
  void genericMysqlConnectionToTheSameDorisRouteCannotBypassActiveGuard() {
    var database = new Database();
    var task = new IntegrationTask();
    task.targetConnectionId = "mysql-alias";
    task.targetSchema = "warehouse";
    task.targetTable = "visits";
    task.latestRun = new RunSummary();
    task.latestRun.status = "RUNNING";
    var alias = DorisTableDesignTest.target();
    alias.databaseType = "Mysql";
    alias.dorisHttpPort = null;
    var service =
        new DorisTableDesignService(
            id -> DorisTableDesignTest.target(),
            id ->
                id.equals("mysql-alias")
                    ? new JdbcEndpoint(alias, "unused")
                    : DorisTableDesignTest.endpoint(),
            () -> List.of(task),
            () -> 1,
            database::open);
    assertEquals(
        "TARGET_TABLE_ACTIVE",
        failure(() -> service.previewColumns("target", "warehouse", "visits", addition(null)))
            .code());
    assertEquals(0, database.opens);
  }

  @Test
  void rejectsPendingSchemaChangesAndUnsupportedModelsButDisplaysUnknownTypes() {
    var database = new Database();
    database.change = "PENDING";
    var service = service(database);
    assertEquals(
        "TARGET_SCHEMA_CHANGE_ACTIVE",
        failure(() -> service.previewColumns("target", "warehouse", "visits", addition(null)))
            .code());
    database.change = null;
    database.rawDdl = database.rawDdl.replace("UNIQUE KEY", "DUPLICATE KEY");
    assertEquals("DUPLICATE", service.structure("target", "warehouse", "visits").model());
    assertEquals(
        "TARGET_TABLE_MODEL_UNSUPPORTED",
        failure(() -> service.previewColumns("target", "warehouse", "visits", addition(null)))
            .code());
    database.rawDdl = database.rawDdl.replace("DUPLICATE KEY", "UNIQUE KEY");
    database.extraUnsupported = true;
    var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
    assertTrue(
        preview.table().columns().stream()
            .anyMatch(column -> column.dataType().equals("ARRAY<INT>")));
    assertFalse(preview.ddl().contains("ARRAY"));
  }

  @Test
  void permissionsAndUncertainMutationFailuresAreSafeAndNeverRetried() {
    for (int error : List.of(1142, 0)) {
      var database = new Database();
      var service = service(database);
      var preview = service.previewColumns("target", "warehouse", "visits", addition(null));
      database.failure =
          new SQLException(
              "jdbc:mysql://private-fe password=raw-secret dangerous SQL",
              error == 1142 ? "42000" : "HYT00",
              error);
      var failure =
          failure(
              () ->
                  service.addColumns(
                      "target", "warehouse", "visits", addition(preview.previewHash()), "admin"));
      assertEquals(
          error == 1142 ? "TARGET_TABLE_PERMISSION_DENIED" : "TARGET_TABLE_ALTER_FAILED",
          failure.code());
      assertFalse(failure.getMessage().contains("raw-secret"));
      assertEquals(1, database.mutationAttempts);
    }
  }

  private static DorisTableDesignInput withHash(DorisTableDesignInput input, String hash) {
    return new DorisTableDesignInput(
        input.targetSchema(),
        input.targetTable(),
        input.comment(),
        input.buckets(),
        input.columns(),
        hash);
  }

  private static DorisAddColumnsInput addition(String hash) {
    return new DorisAddColumnsInput(
        List.of(
            new DorisColumnInput(
                "extra", "INT", null, null, null, false, false, "LITERAL", "7", "新增")),
        hash);
  }

  private static DorisTableDesignService service(Database database) {
    return service(
        database, new ArrayList<>(), new AtomicReference<>(DorisTableDesignTest.endpoint()));
  }

  private static DorisTableDesignService service(
      Database database, List<IntegrationTask> tasks, AtomicReference<JdbcEndpoint> endpoint) {
    return new DorisTableDesignService(
        id -> DorisTableDesignTest.target(),
        id -> endpoint.get(),
        () -> tasks,
        () -> 1,
        database::open);
  }

  private IntegrationException failure(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(IntegrationException.class, action);
  }

  private static final class Database {
    boolean exists = true;
    boolean addVisible;
    boolean applyImmediately;
    boolean visibleWhileRunning;
    boolean extraUnsupported;
    boolean nativeScalars;
    String engine = "OLAP";
    String tableComment = "就诊表";
    String change;
    String addedType = "int";
    String addedDefault = "7";
    String rawDdl =
        "CREATE TABLE `visits` (\n`id` BIGINT NOT NULL COMMENT '主键'\n) ENGINE=OLAP\nUNIQUE KEY (`id`)\nDISTRIBUTED BY HASH (`id`) BUCKETS 4";
    SQLException failure;
    int opens;
    int timeoutCount;
    int maxRowsCount;
    int mutationAttempts;
    final List<String> queries = new ArrayList<>();
    final List<String> mutations = new ArrayList<>();
    final List<String> executions = new ArrayList<>();

    Connection open(JdbcEndpoint endpoint) {
      opens++;
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "createStatement" -> statement(null, Statement.class);
                    case "prepareStatement" -> statement(
                        (String) args[0], java.sql.PreparedStatement.class);
                    case "isClosed" -> false;
                    default -> null;
                  });
    }

    Object statement(String prepared, Class<?> type) {
      return Proxy.newProxyInstance(
          getClass().getClassLoader(),
          new Class<?>[] {type},
          (proxy, method, args) ->
              switch (method.getName()) {
                case "setQueryTimeout" -> {
                  timeoutCount++;
                  yield null;
                }
                case "setMaxRows" -> {
                  maxRowsCount++;
                  yield null;
                }
                case "executeQuery" -> query(prepared == null ? (String) args[0] : prepared);
                case "execute" -> {
                  executions.add((String) args[0]);
                  yield false;
                }
                case "executeUpdate" -> {
                  mutationAttempts++;
                  if (failure != null) throw failure;
                  String sql = (String) args[0];
                  mutations.add(sql);
                  if (sql.startsWith("CREATE")) {
                    exists = true;
                    rawDdl = sql;
                  } else {
                    addVisible = applyImmediately || visibleWhileRunning;
                    change = applyImmediately ? null : "RUNNING";
                  }
                  yield 0;
                }
                default -> null;
              });
    }

    ResultSet query(String sql) throws SQLException {
      queries.add(sql);
      List<Map<String, String>> rows = new ArrayList<>();
      if (sql.contains("information_schema.TABLES")) {
        if (exists)
          rows.add(
              row("TABLE_TYPE", "BASE TABLE", "ENGINE", engine, "TABLE_COMMENT", tableComment));
      } else if (sql.startsWith("SHOW CREATE")) {
        rows.add(row("1", "visits", "2", rawDdl));
      } else if (sql.contains("information_schema.COLUMNS")) {
        rows.add(
            row(
                "COLUMN_NAME",
                "id",
                "COLUMN_TYPE",
                "bigint",
                "IS_NULLABLE",
                "NO",
                "COLUMN_DEFAULT",
                null,
                "COLUMN_COMMENT",
                "主键",
                "ORDINAL_POSITION",
                "1"));
        if (extraUnsupported)
          rows.add(
              row(
                  "COLUMN_NAME",
                  "legacy",
                  "COLUMN_TYPE",
                  "array<int>",
                  "IS_NULLABLE",
                  "YES",
                  "COLUMN_DEFAULT",
                  null,
                  "COLUMN_COMMENT",
                  "",
                  "ORDINAL_POSITION",
                  "2"));
        if (nativeScalars) {
          rows.add(
              row(
                  "COLUMN_NAME",
                  "active",
                  "COLUMN_TYPE",
                  "tinyint(1)",
                  "IS_NULLABLE",
                  "YES",
                  "COLUMN_DEFAULT",
                  null,
                  "COLUMN_COMMENT",
                  "",
                  "ORDINAL_POSITION",
                  "2"));
          rows.add(
              row(
                  "COLUMN_NAME",
                  "day",
                  "COLUMN_TYPE",
                  "date",
                  "IS_NULLABLE",
                  "YES",
                  "COLUMN_DEFAULT",
                  null,
                  "COLUMN_COMMENT",
                  "",
                  "ORDINAL_POSITION",
                  "3"));
          rows.add(
              row(
                  "COLUMN_NAME",
                  "at",
                  "COLUMN_TYPE",
                  "datetime(6)",
                  "IS_NULLABLE",
                  "YES",
                  "COLUMN_DEFAULT",
                  null,
                  "COLUMN_COMMENT",
                  "",
                  "ORDINAL_POSITION",
                  "4"));
          rows.add(
              row(
                  "COLUMN_NAME",
                  "amount",
                  "COLUMN_TYPE",
                  "decimalv3(18, 2)",
                  "IS_NULLABLE",
                  "YES",
                  "COLUMN_DEFAULT",
                  null,
                  "COLUMN_COMMENT",
                  "",
                  "ORDINAL_POSITION",
                  "5"));
        }
        if (addVisible)
          rows.add(
              row(
                  "COLUMN_NAME",
                  "extra",
                  "COLUMN_TYPE",
                  addedType,
                  "IS_NULLABLE",
                  "NO",
                  "COLUMN_DEFAULT",
                  addedDefault,
                  "COLUMN_COMMENT",
                  "新增",
                  "ORDINAL_POSITION",
                  "2"));
      } else if (sql.startsWith("SHOW ALTER")) {
        if (change != null) rows.add(row("JobId", "101", "State", change));
      } else throw new SQLException("Unexpected read");
      int[] index = {-1};
      return (ResultSet)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "next" -> ++index[0] < rows.size();
                    case "getString" -> rows.get(index[0]).get(String.valueOf(args[0]));
                    case "getInt" -> Integer.parseInt(
                        rows.get(index[0]).get(String.valueOf(args[0])));
                    default -> null;
                  });
    }

    Map<String, String> row(String... values) {
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < values.length; i += 2) row.put(values[i], values[i + 1]);
      return row;
    }
  }
}
