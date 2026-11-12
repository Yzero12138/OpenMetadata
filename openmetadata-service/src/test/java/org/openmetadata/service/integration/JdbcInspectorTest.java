package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.conf.ConnectionUrl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;

class JdbcInspectorTest {
  @Test
  void describesAliasedExpressionsWithoutExecutingOrReadingSourceRows() throws Exception {
    try (Connection connection = fixture()) {
      List<String> operations = new ArrayList<>();
      JdbcEndpoint endpoint = endpoint();
      var inspector =
          new JdbcInspector(
              id -> endpoint, (ignored, properties) -> guarded(connection, operations));
      var table =
          inspector.query(
              endpoint,
              "public",
              "WITH v AS (SELECT id, amount FROM visits WHERE biz_day = CAST(:biz_date AS date)) "
                  + "SELECT id AS \"visit_id\", amount * 2 AS \"total\", CAST(NULL AS VARCHAR(40)) AS \"optional\" FROM v",
              LocalDate.of(2026, 1, 1));
      assertEquals("public", table.schema());
      assertEquals("sql_result", table.name());
      assertEquals(
          List.of("visit_id", "total", "optional"),
          table.columns().stream().map(c -> c.name()).toList());
      assertEquals(Types.BIGINT, table.columns().getFirst().jdbcType());
      assertEquals(Types.NUMERIC, table.columns().get(1).jdbcType());
      assertEquals(40, table.columns().get(2).size());
      assertTrue(table.columns().get(2).nullable());
      assertTrue(table.columns().stream().noneMatch(c -> c.primaryKey()));
      assertTrue(operations.contains("readOnly=true"));
      assertTrue(operations.contains("timeout=5"));
      assertTrue(operations.contains("maxRows=1"));
      assertTrue(operations.stream().anyMatch(op -> op.contains("FROM \"public\".visits")));
      assertTrue(operations.stream().anyMatch(op -> op.contains("'2026-01-01'")));
      assertFalse(operations.stream().anyMatch(op -> op.startsWith("execute")));
    }
  }

  @Test
  void rejectsUnsafeDuplicateAndExcessiveResultAliases() throws Exception {
    for (String projection :
        List.of(
            "id AS \"bad alias\"",
            "id AS \"id\", amount AS \"id\"",
            "id AS \"id\", amount AS \"ID\"",
            IntStream.range(0, 129)
                .mapToObj(i -> "id AS \"column_" + i + "\"")
                .collect(Collectors.joining(", ")))) {
      try (Connection connection = fixture()) {
        var endpoint = endpoint();
        var inspector = new JdbcInspector(id -> endpoint, (ignored, properties) -> connection);
        assertEquals(
            "INVALID_QUERY_COLUMNS",
            assertThrows(
                    IntegrationException.class,
                    () ->
                        inspector.query(
                            endpoint,
                            "public",
                            "SELECT "
                                + projection
                                + " FROM visits WHERE biz_day = CAST(:biz_date AS date)",
                            LocalDate.of(2026, 1, 1)))
                .code());
      }
    }
  }

  @Test
  void validatesSqlBeforeOpeningTheDatabaseAndRedactsMetadataErrors() {
    var endpoint = endpoint();
    var inspector =
        new JdbcInspector(
            id -> endpoint,
            (ignored, properties) -> {
              throw new AssertionError("Unsafe SQL must not reach JDBC");
            });
    assertEquals(
        "INVALID_SOURCE_QUERY",
        assertThrows(
                IntegrationException.class,
                () ->
                    inspector.query(
                        endpoint,
                        "public",
                        "DELETE FROM visits WHERE day = :biz_date",
                        LocalDate.now()))
            .code());
    var unavailable =
        new JdbcInspector(
            id -> endpoint,
            (ignored, properties) -> {
              throw new SQLException("sensitive host, username and query text");
            });
    var error =
        assertThrows(
            IntegrationException.class,
            () ->
                unavailable.query(
                    endpoint,
                    "public",
                    "SELECT id FROM visits WHERE day = :biz_date",
                    LocalDate.now()));
    assertEquals("QUERY_METADATA_UNAVAILABLE", error.code());
    assertFalse(error.getMessage().contains("sensitive"));
  }

