package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.db.DataSourceFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openmetadata.schema.service.configuration.elasticsearch.ElasticSearchConfiguration;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.search.IndexMappingLoader;
import org.openmetadata.service.Entity;
import org.openmetadata.service.integration.IntegrationModels.FieldMapping;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.DatabaseRepository;
import org.openmetadata.service.jdbi3.DatabaseSchemaRepository;
import org.openmetadata.service.jdbi3.DatabaseServiceRepository;
import org.openmetadata.service.jdbi3.EntityRelationshipRepository;
import org.openmetadata.service.jdbi3.LineageRepository;
import org.openmetadata.service.jdbi3.PipelineRepository;
import org.openmetadata.service.jdbi3.PipelineServiceRepository;
import org.openmetadata.service.jdbi3.SystemRepository;
import org.openmetadata.service.jdbi3.TableRepository;
import org.openmetadata.service.jdbi3.UserRepository;
import org.openmetadata.service.resources.databases.DatasourceConfig;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.util.jdbi.JdbiUtils;

/**
 * Real repository/SQL/search fixture. The actor is a test label, not an authenticated employee.
 *
 * <p>Opt in with HOSPITAL_INTEGRATION_TEST_CATALOG_JDBC_URL for a disposable database named
 * integration_catalog_test, and HOSPITAL_INTEGRATION_TEST_SEARCH_URL for disposable OpenSearch.
 * The database requires the current migrated native metadata schema. To initialize an empty
 * database, optionally set HOSPITAL_INTEGRATION_TEST_CATALOG_SCHEMA_PATH to a local PostgreSQL
 * schema-only export produced with pg_dump --schema-only --no-owner --no-privileges. This explicit
 * local test input is not a repository or CI dependency; no data, users, or credentials are loaded.
 */
@EnabledIfEnvironmentVariable(named = "HOSPITAL_INTEGRATION_TEST_CATALOG_JDBC_URL", matches = ".+")
class IntegrationCatalogTest {
  private static final String PASSWORD = "integration-test-only";
  private static final String ACTOR = "synthetic-catalog-test";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static Jdbi database;
  private static IntegrationConfiguration configuration;

