package org.openmetadata.service.integration;

import java.util.List;
import java.util.Map;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public interface CdcSourceAdapter {
  String pluginName();

  /** Table metadata and the native database identity must come from the current preflight. */
  Map<String, Object> buildSource(
      String taskId,
      JdbcEndpoint endpoint,
      TableDefinition table,
      List<FieldMapping> mappings,
      CdcOptions options,
      IntegrationRuntimeOptions.Resolved runtime);
}
