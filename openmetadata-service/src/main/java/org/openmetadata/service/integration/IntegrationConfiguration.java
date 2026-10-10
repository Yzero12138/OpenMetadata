package org.openmetadata.service.integration;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;

public final class IntegrationConfiguration {
  public static final String SOURCE_ID = "synthetic-source";
  public static final String TARGET_ID = "synthetic-ods";
  private final boolean enabled;
  private final URI engineUri;
  private final JdbcEndpoint source;
  private final JdbcEndpoint target;
  private final boolean configured;

  private IntegrationConfiguration(Map<String, String> values) {
    enabled = "true".equalsIgnoreCase(values.get("HOSPITAL_INTEGRATION_ENABLED"));
    URI parsedEngine = null;
    JdbcEndpoint parsedSource = null;
    JdbcEndpoint parsedTarget = null;
    try {
      if (enabled) {
        parsedEngine = URI.create(required(values, "HOSPITAL_SEATUNNEL_URL"));
        if (!Set.of("http", "https").contains(parsedEngine.getScheme())
            || parsedEngine.getHost() == null
            || parsedEngine.getUserInfo() != null
            || parsedEngine.getQuery() != null
            || parsedEngine.getFragment() != null) {
          throw new IllegalArgumentException();
        }
        parsedSource = endpoint(values, "HOSPITAL_SOURCE", SOURCE_ID);
        parsedTarget = endpoint(values, "HOSPITAL_TARGET", TARGET_ID);
      }
    } catch (IllegalArgumentException e) {
      parsedEngine = null;
      parsedSource = null;
      parsedTarget = null;
    }
    engineUri = parsedEngine;
    source = parsedSource;
    target = parsedTarget;
    configured = enabled && engineUri != null && source != null && target != null;
  }

  public static IntegrationConfiguration fromEnvironment() {
    return fromValues(System.getenv());
  }

  public static IntegrationConfiguration fromValues(Map<String, String> values) {
    return new IntegrationConfiguration(values);
  }

  public boolean enabled() {
    return enabled;
  }

  public boolean configured() {
    return configured;
  }

  public void requireReady() {
    if (!enabled) {
      throw new IntegrationException("INTEGRATION_DISABLED", 503);
    }
    if (!configured) {
      throw new IntegrationException("INVALID_CONFIGURATION", 503);
    }
  }

  public URI engineUri() {
    requireReady();
    return engineUri;
  }

  public JdbcEndpoint endpoint(String id) {
    requireReady();
    return switch (id == null ? "" : id) {
      case SOURCE_ID -> source;
      case TARGET_ID -> target;
      default -> throw new IntegrationException("INVALID_CONFIGURATION", 400);
    };
  }

  private static JdbcEndpoint endpoint(Map<String, String> values, String prefix, String id) {
    String jdbcUrl = required(values, prefix + "_JDBC_URL");
    if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalArgumentException();
    }
    URI databaseUri = URI.create(jdbcUrl.substring(5));
    String path = databaseUri.getPath();
    if (databaseUri.getHost() == null
        || databaseUri.getUserInfo() != null
        || databaseUri.getFragment() != null
        || path == null
        || !path.matches("/[a-zA-Z][a-zA-Z0-9_]{0,62}")) {
      throw new IllegalArgumentException();
    }
    String query = databaseUri.getRawQuery();
    if (query != null) {
      Set<String> seen = new HashSet<>();
      for (String option : query.split("&", -1)) {
        String[] pair = option.split("=", -1);
        if (pair.length != 2 || !seen.add(pair[0]) || !pair[1].matches("[1-9][0-9]?")) {
          throw new IllegalArgumentException();
        }
        int seconds = Integer.parseInt(pair[1]);
        if (!("connectTimeout".equals(pair[0]) && seconds <= 5)
            && !("socketTimeout".equals(pair[0]) && seconds <= 15)) {
          throw new IllegalArgumentException();
        }
      }
    }
    return new JdbcEndpoint(
        id,
        jdbcUrl,
        required(values, prefix + "_JDBC_USER"),
        required(values, prefix + "_JDBC_PASSWORD"),
        path.substring(1));
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank() || value.length() > 4096) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  public static final class JdbcEndpoint {
    private final String id;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String database;
    private final JdbcDialect dialect;
    private final List<String> schemas;
    private final String tlsMode;

    private JdbcEndpoint(
        String id, String jdbcUrl, String username, String password, String database) {
      this.id = id;
      this.jdbcUrl = jdbcUrl;
      this.username = username;
      this.password = password;
      this.database = database;
      this.dialect = JdbcDialect.Postgres;
      this.schemas = List.of("public");
      this.tlsMode = "DISABLED";
    }

    public JdbcEndpoint(ConnectionDefinition definition, String password) {
      id = definition.id;
      dialect = JdbcDialect.valueOf(definition.databaseType);
      jdbcUrl = dialect.jdbcUrl(definition);
      username = definition.username;
      this.password = password;
      database = definition.database;
      schemas = List.copyOf(definition.schemas);
      tlsMode = definition.tlsMode;
    }

    public String jdbcUrl() {
      return jdbcUrl;
    }

    public String username() {
      return username;
    }

    public String password() {
      return password;
    }

    public String database() {
      return database;
    }

    public JdbcDialect dialect() {
      return dialect;
    }

    public String driver() {
      return dialect.driver();
    }

    public List<String> schemas() {
      return schemas;
    }

    public String tlsMode() {
      return tlsMode;
    }

    public Properties connectionProperties() {
      return dialect.properties(username, password);
    }

    @Override
    public String toString() {
      return id;
    }
  }
}
