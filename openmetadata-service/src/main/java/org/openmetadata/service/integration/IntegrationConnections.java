package org.openmetadata.service.integration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.openmetadata.service.fernet.Fernet;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.ConnectionUpdate;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.StoredConnection;

public final class IntegrationConnections {
  private static final int MAX_CONNECTIONS = 100;
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,62}");
  private static final Pattern DATABASE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.-]{0,126}");
  private static final Pattern HOST =
      Pattern.compile("(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?");
  private static final Set<String> TERMINAL =
      Set.of("FINISHED", "FAILED", "CANCELED", "CANCELLED", "STOPPED");
  private final IntegrationConfiguration configuration;
  private final IntegrationConnectionStore store;
  private final IntegrationTaskStore tasks;

  public IntegrationConnections(
      IntegrationConfiguration configuration,
      IntegrationConnectionStore store,
      IntegrationTaskStore tasks) {
    this.configuration = configuration;
    this.store = store;
    this.tasks = tasks;
  }

  public List<ConnectionDefinition> list() {
    configuration.requireReady();
    synchronized (IntegrationService.TASK_LOCK) {
      List<ConnectionDefinition> connections = new ArrayList<>();
      connections.add(seed(IntegrationConfiguration.SOURCE_ID));
      connections.add(seed(IntegrationConfiguration.TARGET_ID));
      connections.addAll(
          store.list().stream()
              .map(value -> value.definition)
              .sorted(Comparator.comparing(value -> value.name))
              .toList());
      return List.copyOf(connections);
    }
  }

  public ConnectionDefinition connection(String id) {
    configuration.requireReady();
    return isSeed(id) ? seed(id) : store.get(uuid(id)).definition;
  }

  public JdbcEndpoint endpoint(String id) {
    configuration.requireReady();
    if (isSeed(id)) {
      return configuration.endpoint(id);
    }
    StoredConnection stored = store.get(uuid(id));
    if (!Boolean.TRUE.equals(stored.definition.enabled)) {
      throw new IntegrationException("CONNECTION_DISABLED", 409);
    }
    try {
      return new JdbcEndpoint(
          stored.definition, Fernet.getInstance().decrypt(stored.encryptedPassword));
    } catch (RuntimeException e) {
      throw new IntegrationException("CREDENTIALS_NOT_CONFIGURED", 503);
    }
  }

  public ConnectionDefinition create(ConnectionInput input, String actor) {
    configuration.requireReady();
    synchronized (IntegrationService.TASK_LOCK) {
      validate(input, true);
      List<StoredConnection> existing = store.list();
      if (existing.size() >= MAX_CONNECTIONS) {
        throw new IntegrationException("CAPACITY_EXCEEDED", 409);
      }
      uniqueName(input.name, null, existing);
      StoredConnection stored = new StoredConnection();
      stored.definition = new ConnectionDefinition();
      stored.definition.copyInput(input);
      stored.definition.id = UUID.randomUUID().toString();
      stored.definition.version = 1;
      stored.definition.passwordSet = true;
      stored.definition.createdAt = System.currentTimeMillis();
      stored.definition.updatedAt = stored.definition.createdAt;
      stored.definition.updatedBy = actor;
      stored.definition.supportedModes = supportedModes(input);
      stored.encryptedPassword = encrypt(input.password);
      store.save(stored);
      return stored.definition;
    }
  }

  public ConnectionDefinition update(String id, ConnectionUpdate input, String actor) {
    configuration.requireReady();
    synchronized (IntegrationService.TASK_LOCK) {
      requireMutable(id);
      validate(input, false);
      StoredConnection stored = store.get(uuid(id));
      if (input.version == null || input.version != stored.definition.version) {
        throw new IntegrationException("VERSION_CONFLICT", 409);
      }
      if (tasks.list().stream().anyMatch(task -> references(task, id) && blocked(task))) {
        throw new IntegrationException("TASK_ACTIVE", 409);
      }
      uniqueName(input.name, id, store.list());
      stored.definition.copyInput(input);
      stored.definition.version++;
      stored.definition.updatedAt = System.currentTimeMillis();
      stored.definition.updatedBy = actor;
      stored.definition.supportedModes = supportedModes(input);
      if (input.password != null && !input.password.isEmpty()) {
        stored.encryptedPassword = encrypt(input.password);
      }
      store.save(stored);
      return stored.definition;
    }
  }

  public void delete(String id, long version) {
    configuration.requireReady();
    synchronized (IntegrationService.TASK_LOCK) {
      requireMutable(id);
      StoredConnection stored = store.get(uuid(id));
      if (stored.definition.version != version) {
        throw new IntegrationException("VERSION_CONFLICT", 409);
      }
      if (tasks.list().stream().anyMatch(task -> references(task, id))) {
        throw new IntegrationException("CONNECTION_IN_USE", 409);
      }
      store.delete(uuid(id));
    }
  }

  public void requireRole(String id, String role, String mode) {
    ConnectionDefinition connection = connection(id);
    if (!role.equals(connection.role)) {
      throw new IntegrationException("CONNECTION_ROLE_MISMATCH", 400);
    }
    if (!Boolean.TRUE.equals(connection.enabled)) {
      throw new IntegrationException("CONNECTION_DISABLED", 409);
    }
    if ("SOURCE".equals(role) && !connection.supportedModes.contains(mode)) {
      throw new IntegrationException("MODE_UNSUPPORTED", 400);
    }
  }

  private ConnectionDefinition seed(String id) {
    JdbcEndpoint endpoint = configuration.endpoint(id);
    URI uri = URI.create(endpoint.jdbcUrl().substring(5));
    ConnectionDefinition definition = new ConnectionDefinition();
    definition.id = id;
    definition.name = id;
    definition.displayName = IntegrationConfiguration.SOURCE_ID.equals(id) ? "合成业务源" : "合成 ODS 目标";
    definition.role = IntegrationConfiguration.SOURCE_ID.equals(id) ? "SOURCE" : "TARGET";
    definition.databaseType = "Postgres";
    definition.host = uri.getHost();
    definition.port = uri.getPort() == -1 ? 5432 : uri.getPort();
    definition.database = endpoint.database();
    definition.username = endpoint.username();
    definition.schemas = endpoint.schemas();
    definition.tlsMode = "DISABLED";
    definition.enabled = true;
    definition.managed = true;
    definition.synthetic = true;
    definition.passwordSet = true;
    definition.supportedModes = List.of("FULL", "CDC");
    return definition;
  }

  private void validate(ConnectionInput input, boolean create) {
    if (input == null
        || input.name == null
        || !NAME.matcher(input.name).matches()
        || input.displayName == null
        || input.displayName.isBlank()
        || !text(input.displayName, 120)
        || !Set.of("SOURCE", "TARGET").contains(value(input.role))
        || !Set.of("Oracle", "Mssql", "Mysql", "Postgres").contains(value(input.databaseType))
        || input.host == null
        || !HOST.matcher(input.host).matches()
        || input.host.contains("..")
        || input.port == null
        || input.port < 1
        || input.port > 65535
        || input.database == null
        || !DATABASE.matcher(input.database).matches()
        || input.username == null
        || input.username.isBlank()
        || !text(input.username, 128)
        || (create && (input.password == null || input.password.isEmpty()))
        || (input.password != null
            && (input.password.length() > 4096 || input.password.indexOf('\0') >= 0))
        || input.schemas == null
        || input.schemas.isEmpty()
        || input.schemas.size() > 8
        || input.schemas.stream().anyMatch(schema -> !IntegrationValidator.safeIdentifier(schema))
        || new HashSet<>(input.schemas).size() != input.schemas.size()
        || !Set.of("DISABLED", "VERIFY").contains(value(input.tlsMode))
        || input.enabled == null
        || input.databaseVersion == null
        || !input.databaseVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._ -]{0,29}")) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    if ("Oracle".equals(input.databaseType)) {
      if (!Set.of("SERVICE_NAME", "SID").contains(value(input.oracleConnectionType))) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
    } else if (input.oracleConnectionType != null && !input.oracleConnectionType.isEmpty()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    if ("Mysql".equals(input.databaseType) && !input.schemas.equals(List.of(input.database))) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  private List<String> supportedModes(ConnectionInput input) {
    if ("Postgres".equals(input.databaseType)
        && !input.database.contains(".")
        && input.databaseVersion.matches("[0-9]{1,2}(?:[.][0-9]+)*")
        && Integer.parseInt(input.databaseVersion.split("[.]", 2)[0]) >= 10) {
      return List.of("FULL", "CDC");
    }
    return List.of("FULL");
  }

  private String encrypt(String password) {
    try {
      return Fernet.getInstance().encrypt(password);
    } catch (RuntimeException e) {
      throw new IntegrationException("CREDENTIALS_NOT_CONFIGURED", 503);
    }
  }

  private void uniqueName(String name, String id, List<StoredConnection> existing) {
    if (isSeed(name)
        || existing.stream()
            .anyMatch(
                value -> !value.definition.id.equals(id) && value.definition.name.equals(name))) {
      throw new IntegrationException("DUPLICATE_CONNECTION", 409);
    }
  }

  private boolean references(IntegrationTask task, String id) {
    return id.equals(task.sourceConnectionId) || id.equals(task.targetConnectionId);
  }

  private boolean blocked(IntegrationTask task) {
    return task.latestRun != null
        && (!TERMINAL.contains(task.latestRun.status)
            || Boolean.TRUE.equals(task.latestRun.canResume));
  }

  private boolean isSeed(String id) {
    return IntegrationConfiguration.SOURCE_ID.equals(id)
        || IntegrationConfiguration.TARGET_ID.equals(id);
  }

  private void requireMutable(String id) {
    if (isSeed(id)) {
      throw new IntegrationException("CONNECTION_MANAGED", 409);
    }
  }

  private UUID uuid(String id) {
    try {
      UUID parsed = UUID.fromString(id);
      if (!parsed.toString().equals(id)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IntegrationException("CONNECTION_NOT_FOUND", 404);
    }
  }

  private String value(String input) {
    return input == null ? "" : input;
  }

  private boolean text(String input, int max) {
    return input.length() <= max && input.chars().noneMatch(Character::isISOControl);
  }
}