  @BeforeAll
  static void initializeRealNativeRepositories() throws Exception {
    String jdbcUrl = System.getenv("HOSPITAL_INTEGRATION_TEST_CATALOG_JDBC_URL");
    assertEquals("/integration_catalog_test", URI.create(jdbcUrl.substring(5)).getPath());
    DataSourceFactory dataSource = new DataSourceFactory();
    dataSource.setDriverClass("org.postgresql.Driver");
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser("postgres");
    dataSource.setPassword(PASSWORD);
    database = JdbiUtils.createAndSetupJDBI(dataSource);
    loadExplicitSchemaFixture();
    database.useHandle(
        handle -> {
          handle.execute(
              "TRUNCATE entity_extension, entity_relationship, field_relationship, change_event, tag_usage,"
                  + " table_entity, database_schema_entity, database_entity, dbservice_entity,"
                  + " pipeline_entity, pipeline_service_entity");
        });
    Entity.cleanup();
    Entity.setJdbi(database);
    CollectionDAO dao = database.onDemand(CollectionDAO.class);
    Entity.setCollectionDAO(dao);
    Entity.setEntityRelationshipRepository(new EntityRelationshipRepository(dao));
    new SystemRepository();
    new UserRepository();
    DatasourceConfig.initialize("org.postgresql.Driver");
    URI search = URI.create(System.getenv("HOSPITAL_INTEGRATION_TEST_SEARCH_URL"));
    ElasticSearchConfiguration searchConfiguration =
        new ElasticSearchConfiguration()
            .withHost(search.getHost())
            .withPort(search.getPort())
            .withScheme(search.getScheme())
            .withSearchType(ElasticSearchConfiguration.SearchType.OPENSEARCH)
            .withClusterAlias("hospital_integration_test")
            .withConnectionTimeoutSecs(2)
            .withSocketTimeoutSecs(5);
    IndexMappingLoader.init(searchConfiguration);
    SearchRepository searchRepository = new SearchRepository(searchConfiguration, 5);
    Entity.setSearchRepository(searchRepository);
    new DatabaseServiceRepository();
    new DatabaseRepository();
    new DatabaseSchemaRepository();
    new TableRepository();
    new PipelineServiceRepository();
    new PipelineRepository();
    new LineageRepository();
    for (String type :
        List.of(
            Entity.DATABASE_SERVICE,
            Entity.DATABASE,
            Entity.DATABASE_SCHEMA,
            Entity.TABLE,
            Entity.PIPELINE_SERVICE,
            Entity.PIPELINE)) {
      searchRepository.createIndex(searchRepository.getIndexMapping(type));
    }
    database.useHandle(
        handle -> {
          handle.execute(
              "CREATE TABLE IF NOT EXISTS public.integration_source_rows (id bigint PRIMARY KEY, name varchar(100))");
          handle.execute(
              "CREATE TABLE IF NOT EXISTS public.integration_target_rows (event_id bigint PRIMARY KEY, label varchar(100))");
        });
    configuration =
        IntegrationConfiguration.fromValues(
            Map.of(
                "HOSPITAL_INTEGRATION_ENABLED",
                "true",
                "HOSPITAL_SEATUNNEL_URL",
                "http://127.0.0.1:5801",
                "HOSPITAL_SOURCE_JDBC_URL",
                jdbcUrl,
                "HOSPITAL_SOURCE_JDBC_USER",
                "postgres",
                "HOSPITAL_SOURCE_JDBC_PASSWORD",
                PASSWORD,
                "HOSPITAL_TARGET_JDBC_URL",
                jdbcUrl,
                "HOSPITAL_TARGET_JDBC_USER",
                "postgres",
                "HOSPITAL_TARGET_JDBC_PASSWORD",
                PASSWORD));
  }

  @AfterAll
  static void clearGlobalRepositoryFixture() {
    Entity.cleanup();
  }

  @Test
  void nativeCatalogAndMappedColumnLineageAreDurableAndIdempotent() throws Exception {
    EntityExtensionTaskStore store =
        new EntityExtensionTaskStore(Entity.getCollectionDAO().entityExtensionDAO());
    IntegrationService service = new IntegrationService(configuration, store);
    TaskInput input = new TaskInput();
    input.name = "catalog-" + UUID.randomUUID().toString().substring(0, 8);
    input.displayName = "Synthetic catalog repository verification";
    input.sourceConnectionId = "synthetic-source";
    input.targetConnectionId = "synthetic-ods";
    input.sourceSchema = "public";
    input.sourceTable = "integration_source_rows";
    input.targetSchema = "public";
    input.targetTable = "integration_target_rows";
    input.mode = "CDC";
    input.primaryKey = "id";
    input.fieldMappings =
        List.of(new FieldMapping("id", "event_id"), new FieldMapping("name", "label"));
    IntegrationTask task = service.create(input, ACTOR);
    IntegrationTask first = service.syncCatalog(task.id, null, ACTOR);
    assertEquals("SYNCED", first.catalog.status);
    JsonNode source = row("table_entity", first.catalog.sourceFqn);
    JsonNode target = row("table_entity", first.catalog.targetFqn);
    JsonNode pipeline = row("pipeline_entity", first.catalog.pipelineFqn);
    assertEquals(ACTOR, pipeline.path("updatedBy").asText());
    assertEquals("id", source.path("columns").get(0).path("name").asText());
    assertEquals("BIGINT", source.path("columns").get(0).path("dataType").asText());
    assertEquals("PRIMARY_KEY", target.path("columns").get(0).path("constraint").asText());
    assertEquals(
        first.catalog.sourceFqn + ".id",
        source.path("columns").get(0).path("fullyQualifiedName").asText());
    JsonNode lineage = lineage(source, target);
    assertEquals(pipeline.path("id").asText(), lineage.path("pipeline").path("id").asText());
    assertEquals(2, lineage.path("columnsLineage").size());
    assertTrue(
        lineage
            .path("columnsLineage")
            .findValuesAsText("toColumn")
            .contains(first.catalog.targetFqn + ".event_id"));
    assertTrue(lineage.toString().contains(first.catalog.sourceFqn + ".id"));
    assertNotNull(first.catalog.syncedAt);
    assertServiceCredentialsAbsent("dbservice_entity");
    assertServiceCredentialsAbsent("pipeline_service_entity");

    IntegrationTask second = service.syncCatalog(task.id, null, ACTOR);
    assertEquals("SYNCED", second.catalog.status);
    assertEquals(source.path("id"), row("table_entity", second.catalog.sourceFqn).path("id"));
    assertEquals(target.path("id"), row("table_entity", second.catalog.targetFqn).path("id"));
    assertEquals(
        pipeline.path("id"), row("pipeline_entity", second.catalog.pipelineFqn).path("id"));
    assertEquals(2, count("table_entity"));
    assertEquals(2, count("dbservice_entity"));
    assertEquals(2, count("database_entity"));
    assertEquals(2, count("database_schema_entity"));
    assertEquals(1, count("pipeline_service_entity"));
    assertEquals(1, count("pipeline_entity"));
    assertEquals(lineage.path("columnsLineage"), lineage(source, target).path("columnsLineage"));
    assertEquals("SYNCED", store.get(UUID.fromString(task.id)).catalog.status);
  }

