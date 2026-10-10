package org.openmetadata.service.integration;

import jakarta.ws.rs.core.UriInfo;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.lineage.AddLineage;
import org.openmetadata.schema.api.services.CreateDatabaseService.DatabaseServiceType;
import org.openmetadata.schema.api.services.CreatePipelineService.PipelineServiceType;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Pipeline;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.services.DatabaseService;
import org.openmetadata.schema.entity.services.PipelineService;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnConstraint;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.ColumnLineage;
import org.openmetadata.schema.type.EntitiesEdge;
import org.openmetadata.schema.type.LineageDetails;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.type.TableType;
import org.openmetadata.schema.type.Task;
import org.openmetadata.service.Entity;
import org.openmetadata.service.integration.IntegrationModels.CatalogSummary;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.jdbi3.DatabaseRepository;
import org.openmetadata.service.jdbi3.DatabaseSchemaRepository;
import org.openmetadata.service.jdbi3.DatabaseServiceRepository;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.PipelineRepository;
import org.openmetadata.service.jdbi3.PipelineServiceRepository;
import org.openmetadata.service.jdbi3.TableRepository;
import org.openmetadata.service.util.FullyQualifiedName;

public final class CatalogProjector {
  private static final String PIPELINE_SERVICE = "hospital_seatunnel";
  private final IntegrationConnections connections;

  public CatalogProjector(IntegrationConnections connections) {
    this.connections = connections;
  }

  public CatalogSummary sync(
      IntegrationTask task,
      TableDefinition sourceDefinition,
      TableDefinition targetDefinition,
      UriInfo uriInfo,
      String actor) {
    Table source =
        table(
            serviceName(task.sourceConnectionId),
            connections.connection(task.sourceConnectionId).displayName,
            task.sourceConnectionId,
            sourceDefinition,
            uriInfo,
            actor);
    Table target =
        table(
            serviceName(task.targetConnectionId),
            connections.connection(task.targetConnectionId).displayName,
            task.targetConnectionId,
            targetDefinition,
            uriInfo,
            actor);
    PipelineServiceRepository serviceRepository =
        (PipelineServiceRepository) Entity.getEntityRepository(Entity.PIPELINE_SERVICE);
    PipelineService service =
        upsert(
            serviceRepository,
            new PipelineService()
                .withName(PIPELINE_SERVICE)
                .withDisplayName("医院 SeaTunnel 集成")
                .withDescription(
                    "Apache SeaTunnel integration tasks. Runtime connections are managed outside the catalog.")
                .withServiceType(PipelineServiceType.CustomPipeline),
            uriInfo,
            actor);
    PipelineRepository pipelineRepository =
        (PipelineRepository) Entity.getEntityRepository(Entity.PIPELINE);
    String pipelineName = "integration_" + task.id.replace("-", "");
    Pipeline pipeline =
        upsert(
            pipelineRepository,
            new Pipeline()
                .withName(pipelineName)
                .withDisplayName(
                    task.displayName == null || task.displayName.isBlank()
                        ? task.name
                        : task.displayName)
                .withDescription(
                    "SeaTunnel "
                        + task.mode
                        + " integration from "
                        + source.getFullyQualifiedName()
                        + " to "
                        + target.getFullyQualifiedName()
                        + ". Control task: "
                        + task.id)
                .withService(service.getEntityReference())
                .withTasks(List.of(new Task().withName(task.name).withTaskType(task.mode))),
            uriInfo,
            actor);
    List<ColumnLineage> columns =
        task.fieldMappings.stream()
            .map(
                mapping ->
                    new ColumnLineage()
                        .withFromColumns(
                            List.of(
                                FullyQualifiedName.add(
                                    source.getFullyQualifiedName(), mapping.source())))
                        .withToColumn(
                            FullyQualifiedName.add(
                                target.getFullyQualifiedName(), mapping.target())))
            .toList();
    Entity.getLineageRepository()
        .addLineage(
            new AddLineage()
                .withEdge(
                    new EntitiesEdge()
                        .withFromEntity(source.getEntityReference())
                        .withToEntity(target.getEntityReference())
                        .withLineageDetails(
                            new LineageDetails()
                                .withPipeline(pipeline.getEntityReference())
                                .withColumnsLineage(columns))),
            actor);
    CatalogSummary summary = new CatalogSummary();
    summary.status = "SYNCED";
    summary.syncedAt = System.currentTimeMillis();
    summary.sourceFqn = source.getFullyQualifiedName();
    summary.targetFqn = target.getFullyQualifiedName();
    summary.pipelineFqn = pipeline.getFullyQualifiedName();
    return summary;
  }

