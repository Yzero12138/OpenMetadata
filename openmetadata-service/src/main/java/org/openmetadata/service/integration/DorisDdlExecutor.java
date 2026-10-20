package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.TargetTablePreview;

final class DorisDdlExecutor {
  void requireAbsent(JdbcEndpoint endpoint, String schema, String table) {
    requireScope(endpoint, schema, table);
    try (Connection connection =
        DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties())) {
      requireAbsent(connection, schema, table);
    } catch (SQLException e) {
      throw new IntegrationException("CONNECTION_UNAVAILABLE", 503);
    }
  }

  void create(JdbcEndpoint endpoint, TargetTablePreview preview) {
    requireScope(endpoint, preview.table().schema(), preview.table().name());
    try (Connection connection =
        DriverManager.getConnection(endpoint.jdbcUrl(), endpoint.connectionProperties())) {
      requireAbsent(connection, preview.table().schema(), preview.table().name());
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(15);
        statement.executeUpdate(preview.ddl());
      }
    } catch (SQLException e) {
      if (e.getErrorCode() == 1050 || "42S01".equals(e.getSQLState())) {
        throw new IntegrationException("TABLE_ALREADY_EXISTS", 409);
      }
      throw new IntegrationException("TARGET_TABLE_CREATE_FAILED", 503);
    }
  }

  private void requireAbsent(Connection connection, String schema, String table)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw new IntegrationException("TABLE_ALREADY_EXISTS", 409);
        }
      }
    }
  }

  private void requireScope(JdbcEndpoint endpoint, String schema, String table) {
    IntegrationValidator.requireTable(schema, table);
    if (endpoint.dialect() != JdbcDialect.Doris) {
      throw new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
    }
    if (!endpoint.schemas().contains(schema) || !endpoint.database().equals(schema)) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
  }
}
