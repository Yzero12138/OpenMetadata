package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisAddColumnsInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignInput;
import org.openmetadata.service.resources.hospital.HospitalIntegrationResource;
import org.openmetadata.service.security.AuthenticationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;

class DorisTableDesignApiTest {
  @Test
  void everyNewEndpointAuthenticatesBeforeParsingOrStorage() {
    var resource = new HospitalIntegrationResource(new DefaultAuthorizer());
    InputStream unreadable =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("Unauthorized body must never be read");
          }
        };
    assertThrows(
        AuthenticationException.class, () -> resource.targetTableStructure(null, null, null, null));
    assertThrows(
        AuthenticationException.class,
        () -> resource.previewDesignedTargetTable(null, null, unreadable));
    assertThrows(
        AuthenticationException.class,
        () -> resource.createDesignedTargetTable(null, null, unreadable));
    assertThrows(
        AuthenticationException.class,
        () -> resource.previewTargetTableColumns(null, null, null, null, unreadable));
    assertThrows(
        AuthenticationException.class,
        () -> resource.addTargetTableColumns(null, null, null, null, unreadable));
  }

  @Test
  void authorizedMalformedBodiesAreRejectedByAllFourWriteRoutes() {
    var resource = new HospitalIntegrationResource(mock(Authorizer.class));
    for (String body :
        List.of(
            "{", "{\"sql\":\"DROP TABLE secret\"}", "{}{}", "{\"columns\":[],\"columns\":[]}")) {
      assertEquals(
          400, resource.previewDesignedTargetTable(null, "target", bytes(body)).getStatus());
      assertEquals(
          400, resource.createDesignedTargetTable(null, "target", bytes(body)).getStatus());
      assertEquals(
          400,
          resource
              .previewTargetTableColumns(null, "target", "warehouse", "visits", bytes(body))
              .getStatus());
      assertEquals(
          400,
          resource
              .addTargetTableColumns(null, "target", "warehouse", "visits", bytes(body))
              .getStatus());
    }
  }

  @Test
  void strictBoundedParserRejectsUnknownFieldsCoercionFractionsAndOversizedBodies() {
    for (String body :
        List.of(
            "{\"buckets\":1.5}",
            "{\"buckets\":\"1\"}",
            "{\"columns\":[{\"name\":\"id\",\"nullable\":\"true\"}]}",
            "{\"columns\":[{\"name\":\"id\",\"length\":2147483648}]}",
            "{\"columns\":[{\"name\":\"id\",\"sql\":\"INT\"}]}",
            "{\"previewHash\":false}",
            "{\"comment\":\"" + "患".repeat(12000) + "\"}")) {
      assertEquals(
          "INVALID_CONFIGURATION",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      IntegrationModels.read(
                          IntegrationModels.readBody(bytes(body)), DorisTableDesignInput.class),
                  body.length() > 100 ? "oversized" : body)
              .code());
    }
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () ->
                    IntegrationModels.read(
                        IntegrationModels.readBody(bytes("{\"ddl\":\"ALTER TABLE x\"}")),
                        DorisAddColumnsInput.class))
            .code());
  }

  @Test
  void oldSourceDerivedContractStillRejectsDesignerPayload() {
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () ->
                    IntegrationModels.read(
                        IntegrationModels.readBody(bytes("{\"columns\":[]}")),
                        IntegrationModels.TargetTableInput.class))
            .code());
  }

  @Test
  void serviceDelegatesRejectNonDorisTargetsBeforeOpeningJdbc() {
    var service =
        new IntegrationService(
            IntegrationConnectionsTest.configuration(),
            new IntegrationConnectionsTest.MemoryTasks(),
            new IntegrationConnectionsTest.MemoryConnections());
    var input = DorisTableDesignTest.input(List.of(DorisTableDesignTest.key()));
    var addition =
        new DorisAddColumnsInput(
            List.of(
                DorisTableDesignTest.column(
                    "v", "INT", null, null, null, true, false, "NONE", null, null)),
            "0".repeat(64));
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () -> service.targetTableStructure("synthetic-ods", "public", "visits"))
            .code());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () -> service.previewDesignedTargetTable("synthetic-ods", input))
            .code());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () -> service.createDesignedTargetTable("synthetic-ods", input, "admin"))
            .code());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () ->
                    service.previewTargetTableColumns(
                        "synthetic-ods", "public", "visits", addition))
            .code());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () ->
                    service.addTargetTableColumns(
                        "synthetic-ods", "public", "visits", addition, "admin"))
            .code());
  }

  private static ByteArrayInputStream bytes(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
}
