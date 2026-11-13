package org.openmetadata.service.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.openmetadata.service.integration.IntegrationCdcModels.CdcOptions;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

public final class PostgresCdcAdapter implements CdcSourceAdapter {
  @Override
  public String pluginName() {
    return "Postgres-CDC";
  }

  @Override
  public Map<String, Object> buildSource(
      String taskId,
      JdbcEndpoint endpoint,
      TableDefinition table,
      List<FieldMapping> mappings,
      CdcOptions options,
      IntegrationRuntimeOptions.Resolved runtime) {
    if (endpoint == null
        || endpoint.dialect() != JdbcDialect.Postgres
        || endpoint.database().contains(".")) {
      throw new IntegrationException("MODE_UNSUPPORTED", 400);
    }
    if (taskId == null
        || !taskId.matches("[a-zA-Z0-9-]{1,64}")
        || table == null
        || runtime == null
        || runtime.splitSize() == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    IntegrationValidator.requireTable(table.schema(), table.name());
    if (!endpoint.schemas().contains(table.schema())) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    String replicationName = "hospital_" + taskId.replace("-", "");
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("plugin_name", pluginName());
    source.put("plugin_output", "source");
    source.put("url", endpoint.jdbcUrl());
    source.put("username", endpoint.username());
    source.put("password", endpoint.password());
    source.put("database-names", List.of(endpoint.database()));
    source.put("schema-names", List.of(table.schema()));
    source.put(
        "table-names", List.of(endpoint.database() + "." + table.schema() + "." + table.name()));
    source.put("decoding.plugin.name", "pgoutput");
    source.put("startup.mode", "initial");
    source.put("snapshot.fetch.size", runtime.fetchSize());
    source.put("snapshot.split.size", runtime.splitSize());
    source.put("slot.name", replicationName);
    source.put(
        "debezium",
        Map.of(
            "publication.name",
            replicationName,
            "publication.autocreate.mode",
            "filtered",
            "slot.drop.on.stop",
            "false",
            "database.sslmode",
            "VERIFY".equals(endpoint.tlsMode()) ? "verify-full" : "disable",
            "schema.include.list",
            Pattern.quote(table.schema()),
            "table.include.list",
            Pattern.quote(table.schema()) + "\\." + Pattern.quote(table.name())));
    return source;
  }
}
