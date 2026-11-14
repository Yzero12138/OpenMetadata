package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.Path;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.resources.hospital.HospitalIntegrationResource;
import org.openmetadata.service.security.AuthenticationException;
import org.openmetadata.service.security.DefaultAuthorizer;

class HospitalIntegrationResourceTest {
  @Test
  void newReadOnlyEndpointsAuthorizeBeforeParsingEvenABrokenStream() throws Exception {
    var resource = new HospitalIntegrationResource(new DefaultAuthorizer());
    for (String path :
        new String[] {
          "/connections/{id}/cdc-preflight", "/tasks/cdc-preflight", "/tasks/{id}/target-count"
        }) {
      var method =
          Arrays.stream(HospitalIntegrationResource.class.getDeclaredMethods())
              .filter(
                  value ->
                      value.isAnnotationPresent(Path.class)
                          && value.getAnnotation(Path.class).value().equals(path))
              .findFirst()
              .orElse(null);
      assertNotNull(method, path);
      var reads = new AtomicInteger();
      var broken =
          new InputStream() {
            @Override
            public int read() throws IOException {
              reads.incrementAndGet();
              throw new IOException("private-secret");
            }
          };
      var arguments =
          Arrays.stream(method.getParameterTypes())
              .map(type -> type == InputStream.class ? broken : null)
              .toArray();
      var failure =
          assertThrows(InvocationTargetException.class, () -> method.invoke(resource, arguments));
      assertEquals(AuthenticationException.class, failure.getCause().getClass());
      assertEquals(0, reads.get());
    }
  }

  @Test
  void everyEndpointRejectsMissingNativePrincipalBeforeAccessingConfigurationOrStorage() {
    HospitalIntegrationResource resource = new HospitalIntegrationResource(new DefaultAuthorizer());
    assertThrows(AuthenticationException.class, () -> resource.status(null));
    assertThrows(AuthenticationException.class, () -> resource.connections(null));
    assertThrows(AuthenticationException.class, () -> resource.connection(null, null));
    assertThrows(AuthenticationException.class, () -> resource.createConnection(null, null));
    assertThrows(AuthenticationException.class, () -> resource.updateConnection(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.deleteConnection(null, null, 1));
    assertThrows(AuthenticationException.class, () -> resource.tableOptions(null, null));
    assertThrows(AuthenticationException.class, () -> resource.table(null, null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.querySchema(null, null));
    assertThrows(
        AuthenticationException.class, () -> resource.previewTargetTable(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.createTargetTable(null, null, null));
    assertThrows(
        AuthenticationException.class, () -> resource.testConnection(null, "synthetic-source"));
    assertThrows(AuthenticationException.class, () -> resource.tables(null, "synthetic-source"));
    assertThrows(AuthenticationException.class, () -> resource.tasks(null));
    assertThrows(AuthenticationException.class, () -> resource.validate(null, null));
    assertThrows(AuthenticationException.class, () -> resource.create(null, null));
    assertThrows(AuthenticationException.class, () -> resource.task(null, null));
    assertThrows(AuthenticationException.class, () -> resource.update(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.run(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.initialization(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.stop(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.catalog(null, null, null));
  }
}
