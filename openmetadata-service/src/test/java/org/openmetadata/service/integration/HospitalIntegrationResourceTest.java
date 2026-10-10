package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openmetadata.service.resources.hospital.HospitalIntegrationResource;
import org.openmetadata.service.security.AuthenticationException;
import org.openmetadata.service.security.DefaultAuthorizer;

class HospitalIntegrationResourceTest {
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
    assertThrows(
        AuthenticationException.class, () -> resource.testConnection(null, "synthetic-source"));
    assertThrows(AuthenticationException.class, () -> resource.tables(null, "synthetic-source"));
    assertThrows(AuthenticationException.class, () -> resource.tasks(null));
    assertThrows(AuthenticationException.class, () -> resource.validate(null, null));
    assertThrows(AuthenticationException.class, () -> resource.create(null, null));
    assertThrows(AuthenticationException.class, () -> resource.task(null, null));
    assertThrows(AuthenticationException.class, () -> resource.update(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.run(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.stop(null, null, null));
    assertThrows(AuthenticationException.class, () -> resource.catalog(null, null, null));
  }
}
