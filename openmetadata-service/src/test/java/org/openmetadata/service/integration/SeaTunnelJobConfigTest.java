package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.RuntimeOptions;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;

class SeaTunnelJobConfigTest {
  @Test
  void legacyPostgresCdcKeepsTheCompletePriorJdbcAndDorisGraphs() throws Exception {
    var task = task();
    task.mode = "CDC";
    var run = new RunSummary();
    run.jobId = "123456789";
    for (boolean doris : List.of(false, true)) {
      if (doris) {
        task.targetConnectionId = "doris-ods-validation";
        task.targetSchema = "ods_validation";
        task.targetTable = "visits";
      }
      var builder = doris ? sqlBuilder(task) : new SeaTunnelJobConfig(configuration());
      var expected = legacyCdcConfig(doris);
      assertEquals(expected, builder.buildLegacyCdc(task, run));
      task.runtime = new RuntimeOptions(2500, 16000, 12000, 120, 1500);
      assertEquals(expected, builder.buildLegacyCdc(task, run));
      var current = builder.build(task, run);
      var currentSink = (Map<?, ?>) ((List<?>) current.get("sink")).getFirst();
      assertEquals(12000, currentSink.get(doris ? "doris.batch.size" : "batch_size"));
      assertEquals(120000, ((Map<?, ?>) current.get("env")).get("checkpoint.interval"));
      assertEquals(1500000, ((Map<?, ?>) current.get("env")).get("checkpoint.timeout"));
      var legacySource = (Map<?, ?>) ((List<?>) expected.get("source")).getFirst();
      for (String option :
          List.of("fetch_size", "snapshot.fetch.size", "snapshot.split.size", "split.size")) {
        assertFalse(legacySource.containsKey(option), option);
      }
      task.runtime = null;
    }
  }

