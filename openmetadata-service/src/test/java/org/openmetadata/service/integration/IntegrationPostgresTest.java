package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.SqlObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.UpdateInput;
import org.openmetadata.service.jdbi3.CollectionDAO.EntityExtensionDAO;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareAnnotationSqlLocator;

@EnabledIfEnvironmentVariable(named = "HOSPITAL_INTEGRATION_TEST_JDBC_URL", matches = ".+")
class IntegrationPostgresTest {
  private static final String PASSWORD = "integration-test-only";
  private static Jdbi database;
  private static IntegrationConfiguration configuration;

  @BeforeAll
  static void prepareDatabase() throws Exception {
    String jdbcUrl = System.getenv("HOSPITAL_INTEGRATION_TEST_JDBC_URL");
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS entity_extension (id varchar(36) NOT NULL, extension varchar(256) NOT NULL,"
              + " jsonschema varchar(256), json jsonb NOT NULL, PRIMARY KEY (id, extension))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS public.patient (id bigint PRIMARY KEY, name varchar(100))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS public.patient_ods (id bigint PRIMARY KEY, name varchar(100))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS public.patient_ods_other (id bigint PRIMARY KEY, name varchar(100))");
      statement.execute("CREATE SCHEMA IF NOT EXISTS restricted");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS restricted.hidden_patient (id bigint PRIMARY KEY)");
    }
    database = Jdbi.create(jdbcUrl, "postgres", PASSWORD).installPlugin(new SqlObjectPlugin());
    database
        .getConfig(SqlObjects.class)
        .setSqlLocator(new ConnectionAwareAnnotationSqlLocator("org.postgresql.Driver"));
    configuration = IntegrationConfiguration.fromValues(values(jdbcUrl));
  }

  @BeforeEach
  void clearIntegrationTasks() {
    database.useHandle(
        handle ->
            handle.execute(
                "DELETE FROM entity_extension WHERE extension = ?",
                EntityExtensionTaskStore.EXTENSION));
  }

  @Test
  void taskSurvivesAReopenedNativeExtensionDaoWithoutCredentials() throws Exception {
    EntityExtensionTaskStore first =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    IntegrationTask task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = UUID.randomUUID().toString();
    task.version = 1;
    task.createdAt = System.currentTimeMillis();
    task.updatedAt = task.createdAt;
    task.updatedBy = "synthetic-admin";
    first.save(task);
    EntityExtensionTaskStore reopened =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    IntegrationTask loaded = reopened.get(UUID.fromString(task.id));
    assertEquals(task.name, loaded.name);
    assertEquals(task.version, loaded.version);
    assertTrue(reopened.list().stream().anyMatch(value -> value.id.equals(task.id)));
    String stored =
        database
            .onDemand(EntityExtensionDAO.class)
            .getExtension(UUID.fromString(task.id), EntityExtensionTaskStore.EXTENSION);
    assertFalse(stored.contains(PASSWORD));
    assertFalse(stored.contains("jdbc:"));
  }

  @Test
  void inspectorReadsRealTypesAndPrimaryKeysOnlyInAllowedSchema() {
    PostgresInspector inspector = new PostgresInspector(configuration);
    assertTrue(inspector.test("synthetic-source"));
    var tables = inspector.tables("synthetic-source");
    assertTrue(tables.stream().allMatch(table -> table.schema().equals("public")));
    assertFalse(tables.stream().anyMatch(table -> table.name().equals("hidden_patient")));
    var patient = inspector.table("synthetic-source", "public", "patient");
    assertNotNull(patient);
    assertTrue(
        patient.columns().stream()
            .anyMatch(column -> column.name().equals("id") && column.primaryKey()));
    assertEquals("int8", patient.columns().getFirst().dataType());
  }

  @Test
  void managedConnectionSurvivesDaoReopenAndDiscoversRealScopedTables() throws Exception {
    org.openmetadata.service.fernet.Fernet.getInstance()
        .setFernetKey(java.util.Base64.getUrlEncoder().encodeToString(new byte[32]));
    String jdbcUrl = System.getenv("HOSPITAL_INTEGRATION_TEST_JDBC_URL");
    java.net.URI uri = java.net.URI.create(jdbcUrl.substring(5));
    var connectionStore =
        new EntityExtensionConnectionStore(() -> database.onDemand(EntityExtensionDAO.class));
    var tasks = new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    var registry = new IntegrationConnections(configuration, connectionStore, tasks);
    var input = IntegrationConnectionsTest.input("Postgres");
    input.name = "managed_" + UUID.randomUUID().toString().replace("-", "");
    input.host = uri.getHost();
    input.port = uri.getPort();
    input.database = uri.getPath().substring(1);
    input.username = "postgres";
    input.password = PASSWORD;
    input.schemas = List.of("restricted");
    var created = registry.create(input, "synthetic-admin");
    var reopened =
        new IntegrationConnections(
            configuration,
            new EntityExtensionConnectionStore(() -> database.onDemand(EntityExtensionDAO.class)),
            tasks);
    var inspector = new JdbcInspector(reopened::endpoint);
    assertTrue(inspector.test(created.id));
    var options = inspector.tables(created.id, false);
    assertTrue(options.stream().anyMatch(table -> "hidden_patient".equals(table.name())));
    assertTrue(
        options.stream()
            .allMatch(table -> "restricted".equals(table.schema()) && table.columns().isEmpty()));
    assertTrue(
        inspector
            .table(created.id, "restricted", "hidden_patient")
            .columns()
            .getFirst()
            .primaryKey());
    assertEquals(
        "SCHEMA_NOT_ALLOWED",
        assertThrows(
                IntegrationException.class, () -> inspector.table(created.id, "public", "patient"))
            .code());
    String stored =
        database
            .onDemand(EntityExtensionDAO.class)
            .getExtension(UUID.fromString(created.id), EntityExtensionConnectionStore.EXTENSION);
    assertFalse(stored.contains(PASSWORD));
    assertTrue(stored.contains("fernet:"));
    reopened.delete(created.id, created.version);
  }

  @Test
  void inspectorRejectsUnknownConnectionsAndInjectedIdentifiers() {
    PostgresInspector inspector = new PostgresInspector(configuration);
    assertThrows(IntegrationException.class, () -> inspector.tables("production"));
    assertThrows(
        IntegrationException.class,
        () -> inspector.table("synthetic-source", "restricted", "hidden_patient"));
    assertThrows(
        IntegrationException.class,
        () -> inspector.table("synthetic-source", "public", "patient\";drop table patient"));
  }

  @Test
  void reconcilesUncertainSubmissionUsingPersistedIdWithoutSubmittingAgain() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      engine.failFirstSubmission = true;
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      engine.taskId = task.id;
      IntegrationTask uncertain = service.run(task.id, false, "synthetic-admin");
      assertEquals("SUBMISSION_UNKNOWN", uncertain.latestRun.status);
      String stableId = uncertain.latestRun.jobId;
      assertTrue(engine.persistedBeforeSubmission.get());
      IntegrationTask reconciled = service.run(task.id, false, "synthetic-admin");
      assertEquals("RUNNING", reconciled.latestRun.status);
      assertEquals(stableId, reconciled.latestRun.jobId);
      assertEquals(1, engine.submittedIds.size());
      engine.unavailable.set(true);
      IntegrationTask disconnected = service.task(task.id, "synthetic-admin");
      assertEquals("RUNNING", disconnected.latestRun.status);
      assertEquals("ENGINE_UNAVAILABLE", disconnected.latestRun.errorCode);
      UpdateInput update = new UpdateInput();
      update.copyInput(disconnected);
      update.version = disconnected.version;
      assertEquals(
          "TASK_ACTIVE",
          assertThrows(
                  IntegrationException.class,
                  () -> service.update(task.id, update, "synthetic-admin"))
              .code());
    }
  }

  @Test
  void retriesAuthoritativelyMissingUncertainJobWithTheSameId() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      engine.failFirstSubmission = true;
      engine.missing.set(true);
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      engine.taskId = task.id;
      String stableId = service.run(task.id, false, "synthetic-admin").latestRun.jobId;
      IntegrationTask retried = service.run(task.id, false, "synthetic-admin");
      assertEquals("SUBMITTED", retried.latestRun.status);
      assertEquals(stableId, retried.latestRun.jobId);
      assertEquals(List.of(stableId, stableId), engine.submittedIds);
    }
  }

  @Test
  void requiresRealCompletedSavepointAndKeepsRestoreConfigurationImmutable() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      var input = uniqueInput();
      input.mode = "CDC";
      IntegrationTask task = service.create(input, "synthetic-admin");
      engine.taskId = task.id;
      String stableId = service.run(task.id, false, "synthetic-admin").latestRun.jobId;
      engine.state.set("SAVEPOINT_DONE");
      engine.savepointCompleted.set(false);
      IntegrationTask pending = service.task(task.id, "synthetic-admin");
      assertFalse(pending.latestRun.canResume);
      assertEquals("SAVEPOINT_UNAVAILABLE", pending.latestRun.errorCode);
      UpdateInput update = new UpdateInput();
      update.copyInput(pending);
      update.version = pending.version;
      assertEquals(
          "TASK_ACTIVE",
          assertThrows(
                  IntegrationException.class,
                  () -> service.update(task.id, update, "synthetic-admin"))
              .code());
      engine.savepointCompleted.set(true);
      IntegrationTask ready = service.task(task.id, "synthetic-admin");
      assertTrue(ready.latestRun.canResume);
      IntegrationTask resumed = service.run(task.id, true, "synthetic-admin");
      assertEquals("RESUMING", resumed.latestRun.status);
      assertEquals(stableId, resumed.latestRun.jobId);
      assertTrue(engine.submittedQueries.getLast().endsWith("isStartWithSavePoint=true"));
      assertEquals(List.of(stableId, stableId), engine.submittedIds);
    }
  }

  @Test
  void rejectsStaleVersionWithRealDurableStorage() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      UpdateInput first = new UpdateInput();
      first.copyInput(task);
      first.version = task.version;
      first.displayName = "Updated synthetic task";
      assertEquals(task.version + 1, service.update(task.id, first, "synthetic-admin").version);
      assertEquals(
          "VERSION_CONFLICT",
          assertThrows(
                  IntegrationException.class,
                  () -> service.update(task.id, first, "synthetic-admin"))
              .code());
    }
  }

  @Test
  void rejectsUnverifiedFullSavepointRecoveryBeforeStopping() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      assertEquals(
          "INVALID_CONFIGURATION",
          assertThrows(
                  IntegrationException.class, () -> service.stop(task.id, true, "synthetic-admin"))
              .code());
      assertTrue(engine.submittedIds.isEmpty());
    }
  }

  @Test
  void rejectedStopRemainsVisibleAndAllowsRetryAfterRunningReconciliation() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      engine.taskId = task.id;
      service.run(task.id, false, "synthetic-admin");
      engine.rejectFirstStop = true;
      IntegrationTask rejected = service.stop(task.id, false, "synthetic-admin");
      assertEquals("STOP_FAILED", rejected.latestRun.status);
      assertEquals("ENGINE_UNAVAILABLE", rejected.latestRun.errorCode);
      IntegrationTask refreshed = service.task(task.id, "synthetic-admin");
      assertEquals("STOP_FAILED", refreshed.latestRun.status);
      assertEquals("ENGINE_UNAVAILABLE", refreshed.latestRun.errorCode);
      IntegrationTask retried = service.stop(task.id, false, "synthetic-admin");
      assertEquals("STOP_REQUESTED", retried.latestRun.status);
      assertEquals(2, engine.stops.get());
      assertEquals(null, retried.latestRun.errorCode);
      engine.state.set("CANCELED");
      assertEquals("CANCELED", service.task(task.id, "synthetic-admin").latestRun.status);
    }
  }

  @Test
  void listingReconcilesSubmittedJobsAndRetainsFirstTerminalObservationTime() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      IntegrationTask task = service.create(uniqueInput(), "synthetic-admin");
      engine.taskId = task.id;
      service.run(task.id, false, "synthetic-admin");
      engine.state.set("FINISHED");
      IntegrationTask listed = service.tasks("synthetic-admin").getFirst();
      assertEquals("FINISHED", listed.latestRun.status);
      assertNotNull(listed.latestRun.finishedAt);
      long observed = listed.latestRun.finishedAt;
      assertEquals(observed, service.task(task.id, "synthetic-admin").latestRun.finishedAt);
    }
  }

  @Test
  void rejectsDuplicateRouteOwnershipOnCreateAndUpdate() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(database.onDemand(EntityExtensionDAO.class));
    try (EngineFixture engine = new EngineFixture(store)) {
      IntegrationService service = service(engine, store);
      IntegrationTask first = service.create(uniqueInput(), "synthetic-admin");
      assertEquals(
          "DUPLICATE_ROUTE",
          assertThrows(
                  IntegrationException.class,
                  () -> service.create(uniqueInput(), "synthetic-admin"))
              .code());
      var otherInput = uniqueInput();
      otherInput.targetTable = "patient_ods_other";
      IntegrationTask second = service.create(otherInput, "synthetic-admin");
      UpdateInput update = new UpdateInput();
      update.copyInput(first);
      update.name = second.name;
      update.version = second.version;
      assertEquals(
          "DUPLICATE_ROUTE",
          assertThrows(
                  IntegrationException.class,
                  () -> service.update(second.id, update, "synthetic-admin"))
              .code());
      assertEquals(2, store.list().size());
    }
  }

  private IntegrationModels.TaskInput uniqueInput() {
    var input = IntegrationValidatorTest.validInput();
    input.name = "synthetic-" + UUID.randomUUID().toString().substring(0, 8);
    return input;
  }

  private IntegrationService service(EngineFixture engine, EntityExtensionTaskStore store) {
    Map<String, String> config =
        new HashMap<>(values(System.getenv("HOSPITAL_INTEGRATION_TEST_JDBC_URL")));
    config.put(
        "HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:" + engine.server.getAddress().getPort());
    return new IntegrationService(IntegrationConfiguration.fromValues(config), store);
  }

  private static final class EngineFixture implements AutoCloseable {
    private final HttpServer server;
    private final List<String> submittedIds = new CopyOnWriteArrayList<>();
    private final List<String> submittedQueries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean persistedBeforeSubmission = new AtomicBoolean();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicBoolean missing = new AtomicBoolean();
    private final AtomicBoolean savepointCompleted = new AtomicBoolean(true);
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicReference<String> state = new AtomicReference<>("RUNNING");
    private String taskId;
    private boolean failFirstSubmission;
    private boolean rejectFirstStop;

    private EngineFixture(EntityExtensionTaskStore store) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/submit-job",
          exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String id = query.substring("jobId=".length(), query.indexOf('&'));
            submittedIds.add(id);
            submittedQueries.add(query);
            persistedBeforeSubmission.set(
                id.equals(store.get(UUID.fromString(taskId)).latestRun.jobId));
            new ObjectMapper().readTree(exchange.getRequestBody());
            boolean fail = failFirstSubmission && submittedIds.size() == 1;
            byte[] response = ("{\"jobId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.createContext(
          "/stop-job",
          exchange -> {
            new ObjectMapper().readTree(exchange.getRequestBody());
            boolean rejected = rejectFirstStop && stops.incrementAndGet() == 1;
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rejected ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.createContext(
          "/job-info/",
          exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/job-info/".length());
            String body =
                "{\"jobId\":\""
                    + id
                    + "\""
                    + (missing.get() ? "" : ",\"jobStatus\":\"" + state.get() + "\"")
                    + "}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.createContext(
          "/jobs/checkpoints/",
          exchange -> {
            String body =
                "{\"pipelines\":[{\"latestSavepoint\":{\"checkpointType\":\"savepoint\",\"status\":\""
                    + (savepointCompleted.get() ? "COMPLETED" : "IN_PROGRESS")
                    + "\"}}]}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static Map<String, String> values(String jdbcUrl) {
    return Map.of(
        "HOSPITAL_INTEGRATION_ENABLED",
        "true",
        "HOSPITAL_SEATUNNEL_URL",
        "http://127.0.0.1:5801",
        "HOSPITAL_SOURCE_JDBC_URL",
        jdbcUrl,
        "HOSPITAL_SOURCE_JDBC_USER",
        "postgres",
        "HOSPITAL_SOURCE_JDBC_PASSWORD",
        PASSWORD,
        "HOSPITAL_TARGET_JDBC_URL",
        jdbcUrl,
        "HOSPITAL_TARGET_JDBC_USER",
        "postgres",
        "HOSPITAL_TARGET_JDBC_PASSWORD",
        PASSWORD);
  }
}
