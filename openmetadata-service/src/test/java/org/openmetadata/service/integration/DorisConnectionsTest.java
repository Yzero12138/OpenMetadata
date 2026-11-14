package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;

class DorisConnectionsTest {
  private final ObjectMapper json = new ObjectMapper();
  private IntegrationConnections connections;
  private IntegrationConnectionsTest.MemoryConnections store;
  private IntegrationConnectionsTest.MemoryTasks tasks;

  @BeforeEach
  void prepare() {
    Fernet.getInstance().setFernetKey(Base64.getUrlEncoder().encodeToString(new byte[32]));
    store = new IntegrationConnectionsTest.MemoryConnections();
    tasks = new IntegrationConnectionsTest.MemoryTasks();
    connections =
        new IntegrationConnections(IntegrationConnectionsTest.configuration(), store, tasks);
  }

  @Test
  void registersOnlyScopedDorisTargetsAndDoesNotExportCredentials() throws Exception {
    var input = input();
    var created = connections.create(input, "synthetic");
    var endpoint = connections.endpoint(created.id);
    assertEquals("Doris", created.databaseType);
    assertEquals(List.of("FULL"), created.supportedModes);
    assertTrue(endpoint.jdbcUrl().startsWith("jdbc:mysql://doris-fe:9030/warehouse?"));
    assertEquals("com.mysql.cj.jdbc.Driver", endpoint.driver());
    assertEquals("`warehouse`", endpoint.dialect().quote("warehouse"));
    assertEquals(8030, json.valueToTree(created).path("dorisHttpPort").intValue());
    assertFalse(json.writeValueAsString(created).contains("synthetic-business-password"));
  }

