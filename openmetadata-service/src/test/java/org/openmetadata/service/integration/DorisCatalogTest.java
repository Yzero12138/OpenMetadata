package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

class DorisCatalogTest {
  @Test
  void tinyIntColumnUsesNativeCatalogTypeAndRetainsItsDisplayType() {
    var tiny = DorisTableMetadata.column("small_value", "tinyint", true, false, false);
    var column =
        CatalogProjector.columns(new TableDefinition("ods_validation", "visits", List.of(tiny)))
            .getFirst();
    assertEquals(ColumnDataType.TINYINT, column.getDataType());
    assertEquals("TINYINT", column.getDataTypeDisplay());
  }
}
