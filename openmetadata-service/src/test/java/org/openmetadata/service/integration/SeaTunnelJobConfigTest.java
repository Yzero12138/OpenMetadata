package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

class SeaTunnelJobConfigTest {
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

  private IntegrationTask task() {
    IntegrationTask task = IntegrationTask.from(IntegrationValidatorTest.validInput());
    task.id = "12345678-1234-1234-1234-1234567890ab";
    return task;
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
