package org.openmetadata.service.integration;

import jakarta.ws.rs.core.UriInfo;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.CatalogSummary;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.ConnectionStatus;
import org.openmetadata.service.integration.IntegrationModels.ConnectionUpdate;
import org.openmetadata.service.integration.IntegrationModels.EngineStatus;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TaskInput;
import org.openmetadata.service.integration.IntegrationModels.UpdateInput;
import org.openmetadata.service.integration.IntegrationModels.ValidationError;
import org.openmetadata.service.integration.IntegrationModels.ValidationResult;

public final class IntegrationService {
  static final Object TASK_LOCK = new Object();
  private static final SecureRandom JOB_IDS = new SecureRandom();
  private static final int MAX_TASKS = 200;
  private static final int MAX_RUNS = 20;
  private static final int MAX_LIST_REFRESH = 5;
  private static final Duration LIST_REFRESH_BUDGET = Duration.ofSeconds(3);
  private static final Set<String> TERMINAL =
      Set.of("FINISHED", "FAILED", "CANCELED", "CANCELLED", "STOPPED", "SAVEPOINT_DONE");
  private static final Set<String> UNCERTAIN_SUBMISSION =
      Set.of("SUBMITTING", "SUBMISSION_UNKNOWN", "RESUMING", "RESUMPTION_UNKNOWN");
  private final IntegrationConfiguration configuration;
  private final IntegrationTaskStore store;
  private final JdbcInspector inspector;
  private final IntegrationConnections connections;
  private final SeaTunnelJobConfig jobs;
  private final CatalogProjector catalog;
  private SeaTunnelClient engine;
  private int listRefreshCursor;

  public IntegrationService(IntegrationConfiguration configuration, IntegrationTaskStore store) {
    this(
        configuration,
        store,
        new EntityExtensionConnectionStore(
            () -> org.openmetadata.service.Entity.getCollectionDAO().entityExtensionDAO()));
  }

  public IntegrationService(
      IntegrationConfiguration configuration,
      IntegrationTaskStore store,
      IntegrationConnectionStore connectionStore) {
    this.configuration = configuration;
    this.store = store;
    connections = new IntegrationConnections(configuration, connectionStore, store);
    inspector = new JdbcInspector(connections::endpoint);
    jobs = new SeaTunnelJobConfig(connections::endpoint);
    catalog = new CatalogProjector(connections);
  }

  public EngineStatus status() {
    if (!configuration.enabled()) {
      return new EngineStatus(false, false, null, "INTEGRATION_DISABLED");
    }
    if (!configuration.configured()) {
      return new EngineStatus(true, false, null, "INVALID_CONFIGURATION");
    }
    try {
      return new EngineStatus(true, true, engine().version(), null);
    } catch (IntegrationException e) {
      return new EngineStatus(true, false, null, e.code());
    }
  }

  public List<ConnectionDefinition> connections() {
    return connections.list();
  }

  public ConnectionDefinition connection(String id) {
    return connections.connection(id);
  }

  public ConnectionDefinition createConnection(ConnectionInput input, String actor) {
    return connections.create(input, actor);
  }

  public ConnectionDefinition updateConnection(String id, ConnectionUpdate input, String actor) {
    return connections.update(id, input, actor);
  }

  public void deleteConnection(String id, long version) {
    connections.delete(id, version);
  }

  public ConnectionStatus testConnection(String id) {
    connections.connection(id);
    try {
      boolean connected = inspector.test(id);
      return new ConnectionStatus(connected, connected ? null : "CONNECTION_UNAVAILABLE");
    } catch (IntegrationException e) {
      return new ConnectionStatus(false, e.code());
    }
  }

  public List<TableDefinition> tables(String id) {
    return inspector.tables(id);
  }

  public List<TableDefinition> tables(String id, boolean includeColumns) {
    return inspector.tables(id, includeColumns);
  }

  public TableDefinition table(String id, String schema, String table) {
    TableDefinition result = inspector.table(id, schema, table);
    if (result == null) {
      throw new IntegrationException("TABLE_NOT_FOUND", 404);
    }
    return result;
  }