  @Test
  void rejectsSourceRoleTlsVerifyInvalidHttpPortsAndUnsafeDatabase() throws Exception {
    for (String change :
        List.of(
            "\"role\":\"SOURCE\"",
            "\"tlsMode\":\"VERIFY\"",
            "\"dorisHttpPort\":0",
            "\"dorisHttpPort\":65536",
            "\"dorisHttpPort\":null",
            "\"database\":\"warehouse.db\"",
            "\"schemas\":[\"other\"]")) {
      var node = json.valueToTree(input());
      var override = json.readTree("{" + change + "}");
      override
          .fields()
          .forEachRemaining(
              entry ->
                  ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                      .set(entry.getKey(), entry.getValue()));
      var invalid = IntegrationModels.readConnection(node, false);
      assertEquals(
          "INVALID_CONFIGURATION",
          assertThrows(IntegrationException.class, () -> connections.create(invalid, "synthetic"))
              .code(),
          change);
    }
    var postgres = json.valueToTree(IntegrationConnectionsTest.input("Postgres"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) postgres).put("dorisHttpPort", 8030);
    var invalid = IntegrationModels.readConnection(postgres, false);
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(IntegrationException.class, () -> connections.create(invalid, "synthetic"))
            .code());
  }

  @Test
  void usesDorisStreamLoadSinkWithStableResumeAndFreshRunLabels() throws Exception {
    var created = connections.create(input(), "synthetic");
    var builder = new SeaTunnelJobConfig(connections::endpoint);
    var task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    task.targetConnectionId = created.id;
    task.targetSchema = "warehouse";
    task.latestRun = new RunSummary();
    task.latestRun.jobId = "1001";
    var sink = sink(builder.build(task));
    assertEquals("Doris", sink.get("plugin_name"));
    assertEquals("doris-fe:8030", sink.get("fenodes"));
    assertEquals(9030, sink.get("query-port"));
    assertEquals("warehouse", sink.get("database"));
    assertEquals(task.targetTable, sink.get("table"));
    assertEquals(false, sink.get("sink.enable-2pc"));
    assertEquals("IGNORE", sink.get("schema_save_mode"));
    assertEquals("APPEND_DATA", sink.get("data_save_mode"));
    assertEquals("json", ((Map<?, ?>) sink.get("doris.config")).get("format"));
    assertEquals("true", ((Map<?, ?>) sink.get("doris.config")).get("read_json_by_line"));
    assertFalse(sink.containsKey("url"));
    assertFalse(sink.containsKey("enable_upsert"));
    assertEquals(sink.get("sink.label-prefix"), sink(builder.build(task)).get("sink.label-prefix"));
    task.latestRun.jobId = "1002";
    assertNotEquals(
        sink.get("sink.label-prefix"), sink(builder.build(task)).get("sink.label-prefix"));
    task.mode = "CDC";
    var cdc = builder.build(task);
    assertEquals(
        "Postgres-CDC", ((Map<?, ?>) ((List<?>) cdc.get("source")).getFirst()).get("plugin_name"));
    assertEquals(true, sink(cdc).get("sink.enable-delete"));
  }

  @Test
  void boundsTheCompleteNativeConnectorLabelAtTheLongestSupportedTablePath() throws Exception {
    var created = connections.create(input(), "synthetic");
    var builder = new SeaTunnelJobConfig(connections::endpoint);
    var task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    task.targetConnectionId = created.id;
    task.targetSchema = "warehouse";
    task.targetTable = "t".repeat(67 - task.targetSchema.length());
    task.latestRun = new RunSummary();
    task.latestRun.jobId = Long.toString(Long.MAX_VALUE);
    var config = builder.build(task);
    String prefix = (String) sink(config).get("sink.label-prefix");
    String label =
        prefix
            + "_"
            + task.targetSchema
            + "_"
            + task.targetTable
            + "_"
            + task.latestRun.jobId
            + "_0_"
            + Long.MAX_VALUE;
    assertEquals(17, prefix.length());
    assertEquals(128, label.length());
    assertTrue(label.matches("[-_A-Za-z0-9:]{1,128}"));
    assertEquals(1, ((Map<?, ?>) config.get("env")).get("parallelism"));
    assertEquals(prefix, sink(builder.build(task)).get("sink.label-prefix"));
    task.latestRun.jobId = "984918146341189798";
    assertNotEquals(prefix, sink(builder.build(task)).get("sink.label-prefix"));
    task.targetTable += "t";
    assertEquals(
        "DORIS_LABEL_INVALID",
        assertThrows(IntegrationException.class, () -> builder.build(task)).code());
    for (String name : List.of("table$", "table#", "表名", "table\u00b2")) {
      task.targetTable = name;
      assertEquals(
          "DORIS_LABEL_INVALID",
          assertThrows(IntegrationException.class, () -> builder.build(task)).code());
    }
    task.targetTable = "visits";
    task.latestRun.jobId = "9223372036854775808";
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(IntegrationException.class, () -> builder.build(task)).code());
  }

  @Test
  void rejectsUnusableLabelsBeforeMetadataTableCreationOrStartingAnExistingTask() throws Exception {
    var created = connections.create(input(), "synthetic");
    var service = new IntegrationService(IntegrationConnectionsTest.configuration(), tasks, store);
    var task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    task.targetConnectionId = created.id;
    task.targetSchema = "warehouse";
    task.targetTable = "t".repeat(59);
    var result = service.validate(task);
    assertFalse(result.valid());
    assertEquals("DORIS_LABEL_INVALID", result.errors().getFirst().code());
    assertEquals(
        "DORIS_LABEL_INVALID",
        assertThrows(IntegrationException.class, () -> service.create(task, "synthetic")).code());
    tasks.save(task);
    assertEquals(
        "DORIS_LABEL_INVALID",
        assertThrows(IntegrationException.class, () -> service.run(task.id, false, "synthetic"))
            .code());
    assertNull(tasks.get(UUID.fromString(task.id)).latestRun);
    var tableInput =
        new TargetTableInput(
            "synthetic-source",
            "public",
            "visits",
            "warehouse",
            task.targetTable,
            List.of(new FieldMapping("id", "id")),
            "0".repeat(64));
    assertEquals(
        "DORIS_LABEL_INVALID",
        assertThrows(
                IntegrationException.class,
                () -> service.previewTargetTable(created.id, tableInput))
            .code());
    assertEquals(
        "DORIS_LABEL_INVALID",
        assertThrows(
                IntegrationException.class, () -> service.createTargetTable(created.id, tableInput))
            .code());
  }

  private Map<?, ?> sink(Map<String, Object> config) {
    return (Map<?, ?>) ((List<?>) config.get("sink")).getFirst();
  }

  private ConnectionInput input() throws Exception {
    return assertDoesNotThrow(
        () ->
            IntegrationModels.readConnection(
                json.readTree(
                    """
        {"name":"doris_target","displayName":"合成 Doris 目标","role":"TARGET","databaseType":"Doris",
         "databaseVersion":"4.0","host":"doris-fe","port":9030,"dorisHttpPort":8030,
         "database":"warehouse","username":"loader","password":"synthetic-business-password",
         "schemas":["warehouse"],"tlsMode":"DISABLED","enabled":true}
        """),
                false));
  }
}