  private JsonNode row(String table, String fqn) throws Exception {
    String value =
        database.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT json::text FROM "
                            + table
                            + " WHERE json->>'fullyQualifiedName' = :fqn")
                    .bind("fqn", fqn)
                    .mapTo(String.class)
                    .one());
    return JSON.readTree(value);
  }

  private static void loadExplicitSchemaFixture() throws Exception {
    boolean initialized =
        database.withHandle(
            handle ->
                handle
                    .createQuery("SELECT to_regclass('public.table_entity') IS NOT NULL")
                    .mapTo(Boolean.class)
                    .one());
    if (initialized) {
      return;
    }
    String schemaPath = System.getenv("HOSPITAL_INTEGRATION_TEST_CATALOG_SCHEMA_PATH");
    assertNotNull(
        schemaPath, "An empty catalog test database requires an explicit schema fixture path.");
    String script =
        Files.readString(Path.of(schemaPath))
            .lines()
            .filter(line -> !line.startsWith("\\restrict ") && !line.startsWith("\\unrestrict "))
            .collect(Collectors.joining("\n"));
    try (var handle = database.open();
        Statement statement = handle.getConnection().createStatement()) {
      try {
        statement.execute(script);
      } finally {
        statement.execute("SET search_path TO public");
      }
    }
  }

  private JsonNode lineage(JsonNode source, JsonNode target) throws Exception {
    String value =
        database.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT json::text FROM entity_relationship WHERE fromId = :source AND toId = :target AND relation = :relation")
                    .bind("source", source.path("id").asText())
                    .bind("target", target.path("id").asText())
                    .bind("relation", Relationship.UPSTREAM.ordinal())
                    .mapTo(String.class)
                    .one());
    return JSON.readTree(value);
  }

  private int count(String table) {
    return database.withHandle(
        handle -> handle.createQuery("SELECT count(*) FROM " + table).mapTo(Integer.class).one());
  }

  private void assertServiceCredentialsAbsent(String table) {
    List<String> services =
        database.withHandle(
            handle ->
                handle.createQuery("SELECT json::text FROM " + table).mapTo(String.class).list());
    for (String service : services) {
      assertFalse(service.contains(PASSWORD));
      assertFalse(service.contains("jdbc:"));
      assertFalse(service.contains("password"));
    }
  }
}