  @Test
  void mysqlMetadataForcesServerPrepareAndDisablesClientExecutionFallbackOnlyForQueryInspection() {
    var definition = new ConnectionDefinition();
    definition.copyInput(IntegrationConnectionsTest.input("Mysql"));
    var endpoint = new JdbcEndpoint(definition, "synthetic-password");
    List<Properties> opened = new ArrayList<>();
    var inspector =
        new JdbcInspector(
            id -> endpoint,
            (ignored, properties) -> {
              opened.add(properties);
              throw new SQLException("synthetic connection boundary");
            });
    assertEquals(
        "QUERY_METADATA_UNAVAILABLE",
        assertThrows(
                IntegrationException.class,
                () ->
                    inspector.query(
                        endpoint,
                        "business",
                        "SELECT id FROM visits WHERE biz_day = :biz_date",
                        LocalDate.of(2025, 12, 31)))
            .code());
    Properties metadataProperties = opened.getFirst();
    assertEquals("true", metadataProperties.getProperty("useServerPrepStmts"));
    assertEquals("false", metadataProperties.getProperty("emulateUnsupportedPstmts"));
    assertEquals("15000", metadataProperties.getProperty("socketTimeout"));
    assertEquals(endpoint.username(), metadataProperties.getProperty("user"));
    var driverProperties =
        ConnectionUrl.getConnectionUrlInstance(
                "jdbc:mysql://localhost/business?useServerPrepStmts=false&emulateUnsupportedPstmts=true",
                metadataProperties)
            .getMainHost()
            .getHostProperties();
    assertEquals("true", driverProperties.get("useServerPrepStmts"));
    assertEquals("false", driverProperties.get("emulateUnsupportedPstmts"));
    assertThrows(IntegrationException.class, () -> inspector.test("source"));
    assertEquals(endpoint.connectionProperties(), opened.get(1));
    assertFalse(opened.get(1).containsKey("useServerPrepStmts"));
  }

  @Test
  void sqlServerMetadataTerminatesTheDriversFmtOnlyPrefixBeforeAWithQuery() throws Exception {
    try (Connection connection = fixture()) {
      var definition = new ConnectionDefinition();
      definition.copyInput(IntegrationConnectionsTest.input("Mssql"));
      var endpoint = new JdbcEndpoint(definition, "synthetic-password");
      List<String> operations = new ArrayList<>();
      var inspector =
          new JdbcInspector(
              id -> endpoint, (ignored, properties) -> guarded(connection, operations));
      assertEquals(
          "QUERY_METADATA_UNAVAILABLE",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      inspector.query(
                          endpoint,
                          "dbo",
                          "WITH v AS (SELECT 1 AS \"id\" WHERE CAST(:biz_date AS DATE) = DATE '2025-12-31') SELECT \"id\" FROM v",
                          LocalDate.of(2025, 12, 31)))
              .code());
      assertTrue(operations.stream().anyMatch(operation -> operation.startsWith(";WITH v AS")));
    }
  }

  private Connection fixture() throws Exception {
    Connection connection =
        DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE visits (id BIGINT PRIMARY KEY, amount NUMERIC(10, 2), biz_day DATE)");
      statement.execute("INSERT INTO visits VALUES (1, 20.00, DATE '2026-01-01')");
    }
    return connection;
  }

  private JdbcEndpoint endpoint() {
    var definition = new ConnectionDefinition();
    definition.copyInput(IntegrationConnectionsTest.input("Postgres"));
    return new JdbcEndpoint(definition, "synthetic-password");
  }

  private Connection guarded(Connection delegate, List<String> operations) {
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("setReadOnly")) {
                operations.add("readOnly=" + args[0]);
              }
              if (method.getName().equals("prepareStatement")) {
                operations.add((String) args[0]);
                PreparedStatement statement = (PreparedStatement) method.invoke(delegate, args);
                return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (statementProxy, statementMethod, statementArgs) -> {
                      if (statementMethod.getName().startsWith("execute")) {
                        throw new AssertionError("Metadata inspection must never execute a query");
                      }
                      if (statementMethod.getName().equals("setQueryTimeout")) {
                        operations.add("timeout=" + statementArgs[0]);
                      }
                      if (statementMethod.getName().equals("setMaxRows")) {
                        operations.add("maxRows=" + statementArgs[0]);
                      }
                      try {
                        return statementMethod.invoke(statement, statementArgs);
                      } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                      }
                    });
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException exception) {
                throw exception.getCause();
              }
            });
  }
}
