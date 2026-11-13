package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.InitializationConfig;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.ScheduleConfig;

class InitializationConnectionGuardTest {
  private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");
  private final IntegrationConnectionsTest.MemoryConnections store =
      new IntegrationConnectionsTest.MemoryConnections();
  private final IntegrationConnectionsTest.MemoryTasks tasks =
      new IntegrationConnectionsTest.MemoryTasks();
  private IntegrationConnections connections;

  @BeforeEach
  void prepare() {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    connections =
        new IntegrationConnections(IntegrationConnectionsTest.configuration(), store, tasks);
  }

  @Test
  void failedInitializationWithCompletedBatchesRejectsPhysicalSourceChanges() {
    ConnectionInput input = IntegrationConnectionsTest.input("Postgres");
    var connection = connections.create(input, "synthetic-admin");
    IntegrationTask task = failedInitialization(connection.id);
    var edit = IntegrationConnectionsTest.update(input, connection.version);
    edit.host = "different-business-db.example.internal";

    IntegrationException failure =
        assertThrows(
            IntegrationException.class,
            () -> connections.update(connection.id, edit, "synthetic-admin"));

    assertEquals("INITIALIZATION_CONFIGURATION_LOCKED", failure.code());
    assertEquals(input.host, connections.connection(connection.id).host);
    assertEquals(1, task.initializationState.completedBatches);
    assertEquals("2026-11-11", task.initializationState.nextDate);
  }

  @Test
  void failedInitializationAllowsCredentialAndNameRepairs() {
    ConnectionInput input = IntegrationConnectionsTest.input("Postgres");
    var connection = connections.create(input, "synthetic-admin");
    IntegrationTask task = failedInitialization(connection.id);
    task.initializationState.sourceRouteHash =
        IntegrationConnectionFingerprint.of(connections.endpoint(connection.id));
    var edit = IntegrationConnectionsTest.update(input, connection.version);
    edit.name = "business_source_repaired";
    edit.displayName = "Synthetic source repaired";
    edit.username = "rotated_readonly_user";
    edit.password = "rotated-synthetic-password";

    var repaired = connections.update(connection.id, edit, "synthetic-admin");

    assertEquals(edit.name, repaired.name);
    assertEquals(edit.displayName, repaired.displayName);
    assertEquals(edit.username, connections.endpoint(connection.id).username());
    assertEquals(edit.password, connections.endpoint(connection.id).password());
    assertEquals("FAILED", task.initializationState.status);
    assertEquals(1, task.initializationState.completedBatches);
  }

  @Test
  void failedInitializationAllowsDisablingAndReenablingTheSameSource() {
    ConnectionInput input = IntegrationConnectionsTest.input("Postgres");
    var connection = connections.create(input, "synthetic-admin");
    IntegrationTask task = failedInitialization(connection.id);
    task.initializationState.sourceRouteHash =
        IntegrationConnectionFingerprint.of(connections.endpoint(connection.id));
    var edit = IntegrationConnectionsTest.update(input, connection.version);
    edit.enabled = false;

    var disabled = connections.update(connection.id, edit, "synthetic-admin");

    assertFalse(disabled.enabled);
    edit.enabled = true;
    edit.version = disabled.version;
    assertTrue(connections.update(connection.id, edit, "synthetic-admin").enabled);
    assertEquals(1, task.initializationState.completedBatches);
  }

  @Test
  void externallyDriftedSourceCanBeRestoredToItsBoundRoute() {
    ConnectionInput input = IntegrationConnectionsTest.input("Postgres");
    var connection = connections.create(input, "synthetic-admin");
    IntegrationTask task = failedInitialization(connection.id);
    String boundRoute = IntegrationConnectionFingerprint.of(connections.endpoint(connection.id));
    task.initializationState.sourceRouteHash = boundRoute;
    var drifted = store.get(UUID.fromString(connection.id));
    drifted.definition.host = "externally-moved-business-db.example.internal";
    drifted.definition.version++;
    store.save(drifted);
    var restore = IntegrationConnectionsTest.update(input, drifted.definition.version);

    var repaired = connections.update(connection.id, restore, "synthetic-admin");

    assertEquals(input.host, repaired.host);
    assertEquals(
        boundRoute, IntegrationConnectionFingerprint.of(connections.endpoint(connection.id)));
    assertEquals(boundRoute, task.initializationState.sourceRouteHash);
    assertEquals(1, task.initializationState.completedBatches);
  }

  @Test
  void credentialRepairCannotPreserveAnExternallyDriftedRoute() {
    ConnectionInput input = IntegrationConnectionsTest.input("Postgres");
    var connection = connections.create(input, "synthetic-admin");
    IntegrationTask task = failedInitialization(connection.id);
    task.initializationState.sourceRouteHash =
        IntegrationConnectionFingerprint.of(connections.endpoint(connection.id));
    var drifted = store.get(UUID.fromString(connection.id));
    drifted.definition.host = "externally-moved-business-db.example.internal";
    drifted.definition.version++;
    store.save(drifted);
    var edit = IntegrationConnectionsTest.update(input, drifted.definition.version);
    edit.host = drifted.definition.host;
    edit.password = "rotated-synthetic-password";

    IntegrationException failure =
        assertThrows(
            IntegrationException.class,
            () -> connections.update(connection.id, edit, "synthetic-admin"));

    assertEquals("INITIALIZATION_CONFIGURATION_LOCKED", failure.code());
    assertEquals(1, task.initializationState.completedBatches);
  }

  private IntegrationTask failedInitialization(String sourceConnectionId) {
    IntegrationTask task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.mode = "SQL_T1";
    task.sourceConnectionId = sourceConnectionId;
    task.sourceTable = "sql_result";
    task.schedule = new ScheduleConfig();
    task.schedule.enabled = true;
    task.initialization = new InitializationConfig();
    task.initialization.enabled = true;
    task.initialization.startDate = "2026-11-10";
    task.initializationState =
        IntegrationInitialization.create(task.initialization, task.schedule, NOW);
    RunSummary completed = new RunSummary();
    completed.jobId = "101";
    IntegrationInitialization.reserve(
        task, completed, IntegrationInitialization.nextBatch(task).orElseThrow(), NOW);
    task.latestRun = completed;
    completed.status = "FINISHED";
    IntegrationInitialization.complete(task, NOW);
    RunSummary failed = new RunSummary();
    failed.jobId = "102";
    IntegrationInitialization.reserve(
        task, failed, IntegrationInitialization.nextBatch(task).orElseThrow(), NOW);
    task.latestRun = failed;
    failed.status = "FAILED";
    IntegrationInitialization.complete(task, NOW);
    task.schedule.enabled = false;
    tasks.values.add(task);
    return task;
  }
}
