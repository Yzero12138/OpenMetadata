package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;

/** Opt-in real Doris fixture; only new UUID-prefixed tables in the isolated validation database. */
@EnabledIfEnvironmentVariable(named = "HOSPITAL_DORIS_TEST_JDBC_URL", matches = ".+")
class DorisJdbcTest {
  @Test
  @Timeout(90)
  void generatedDdlHasRealUniqueKeysUnicodeCapacityAndCannotReplaceExistingTables()
      throws Exception {
    var endpoint = endpoint();
    var target = definition();
    String table = "codex_doris_test_" + UUID.randomUUID().toString().replace("-", "");
    String duplicate = "codex_doris_dup_" + UUID.randomUUID().toString().replace("-", "");
    var input =
        new TargetTableInput(
            "synthetic-source",
            "public",
            "visits",
            target.database,
            table,
            List.of(new FieldMapping("id", "id"), new FieldMapping("patient_name", "patient_name")),
            null);
    var preview =
        DorisTableDdl.preview(
            input, DorisTableDdlTest.source(), DorisTableDdlTest.sourceConnection(), target, 1);
    var executor = new DorisDdlExecutor();
    var inspector = new JdbcInspector(id -> endpoint);
    assertTrue(inspector.test(target.id));
    try (Connection connection =
        DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties())) {
      executor.requireAbsent(endpoint, target.database, table);
      executor.create(endpoint, preview);
      var inspected = inspector.table(target.id, target.database, table);
      assertEquals(2, inspected.columns().size());
      assertEquals(
          "id",
          inspected.columns().stream()
              .filter(column -> column.primaryKey())
              .findFirst()
              .orElseThrow()
              .name());
      assertEquals(100, inspected.columns().get(1).unicodeCapacity());
      var task = IntegrationValidatorTest.validInput();
      task.sourceTable = "visits";
      task.targetSchema = target.database;
      task.targetTable = table;
      task.fieldMappings = input.fieldMappings();
      assertTrue(
          IntegrationValidator.validateTables(task, DorisTableDdlTest.source(), inspected).valid());
      String unicode = "合成患者中文🙂".repeat(10);
      assertTrue(unicode.codePointCount(0, unicode.length()) <= 100);
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO "
                  + qualified(target.database, table)
                  + " (`id`, `patient_name`) VALUES (?, ?)")) {
        insert.setLong(1, 1);
        insert.setString(2, unicode);
        assertEquals(1, insert.executeUpdate());
        assertEquals(1, insert.executeUpdate());
      }
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT id, patient_name FROM " + qualified(target.database, table))) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertEquals(unicode, rows.getString(2));
        assertFalse(rows.next());
      }
      assertEquals(
          "TABLE_ALREADY_EXISTS",
          assertThrows(IntegrationException.class, () -> executor.create(endpoint, preview))
              .code());
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE "
                + qualified(target.database, duplicate)
                + " (`id` BIGINT NOT NULL, `patient_name` VARCHAR(400)) ENGINE=OLAP DUPLICATE KEY (`id`)"
                + " DISTRIBUTED BY HASH (`id`) BUCKETS 1 PROPERTIES (\"replication_num\"=\"1\")");
      }
      assertFalse(
          inspector.table(target.id, target.database, duplicate).columns().stream()
              .anyMatch(column -> column.primaryKey()));
    }
  }

  @Test
  @Timeout(90)
  @EnabledIfEnvironmentVariable(named = "HOSPITAL_DORIS_TEST_SOURCE_JDBC_URL", matches = ".+")
  void serviceRechecksLiveSourceMetadataAndConnectionVersionBeforeCreating() throws Exception {
    String sourceUrl = required("HOSPITAL_DORIS_TEST_SOURCE_JDBC_URL");
    URI sourceUri = URI.create(sourceUrl.substring(5));
    assertEquals("127.0.0.1", sourceUri.getHost());
    assertEquals("/integration_test", sourceUri.getPath());
    String sourceUser = required("HOSPITAL_DORIS_TEST_SOURCE_USER");
    String sourcePassword = required("HOSPITAL_DORIS_TEST_SOURCE_PASSWORD");
    String table = "codex_doris_api_" + UUID.randomUUID().toString().replace("-", "");
    var target = definition();
    var values = new HashMap<String, String>();
    values.put("HOSPITAL_INTEGRATION_ENABLED", "true");
    values.put("HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801");
    for (String role : List.of("SOURCE", "TARGET")) {
      values.put("HOSPITAL_" + role + "_JDBC_URL", sourceUrl);
      values.put("HOSPITAL_" + role + "_JDBC_USER", sourceUser);
      values.put("HOSPITAL_" + role + "_JDBC_PASSWORD", sourcePassword);
    }
    values.put("HOSPITAL_DORIS_ENABLED", "true");
    values.put("HOSPITAL_DORIS_HOST", target.host);
    values.put("HOSPITAL_DORIS_SQL_PORT", String.valueOf(target.port));
    values.put("HOSPITAL_DORIS_HTTP_PORT", String.valueOf(target.dorisHttpPort));
    values.put("HOSPITAL_DORIS_DATABASE", target.database);
    values.put("HOSPITAL_DORIS_USER", target.username);
    values.put("HOSPITAL_DORIS_PASSWORD", required("HOSPITAL_DORIS_TEST_PASSWORD"));
    values.put("HOSPITAL_DORIS_REPLICATION_NUM", "1");
    var service =
        new IntegrationService(
            IntegrationConfiguration.fromValues(values),
            new IntegrationConnectionsTest.MemoryTasks(),
            new IntegrationConnectionsTest.MemoryConnections());
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    var sourceInput = IntegrationConnectionsTest.input("Postgres");
    sourceInput.host = sourceUri.getHost();
    sourceInput.port = sourceUri.getPort() == -1 ? 5432 : sourceUri.getPort();
    sourceInput.database = "integration_test";
    sourceInput.username = sourceUser;
    sourceInput.password = sourcePassword;
    var source = service.createConnection(sourceInput, "synthetic-doris-api-test");
    try (var connection = DriverManager.getConnection(sourceUrl, sourceUser, sourcePassword);
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE public.\""
              + table
              + "\" (id BIGINT PRIMARY KEY, patient_name VARCHAR(100) NOT NULL)");
      try {
        var input =
            new TargetTableInput(
                source.id,
                "public",
                table,
                target.database,
                table,
                List.of(
                    new FieldMapping("id", "id"), new FieldMapping("patient_name", "patient_name")),
                null);
        var preview = service.previewTargetTable(target.id, input);
        statement.executeUpdate(
            "ALTER TABLE public.\"" + table + "\" ALTER COLUMN patient_name TYPE VARCHAR(101)");
        var stale = withHash(input, preview.previewHash());
        assertEquals(
            "TABLE_PREVIEW_STALE",
            assertThrows(
                    IntegrationException.class, () -> service.createTargetTable(target.id, stale))
                .code());
        assertEquals(
            "TABLE_NOT_FOUND",
            assertThrows(
                    IntegrationException.class,
                    () -> service.table(target.id, target.database, table))
                .code());
        var refreshed = service.previewTargetTable(target.id, input);
        var update = IntegrationConnectionsTest.update(sourceInput, source.version);
        update.displayName = "合成源连接版本变更";
        service.updateConnection(source.id, update, "synthetic-doris-api-test");
        assertEquals(
            "TABLE_PREVIEW_STALE",
            assertThrows(
                    IntegrationException.class,
                    () ->
                        service.createTargetTable(
                            target.id, withHash(input, refreshed.previewHash())))
                .code());
        var latest = service.previewTargetTable(target.id, input);
        var created = service.createTargetTable(target.id, withHash(input, latest.previewHash()));
        assertEquals(table, created.name());
        assertEquals(101, created.columns().get(1).unicodeCapacity());
        assertEquals(
            "TABLE_ALREADY_EXISTS",
            assertThrows(
                    IntegrationException.class,
                    () ->
                        service.createTargetTable(target.id, withHash(input, latest.previewHash())))
                .code());
      } finally {
        statement.executeUpdate("DROP TABLE public.\"" + table + "\"");
      }
    }
  }

  private TargetTableInput withHash(TargetTableInput input, String hash) {
    return new TargetTableInput(
        input.sourceConnectionId(),
        input.sourceSchema(),
        input.sourceTable(),
        input.targetSchema(),
        input.targetTable(),
        input.fieldMappings(),
        hash);
  }

  private JdbcEndpoint endpoint() {
    return new JdbcEndpoint(definition(), required("HOSPITAL_DORIS_TEST_PASSWORD"));
  }

  private ConnectionDefinition definition() {
    URI uri = URI.create(required("HOSPITAL_DORIS_TEST_JDBC_URL").substring(5));
    assertEquals("127.0.0.1", uri.getHost());
    assertTrue(List.of("/ods_validation", "/integration_doris_test").contains(uri.getPath()));
    assertTrue(uri.getPort() > 0);
    var target = DorisTableDdlTest.targetConnection();
    target.host = uri.getHost();
    target.port = uri.getPort();
    target.dorisHttpPort =
        Integer.parseInt(System.getenv().getOrDefault("HOSPITAL_DORIS_TEST_HTTP_PORT", "8030"));
    target.database = uri.getPath().substring(1);
    target.schemas = List.of(target.database);
    target.username = required("HOSPITAL_DORIS_TEST_USER");
    target.tlsMode = "DISABLED";
    return target;
  }

  private String qualified(String schema, String table) {
    return JdbcDialect.Doris.quote(schema) + "." + JdbcDialect.Doris.quote(table);
  }

  private String required(String name) {
    String value = System.getenv(name);
    assertTrue(
        value != null && !value.isBlank(), name + " must be configured for the opted-in fixture");
    return value;
  }
}
