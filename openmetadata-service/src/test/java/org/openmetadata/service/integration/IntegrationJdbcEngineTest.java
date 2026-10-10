package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.SqlObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.jdbi3.CollectionDAO.EntityExtensionDAO;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareAnnotationSqlLocator;

/**
 * Explicitly opted-in, disposable four-database fixture. No authenticated employee or catalog writes.
 * All JDBC endpoints use this fixture's shared loopback network; the metadata DB must be integration_test.
 */
@EnabledIfEnvironmentVariable(named = "HOSPITAL_INTEGRATION_TEST_JDBC_ENGINE_URL", matches = ".+")
class IntegrationJdbcEngineTest {
  private static final String PASSWORD = "integration-test-only";

  @Test
  @Timeout(300)
  void persistedConnectionsExtractFourDatabaseFamiliesThroughTheRealEngine() throws Exception {
    String jdbc = System.getenv("HOSPITAL_INTEGRATION_TEST_JDBC_URL");
    URI databaseUri = URI.create(jdbc.substring(5));
    assertEquals("127.0.0.1", databaseUri.getHost());
    assertEquals("/integration_test", databaseUri.getPath());
    String engineUrl = System.getenv("HOSPITAL_INTEGRATION_TEST_JDBC_ENGINE_URL");
    assertEquals("http://127.0.0.1:8080", engineUrl);
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    Jdbi database = Jdbi.create(jdbc, "postgres", PASSWORD).installPlugin(new SqlObjectPlugin());
    database
        .getConfig(SqlObjects.class)
        .setSqlLocator(new ConnectionAwareAnnotationSqlLocator("org.postgresql.Driver"));
    database.useHandle(
        handle ->
            handle.execute(
                "CREATE TABLE IF NOT EXISTS entity_extension (id varchar(36) NOT NULL, extension varchar(256) NOT NULL,"
                    + " jsonschema varchar(256), json jsonb NOT NULL, PRIMARY KEY (id, extension))"));
    database.useHandle(
        handle ->
            handle.execute(
                "DELETE FROM entity_extension WHERE extension IN ('hospital.integration.task', 'hospital.integration.connection')"));
    Map<String, String> values = new HashMap<>();
    values.put("HOSPITAL_INTEGRATION_ENABLED", "true");
    values.put("HOSPITAL_SEATUNNEL_URL", engineUrl);
    for (String role : List.of("SOURCE", "TARGET")) {
      values.put("HOSPITAL_" + role + "_JDBC_URL", jdbc);
      values.put("HOSPITAL_" + role + "_JDBC_USER", "postgres");
      values.put("HOSPITAL_" + role + "_JDBC_PASSWORD", PASSWORD);
    }
    var configuration = IntegrationConfiguration.fromValues(values);
    var taskStore = new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    var connectionStore =
        new EntityExtensionConnectionStore(() -> database.onDemand(EntityExtensionDAO.class));
    var service = new IntegrationService(configuration, taskStore, connectionStore);
    assertTrue(service.status().reachable());
    for (String type : List.of("Postgres", "Mysql", "Oracle", "Mssql")) {
      String suffix = type.toLowerCase();
      ConnectionInput source = source(type);
      if (type.equals("Mssql")) {
        var master = source(type);
        master.database = "master";
        var masterEndpoint =
            new IntegrationConfiguration.JdbcEndpoint(sourceDefinition(master), master.password);
        try (Connection connection =
                DriverManager.getConnection(
                    masterEndpoint.jdbcUrl(), masterEndpoint.connectionProperties());
            Statement statement = connection.createStatement()) {
          statement.execute("IF DB_ID(N'business') IS NULL CREATE DATABASE business");
        }
      }
      String table = type.equals("Oracle") ? "HOSPITAL_JDBC_VISIT" : "hospital_jdbc_visit";
      var endpoint =
          new IntegrationConfiguration.JdbcEndpoint(sourceDefinition(source), source.password);
      String sourceTable =
          endpoint.dialect().quote(source.schemas.getFirst())
              + "."
              + endpoint.dialect().quote(table);
      try (Connection connection =
              DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties());
          Statement statement = connection.createStatement()) {
        assertEquals(
            type.equals("Oracle") ? 11 : type.equals("Mssql") ? 15 : type.equals("Mysql") ? 8 : 17,
            connection.getMetaData().getDatabaseMajorVersion());
        String idType = type.equals("Oracle") ? "NUMBER(18)" : "BIGINT";
        String textType =
            type.equals("Oracle")
                ? "VARCHAR2(100 CHAR)"
                : type.equals("Mssql") ? "NVARCHAR(100)" : "VARCHAR(100)";
        if (type.equals("Oracle")) {
          statement.execute(
              "BEGIN EXECUTE IMMEDIATE 'DROP TABLE "
                  + sourceTable
                  + "'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
        } else {
          statement.execute("DROP TABLE IF EXISTS " + sourceTable);
        }
        statement.execute(
            "CREATE TABLE "
                + sourceTable
                + " (id "
                + idType
                + " PRIMARY KEY, patient_name "
                + textType
                + " NOT NULL)");
        for (int i = 1; i <= 100; i++) {
          statement.execute(
              "INSERT INTO "
                  + sourceTable
                  + " VALUES ("
                  + i
                  + ", "
                  + (type.equals("Mssql") ? "N" : "")
                  + "'合成患者_"
                  + i
                  + "')");
        }
      }
      String targetTable = "hospital_jdbc_ods_" + suffix;
      try (Connection connection = DriverManager.getConnection(jdbc, "postgres", PASSWORD);
          Statement statement = connection.createStatement()) {
        statement.execute("DROP TABLE IF EXISTS public." + targetTable);
        statement.execute(
            "CREATE TABLE public."
                + targetTable
                + " (id bigint PRIMARY KEY, patient_name varchar(100) NOT NULL)");
      }
      source.name = "engine_source_" + suffix;
      var registeredSource = service.createConnection(source, "synthetic-engine-test");
      ConnectionInput target = source("Postgres");
      target.name = "engine_target_" + suffix;
      target.role = "TARGET";
      var registeredTarget = service.createConnection(target, "synthetic-engine-test");
      var reopened = new IntegrationService(configuration, taskStore, connectionStore);
      assertTrue(reopened.testConnection(registeredSource.id).connected());
      assertTrue(
          reopened.tables(registeredSource.id, false).stream()
              .anyMatch(value -> value.name().equals(table) && value.columns().isEmpty()));
      var discovered = reopened.table(registeredSource.id, source.schemas.getFirst(), table);
      String id = type.equals("Oracle") ? "ID" : "id";
      String name = type.equals("Oracle") ? "PATIENT_NAME" : "patient_name";
      assertTrue(
          discovered.columns().stream()
              .anyMatch(column -> column.name().equals(id) && column.primaryKey()));
      var input = IntegrationValidatorTest.validInput();
      input.name = "jdbc_engine_" + suffix;
      input.displayName = "Synthetic JDBC " + type;
      input.sourceConnectionId = registeredSource.id;
      input.targetConnectionId = registeredTarget.id;
      input.sourceSchema = source.schemas.getFirst();
      input.sourceTable = table;
      input.targetSchema = "public";
      input.targetTable = targetTable;
      input.mode = "FULL";
      input.primaryKey = id;
      input.fieldMappings =
          List.of(new FieldMapping(id, "id"), new FieldMapping(name, "patient_name"));
      var validation = reopened.validate(input);
      assertTrue(validation.valid(), () -> type + ": " + validation.errors());
      IntegrationTask task = reopened.create(input, "synthetic-engine-test");
      task = reopened.run(task.id, false, "synthetic-engine-test");
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (!List.of("FINISHED", "FAILED", "CANCELED").contains(task.latestRun.status)
          && System.nanoTime() < deadline) {
        Thread.sleep(1000);
        task = reopened.task(task.id, "synthetic-engine-test");
      }
      assertEquals("FINISHED", task.latestRun.status, type + ": " + task.latestRun.errorCode);
      try (Connection connection = DriverManager.getConnection(jdbc, "postgres", PASSWORD);
          Statement statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT count(*), min(id), max(id), count(*) FILTER (WHERE patient_name = '合成患者_' || id) FROM public."
                      + targetTable)) {
        assertTrue(rows.next());
        assertEquals(100, rows.getInt(1), type);
        assertEquals(1, rows.getInt(2), type);
        assertEquals(100, rows.getInt(3), type);
        assertEquals(100, rows.getInt(4), type);
      }
      System.out.println(
          "Verified real "
              + type
              + " FULL: persisted connection, scoped metadata, 100 exact rows through SeaTunnel");
    }
  }

  private IntegrationModels.ConnectionDefinition sourceDefinition(ConnectionInput input) {
    var definition = new IntegrationModels.ConnectionDefinition();
    definition.copyInput(input);
    definition.id = "synthetic-engine-fixture";
    return definition;
  }

  static ConnectionInput source(String type) {
    var input = IntegrationConnectionsTest.input(type);
    input.host = "127.0.0.1";
    input.password = PASSWORD;
    switch (type) {
      case "Postgres" -> {
        input.database = "integration_test";
        input.username = "postgres";
      }
      case "Mysql" -> {
        input.username = "root";
        input.tlsMode = "VERIFY";
      }
      case "Oracle" -> {
        input.database = "XE";
        input.databaseVersion = "11g";
        input.username = "SYSTEM";
        input.schemas = List.of("SYSTEM");
        input.oracleConnectionType = "SID";
      }
      case "Mssql" -> {
        input.username = "sa";
        input.password = "Synthetic_Test_2026!";
      }
      default -> throw new IllegalArgumentException();
    }
    return input;
  }
}
