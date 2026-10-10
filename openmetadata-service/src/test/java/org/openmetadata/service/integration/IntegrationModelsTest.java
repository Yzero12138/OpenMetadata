package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IntegrationModelsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void rejectsCallerSuppliedCredentialsAndEngineConfiguration() throws Exception {
    for (String field : new String[] {"password", "jdbcUrl", "config", "query", "plugin_name"}) {
      var input = JSON.valueToTree(IntegrationValidatorTest.validInput());
      ((ObjectNode) input).put(field, "forbidden");
      IntegrationException failure =
          assertThrows(IntegrationException.class, () -> IntegrationModels.readInput(input, false));
      assertEquals("INVALID_CONFIGURATION", failure.code());
      assertFalse(failure.getMessage().contains("forbidden"));
    }
  }

  @Test
  void hidesInternalJdbcDetailsFromTableResponse() throws Exception {
    String response = JSON.writeValueAsString(IntegrationValidatorTest.source());
    assertFalse(response.contains("jdbcType"));
    assertFalse(response.contains("hasDefault"));
    assertFalse(response.contains("scale"));
    assertFalse(response.contains("size"));
  }

  @Test
  void boundsRequestBytesBeforeJsonParsing() {
    ByteArrayInputStream body =
        new ByteArrayInputStream(" ".repeat(32769).getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(IntegrationException.class, () -> IntegrationModels.readBody(body)).code());
  }

  @Test
  void updateRequiresAPositiveIntegralVersionWithoutStringCoercion() throws Exception {
    for (String version : new String[] {"null", "0", "-1", "1.5", "\"1\""}) {
      ObjectNode input = JSON.valueToTree(IntegrationValidatorTest.validInput());
      input.set("version", JSON.readTree(version));
      assertThrows(IntegrationException.class, () -> IntegrationModels.readInput(input, true));
    }
  }
}
