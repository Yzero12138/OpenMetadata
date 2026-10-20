package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;

class DorisTableApiTest {
  @Test
  void nonDorisTargetRejectionDoesNotOpenAnySourceOrTargetConnection() {
    var service = service();
    var input = input("new_visits", "0".repeat(64));
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class,
                () -> service.previewTargetTable("synthetic-ods", input))
            .code());
    assertEquals(
        "UNSUPPORTED_TARGET_TYPE",
        assertThrows(
                IntegrationException.class, () -> service.createTargetTable("synthetic-ods", input))
            .code());
  }

  @Test
  void unsafeNamesAndMissingPreviewHashesAreRejectedBeforeJdbcAccess() {
    var service = service();
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () -> service.createTargetTable("synthetic-ods", input("new_visits", null)))
            .code());
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () ->
                    service.previewTargetTable(
                        "synthetic-ods", input("new_visits; DROP TABLE patients", null)))
            .code());
  }

  private TargetTableInput input(String name, String hash) {
    return new TargetTableInput(
        "synthetic-source",
        "public",
        "visits",
        "public",
        name,
        List.of(new FieldMapping("id", "id")),
        hash);
  }

  private IntegrationService service() {
    return new IntegrationService(
        IntegrationConnectionsTest.configuration(),
        new IntegrationConnectionsTest.MemoryTasks(),
        new IntegrationConnectionsTest.MemoryConnections());
  }
}
