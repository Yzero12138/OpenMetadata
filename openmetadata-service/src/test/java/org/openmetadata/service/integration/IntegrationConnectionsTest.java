package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.ConnectionUpdate;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.StoredConnection;

class IntegrationConnectionsTest {
  private final MemoryConnections store = new MemoryConnections();
  private final MemoryTasks tasks = new MemoryTasks();
  private IntegrationConnections connections;

  @BeforeEach
  void prepare() {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    connections = new IntegrationConnections(configuration(), store, tasks);
  }

  @Test
  void persistsOnlyEncryptedCredentialsAndReturnsRedactedDefinitions() throws Exception {
    var created = connections.create(input("Postgres"), "synthetic-admin");
    String persisted =
        IntegrationModels.serializeConnection(store.get(UUID.fromString(created.id)));
    assertFalse(persisted.contains("synthetic-business-password"));
    assertTrue(persisted.contains("fernet:"));
    String response = new ObjectMapper().writeValueAsString(created);
    assertFalse(response.contains("synthetic-business-password"));
    assertFalse(response.contains("fernet:"));
    assertFalse(response.contains("\"password\""));
    assertTrue(created.passwordSet);
    assertFalse(created.synthetic);
    assertFalse(created.managed);
    assertEquals("synthetic-business-password", connections.endpoint(created.id).password());
    assertEquals(3, connections.list().size());
  }

  @Test
  void retainsBlankPasswordRejectsStaleVersionAndRotatesExplicitPassword() {
    var created = connections.create(input("Postgres"), "synthetic-admin");
    var edit = update(input("Postgres"), created.version);
    edit.password = "";
    edit.displayName = "业务来源维护";
    var saved = connections.update(created.id, edit, "synthetic-admin");
    assertEquals(created.version + 1, saved.version);
    assertEquals("synthetic-business-password", connections.endpoint(created.id).password());
    assertEquals("VERSION_CONFLICT", failure(() -> connections.update(created.id, edit, "other")));
    edit.version = saved.version;
    edit.password = "rotated-synthetic-password";
    connections.update(created.id, edit, "synthetic-admin");
    assertEquals("rotated-synthetic-password", connections.endpoint(created.id).password());
  }

  @Test
  void rejectsUrlInjectionUnknownFieldsInvalidSchemasAndMissingPassword() {
    for (String host :
        List.of("db;password=secret", "db/path", "db?ssl=false", "user@db", "db:123")) {
      var input = input("Postgres");
      input.host = host;
      assertEquals("INVALID_CONFIGURATION", failure(() -> connections.create(input, "synthetic")));
    }
    var input = input("Postgres");
    input.schemas = List.of("public;drop schema public");
    assertEquals("INVALID_CONFIGURATION", failure(() -> connections.create(input, "synthetic")));
    input.schemas = List.of("public");
    input.password = "";
    assertEquals("INVALID_CONFIGURATION", failure(() -> connections.create(input, "synthetic")));
    assertThrows(
        IntegrationException.class,
        () ->
            IntegrationModels.readConnection(
                new ObjectMapper().createObjectNode().put("jdbcUrl", "jdbc:unknown:injected"),
                false));
  }

  @Test
  void environmentConnectionsAreImmutableAndReferencedConnectionsCannotBeDeleted() {
    assertEquals("CONNECTION_MANAGED", failure(() -> connections.delete("synthetic-source", 0)));
    var created = connections.create(input("Postgres"), "synthetic");
    IntegrationTask task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.sourceConnectionId = created.id;
    tasks.values.add(task);
    assertEquals(
        "CONNECTION_IN_USE", failure(() -> connections.delete(created.id, created.version)));
    task.latestRun = new IntegrationModels.RunSummary();
    task.latestRun.status = "SUBMISSION_UNKNOWN";
    assertEquals(
        "TASK_ACTIVE",
        failure(
            () ->
                connections.update(
                    created.id, update(input("Postgres"), created.version), "synthetic")));
    task.latestRun.status = "SAVEPOINT_DONE";
    assertEquals(
        "TASK_ACTIVE",
        failure(
            () ->
                connections.update(
                    created.id, update(input("Postgres"), created.version), "synthetic")));
    tasks.values.clear();
    connections.delete(created.id, created.version);
    assertEquals("CONNECTION_NOT_FOUND", failure(() -> connections.connection(created.id)));
  }

  @Test
  void sourceRolesEnabledStateAndAllowedSchemaAreEnforced() {
    var input = input("Postgres");
    input.enabled = false;
    var created = connections.create(input, "synthetic");
    String id = created.id;
    assertEquals("CONNECTION_DISABLED", failure(() -> connections.endpoint(id)));
    var edit = update(input("Postgres"), created.version);
    created = connections.update(created.id, edit, "synthetic");
    assertTrue(connections.endpoint(created.id).schemas().contains("public"));
    assertEquals("SOURCE", connections.connection(created.id).role);
  }