  public ValidationResult validate(TaskInput input) {
    configuration.requireReady();
    ValidationResult structure = IntegrationValidator.validateStructure(input);
    if (!structure.valid()) {
      return structure;
    }
    try {
      connections.requireRole(input.sourceConnectionId, "SOURCE", input.mode);
      connections.requireRole(input.targetConnectionId, "TARGET", input.mode);
      return IntegrationValidator.validateTables(
          input,
          inspector.table(input.sourceConnectionId, input.sourceSchema, input.sourceTable),
          inspector.table(input.targetConnectionId, input.targetSchema, input.targetTable));
    } catch (IntegrationException e) {
      return new ValidationResult(
          false, List.of(new ValidationError("connection", e.code(), e.getMessage())));
    }
  }

  public List<IntegrationTask> tasks(String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      List<IntegrationTask> tasks = store.list();
      List<IntegrationTask> pending =
          tasks.stream().filter(task -> needsRefresh(task.latestRun)).toList();
      if (!pending.isEmpty()) {
        long deadline = System.nanoTime() + LIST_REFRESH_BUDGET.toNanos();
        int count = Math.min(MAX_LIST_REFRESH, pending.size());
        for (int index = 0; index < count && System.nanoTime() < deadline; index++) {
          int selected = Math.floorMod(listRefreshCursor++, pending.size());
          refresh(pending.get(selected), actor, deadline);
        }
      }
      return tasks;
    }
  }

  public IntegrationTask create(TaskInput input, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      requireValid(input);
      List<IntegrationTask> existing = store.list();
      if (existing.size() >= MAX_TASKS) {
        throw new IntegrationException("CAPACITY_EXCEEDED", 409);
      }
      if (existing.stream().anyMatch(task -> task.name.equals(input.name))) {
        throw new IntegrationException("DUPLICATE_TASK", 409);
      }
      requireUniqueRoute(input, null, existing);
      IntegrationTask task = IntegrationTask.from(input);
      task.id = UUID.randomUUID().toString();
      task.createdAt = System.currentTimeMillis();
      task.updatedAt = task.createdAt;
      task.updatedBy = actor;
      task.version = 1;
      store.save(task);
      return task;
    }
  }

  public IntegrationTask task(String id, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      refresh(task, actor);
      return task;
    }
  }

  public IntegrationTask update(String id, UpdateInput input, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      if (input.version == null || task.version != input.version) {
        throw new IntegrationException("VERSION_CONFLICT", 409);
      }
      refresh(task, actor);
      if (blocked(task.latestRun)) {
        throw new IntegrationException("TASK_ACTIVE", 409);
      }
      requireValid(input);
      List<IntegrationTask> existing = store.list();
      if (existing.stream()
          .anyMatch(other -> !other.id.equals(task.id) && other.name.equals(input.name))) {
        throw new IntegrationException("DUPLICATE_TASK", 409);
      }
      requireUniqueRoute(input, task.id, existing);
      task.replaceInput(input);
      task.catalog = new CatalogSummary();
      save(task, actor);
      return task;
    }
  }

  public IntegrationTask run(String id, boolean resume, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      boolean uncertain =
          task.latestRun != null && UNCERTAIN_SUBMISSION.contains(task.latestRun.status);
      boolean uncertainResume =
          task.latestRun != null
              && Set.of("RESUMING", "RESUMPTION_UNKNOWN").contains(task.latestRun.status);
      RefreshResult result = refresh(task, actor);
      if (task.latestRun != null && result == RefreshResult.UNAVAILABLE) {
        return task;
      }
      if (uncertain) {
        if (result == RefreshResult.FOUND) {
          return task;
        }
        if (result == RefreshResult.NOT_FOUND) {
          requireValid(task);
          submit(task, uncertainResume, actor);
          return task;
        }
      }
      if (resume) {
        if (task.latestRun == null || !Boolean.TRUE.equals(task.latestRun.canResume)) {
          throw new IntegrationException("SAVEPOINT_UNAVAILABLE", 409);
        }
        requireValid(task);
        task.latestRun.submittedAt = System.currentTimeMillis();
        task.latestRun.finishedAt = null;
        submit(task, true, actor);
        return task;
      }
      if (blocked(task.latestRun)) {
        throw new IntegrationException("TASK_ACTIVE", 409);
      }
      requireValid(task);
      RunSummary run = new RunSummary();
      run.jobId = newJobId();
      run.status = "SUBMITTING";
      run.submittedAt = System.currentTimeMillis();
      run.canResume = false;
      task.latestRun = run;
      task.runs.add(run);
      if (task.runs.size() > MAX_RUNS) {
        task.runs =
            new ArrayList<>(task.runs.subList(task.runs.size() - MAX_RUNS, task.runs.size()));
      }
      submit(task, false, actor);
      return task;
    }
  }

  public IntegrationTask stop(String id, boolean savepoint, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      if (savepoint && !"CDC".equals(task.mode)) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      refresh(task, actor);
      if (task.latestRun == null || TERMINAL.contains(task.latestRun.status)) {
        return task;
      }
      task.latestRun.status = "STOP_REQUESTED";
      task.latestRun.savepointRequested = savepoint;
      task.latestRun.canResume = false;
      task.latestRun.errorCode = null;
      task.latestRun.errorMessage = null;
      save(task, actor);
      try {
        engine().stop(task.latestRun.jobId, savepoint);
        refresh(task, actor);
      } catch (IntegrationException e) {
        task.latestRun.status = "STOP_FAILED";
        error(task.latestRun, e);
        save(task, actor);
      }
      return task;
    }
  }

  public IntegrationTask syncCatalog(String id, UriInfo uriInfo, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      try {
        connections.requireRole(task.sourceConnectionId, "SOURCE", task.mode);
        connections.requireRole(task.targetConnectionId, "TARGET", task.mode);
        TableDefinition source =
            inspector.table(task.sourceConnectionId, task.sourceSchema, task.sourceTable);
        TableDefinition target =
            inspector.table(task.targetConnectionId, task.targetSchema, task.targetTable);
        if (!IntegrationValidator.validateTables(task, source, target).valid()) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        task.catalog = catalog.sync(task, source, target, uriInfo, actor);
      } catch (RuntimeException e) {
        task.catalog.status = "FAILED";
        task.catalog.errorCode = "CATALOG_SYNC_FAILED";
        task.catalog.errorMessage =
            new IntegrationException("CATALOG_SYNC_FAILED", 503).getMessage();
      }
      save(task, actor);
      return task;
    }
  }

  private void submit(IntegrationTask task, boolean resume, String actor) {
    task.latestRun.status = resume ? "RESUMING" : "SUBMITTING";
    task.latestRun.canResume = false;
    task.latestRun.errorCode = null;
    task.latestRun.errorMessage = null;
    save(task, actor);
    try {
      engine().submit(task.latestRun.jobId, task.name, jobs.build(task), resume);
      task.latestRun.status = resume ? "RESUMING" : "SUBMITTED";
    } catch (IntegrationException e) {
      task.latestRun.status =
          "SUBMISSION_UNKNOWN".equals(e.code())
              ? (resume ? "RESUMPTION_UNKNOWN" : "SUBMISSION_UNKNOWN")
              : "FAILED";
      if ("FAILED".equals(task.latestRun.status)) {
        task.latestRun.finishedAt = System.currentTimeMillis();
      }
      error(task.latestRun, e);
    }
    save(task, actor);
  }

  private RefreshResult refresh(IntegrationTask task, String actor) {
    return refresh(task, actor, System.nanoTime() + Duration.ofSeconds(10).toNanos());
  }

  private RefreshResult refresh(IntegrationTask task, String actor, long deadline) {
    if (task.latestRun == null) {
      return RefreshResult.NONE;
    }
    String before = IntegrationModels.serialize(task);
    RunSummary run = task.latestRun;
    RefreshResult result;
    try {
      SeaTunnelClient.JobSnapshot snapshot = engine().info(run.jobId, remaining(deadline));
      boolean pendingResume =
          "RESUMING".equals(run.status)
              && "SAVEPOINT_DONE".equals(snapshot.status())
              && System.currentTimeMillis() - run.submittedAt < 30000;
      boolean pendingStop =
          "STOP_REQUESTED".equals(run.status) && !TERMINAL.contains(snapshot.status());
      boolean failedStop =
          "STOP_FAILED".equals(run.status) && !TERMINAL.contains(snapshot.status());
      if (!pendingResume && !pendingStop && !failedStop) {
        run.status = snapshot.status();
      }
      run.sourceReceivedCount = snapshot.sourceReceivedCount();
      run.sinkWriteCount = snapshot.sinkWriteCount();
      if (!failedStop) {
        run.errorCode = null;
        run.errorMessage = null;
      }
      run.canResume = false;
      if (TERMINAL.contains(run.status)) {
        if (run.finishedAt == null) {
          // SeaTunnel 3.0 does not report a finish time; record the first terminal observation.
          run.finishedAt = System.currentTimeMillis();
        }
        if ("FAILED".equals(run.status)) {
          error(run, new IntegrationException("ENGINE_JOB_FAILED", 409));
        }
        if ("CDC".equals(task.mode) && "SAVEPOINT_DONE".equals(run.status)) {
          try {
            run.canResume = engine().hasCompletedSavepoint(run.jobId, remaining(deadline));
            if (!run.canResume) {
              error(run, new IntegrationException("SAVEPOINT_UNAVAILABLE", 409));
            }
          } catch (IntegrationException e) {
            error(run, new IntegrationException("SAVEPOINT_UNAVAILABLE", 409));
          }
        }
      }
      result = RefreshResult.FOUND;
    } catch (IntegrationException e) {
      error(run, e);
      result =
          "JOB_NOT_FOUND".equals(e.code()) ? RefreshResult.NOT_FOUND : RefreshResult.UNAVAILABLE;
    }
    if (!before.equals(IntegrationModels.serialize(task))) {
      save(task, actor);
    }
    return result;
  }

  private IntegrationTask load(String id) {
    try {
      UUID uuid = UUID.fromString(id);
      if (!uuid.toString().equals(id)) {
        throw new IllegalArgumentException();
      }
      return store.get(uuid);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  private void requireValid(TaskInput input) {
    if (!validate(input).valid()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  private void requireUniqueRoute(TaskInput input, String id, List<IntegrationTask> existing) {
    if (existing.stream()
        .anyMatch(
            task ->
                !task.id.equals(id)
                    && task.sourceConnectionId.equals(input.sourceConnectionId)
                    && task.sourceSchema.equals(input.sourceSchema)
                    && task.sourceTable.equals(input.sourceTable)
                    && task.targetConnectionId.equals(input.targetConnectionId)
                    && task.targetSchema.equals(input.targetSchema)
                    && task.targetTable.equals(input.targetTable))) {
      throw new IntegrationException("DUPLICATE_ROUTE", 409);
    }
  }

  private boolean needsRefresh(RunSummary run) {
    return run != null
        && (!TERMINAL.contains(run.status)
            || ("SAVEPOINT_DONE".equals(run.status) && !Boolean.TRUE.equals(run.canResume)));
  }

  private Duration remaining(long deadline) {
    return Duration.ofMillis(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
  }

  private boolean blocked(RunSummary run) {
    return run != null
        && (!TERMINAL.contains(run.status)
            || "SAVEPOINT_DONE".equals(run.status)
            || Boolean.TRUE.equals(run.canResume));
  }

  private void error(RunSummary run, IntegrationException failure) {
    run.errorCode = failure.code();
    run.errorMessage = failure.getMessage();
  }

  private void save(IntegrationTask task, String actor) {
    task.version++;
    task.updatedAt = System.currentTimeMillis();
    task.updatedBy = actor;
    if (task.latestRun != null && !task.runs.isEmpty()) {
      task.runs.set(task.runs.size() - 1, task.latestRun);
    }
    store.save(task);
  }

  private synchronized SeaTunnelClient engine() {
    if (engine == null) {
      engine = new SeaTunnelClient(configuration.engineUri(), Duration.ofSeconds(10));
    }
    return engine;
  }

  private String newJobId() {
    long id;
    do {
      id = JOB_IDS.nextLong() & Long.MAX_VALUE;
    } while (id == 0);
    return Long.toString(id);
  }

  private enum RefreshResult {
    NONE,
    FOUND,
    NOT_FOUND,
    UNAVAILABLE
  }
}