  @Test
  void oracleFullUsesNativeDynamicSplitsWithTheExactMappedKeyAliasAndProtectedDescriptor() {
    var definition = new ConnectionDefinition();
    definition.copyInput(IntegrationConnectionsTest.input("Oracle"));
    definition.id = "01234567-1234-1234-1234-123456789012";
    definition.oracleConnectionType = "SID";
    var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, "synthetic-oracle-secret");
    var task = task();
    task.sourceConnectionId = definition.id;
    task.sourceSchema = definition.schemas.getFirst();
    task.sourceTable = "医嘱记录";
    task.primaryKey = "YZLSH";
    task.fieldMappings =
        List.of(
            new FieldMapping("YZLSH", "yzlsh"),
            new FieldMapping("XML_CLOB", "xml_payload"),
            new FieldMapping("患者姓名", "PatientName"));
    var builder =
        new SeaTunnelJobConfig(
            id -> id.equals(definition.id) ? endpoint : configuration().endpoint(id));
    String expectedQuery =
        "SELECT \"YZLSH\" AS \"yzlsh\", \"XML_CLOB\" AS \"xml_payload\", "
            + "\"患者姓名\" AS \"PatientName\" FROM \""
            + task.sourceSchema
            + "\".\"医嘱记录\"";
    var config = builder.build(task);
    var source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    assertEquals(
        List.of(Map.of("query", expectedQuery, "partition_column", "yzlsh")),
        source.get("table_list"));
    assertEquals(endpoint.jdbcUrl(), source.get("url"));
    assertTrue(endpoint.jdbcUrl().contains("(DESCRIPTION="));
    assertEquals(definition.username, source.get("username"));
    assertEquals("synthetic-oracle-secret", source.get("password"));
    assertFalse(endpoint.jdbcUrl().contains("synthetic-oracle-secret"));
    assertEquals("HospitalOracle", source.get("dialect"));
    assertEquals(1000, source.get("fetch_size"));
    assertEquals(8192, source.get("split.size"));
    assertEquals(false, source.get("split.allow-sampling"));
    assertEquals(true, source.get("skip_analyze"));
    assertEquals(true, source.get("use_select_count"));
    assertEquals(true, source.get("enable_concurrent_read"));
    for (String option :
        List.of(
            "query",
            "partition_column",
            "table_path",
            "partition_num",
            "partition_lower_bound",
            "partition_upper_bound")) {
      assertFalse(source.containsKey(option), option);
    }
    // The native splitter owns JDBC BigDecimal/setObject boundaries; no PK values enter this graph.
    assertEquals(
        Set.of(
            "plugin_name",
            "plugin_output",
            "url",
            "username",
            "password",
            "driver",
            "fetch_size",
            "query_timeout_sec",
            "table_list",
            "split.size",
            "split.allow-sampling",
            "skip_analyze",
            "use_select_count",
            "enable_concurrent_read",
            "dialect",
            "properties"),
        source.keySet());
    assertEquals(List.of(), config.get("transform"));
    task.runtime = new RuntimeOptions(3000, 20000, 20000, 90, 1200);
    var configured = (Map<?, ?>) ((List<?>) builder.build(task).get("source")).getFirst();
    assertEquals(3000, configured.get("fetch_size"));
    assertEquals(20000, configured.get("split.size"));
    assertEquals(source.get("table_list"), configured.get("table_list"));
  }

  @Test
  void newFullAndCdcJobsUseModeSpecificRuntimeDefaultsWithoutMutatingSavedTasks() {
    var task = task();
    var builder = new SeaTunnelJobConfig(configuration());
    for (String mode : List.of("FULL", "CDC")) {
      task.mode = mode;
      String before = IntegrationModels.serialize(task);
      var config = builder.build(task);
      var environment = (Map<?, ?>) config.get("env");
      var sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
      assertEquals(mode.equals("FULL") ? 10000 : 1000, sink.get("batch_size"), mode);
      assertEquals(
          mode.equals("FULL") ? 60000 : 30000, environment.get("checkpoint.interval"), mode);
      assertEquals(
          mode.equals("FULL") ? 900000 : 300000, environment.get("checkpoint.timeout"), mode);
      assertEquals(1, environment.get("parallelism"));
      assertEquals(before, IntegrationModels.serialize(task));
    }
  }

  @Test
  void configuredFetchBatchAndCheckpointsUseNativeJdbcAndDorisKeys() {
    var task = task();
    task.runtime = new RuntimeOptions(2500, 16000, 12000, 120, 1500);
    var config = new SeaTunnelJobConfig(configuration()).build(task);
    var source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    var sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
    assertEquals(2500, source.get("fetch_size"));
    assertEquals(12000, sink.get("batch_size"));
    assertFalse(sink.containsKey("doris.batch.size"));
    assertEquals(120000, ((Map<?, ?>) config.get("env")).get("checkpoint.interval"));
    assertEquals(1500000, ((Map<?, ?>) config.get("env")).get("checkpoint.timeout"));

    task = initializationTask("DAY");
    task.latestRun.windowEnd = "2024-02-15 00:00:00";
    task.initializationState.pendingEndDate = "2024-02-15";
    task.runtime = new RuntimeOptions(2500, null, 12000, 120, 1500);
    var dorisConfig = sqlBuilder(task).build(task);
    var doris = (Map<?, ?>) ((List<?>) dorisConfig.get("sink")).getFirst();
    assertEquals(12000, doris.get("doris.batch.size"));
    assertFalse(doris.containsKey("batch_size"));
    assertEquals(
        Map.of(
            "format",
            "json",
            "read_json_by_line",
            "true",
            "strict_mode",
            "true",
            "max_filter_ratio",
            "0",
            "num_as_string",
            "true"),
        doris.get("doris.config"));
  }

  @Test
  void sqlT1KeepsItsSmallBatchProfileAndDoesNotSplitCustomAggregateQueries() {
    var task = initializationTask("DAY");
    task.sourceQuery = "SELECT id, COUNT(*) AS name FROM visits WHERE day = :biz_date GROUP BY id";
    task.latestRun.windowEnd = "2024-02-15 00:00:00";
    task.initializationState.pendingEndDate = "2024-02-15";
    var config = sqlBuilder(task).build(task);
    var source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    var sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
    assertEquals(
        "SELECT id, COUNT(*) AS name FROM \"public\".visits WHERE day = '2024-02-14' GROUP BY id",
        source.get("query"));
    assertEquals(1000, source.get("fetch_size"));
    assertEquals(100, sink.get("doris.batch.size"));
    assertEquals(60000, ((Map<?, ?>) config.get("env")).get("checkpoint.interval"));
    assertEquals(900000, ((Map<?, ?>) config.get("env")).get("checkpoint.timeout"));
    for (String option : List.of("table_list", "partition_column", "table_path", "split.size")) {
      assertFalse(source.containsKey(option), option);
    }
    task.runtime = new RuntimeOptions(null, 8192, null, null, null);
    assertThrows(IntegrationException.class, () -> sqlBuilder(task).build(task));
  }

  @Test
  void initializationUsesThePersistedPartialMonthAndRetriesExactlyTheSameWindow() {
    var task = initializationTask("MONTH");
    var builder = sqlBuilder(task);
    var first = builder.build(task, task.latestRun);
    String query = sourceQuery(first);
    assertTrue(query.contains("'2024-02-14 00:00:00'"));
    assertTrue(query.contains("'2024-03-01 00:00:00'"));
    assertFalse(query.contains("'2024-02-15 00:00:00'"));
    task.latestRun.status = "FAILED";
    task.latestRun.jobId = "123456790";
    task.initializationState.pendingJobId = task.latestRun.jobId;
    assertEquals(query, sourceQuery(builder.build(task, task.latestRun)));
    var sink = (Map<?, ?>) ((List<?>) first.get("sink")).getFirst();
    assertEquals("Doris", sink.get("plugin_name"));
    assertEquals(false, sink.get("sink.enable-delete"));
    assertEquals("APPEND_DATA", sink.get("data_save_mode"));
  }

  @Test
  void initializationDayKeepsTheLegacyBusinessDateParameter() {
    var task = initializationTask("DAY");
    task.sourceQuery = "SELECT id, name FROM visits WHERE day = :biz_date";
    task.initializationState.pendingEndDate = "2024-02-15";
    task.latestRun.windowEnd = "2024-02-15 00:00:00";
    assertTrue(sourceQuery(sqlBuilder(task).build(task)).contains("day = '2024-02-14'"));
  }

  @Test
  void initializationRejectsStaleOrForgedRunIdentityAndWindowContext() {
    List<Consumer<IntegrationTask>> corruptions =
        List.of(
            task -> task.latestRun.initializationId = "another-initialization",
            task -> task.initializationState.id = null,
            task -> task.initialization.enabled = false,
            task -> task.initialization.batchUnit = "DAY",
            task -> task.initialization.startDate = "2024-02-13",
            task -> task.initializationState.status = "COMPLETED",
            task -> task.initializationState.completedBatches = 2,
            task -> task.latestRun.batchIndex = null,
            task -> task.latestRun.batchIndex = 2L,
            task -> task.initializationState.pendingJobId = "old-job",
            task -> task.latestRun.jobId = null,
            task -> task.initializationState.pendingStartDate = "2024-02-13",
            task -> task.initializationState.pendingEndDate = "2024-03-02",
            task -> task.latestRun.businessDate = "2024-02-15",
            task -> task.latestRun.windowStart = "2024-02-15 00:00:00",
            task -> task.latestRun.windowEnd = "2024-03-01 00:00:00' OR 1=1 -- ",
            task -> task.latestRun.windowEnd = "2024-03-01 01:00:00",
            task -> task.latestRun.windowEnd = "2024-03-01T00:00:00",
            task -> task.latestRun.windowEnd = "2024-2-29 00:00:00",
            task -> task.latestRun.windowEnd = null,
            task -> task.latestRun.trigger = "DAILY",
            task -> task.initializationState = null);
    for (int i = 0; i < corruptions.size(); i++) {
      var task = initializationTask("MONTH");
      corruptions.get(i).accept(task);
      assertThrows(
          IntegrationException.class,
          () -> sqlBuilder(task).build(task, task.latestRun),
          "corruption " + i);
    }
  }

  @Test
  void initializationRejectsAWindowOverOneMonthEvenWhenTheSavedContextAgrees() {
    var task = initializationTask("MONTH");
    task.latestRun.windowEnd = "2024-03-17 00:00:00";
    task.initializationState.pendingEndDate = "2024-03-17";
    assertThrows(IntegrationException.class, () -> sqlBuilder(task).build(task));
  }

  @Test
  void monthlyInitializationRequiresBothRealWindowParametersAndRejectsBusinessDate() {
    for (String predicate :
        List.of(
            "day = :biz_date",
            "updated_at >= :window_start",
            "updated_at < :window_end",
            "updated_at >= :window_start AND updated_at < :window_end AND day = :biz_date")) {
      var task = initializationTask("MONTH");
      task.sourceQuery = "SELECT id, name FROM visits WHERE " + predicate;
      assertThrows(IntegrationException.class, () -> sqlBuilder(task).build(task), predicate);
    }
  }

  @Test
  void dailyRunsRejectSavedWindowsThatDoNotMatchTheirOneDayBusinessDate() {
    var task = initializationTask("DAY");
    task.initialization = null;
    task.initializationState = null;
    task.latestRun.initializationId = null;
    task.latestRun.batchIndex = null;
    task.latestRun.trigger = "DAILY";
    task.latestRun.windowEnd = "2024-02-15 00:00:00";
    var builder = sqlBuilder(task);
    assertTrue(sourceQuery(builder.build(task)).contains("'2024-02-15 00:00:00'"));
    task.latestRun.windowEnd = "2024-03-01 00:00:00";
    assertThrows(IntegrationException.class, () -> builder.build(task));
    task.latestRun.windowStart = null;
    task.latestRun.windowEnd = null;
    assertTrue(sourceQuery(builder.build(task)).contains("'2024-02-15 00:00:00'"));
    task.latestRun.windowStart = "2024-02-14 00:00:00";
    assertThrows(IntegrationException.class, () -> builder.build(task));
  }

  @Test
  void sqlT1SupportsEverySourceDialectAndUsesTheExplicitRunForDorisLabels() {
    var targetDefinition = DorisTableDdlTest.targetConnection();
    targetDefinition.host = "doris.example.internal";
    targetDefinition.port = 9030;
    targetDefinition.dorisHttpPort = 8030;
    targetDefinition.username = "synthetic-writer";
    targetDefinition.tlsMode = "DISABLED";
    var target = new IntegrationConfiguration.JdbcEndpoint(targetDefinition, "synthetic-password");
    for (String type : List.of("Oracle", "Mssql", "Mysql", "Postgres")) {
      var definition = new ConnectionDefinition();
      definition.copyInput(IntegrationConnectionsTest.input(type));
      definition.id = "01234567-1234-1234-1234-123456789012";
      var sourceEndpoint =
          new IntegrationConfiguration.JdbcEndpoint(definition, "synthetic-password");
      var task = IntegrationTask.from(IntegrationValidatorTest.sqlInput());
      task.id = "12345678-1234-1234-1234-1234567890ab";
      task.sourceConnectionId = definition.id;
      task.sourceSchema = definition.schemas.getFirst();
      task.targetConnectionId = targetDefinition.id;
      task.targetSchema = "ods_validation";
      task.targetTable = "visits";
      String lower =
          switch (type) {
            case "Oracle" -> "TO_DATE(:window_start, 'YYYY-MM-DD HH24:MI:SS')";
            case "Mssql" -> "CAST(:window_start AS datetime2)";
            case "Mysql" -> "CAST(:window_start AS DATETIME)";
            default -> ":window_start::timestamp";
          };
      task.sourceQuery =
          "SELECT id, name FROM visits WHERE updated_at >= "
              + lower
              + " AND updated_at < "
              + lower.replace(":window_start", ":window_end");
      var builder =
          new SeaTunnelJobConfig(id -> id.equals(definition.id) ? sourceEndpoint : target);
      var run = new RunSummary();
      run.businessDate = "2024-02-29";
      run.jobId = "123456789";
      var config = builder.build(task, run);
      var source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
      var sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
      String query = (String) source.get("query");
      assertTrue(query.contains(sourceEndpoint.dialect().quote(task.sourceSchema) + ".visits"));
      assertTrue(query.contains("'2024-02-29 00:00:00'"));
      assertTrue(query.contains("'2024-03-01 00:00:00'"));
      assertEquals(sourceEndpoint.driver(), source.get("driver"));
      if (type.equals("Oracle")) {
        assertEquals("HospitalOracle", source.get("dialect"));
        assertEquals(
            "false", ((Map<?, ?>) source.get("properties")).get("oracle.jdbc.timezoneAsRegion"));
      }
      String firstLabel = (String) sink.get("sink.label-prefix");
      run.jobId = "123456790";
      var retrySink = (Map<?, ?>) ((List<?>) builder.build(task, run).get("sink")).getFirst();
      assertNotEquals(firstLabel, retrySink.get("sink.label-prefix"));
      assertEquals("2024-02-29", run.businessDate);
      var initialization = initializationTask("MONTH");
      task.initialization = initialization.initialization;
      task.initializationState = initialization.initializationState;
      task.latestRun = initialization.latestRun;
      RunSummary restoredRun =
          IntegrationModels.deserialize(IntegrationModels.serialize(task)).latestRun;
      String initializationQuery = sourceQuery(builder.build(task, restoredRun));
      assertTrue(initializationQuery.contains("'2024-02-14 00:00:00'"), type);
      assertTrue(initializationQuery.contains("'2024-03-01 00:00:00'"), type);
      assertTrue(
          initializationQuery.contains(
              sourceEndpoint.dialect().quote(task.sourceSchema) + ".visits"),
          type);
    }
  }

  @Test
  void sqlT1UsesSavedDateRawQueryAndAliasTransformWithNativeDorisUpsert() {
    var task = IntegrationTask.from(IntegrationValidatorTest.sqlInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    task.sourceQuery =
        "WITH v AS (SELECT id, name FROM visits WHERE day = :biz_date) SELECT id, name FROM v";
    task.targetConnectionId = "doris-ods-validation";
    task.targetSchema = "ods_validation";
    task.targetTable = "visits";
    task.fieldMappings =
        List.of(new FieldMapping("id", "visit_id"), new FieldMapping("name", "患者姓名"));
    var definition = DorisTableDdlTest.targetConnection();
    definition.host = "doris.example.internal";
    definition.port = 9030;
    definition.dorisHttpPort = 8030;
    definition.username = "synthetic-writer";
    definition.tlsMode = "DISABLED";
    var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, "synthetic-password");
    var builder =
        new SeaTunnelJobConfig(
            id -> id.equals(task.targetConnectionId) ? endpoint : configuration().endpoint(id));
    var run = new RunSummary();
    run.businessDate = "2025-12-31";
    run.jobId = "123456789";
    var config = builder.build(task, run);
    var source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    var transform = (Map<?, ?>) ((List<?>) config.get("transform")).getFirst();
    var sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
    assertEquals(
        "WITH v AS (SELECT id, name FROM \"public\".visits WHERE day = '2025-12-31') SELECT id, name FROM v",
        source.get("query"));
    assertEquals("SELECT `id` AS `visit_id`, `name` AS `患者姓名` FROM source", transform.get("query"));
    assertEquals("mapped", sink.get("plugin_input"));
    assertEquals("Doris", sink.get("plugin_name"));
    assertEquals(false, sink.get("sink.enable-delete"));
    assertEquals("APPEND_DATA", sink.get("data_save_mode"));
    assertEquals("BATCH", ((Map<?, ?>) config.get("env")).get("job.mode"));
    assertThrows(IntegrationException.class, () -> builder.build(task));
    task.targetConnectionId = "synthetic-ods";
    task.targetSchema = "public";
    assertEquals(
        "MODE_UNSUPPORTED",
        assertThrows(
                IntegrationException.class,
                () -> new SeaTunnelJobConfig(configuration()).build(task, run))
            .code());
  }

  @Test
  void businessJdbcQueriesUseEachDatabaseDialectAndRejectUnimplementedCdc() throws Exception {
    for (String type : List.of("Oracle", "Mssql", "Mysql", "Postgres")) {
      var input = IntegrationConnectionsTest.input(type);
      ConnectionDefinition definition = new ConnectionDefinition();
      definition.copyInput(input);
      definition.id = "01234567-1234-1234-1234-123456789012";
      var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, input.password);
      assertTrue(
          Class.forName(endpoint.driver()).getDeclaredConstructor().newInstance()
              instanceof java.sql.Driver);
      IntegrationTask task = task();
      task.sourceConnectionId = definition.id;
      task.sourceSchema = definition.schemas.getFirst();
      var builder =
          new SeaTunnelJobConfig(
              id -> id.equals(definition.id) ? endpoint : configuration().endpoint(id));
      var config = builder.build(task);
      Map<?, ?> source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
      assertEquals(endpoint.driver(), source.get("driver"));
      if (type.equals("Oracle")) {
        assertEquals("HospitalOracle", source.get("dialect"));
        assertEquals(
            "false", endpoint.connectionProperties().getProperty("oracle.jdbc.timezoneAsRegion"));
        assertEquals(
            "false", ((Map<?, ?>) source.get("properties")).get("oracle.jdbc.timezoneAsRegion"));
        task.sourceConnectionId = "synthetic-source";
        task.sourceSchema = "public";
        task.targetConnectionId = definition.id;
        task.targetSchema = definition.schemas.getFirst();
        var targetConfig = builder.build(task);
        Map<?, ?> sink = (Map<?, ?>) ((List<?>) targetConfig.get("sink")).getFirst();
        assertEquals(
            "false", ((Map<?, ?>) sink.get("properties")).get("oracle.jdbc.timezoneAsRegion"));
        task.sourceConnectionId = definition.id;
        task.sourceSchema = definition.schemas.getFirst();
        task.targetConnectionId = "synthetic-ods";
        task.targetSchema = "public";
      }
      assertEquals(
          "SELECT "
              + endpoint.dialect().quote("id")
              + " AS "
              + endpoint.dialect().quote("id")
              + ", "
              + endpoint.dialect().quote("name")
              + " AS "
              + endpoint.dialect().quote("name")
              + " FROM "
              + endpoint.dialect().quote(task.sourceSchema)
              + "."
              + endpoint.dialect().quote("patient"),
          type.equals("Oracle")
              ? ((Map<?, ?>) ((List<?>) source.get("table_list")).getFirst()).get("query")
              : source.get("query"));
      if (!type.equals("Postgres")) {
        task.mode = "CDC";
        assertEquals(
            "MODE_UNSUPPORTED",
            assertThrows(IntegrationException.class, () -> builder.build(task)).code());
      }
    }
  }

  @Test
  void fullQueryUsesOnlyValidatedQuotedColumnsAndAliases() {
    IntegrationTask task = task();
    task.fieldMappings = List.of(new FieldMapping("id", "visit_id"));
    Map<String, Object> config = new SeaTunnelJobConfig(configuration()).build(task);
    Map<?, ?> source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    assertEquals("SELECT \"id\" AS \"visit_id\" FROM \"public\".\"patient\"", source.get("query"));
    assertEquals("Jdbc", source.get("plugin_name"));
    assertFalse(config.containsKey("params"));
  }

  @Test
  void cdcUsesStableTaskSlotPgoutputAndPrimaryKeyUpsert() {
    IntegrationTask task = task();
    task.mode = "CDC";
    Map<String, Object> config = new SeaTunnelJobConfig(configuration()).build(task);
    Map<?, ?> source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    Map<?, ?> sink = (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
    assertEquals("Postgres-CDC", source.get("plugin_name"));
    assertEquals("pgoutput", source.get("decoding.plugin.name"));
    assertEquals("hospital_123456781234123412341234567890ab", source.get("slot.name"));
    assertEquals(List.of("source_db.public.patient"), source.get("table-names"));
    assertEquals(List.of("id"), sink.get("primary_keys"));
    assertEquals(true, sink.get("enable_upsert"));
    assertEquals(false, sink.get("is_exactly_once"));
    assertTrue(config.containsKey("transform"));
  }

  @Test
  void cdcPassesTlsVerificationToTheDebeziumSnapshotAndWalConfiguration() {
    var input = IntegrationConnectionsTest.input("Postgres");
    input.tlsMode = "VERIFY";
    var definition = new ConnectionDefinition();
    definition.copyInput(input);
    definition.id = "01234567-1234-1234-1234-123456789012";
    var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, input.password);
    IntegrationTask task = task();
    task.mode = "CDC";
    task.sourceConnectionId = definition.id;
    var builder =
        new SeaTunnelJobConfig(
            id -> id.equals(definition.id) ? endpoint : configuration().endpoint(id));
    var config = builder.build(task);
    Map<?, ?> source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    assertEquals("verify-full", ((Map<?, ?>) source.get("debezium")).get("database.sslmode"));
  }

  @Test
  void cdcQuotesLiteralSchemaAndTableNamesForDebeziumRegexFilters() {
    IntegrationTask task = task();
    task.mode = "CDC";
    task.sourceTable = "patient$";
    var config = new SeaTunnelJobConfig(configuration()).build(task);
    Map<?, ?> source = (Map<?, ?>) ((List<?>) config.get("source")).getFirst();
    Map<?, ?> dbz = (Map<?, ?>) source.get("debezium");
    var filter = java.util.regex.Pattern.compile((String) dbz.get("table.include.list"));
    assertTrue(filter.matcher("public.patient$").matches());
    assertFalse(filter.matcher("public.patient").matches());
    assertFalse(filter.matcher("other.patient$").matches());
    assertEquals("disable", dbz.get("database.sslmode"));
  }

  @Test
  void builderRejectsDottedPostgresDatabaseCdcBeforeSubmission() {
    var input = IntegrationConnectionsTest.input("Postgres");
    input.database = "business.db";
    var definition = new ConnectionDefinition();
    definition.copyInput(input);
    definition.id = "01234567-1234-1234-1234-123456789012";
    var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, input.password);
    var task = task();
    task.sourceConnectionId = definition.id;
    task.mode = "CDC";
    var builder =
        new SeaTunnelJobConfig(
            id -> id.equals(definition.id) ? endpoint : configuration().endpoint(id));
    assertEquals(
        "MODE_UNSUPPORTED",
        assertThrows(IntegrationException.class, () -> builder.build(task)).code());
    task.mode = "FULL";
    assertEquals(
        "Jdbc",
        ((Map<?, ?>) ((List<?>) builder.build(task).get("source")).getFirst()).get("plugin_name"));
  }

  private IntegrationTask task() {
    IntegrationTask task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    return task;
  }

  private Map<String, Object> legacyCdcConfig(boolean doris) {
    var source =
        Map.ofEntries(
            Map.entry("plugin_name", "Postgres-CDC"),
            Map.entry("plugin_output", "source"),
            Map.entry("url", "jdbc:postgresql://source:5432/source_db"),
            Map.entry("username", "source_user"),
            Map.entry("password", "synthetic-source-secret"),
            Map.entry("database-names", List.of("source_db")),
            Map.entry("schema-names", List.of("public")),
            Map.entry("table-names", List.of("source_db.public.patient")),
            Map.entry("decoding.plugin.name", "pgoutput"),
            Map.entry("startup.mode", "initial"),
            Map.entry("slot.name", "hospital_123456781234123412341234567890ab"),
            Map.entry(
                "debezium",
                Map.of(
                    "publication.name", "hospital_123456781234123412341234567890ab",
                    "publication.autocreate.mode", "filtered",
                    "slot.drop.on.stop", "false",
                    "database.sslmode", "disable",
                    "schema.include.list", "\\Qpublic\\E",
                    "table.include.list", "\\Qpublic\\E\\.\\Qpatient\\E")));
    Map<String, Object> sink = new LinkedHashMap<>();
    sink.put("plugin_input", "mapped");
    sink.put("username", doris ? "synthetic-writer" : "target_user");
    sink.put("password", doris ? "synthetic-password" : "synthetic-target-secret");
    if (doris) {
      sink.put("plugin_name", "Doris");
      sink.put("fenodes", "doris.example.internal:8030");
      sink.put("query-port", 9030);
      sink.put("database", "ods_validation");
      sink.put("table", "visits");
      sink.put("sink.enable-2pc", false);
      sink.put("sink.enable-delete", true);
      sink.put("sink.label-prefix", "h9292152de3cc85b7");
      sink.put("doris.batch.size", 100);
      sink.put(
          "doris.config",
          Map.of(
              "format",
              "json",
              "read_json_by_line",
              "true",
              "strict_mode",
              "true",
              "max_filter_ratio",
              "0"));
    } else {
      sink.put("plugin_name", "Jdbc");
      sink.put("url", "jdbc:postgresql://target:5432/target_db");
      sink.put("driver", "org.postgresql.Driver");
      sink.put("generate_sink_sql", true);
      sink.put("database", "target_db");
      sink.put("table", "public.patient_ods");
      sink.put("primary_keys", List.of("id"));
      sink.put("enable_upsert", true);
      sink.put("is_exactly_once", false);
      sink.put("batch_size", 100);
      sink.put("batch_interval_ms", 1000);
    }
    sink.put("schema_save_mode", "IGNORE");
    sink.put("data_save_mode", "APPEND_DATA");
    return Map.of(
        "env",
        Map.of(
            "job.mode",
            "STREAMING",
            "parallelism",
            1,
            "checkpoint.interval",
            5000,
            "checkpoint.timeout",
            60000,
            "checkpoint.retain-after-job-cancelled",
            true),
        "source",
        List.of(source),
        "transform",
        List.of(
            Map.of(
                "plugin_name",
                "Sql",
                "plugin_input",
                "source",
                "plugin_output",
                "mapped",
                "query",
                "SELECT `id` AS `id`, `name` AS `name` FROM source")),
        "sink",
        List.of(sink));
  }

  private IntegrationTask initializationTask(String batchUnit) {
    var task = IntegrationTask.from(IntegrationValidatorTest.sqlInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    task.targetConnectionId = "doris-ods-validation";
    task.targetSchema = "ods_validation";
    task.targetTable = "visits";
    task.sourceQuery =
        "SELECT id, name FROM visits WHERE updated_at >= :window_start AND updated_at < :window_end";
    task.initialization = new IntegrationModels.InitializationConfig();
    task.initialization.enabled = true;
    task.initialization.startDate = "2024-02-14";
    task.initialization.batchUnit = batchUnit;
    task.schedule = new IntegrationModels.ScheduleConfig();
    task.schedule.enabled = true;
    task.initializationState = new IntegrationModels.InitializationState();
    task.initializationState.id = "initialization-1";
    task.initializationState.status = "RUNNING";
    task.initializationState.startDate = "2024-02-14";
    task.initializationState.cutoffDate = "2024-04-01";
    task.initializationState.nextDate = "2024-02-14";
    task.initializationState.batchUnit = batchUnit;
    task.initializationState.totalBatches = 2;
    task.initializationState.pendingStartDate = "2024-02-14";
    task.initializationState.pendingEndDate = "2024-03-01";
    task.initializationState.pendingJobId = "123456789";
    task.latestRun = new RunSummary();
    task.latestRun.jobId = task.initializationState.pendingJobId;
    task.latestRun.businessDate = "2024-02-14";
    task.latestRun.windowStart = "2024-02-14 00:00:00";
    task.latestRun.windowEnd = "2024-03-01 00:00:00";
    task.latestRun.trigger = "INITIALIZATION";
    task.latestRun.initializationId = task.initializationState.id;
    task.latestRun.batchIndex = 1L;
    return task;
  }

  private SeaTunnelJobConfig sqlBuilder(IntegrationTask task) {
    var definition = DorisTableDdlTest.targetConnection();
    definition.host = "doris.example.internal";
    definition.port = 9030;
    definition.dorisHttpPort = 8030;
    definition.username = "synthetic-writer";
    definition.tlsMode = "DISABLED";
    var endpoint = new IntegrationConfiguration.JdbcEndpoint(definition, "synthetic-password");
    return new SeaTunnelJobConfig(
        id -> id.equals(task.targetConnectionId) ? endpoint : configuration().endpoint(id));
  }

  private String sourceQuery(Map<String, Object> config) {
    return (String) ((Map<?, ?>) ((List<?>) config.get("source")).getFirst()).get("query");
  }

  private IntegrationConfiguration configuration() {
    return IntegrationConfiguration.fromValues(
        Map.of(
            "HOSPITAL_INTEGRATION_ENABLED", "true",
            "HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801",
            "HOSPITAL_SOURCE_JDBC_URL", "jdbc:postgresql://source:5432/source_db",
            "HOSPITAL_SOURCE_JDBC_USER", "source_user",
            "HOSPITAL_SOURCE_JDBC_PASSWORD", "synthetic-source-secret",
            "HOSPITAL_TARGET_JDBC_URL", "jdbc:postgresql://target:5432/target_db",
            "HOSPITAL_TARGET_JDBC_USER", "target_user",
            "HOSPITAL_TARGET_JDBC_PASSWORD", "synthetic-target-secret"));
  }
}