  @Test
  void eachDatabaseFamilyBuildsSafeDialectConfigurationAndFullOnlyCapabilities() {
    for (String type : List.of("Oracle", "Mssql", "Mysql", "Postgres")) {
      var input = input(type);
      input.name = "source_" + type.toLowerCase();
      var created = connections.create(input, "synthetic");
      var endpoint = connections.endpoint(created.id);
      assertNotEquals("", endpoint.driver());
      assertTrue(endpoint.jdbcUrl().startsWith("jdbc:"));
      assertFalse(endpoint.jdbcUrl().contains(input.password));
      assertFalse(endpoint.jdbcUrl().contains(input.username));
      assertEquals(
          type.equals("Postgres") ? List.of("FULL", "CDC") : List.of("FULL"),
          created.supportedModes);
      assertEquals(
          type.equals("Mysql") ? "`patient`" : type.equals("Mssql") ? "[patient]" : "\"patient\"",
          endpoint.dialect().quote("patient"));
    }
  }

  @Test
  void onlyPostgresVersionsWithPgoutputAdvertiseCdc() {
    for (String version : List.of("9.6", "unknown", "17beta1")) {
      var input = input("Postgres");
      input.name = "legacy_" + version.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
      input.databaseVersion = version;
      assertEquals(List.of("FULL"), connections.create(input, "synthetic").supportedModes);
    }
    var input = input("Postgres");
    input.databaseVersion = "16.4";
    assertEquals(List.of("FULL", "CDC"), connections.create(input, "synthetic").supportedModes);
  }

  @Test
  void dottedDatabaseNamesAdvertiseFullOnly() {
    var input = input("Postgres");
    input.database = "business.db";
    assertEquals(List.of("FULL"), connections.create(input, "synthetic").supportedModes);
  }

  @Test
  void failsClosedWithoutInitializedFernetKey() {
    Fernet.getInstance().setFernetKey((String) null);
    assertEquals(
        "CREDENTIALS_NOT_CONFIGURED",
        failure(() -> connections.create(input("Postgres"), "synthetic")));
    assertTrue(store.values.isEmpty());
  }

  static ConnectionInput input(String type) {
    ConnectionInput input = new ConnectionInput();
    input.name = "business_source";
    input.displayName = "合成业务来源";
    input.role = "SOURCE";
    input.databaseType = type;
    input.databaseVersion =
        switch (type) {
          case "Oracle" -> "19c";
          case "Mssql" -> "2019";
          case "Mysql" -> "8";
          default -> "17";
        };
    input.host = "business-db.example.internal";
    input.port =
        switch (type) {
          case "Oracle" -> 1521;
          case "Mssql" -> 1433;
          case "Mysql" -> 3306;
          default -> 5432;
        };
    input.database = "business";
    input.username = "readonly_user";
    input.password = "synthetic-business-password";
    input.schemas =
        List.of(
            type.equals("Oracle")
                ? "HIS"
                : type.equals("Mssql") ? "dbo" : type.equals("Mysql") ? "business" : "public");
    input.oracleConnectionType = type.equals("Oracle") ? "SERVICE_NAME" : null;
    input.tlsMode = "DISABLED";
    input.enabled = true;
    return input;
  }

  static ConnectionUpdate update(ConnectionInput input, long version) {
    ConnectionUpdate update = new ConnectionUpdate();
    update.copyInput(input);
    update.password = input.password;
    update.version = version;
    return update;
  }

  static IntegrationConfiguration configuration() {
    return IntegrationConfiguration.fromValues(
        Map.of(
            "HOSPITAL_INTEGRATION_ENABLED",
            "true",
            "HOSPITAL_SEATUNNEL_URL",
            "http://127.0.0.1:5801",
            "HOSPITAL_SOURCE_JDBC_URL",
            "jdbc:postgresql://source:5432/source_db",
            "HOSPITAL_SOURCE_JDBC_USER",
            "source_user",
            "HOSPITAL_SOURCE_JDBC_PASSWORD",
            "synthetic-source-secret",
            "HOSPITAL_TARGET_JDBC_URL",
            "jdbc:postgresql://target:5432/target_db",
            "HOSPITAL_TARGET_JDBC_USER",
            "target_user",
            "HOSPITAL_TARGET_JDBC_PASSWORD",
            "synthetic-target-secret"));
  }

  private String failure(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(IntegrationException.class, action).code();
  }

  static final class MemoryConnections implements IntegrationConnectionStore {
    final Map<UUID, String> values = new LinkedHashMap<>();

    public List<StoredConnection> list() {
      return values.values().stream().map(IntegrationModels::deserializeConnection).toList();
    }

    public StoredConnection get(UUID id) {
      if (!values.containsKey(id)) {
        throw new IntegrationException("CONNECTION_NOT_FOUND", 404);
      }
      return IntegrationModels.deserializeConnection(values.get(id));
    }

    public void save(StoredConnection value) {
      values.put(
          UUID.fromString(value.definition.id), IntegrationModels.serializeConnection(value));
    }

    public void delete(UUID id) {
      values.remove(id);
    }
  }

  static final class MemoryTasks implements IntegrationTaskStore {
    final List<IntegrationTask> values = new ArrayList<>();

    public List<IntegrationTask> list() {
      return values;
    }

    public IntegrationTask get(UUID id) {
      return values.stream()
          .filter(value -> id.toString().equals(value.id))
          .findFirst()
          .orElseThrow();
    }

    public void save(IntegrationTask value) {
      values.add(value);
    }
  }
}
