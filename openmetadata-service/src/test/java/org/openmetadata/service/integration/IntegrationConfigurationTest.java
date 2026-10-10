package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationConfigurationTest {
  @Test
  void failsClosedWhenDisabledOrRequiredSecretsAreMissing() {
    IntegrationConfiguration disabled = IntegrationConfiguration.fromValues(Map.of());
    assertFalse(disabled.enabled());
    assertEquals(
        "INTEGRATION_DISABLED",
        assertThrows(IntegrationException.class, disabled::requireReady).code());
    IntegrationConfiguration incomplete =
        IntegrationConfiguration.fromValues(Map.of("HOSPITAL_INTEGRATION_ENABLED", "true"));
    assertTrue(incomplete.enabled());
    assertFalse(incomplete.configured());
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(IntegrationException.class, incomplete::requireReady).code());
  }

  @Test
  void permitsOnlyBoundedConfiguredJdbcTimeoutOptions() {
    for (String query :
        new String[] {
          "socketTimeout=0",
          "connectTimeout=60",
          "password=hidden",
          "sslmode=disable",
          "socketTimeout=15&socketTimeout=1"
        }) {
      Map<String, String> values = values();
      values.put("HOSPITAL_SOURCE_JDBC_URL", "jdbc:postgresql://source:5432/source_db?" + query);
      assertFalse(IntegrationConfiguration.fromValues(values).configured());
    }
    Map<String, String> values = values();
    values.put(
        "HOSPITAL_SOURCE_JDBC_URL",
        "jdbc:postgresql://source:5432/source_db?connectTimeout=5&socketTimeout=15");
    IntegrationConfiguration configured = IntegrationConfiguration.fromValues(values);
    assertTrue(configured.configured());
    assertEquals("synthetic-source", configured.endpoint("synthetic-source").toString());
    assertFalse(configured.endpoint("synthetic-source").toString().contains("hidden"));
  }

  private Map<String, String> values() {
    return new HashMap<>(
        Map.of(
            "HOSPITAL_INTEGRATION_ENABLED", "true",
            "HOSPITAL_SEATUNNEL_URL", "http://127.0.0.1:5801",
            "HOSPITAL_SOURCE_JDBC_URL", "jdbc:postgresql://source:5432/source_db",
            "HOSPITAL_SOURCE_JDBC_USER", "source_user",
            "HOSPITAL_SOURCE_JDBC_PASSWORD", "hidden-source-secret",
            "HOSPITAL_TARGET_JDBC_URL", "jdbc:postgresql://target:5432/target_db",
            "HOSPITAL_TARGET_JDBC_USER", "target_user",
            "HOSPITAL_TARGET_JDBC_PASSWORD", "hidden-target-secret"));
  }
}