  private Table table(
      String serviceName,
      String displayName,
      String connectionId,
      TableDefinition definition,
      UriInfo uriInfo,
      String actor) {
    DatabaseServiceRepository serviceRepository =
        (DatabaseServiceRepository) Entity.getEntityRepository(Entity.DATABASE_SERVICE);
    DatabaseService service =
        upsert(
            serviceRepository,
            new DatabaseService()
                .withName(serviceName)
                .withDisplayName(displayName)
                .withServiceType(
                    DatabaseServiceType.fromValue(
                        connections.connection(connectionId).databaseType))
                .withDescription(
                    connections.connection(connectionId).synthetic
                        ? "Isolated synthetic integration fixture. Runtime credentials are not stored in the catalog."
                        : "Registered business integration connection. Runtime credentials are not stored in the catalog."),
            uriInfo,
            actor);
    DatabaseRepository databaseRepository =
        (DatabaseRepository) Entity.getEntityRepository(Entity.DATABASE);
    Database database =
        upsert(
            databaseRepository,
            new Database()
                .withName(connections.connection(connectionId).database)
                .withService(service.getEntityReference()),
            uriInfo,
            actor);
    DatabaseSchemaRepository schemaRepository =
        (DatabaseSchemaRepository) Entity.getEntityRepository(Entity.DATABASE_SCHEMA);
    DatabaseSchema schema =
        upsert(
            schemaRepository,
            new DatabaseSchema()
                .withName(definition.schema())
                .withDatabase(database.getEntityReference()),
            uriInfo,
            actor);
    TableRepository tableRepository = (TableRepository) Entity.getEntityRepository(Entity.TABLE);
    List<String> keys =
        definition.columns().stream()
            .filter(ColumnDefinition::primaryKey)
            .map(ColumnDefinition::name)
            .toList();
    Table table =
        new Table()
            .withName(definition.name())
            .withDatabaseSchema(schema.getEntityReference())
            .withTableType(TableType.Regular)
            .withColumns(columns(definition));
    if (!keys.isEmpty()) {
      table.withTableConstraints(
          List.of(
              new TableConstraint()
                  .withConstraintType(TableConstraint.ConstraintType.PRIMARY_KEY)
                  .withColumns(keys)));
    }
    return upsert(tableRepository, table, uriInfo, actor);
  }

  static List<Column> columns(TableDefinition definition) {
    List<Column> columns = new ArrayList<>();
    int position = 1;
    for (ColumnDefinition field : definition.columns()) {
      Column column =
          new Column()
              .withName(field.name())
              .withDataType(dataType(field))
              .withDataTypeDisplay(field.dataType())
              .withOrdinalPosition(position++)
              .withConstraint(
                  field.primaryKey()
                      ? ColumnConstraint.PRIMARY_KEY
                      : field.nullable() ? ColumnConstraint.NULL : ColumnConstraint.NOT_NULL);
      if (Set.of(Types.VARCHAR, Types.CHAR, Types.NVARCHAR, Types.NCHAR)
          .contains(field.jdbcType())) {
        column.withDataLength(field.size());
      }
      if (field.jdbcType() == Types.NUMERIC || field.jdbcType() == Types.DECIMAL) {
        column.withPrecision(field.size()).withScale(field.scale());
      }
      columns.add(column);
    }
    return List.copyOf(columns);
  }

  private static ColumnDataType dataType(ColumnDefinition column) {
    return switch (column.jdbcType()) {
      case Types.SMALLINT -> ColumnDataType.SMALLINT;
      case Types.INTEGER -> ColumnDataType.INT;
      case Types.BIGINT -> ColumnDataType.BIGINT;
      case Types.NUMERIC, Types.DECIMAL -> ColumnDataType.NUMERIC;
      case Types.REAL -> ColumnDataType.FLOAT;
      case Types.FLOAT, Types.DOUBLE -> ColumnDataType.DOUBLE;
      case Types.BIT, Types.BOOLEAN -> ColumnDataType.BOOLEAN;
      case Types.CHAR, Types.NCHAR -> ColumnDataType.CHAR;
      case Types.VARCHAR, Types.NVARCHAR -> ColumnDataType.VARCHAR;
      case Types.LONGVARCHAR, Types.LONGNVARCHAR -> ColumnDataType.TEXT;
      case Types.DATE -> ColumnDataType.DATE;
      case Types.TIME, Types.TIME_WITH_TIMEZONE -> ColumnDataType.TIME;
      case Types.TIMESTAMP -> ColumnDataType.TIMESTAMP;
      case Types.TIMESTAMP_WITH_TIMEZONE -> ColumnDataType.TIMESTAMPZ;
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> ColumnDataType.BYTEA;
      case Types.ARRAY -> ColumnDataType.ARRAY;
      default -> switch (column.dataType().toLowerCase(Locale.ROOT)) {
        case "uuid" -> ColumnDataType.UUID;
        case "json", "jsonb" -> ColumnDataType.JSON;
        default -> ColumnDataType.UNKNOWN;
      };
    };
  }

  private <T extends EntityInterface> T upsert(
      EntityRepository<T> repository, T entity, UriInfo uriInfo, String actor) {
    entity.setId(UUID.randomUUID());
    entity.setUpdatedAt(System.currentTimeMillis());
    entity.setUpdatedBy(actor);
    repository.prepareInternal(entity, true);
    return repository.createOrUpdate(uriInfo, entity, actor).getEntity();
  }

  private String serviceName(String id) {
    return switch (id) {
      case IntegrationConfiguration.SOURCE_ID -> "hospital_synthetic_source";
      case IntegrationConfiguration.TARGET_ID -> "hospital_synthetic_ods";
      default -> "hospital_connection_" + id.replace("-", "");
    };
  }
}
