package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcCheck;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightInput;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcPreflightResult;
import org.openmetadata.service.integration.IntegrationCdcModels.TargetRowCount;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.resources.hospital.HospitalIntegrationResource;
import org.openmetadata.service.security.Authorizer;

class HospitalCdcResourceTest {
  private HospitalIntegrationResource resource;
  private IntegrationService service;

  @BeforeEach
  void prepare() throws Exception {
    resource = new HospitalIntegrationResource(mock(Authorizer.class));
    service = mock(IntegrationService.class);
    Field field = HospitalIntegrationResource.class.getDeclaredField("service");
    field.setAccessible(true);
    field.set(resource, service);
  }

  @Test
  void authorizedMalformedJsonUnknownOptionsAndWrongTypesFailBeforeService() {
    for (String json :
        List.of(
            "{",
            "[]",
            "null",
            "{\"schema\":\"public\",\"schema\":\"other\"}",
            "{\"schema\":\"public\",\"table\":\"patient\",\"debezium\":{}}",
            "{\"columns\":\"id\"}",
            "{\"cdc\":{\"databaseName\":123}}")) {
      var response = resource.cdcPreflight(null, "synthetic-source", body(json));
      assertEquals(400, response.getStatus(), json);
      assertEquals("INVALID_CONFIGURATION", ((Map<?, ?>) response.getEntity()).get("errorCode"));
    }
    for (String json :
        List.of(
            "{\"runtime\":{\"fetchSize\":1000.2}}",
            "{\"runtime\":{\"fetchSize\":\"1000\"}}",
            "{\"primaryKey\":\"id\",\"unknown\":true}")) {
      assertEquals(400, resource.cdcPreflight(null, body(json)).getStatus(), json);
    }
    verifyNoInteractions(service);
  }

  @Test
  void bothPreflightApisReturnReadyFalseWithChecksForUiInsteadOfSubmitting() throws Exception {
    var result =
        new CdcPreflightResult(
            "synthetic-source",
            "Oracle",
            "11.2.0.1.0",
            "HISDB",
            true,
            false,
            List.of(
                new CdcCheck("engine-plugin", "FAIL", "CDC_ENGINE_PLUGIN_MISSING", true),
                new CdcCheck("archive-log", "FAIL", "ORACLE_ARCHIVELOG_REQUIRED", true),
                new CdcCheck(
                    "mining-privileges", "UNKNOWN", "ORACLE_MINING_PRIVILEGES_UNVERIFIED", true),
                new CdcCheck(
                    "database-version", "UNKNOWN", "ORACLE_CDC_VERSION_UNCERTIFIED", false)));
    when(service.cdcPreflight(anyString(), any(CdcPreflightInput.class))).thenReturn(result);
    when(service.cdcPreflight(any(TaskInput.class))).thenReturn(result);
    var connection =
        resource.cdcPreflight(
            null,
            "synthetic-source",
            body("{\"schema\":\"HIS\",\"table\":\"ORDERS\",\"columns\":[\"ID\"]}"));
    var task =
        resource.cdcPreflight(
            null, body(new ObjectMapper().writeValueAsString(IntegrationCdcExecutionTest.input())));
    assertEquals(200, connection.getStatus());
    assertEquals(result, connection.getEntity());
    assertEquals(result, task.getEntity());
    String json = new ObjectMapper().writeValueAsString(task.getEntity());
    assertTrue(json.contains("ORACLE_CDC_VERSION_UNCERTIFIED"));
    assertFalse(json.contains("password"));
    assertFalse(result.ready());
  }

  @Test
  void manualCountReturnsZeroAndFixedErrorsDoNotExposeUnderlyingSecrets() {
    when(service.targetCount("task")).thenReturn(new TargetRowCount(0, 1234));
    Response response = resource.targetCount(null, "task");
    assertEquals(200, response.getStatus());
    assertEquals(new TargetRowCount(0, 1234), response.getEntity());
    when(service.targetCount("other"))
        .thenThrow(new IllegalStateException("jdbc:private password=private-secret"));
    response = resource.targetCount(null, "other");
    assertEquals(503, response.getStatus());
    assertFalse(response.getEntity().toString().contains("private-secret"));
    when(service.cdcPreflight(anyString(), any(CdcPreflightInput.class)))
        .thenThrow(new IntegrationException("SCHEMA_NOT_ALLOWED", 400));
    response =
        resource.cdcPreflight(
            null, "synthetic-source", body("{\"schema\":\"escape\",\"table\":\"patient\"}"));
    assertEquals(400, response.getStatus());
    assertEquals("SCHEMA_NOT_ALLOWED", ((Map<?, ?>) response.getEntity()).get("errorCode"));
  }

  private ByteArrayInputStream body(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }
}
