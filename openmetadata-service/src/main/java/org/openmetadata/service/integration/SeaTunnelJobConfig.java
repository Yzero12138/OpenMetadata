package org.openmetadata.service.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

public final class SeaTunnelJobConfig {
  private final Function<String, JdbcEndpoint> endpoints;

  public SeaTunnelJobConfig(IntegrationConfiguration configuration) {
    this(configuration::endpoint);
  }

  public SeaTunnelJobConfig(Function<String, JdbcEndpoint> endpoints) {
    this.endpoints = endpoints;
  }

  public Map<String, Object> build(IntegrationTask task) {
    if (!IntegrationValidator.validateStructure(task).valid()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    JdbcEndpoint sourceConnection = endpoints.apply(task.sourceConnectionId);
    JdbcEndpoint targetConnection = endpoints.apply(task.targetConnectionId);
    boolean cdc = "CDC".equals(task.mode);
    if (cdc
        && (sourceConnection.dialect() != JdbcDialect.Postgres
            || sourceConnection.database().contains("."))) {
      throw new IntegrationException("MODE_UNSUPPORTED", 400);
    }
    if (!sourceConnection.schemas().contains(task.sourceSchema)
        || !targetConnection.schemas().contains(task.targetSchema)) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("plugin_name", cdc ? "Postgres-CDC" : "Jdbc");
    source.put("plugin_output", "source");
    source.put("url", sourceConnection.jdbcUrl());
    source.put("username", sourceConnection.username());
    source.put("password", sourceConnection.password());
    if (cdc) {
      String replicationName = "hospital_" + task.id.replace("-", "");
      source.put("database-names", List.of(sourceConnection.database()));
      source.put("schema-names", List.of(task.sourceSchema));
      source.put(
          "table-names",
          List.of(sourceConnection.database() + "." + task.sourceSchema + "." + task.sourceTable));
      source.put("decoding.plugin.name", "pgoutput");
      source.put("startup.mode", "initial");
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
              "VERIFY".equals(sourceConnection.tlsMode()) ? "verify-full" : "disable",
              "schema.include.list",
              Pattern.quote(task.sourceSchema),
              "table.include.list",
              Pattern.quote(task.sourceSchema) + "\\." + Pattern.quote(task.sourceTable)));
    } else {
      source.put("driver", sourceConnection.driver());
      source.put("fetch_size", 1000);
      source.put("query_timeout_sec", 60);
      source.put(
          "query",
          "SELECT "
              + task.fieldMappings.stream()
                  .map(
                      mapping ->
                          sourceConnection.dialect().quote(mapping.source())
                              + " AS "
                              + sourceConnection.dialect().quote(mapping.target()))
                  .collect(Collectors.joining(", "))
              + " FROM "
              + sourceConnection.dialect().quote(task.sourceSchema)
              + "."
              + sourceConnection.dialect().quote(task.sourceTable));
      if (sourceConnection.dialect() == JdbcDialect.Oracle) {
        source.put("dialect", "HospitalOracle");
        source.put(
            "properties",
            Map.of(
                "oracle.net.CONNECT_TIMEOUT",
                "5000",
                "oracle.jdbc.ReadTimeout",
                "15000",
                "oracle.jdbc.timezoneAsRegion",
                "false"));
      }
    }
    List<Map<String, Object>> transform = List.of();
    if (cdc) {
      String projection =
          task.fieldMappings.stream()
              .map(mapping -> "`" + mapping.source() + "` AS `" + mapping.target() + "`")
              .collect(Collectors.joining(", "));
      transform =
          List.of(
              Map.of(
                  "plugin_name",
                  "Sql",
                  "plugin_input",
                  "source",
                  "plugin_output",
                  "mapped",
                  "query",
                  "SELECT " + projection + " FROM source"));
    }
    String targetKey =
        task.fieldMappings.stream()
            .filter(mapping -> mapping.source().equals(task.primaryKey))
            .map(FieldMapping::target)
            .findFirst()
            .orElseThrow(() -> new IntegrationException("INVALID_CONFIGURATION", 400));
    Map<String, Object> sink = new LinkedHashMap<>();
    sink.put("plugin_name", "Jdbc");
    sink.put("plugin_input", cdc ? "mapped" : "source");
    sink.put("url", targetConnection.jdbcUrl());
    sink.put("driver", targetConnection.driver());
    sink.put("username", targetConnection.username());
    sink.put("password", targetConnection.password());
    sink.put("generate_sink_sql", true);
    sink.put("database", targetConnection.database());
    sink.put(
        "table",
        targetConnection.dialect() == JdbcDialect.Mysql
            ? task.targetTable
            : task.targetSchema + "." + task.targetTable);
    if (targetConnection.dialect() == JdbcDialect.Oracle) {
      sink.put("dialect", "HospitalOracle");
      sink.put(
          "properties",
          Map.of(
              "oracle.net.CONNECT_TIMEOUT",
              "5000",
              "oracle.jdbc.ReadTimeout",
              "15000",
              "oracle.jdbc.timezoneAsRegion",
              "false"));
    }
    sink.put("primary_keys", List.of(targetKey));
    sink.put("enable_upsert", true);
    sink.put("is_exactly_once", false);
    sink.put("batch_size", 100);
    sink.put("batch_interval_ms", 1000);
    sink.put("schema_save_mode", "IGNORE");
    sink.put("data_save_mode", "APPEND_DATA");
    Map<String, Object> environment =
        Map.of(
            "job.mode",
            cdc ? "STREAMING" : "BATCH",
            "parallelism",
            1,
            "checkpoint.interval",
            5000,
            "checkpoint.timeout",
            60000,
            "checkpoint.retain-after-job-cancelled",
            true);
    return Map.of(
        "env",
        environment,
        "source",
        List.of(source),
        "transform",
        transform,
        "sink",
        List.of(sink));
  }
}
