package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

class IntegrationCdcModelsTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void retainsTypedCdcOptionsAcrossCopiesAndStorage() throws Exception {
    var input =
        IntegrationModels.readInput(
            json.readTree(
                "{\"name\":\"cdc\",\"cdc\":{\"databaseName\":\"HISDB\",\"serverTimeZone\":\"Asia/Shanghai\",\"pdbName\":null,\"rootServiceName\":null}}"),
            false);
    var task = IntegrationTask.from(input);
    var restored = IntegrationModels.deserialize(IntegrationModels.serialize(task));
    assertEquals(input.cdc, restored.cdc);
    assertEquals("HISDB", restored.cdc.databaseName());
    assertEquals("Asia/Shanghai", restored.cdc.serverTimeZone());
    task.replaceInput(IntegrationModels.readInput(json.readTree("{\"name\":\"full\"}"), false));
    assertNull(task.cdc);
  }

  @Test
  void legacyTasksStillDeserializeWithoutCdcAndRuntime() {
    var legacy = IntegrationModels.deserialize("{\"id\":\"legacy\",\"mode\":\"CDC\"}");
    assertNull(legacy.cdc);
    assertNull(legacy.runtime);
    assertTrue(IntegrationModels.serialize(legacy).contains("\"mode\":\"CDC\""));
  }

  @Test
  void rejectsArbitraryDebeziumConfiguration() throws Exception {
    var body =
        json.readTree(
            "{\"cdc\":{\"databaseName\":\"HISDB\",\"debezium\":{\"heartbeat.action.query\":\"UPDATE x SET y=1\"}}}");
    assertThrows(IntegrationException.class, () -> IntegrationModels.readInput(body, false));
  }

  @Test
  void oracleByteLengthProvenanceIsImmutableAndDoesNotChangePublicColumnJson() throws Exception {
    var column =
        new ColumnDefinition(
            "NOTE",
            "VARCHAR2",
            true,
            false,
            Types.VARCHAR,
            2000,
            0,
            false,
            false,
            0,
            JdbcDialect.Oracle,
            4000);
    assertEquals(4000, column.oracleDataLengthBytes());
    assertEquals(
        json.readTree(
            "{\"name\":\"NOTE\",\"dataType\":\"VARCHAR2\",\"nullable\":true,\"primaryKey\":false}"),
        json.valueToTree(column));
    assertNull(
        new ColumnDefinition(
                "NOTE",
                "VARCHAR2",
                true,
                false,
                Types.VARCHAR,
                2000,
                0,
                false,
                false,
                0,
                JdbcDialect.Oracle)
            .oracleDataLengthBytes());
  }
}
