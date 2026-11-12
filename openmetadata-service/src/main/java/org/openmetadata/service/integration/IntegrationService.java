package org.openmetadata.service.integration;

import jakarta.ws.rs.core.UriInfo;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.service.integration.IntegrationModels.CatalogSummary;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;
import org.openmetadata.service.integration.IntegrationModels.ConnectionStatus;
import org.openmetadata.service.integration.IntegrationModels.ConnectionUpdate;
import org.openmetadata.service.integration.IntegrationModels.EngineStatus;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;
import org.openmetadata.service.integration.IntegrationModels.QuerySchemaInput;
import org.openmetadata.service.integration.IntegrationModels.RunInput;
import org.openmetadata.service.integration.IntegrationModels.RunSummary;
import org.openmetadata.service.integration.IntegrationModels.ScheduleState;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;
import org.openmetadata.service.integration.IntegrationModels.TargetTableInput;
import org.openmetadata.service.integration.IntegrationModels.TargetTablePreview;
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

  public TableDefinition querySchema(QuerySchemaInput input) {
    configuration.requireReady();
    if (input == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    connections.requireRole(input.connectionId(), "SOURCE", "FULL");
    return inspector.query(
        connections.endpoint(input.connectionId()),
        input.schema(),
        input.query(),
        businessDate(input.businessDate(), "Asia/Shanghai"));
  }

  public TargetTablePreview previewTargetTable(String id, TargetTableInput input) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      TargetTablePreview preview = targetTablePreview(id, input);
      new DorisDdlExecutor()
          .requireAbsent(connections.endpoint(id), input.targetSchema(), input.targetTable());
      return preview;
    }
  }

  public TableDefinition createTargetTable(String id, TargetTableInput input) {
    configuration.requireReady();
    DorisTableDdl.requireInput(input);
    if (input.previewHash() == null || !input.previewHash().matches("[a-f0-9]{64}")) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    synchronized (TASK_LOCK) {
      requireDorisTarget(id);
      TargetTablePreview current;
      try {
        current = targetTablePreview(id, input);
      } catch (IntegrationException e) {
        if (Set.of(
                "TABLE_NOT_FOUND",
                "UNSUPPORTED_TARGET_TYPE",
                "SCHEMA_NOT_ALLOWED",
                "INVALID_CONFIGURATION")
            .contains(e.code())) {
          throw new IntegrationException("TABLE_PREVIEW_STALE", 409);
        }
        throw e;
      }
      if (!current.previewHash().equals(input.previewHash())) {
        throw new IntegrationException("TABLE_PREVIEW_STALE", 409);
      }
      new DorisDdlExecutor().create(connections.endpoint(id), current);
      return table(id, current.table().schema(), current.table().name());
    }
  }

  private TargetTablePreview targetTablePreview(String id, TargetTableInput input) {
    DorisTableDdl.requireInput(input);
    connections.requireRole(input.sourceConnectionId(), "SOURCE", "FULL");
    requireDorisTarget(id);
    ConnectionDefinition source = connections.connection(input.sourceConnectionId());
    ConnectionDefinition target = connections.connection(id);
    return DorisTableDdl.preview(
        input,
        "SQL_T1".equals(input.mode())
            ? inspector.query(
                connections.endpoint(input.sourceConnectionId()),
                input.sourceSchema(),
                input.sourceQuery(),
                LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1))
            : inspector.table(
                input.sourceConnectionId(), input.sourceSchema(), input.sourceTable()),
        source,
        target,
        configuration.dorisReplicationNum());
  }

  private void requireDorisTarget(String id) {
    connections.requireRole(id, "TARGET", "FULL");
    if (!"Doris".equals(connections.connection(id).databaseType)) {
      throw new IntegrationException("UNSUPPORTED_TARGET_TYPE", 400);
    }
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
      if ("SQL_T1".equals(input.mode)) {
        requireDorisTarget(input.targetConnectionId);
        IntegrationSchedule.validate(input.schedule);
      }
      if ("Doris".equals(connections.connection(input.targetConnectionId).databaseType)) {
        DorisLoadLabel.requireTablePath(input.targetSchema, input.targetTable);
      }
      return IntegrationValidator.validateTables(
          input,
          sourceDefinition(input),
          inspector.table(input.targetConnectionId, input.targetSchema, input.targetTable));
    } catch (IntegrationException e) {
      return new ValidationResult(
          false, List.of(new ValidationError("connection", e.code(), e.getMessage())));
    }
  }

  public List<IntegrationTask> tasks(String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      List<IntegrationTask> tasks = store.list().stream().map(this::copy).toList();
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
      configureSchedule(task, null, Instant.now());
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
      if (blocked(task.latestRun) && !scheduleToggleOnly(task, input)) {
        throw new IntegrationException("TASK_ACTIVE", 409);
      }
      requireValid(input);
      List<IntegrationTask> existing = store.list();
      if (existing.stream()
          .anyMatch(other -> !other.id.equals(task.id) && other.name.equals(input.name))) {
        throw new IntegrationException("DUPLICATE_TASK", 409);
      }
      requireUniqueRoute(input, task.id, existing);
      var previousSchedule = task.schedule;
      task.replaceInput(input);
      configureSchedule(task, previousSchedule, Instant.now());
      if (task.scheduleState != null && task.scheduleState.pendingBusinessDate == null) {
        task.scheduleState.lastError = null;
      }
      task.catalog = new CatalogSummary();
      save(task, actor);
      return task;
    }
  }

  public IntegrationTask run(String id, boolean resume, String actor) {
    return run(id, new RunInput(resume), actor);
  }

  public IntegrationTask run(String id, RunInput request, String actor) {
    configuration.requireReady();
    synchronized (TASK_LOCK) {
      IntegrationTask task = load(id);
      if ("SQL_T1".equals(task.mode)) {
        return runSql(task, request, actor, null);
      }
      if (request.businessDate() != null || request.retry()) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
      boolean resume = request.resume();
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
        TableDefinition source = sourceDefinition(task);
        TableDefinition target =
            inspector.table(task.targetConnectionId, task.targetSchema, task.targetTable);
        if (!IntegrationValidator.validateTables(task, source, target).valid()) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        if ("SQL_T1".equals(task.mode)) {
          List<TableDefinition> physicalSources =
              IntegrationSql.references(
                      task.sourceQuery,
                      task.sourceSchema,
                      connections.endpoint(task.sourceConnectionId).dialect())
                  .stream()
                  .map(
                      reference ->
                          inspector.table(
                              task.sourceConnectionId, reference.schema(), reference.table()))
                  .toList();
          task.catalog = catalog.syncSql(task, physicalSources, target, uriInfo, actor);
        } else {
          task.catalog = catalog.sync(task, source, target, uriInfo, actor);
        }
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
      engine().submit(task.latestRun.jobId, task.name, jobs.build(task, task.latestRun), resume);
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
    if ("SQL_T1".equals(task.mode) && TERMINAL.contains(task.latestRun.status)) {
      completeScheduledRun(task);
      if (!before.equals(IntegrationModels.serialize(task))) {
        save(task, actor);
      }
      return RefreshResult.FOUND;
    }
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
    completeScheduledRun(task);
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
      return copy(store.get(uuid));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  private void requireValid(TaskInput input) {
    ValidationResult result = validate(input);
    if (result.errors().stream().anyMatch(error -> "DORIS_LABEL_INVALID".equals(error.code()))) {
      throw new IntegrationException("DORIS_LABEL_INVALID", 400);
    }
    if (!result.valid()) {
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
    long previousVersion = task.version;
    task.version++;
    task.updatedAt = System.currentTimeMillis();
    task.updatedBy = actor;
    if (task.latestRun != null && !task.runs.isEmpty()) {
      task.runs.set(task.runs.size() - 1, task.latestRun);
    }
    if (!store.compareAndSave(task, previousVersion)) {
      throw new IntegrationException("VERSION_CONFLICT", 409);
    }
  }

  private synchronized SeaTunnelClient engine() {
    if (engine == null) {
      engine = new SeaTunnelClient(configuration.engineUri(), Duration.ofSeconds(10));
    }
    return engine;
  }

  private IntegrationTask copy(IntegrationTask task) {
    return IntegrationModels.deserialize(IntegrationModels.serialize(task));
  }

  private boolean scheduleToggleOnly(IntegrationTask task, TaskInput input) {
    if (!"SQL_T1".equals(task.mode)
        || !"SQL_T1".equals(input.mode)
        || task.schedule == null
        || input.schedule == null
        || !Objects.equals(task.schedule.dailyTime, input.schedule.dailyTime)
        || !Objects.equals(task.schedule.timeZone, input.schedule.timeZone)) {
      return false;
    }
    IntegrationTask oldDefinition = IntegrationTask.from(task);
    IntegrationTask newDefinition = IntegrationTask.from(input);
    oldDefinition.schedule = null;
    newDefinition.schedule = null;
    return IntegrationModels.serialize(oldDefinition)
        .equals(IntegrationModels.serialize(newDefinition));
  }

  private TableDefinition sourceDefinition(TaskInput input) {
    if (!"SQL_T1".equals(input.mode)) {
      return inspector.table(input.sourceConnectionId, input.sourceSchema, input.sourceTable);
    }
    return inspector.query(
        connections.endpoint(input.sourceConnectionId),
        input.sourceSchema,
        input.sourceQuery,
        LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
  }

  private LocalDate businessDate(String value, String timeZone) {
    LocalDate today = LocalDate.now(ZoneId.of(timeZone));
    if (value == null) {
      return today.minusDays(1);
    }
    try {
      LocalDate date = LocalDate.parse(value);
      if (!date.toString().equals(value)
          || date.isBefore(LocalDate.of(2000, 1, 1))
          || !date.isBefore(today)) {
        throw new DateTimeParseException("Invalid business date", value, 0);
      }
      return date;
    } catch (DateTimeParseException e) {
      throw new IntegrationException("BUSINESS_DATE_INVALID", 400);
    }
  }

  private void configureSchedule(
      IntegrationTask task, IntegrationModels.ScheduleConfig previous, Instant now) {
    if (!"SQL_T1".equals(task.mode)) {
      task.scheduleState = null;
      return;
    }
    if (task.schedule == null) {
      task.schedule = new IntegrationModels.ScheduleConfig();
    }
    IntegrationSchedule.validate(task.schedule);
    boolean unchanged =
        previous != null
            && previous.enabled == task.schedule.enabled
            && Objects.equals(previous.dailyTime, task.schedule.dailyTime)
            && Objects.equals(previous.timeZone, task.schedule.timeZone);
    if (unchanged && task.scheduleState != null) {
      return;
    }
    if (task.scheduleState == null) {
      task.scheduleState = new ScheduleState();
    }
    if (task.scheduleState.pendingBusinessDate == null) {
      task.scheduleState.nextRunAt = IntegrationSchedule.initialize(task.schedule, now);
      task.scheduleState.lastError = null;
    }
  }

  private IntegrationTask runSql(
      IntegrationTask task, RunInput request, String actor, Long scheduledFor) {
    if (request.resume()) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    boolean uncertain =
        task.latestRun != null && UNCERTAIN_SUBMISSION.contains(task.latestRun.status);
    RefreshResult result = refresh(task, actor);
    if (result == RefreshResult.UNAVAILABLE || (uncertain && result == RefreshResult.FOUND)) {
      return task;
    }
    if (uncertain && result == RefreshResult.NOT_FOUND) {
      // A live submitter owns this durable reservation for at least its HTTP timeout.
      if (System.currentTimeMillis() - task.latestRun.submittedAt < 30000) {
        return task;
      }
      requireValid(task);
      task.latestRun.submittedAt = System.currentTimeMillis();
      submit(
          task, false, actor); // Same job ID and business date; never allocate a new uncertain job.
      return task;
    }
    if (blocked(task.latestRun)) {
      throw new IntegrationException("TASK_ACTIVE", 409);
    }
    String zone = task.schedule == null ? "Asia/Shanghai" : task.schedule.timeZone;
    LocalDate date;
    if (request.retry()) {
      if (task.latestRun == null
          || task.latestRun.businessDate == null
          || (request.businessDate() != null
              && !request.businessDate().equals(task.latestRun.businessDate))) {
        throw new IntegrationException("BUSINESS_DATE_INVALID", 400);
      }
      date = businessDate(task.latestRun.businessDate, zone);
    } else {
      date = businessDate(request.businessDate(), zone);
    }
    if (task.scheduleState != null && task.scheduleState.pendingBusinessDate != null) {
      if (!task.scheduleState.pendingBusinessDate.equals(date.toString())) {
        throw new IntegrationException("SCHEDULE_DATE_PENDING", 409);
      }
      scheduledFor = task.scheduleState.nextRunAt;
    } else if (task.scheduleState != null
        && task.scheduleState.lastError != null
        && task.scheduleState.nextRunAt != null
        && task.schedule != null
        && IntegrationSchedule.occurrence(task.schedule, task.scheduleState.nextRunAt)
            .businessDate()
            .equals(date.toString())) {
      // A pre-submission validation failure can be repaired and backfilled at its saved cursor.
      scheduledFor = task.scheduleState.nextRunAt;
    }
    requireValid(task);
    RunSummary run = new RunSummary();
    run.jobId = newJobId();
    run.status = "SUBMITTING";
    run.submittedAt = System.currentTimeMillis();
    run.canResume = false;
    run.businessDate = date.toString();
    run.windowStart = date + " 00:00:00";
    run.windowEnd = date.plusDays(1) + " 00:00:00";
    run.trigger = request.retry() ? "RETRY" : scheduledFor == null ? "MANUAL" : "SCHEDULED";
    run.scheduledFor = scheduledFor;
    if (scheduledFor != null) {
      task.scheduleState.pendingBusinessDate = run.businessDate;
      task.scheduleState.lastError = null;
    }
    task.latestRun = run;
    task.runs.add(run);
    if (task.runs.size() > MAX_RUNS) {
      task.runs = new ArrayList<>(task.runs.subList(task.runs.size() - MAX_RUNS, task.runs.size()));
    }
    submit(task, false, actor); // save/CAS happens before contacting SeaTunnel.
    completeScheduledRun(task);
    return task;
  }

  private void completeScheduledRun(IntegrationTask task) {
    RunSummary run = task.latestRun;
    ScheduleState state = task.scheduleState;
    if (!"SQL_T1".equals(task.mode)
        || state == null
        || run == null
        || run.scheduledFor == null
        || !Objects.equals(state.nextRunAt, run.scheduledFor)
        || !Objects.equals(state.pendingBusinessDate, run.businessDate)) {
      return;
    }
    if ("FINISHED".equals(run.status)) {
      state.lastBusinessDate = run.businessDate;
      state.nextRunAt = IntegrationSchedule.next(task.schedule, run.scheduledFor);
      state.pendingBusinessDate = null;
      state.lastError = null;
    } else if (TERMINAL.contains(run.status)) {
      state.lastError = run.errorCode == null ? run.status : run.errorCode;
    }
  }

  /** Quartz calls this without an employee session; only explicitly enabled admin tasks are due. */
  public void dispatchScheduled(Instant now) {
    if (!configuration.enabled() || !configuration.configured()) {
      return;
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    int submitted = 0;
    List<IntegrationTask> scheduled =
        store.list().stream()
            .filter(task -> "SQL_T1".equals(task.mode))
            .sorted(java.util.Comparator.comparing(task -> task.id))
            .toList();
    int offset =
        scheduled.isEmpty() ? 0 : Math.floorMod(now.getEpochSecond() / 60, scheduled.size());
    for (int index = 0; index < scheduled.size(); index++) {
      if (System.nanoTime() >= deadline || submitted >= 3) {
        break;
      }
      IntegrationTask listed = scheduled.get((offset + index) % scheduled.size());
      IntegrationTask attempted = null;
      try {
        synchronized (TASK_LOCK) {
          IntegrationTask task = load(listed.id);
          attempted = task;
          if (!"SQL_T1".equals(task.mode)) {
            continue;
          }
          refresh(
              task,
              "system:sql-t1",
              Math.min(deadline, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
          if (task.schedule != null
              && task.schedule.enabled
              && task.latestRun != null
              && UNCERTAIN_SUBMISSION.contains(task.latestRun.status)) {
            long beforeSubmit = task.latestRun.submittedAt;
            runSql(task, new RunInput(false, null, false), "system:sql-t1", null);
            if (task.latestRun.submittedAt != beforeSubmit) {
              submitted++;
            }
            continue;
          }
          var due = IntegrationSchedule.due(task.schedule, task.scheduleState, now);
          if (due.isPresent() && !blocked(task.latestRun)) {
            runSql(
                task,
                new RunInput(false, due.get().businessDate(), false),
                "system:sql-t1",
                due.get().scheduledFor());
            submitted++;
          }
        }
      } catch (IntegrationException e) {
        // Another pod may have won CAS; keep its reservation intact and retry on the next poll.
        if (attempted != null && !Set.of("VERSION_CONFLICT", "TASK_ACTIVE").contains(e.code())) {
          recordScheduleError(attempted.id, attempted.version, e.code());
        }
      }
    }
  }

  private void recordScheduleError(String id, long attemptedVersion, String code) {
    try {
      IntegrationTask task = load(id);
      if (task.version == attemptedVersion
          && task.scheduleState != null
          && !Objects.equals(task.scheduleState.lastError, code)) {
        task.scheduleState.lastError = code;
        save(task, "system:sql-t1");
      }
    } catch (IntegrationException ignored) {
      // Durable task state remains the authority if the store is temporarily unavailable.
    }
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
