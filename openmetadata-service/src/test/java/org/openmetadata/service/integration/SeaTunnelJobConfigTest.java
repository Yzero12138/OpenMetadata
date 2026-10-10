package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

class SeaTunnelJobConfigTest {
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
          source.get("query"));
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
