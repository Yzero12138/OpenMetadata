package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DorisConfigurationTest {
  @Test
  void optionalManagedDorisSeedLeavesExistingPostgresSeedsUntouched() {
    var values = values();
    assertEquals(2, connections(values).list().size());
    values.put("HOSPITAL_DORIS_ENABLED", "false");
    assertEquals(2, connections(values).list().size());
    values.put("HOSPITAL_DORIS_ENABLED", "true");
    var connections = connections(values);
    assertEquals(3, connections.list().size());
    var doris = connections.connection("doris-ods-validation");
    assertEquals("Doris 验证目标", doris.displayName);
    assertEquals("Doris", doris.databaseType);
    assertEquals("TARGET", doris.role);
    assertEquals("4.0.8", doris.databaseVersion);
    assertEquals(List.of("ods_validation"), doris.schemas);
    assertEquals(List.of("FULL"), doris.supportedModes);
    assertTrue(doris.managed);
    assertTrue(doris.synthetic);
    assertTrue(doris.passwordSet);
    assertEquals("synthetic-only-doris-secret", connections.endpoint(doris.id).password());
    assertEquals("Postgres", connections.connection("synthetic-source").databaseType);
    assertEquals("Postgres", connections.connection("synthetic-ods").databaseType);
    assertEquals(
        "CONNECTION_MANAGED",
        assertThrows(IntegrationException.class, () -> connections.delete(doris.id, doris.version))
            .code());
    var input = IntegrationValidatorTest.validInput();
    input.targetConnectionId = doris.id;
    input.targetSchema = "ods_validation";
    assertTrue(IntegrationValidator.validateStructure(input).valid());
  }

  @Test
  void incompleteOptionalDorisConfigDoesNotDisableLegacyConnections() {
    var values = values();
    values.put("HOSPITAL_DORIS_ENABLED", "true");
    values.remove("HOSPITAL_DORIS_PASSWORD");
    var connections = connections(values);
    assertEquals(2, connections.list().size());
    assertThrows(IntegrationException.class, () -> connections.endpoint("doris-ods-validation"));
    assertFalse(
        connections.list().stream()
            .anyMatch(connection -> "Doris".equals(connection.databaseType)));
  }

  private IntegrationConnections connections(Map<String, String> values) {
    return new IntegrationConnections(
        IntegrationConfiguration.fromValues(values),
        new IntegrationConnectionsTest.MemoryConnections(),
        new IntegrationConnectionsTest.MemoryTasks());
  }

  private Map<String, String> values() {
    var values = new HashMap<String, String>();
    values.put("HOSPITAL_INTEGRATION_ENABLED", "true");
    values.put("HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801");
    for (String role : List.of("SOURCE", "TARGET")) {
      values.put("HOSPITAL_" + role + "_JDBC_URL", "jdbc:postgresql://pg:5432/integration_test");
      values.put("HOSPITAL_" + role + "_JDBC_USER", "synthetic");
      values.put("HOSPITAL_" + role + "_JDBC_PASSWORD", "synthetic-only-secret");
    }
    values.put("HOSPITAL_DORIS_HOST", "hospital-doris-fe");
    values.put("HOSPITAL_DORIS_SQL_PORT", "9030");
    values.put("HOSPITAL_DORIS_HTTP_PORT", "8030");
    values.put("HOSPITAL_DORIS_DATABASE", "ods_validation");
    values.put("HOSPITAL_DORIS_USER", "hospital_doris_writer");
    values.put("HOSPITAL_DORIS_PASSWORD", "synthetic-only-doris-secret");
    return values;
  }
}
