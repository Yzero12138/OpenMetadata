# Hospital Data Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for implementation and review. The runtime, Java control extension and React interface have separate write boundaries; superpowers:dispatching-parallel-agents permits these independent tasks to proceed together. User approved the design and implementation on 2026-10-09.

**Goal:** Operate a real SeaTunnel 3.0.0 PostgreSQL full/CDC synthetic-data link from the existing Chinese hospital workbench, using the existing Integrate identity and native administrator authorization.

**Architecture:** SeaTunnel runs independently with persistent checkpoints and internal-only REST. Two new PostgreSQL instances hold synthetic source and ODS records. The OpenMetadata extension stores only task definitions, logical connection references, execution identifiers, audit and catalog synchronization states; source/target structure and lineage use native catalog repositories. Browser requests never contain database passwords or arbitrary engine configuration.

**Tech Stack:** Apache SeaTunnel 3.0.0, a verified compatible pinned JVM, PostgreSQL 17.10, Kubernetes StatefulSets/PVCs/Secrets/NetworkPolicies, Java 21/Dropwizard/Jdbi, React 18/TypeScript/native UI core components, Jest/Playwright. User confirmed PC-only delivery; desktop acceptance uses 1440 and 1920, with no mobile UI requirement.

## Shared API contract

All paths below are relative to `/api/v1/hospital/integration`. Native authentication applies and first-stage control endpoints require `Authorizer.authorizeAdmin`. Every response excludes raw JDBC configuration and credentials. Job IDs are decimal strings, never JavaScript numbers.

```typescript
type IntegrationMode = 'FULL' | 'CDC';
interface FieldMapping { source: string; target: string; }
interface TaskInput {
  name: string;
  displayName: string;
  sourceConnectionId: 'synthetic-source';
  targetConnectionId: 'synthetic-ods';
  sourceSchema: string;
  sourceTable: string;
  targetSchema: string;
  targetTable: string;
  mode: IntegrationMode;
  primaryKey: string;
  fieldMappings: FieldMapping[];
}
interface RunSummary {
  jobId: string;
  status: string;
  submittedAt: number;
  finishedAt?: number;
  sourceReceivedCount?: number;
  sinkWriteCount?: number;
  errorCode?: string;
  errorMessage?: string;
  savepointRequested?: boolean;
  canResume?: boolean;
}
interface IntegrationTask extends TaskInput {
  id: string;
  version: number;
  createdAt: number;
  updatedAt: number;
  updatedBy: string;
  latestRun?: RunSummary;
  runs: RunSummary[];
  catalog: { status: 'PENDING' | 'SYNCED' | 'FAILED'; syncedAt?: number;
    sourceFqn?: string; targetFqn?: string; pipelineFqn?: string;
    errorCode?: string; errorMessage?: string; };
}
```

* `GET /status`: `{enabled, engineVersion?, reachable, errorCode?}`. Do not replace unavailable engine metrics with zeros.
* `GET /connections`: `{data:[{id,displayName,role:'SOURCE'|'TARGET',databaseType:'Postgres',synthetic:true}]}`.
* `POST /connections/{id}/test`: `{connected:boolean,errorCode?:string}`; no connection string in result.
* `GET /connections/{id}/tables`: `{data:[{schema,name,columns:[{name,dataType,nullable,primaryKey}]}]}`; restricted schemas, technical names validated server-side.
* `GET /tasks`: `{data:IntegrationTask[]}`.
* `POST /tasks/validate`: `TaskInput` -> `{valid:boolean,errors:[{field,code,message}]}`; validate allowlisted connection, available tables, all field types, unique source/target columns and a mapped real primary key. No side effects.
* `POST /tasks`: `TaskInput` -> `IntegrationTask` (201); duplicate technical name or source-to-target route 409.
* `GET /tasks/{id}`: `IntegrationTask`; refresh true engine status and preserve previous known state when engine unreachable, with explicit error code.
* `PUT /tasks/{id}`: `TaskInput & {version:number}` -> `IntegrationTask`; stale version or active/uncertain run 409.
* `POST /tasks/{id}/run`: `{resume?:boolean}` -> `IntegrationTask`; persist stable job ID before submit. Reconcile uncertain submissions instead of creating another ID. Active jobs cannot be started twice. CDC resumes use the same stopped job/config and a verified savepoint.
* `POST /tasks/{id}/stop`: `{savepoint:boolean}` -> `IntegrationTask`; preserve stop failures and show asynchronous stopping truthfully.
* `POST /tasks/{id}/catalog`: -> `IntegrationTask`; native catalog synchronization is explicit and retryable, independent of transfer status.

## Task 1 — Fixed runtime and isolated databases (root write boundary)

**Create:** `docker/hospital/seatunnel/Dockerfile`, `docker/hospital/seatunnel/build-image.ps1`, `deploy/hospital/k8s/integration/seatunnel.yaml`, `deploy/hospital/k8s/integration/databases.yaml`, `deploy/hospital/k8s/integration/kustomization.yaml`, `deploy/hospital/k8s/integration/create-secrets.ps1`, `tools/hospital-integration/verify-engine.ps1`, `tools/hospital-integration/synthetic.sql`.

- [x] Download the official 3.0.0 binary and SHA-512; fail on mismatch. Inspect packaged startup scripts/JVM options and include fixed JDBC/CDC connector and PostgreSQL driver artifacts with checksums in the built image.
- [x] Render a single-node test StatefulSet with internal REST 8080, Hazelcast 5801, persistent filesystem checkpoint and persistent IMap storage. Preserve packaged JVM files by mounting individual configuration files. Use explicit requests/limits and pinned images.
- [x] Create two PostgreSQL StatefulSets with independent PVCs and credentials, the source configured for logical replication. The Secret creator generates random passwords in memory and never prints values; rerunning preserves existing credentials.
- [x] Restrict REST ingress to the hospital application, database ingress to engine/application, and discovery/cluster traffic to engine labels. Do not change the approved Integrate backchannel policy.
- [x] Build/push the engine image, server-side validate manifests, apply only new resources and wait for ready state. Record image digest, `/overview` version and observed behavior.
- [x] Run actual full then CDC operations against the synthetic schema. SQL fixture:

```sql
CREATE TABLE public.visit_events (
  visit_id BIGINT PRIMARY KEY,
  department_code VARCHAR(32) NOT NULL,
  visit_type VARCHAR(16) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
INSERT INTO public.visit_events
SELECT n, 'DEPT_' || (n % 5), 'SYNTHETIC', TIMESTAMP '2026-10-09 00:00:00'
FROM generate_series(1,100) n;
```

- [x] Verify 100 initial rows; insert IDs 101–120, update IDs 11–20, delete IDs 1–5. Compare every selected value by primary key and require 115 final rows. Stop with savepoint, modify the source while stopped, resume and verify exact convergence without duplicate keys. Restart the engine and verify checkpoint/state persistence.
- [x] Command: `pwsh -File tools/hospital-integration/verify-engine.ps1`; expected JSON evidence includes real engine version, full count, CDC count, field equality and recovery results. Failures must exit nonzero.

## Task 2 — Native authenticated Java control extension (backend write boundary)

**Create:** `openmetadata-service/src/main/java/org/openmetadata/service/resources/hospital/HospitalIntegrationResource.java`; focused classes under `openmetadata-service/src/main/java/org/openmetadata/service/integration/` for models, config, PostgreSQL inspection/config compilation, REST transport, durable store, orchestration and catalog projection. Tests live under the corresponding `src/test/java/.../integration/` and `.../resources/hospital/` packages. **Do not edit UI or deployment files.**

- [x] Write regression tests first for untrusted identifiers/mappings, credential redaction, unavailable metrics, native authorization and ambiguous submission reconciliation. Use real loopback HTTP and a real PostgreSQL boundary for persistence/introspection; avoid mocking internal classes.
- [x] Load only named environment settings: `HOSPITAL_INTEGRATION_ENABLED`, `HOSPITAL_SEATUNNEL_URL`, `HOSPITAL_SOURCE_JDBC_URL/USER/PASSWORD`, `HOSPITAL_TARGET_JDBC_URL/USER/PASSWORD`. Validate URL schemes and fail closed if required settings absent. Engine credentials/configuration never become DTO fields.
- [x] Use `EntityExtensionDAO` with a distinct `hospital.integration.task` namespace for durable control JSON, one UUID per task. Guard concurrent mutation/version changes and bound task/run history; the existing test deployment is one application replica. Preserve audit actor and never store raw credentials in extension JSON.
- [x] Implement the shared API contract. Compile only verified PostgreSQL templates with quoted, validated identifiers. A full source uses JDBC bounded reads; CDC uses `Postgres-CDC` with `pgoutput`, deterministic replication slot/publication and primary-key upsert/delete sink. No arbitrary SQL, URL or plugin name comes from the browser.
- [x] Persist the run ID before HTTP submit. A timeout stores `SUBMISSION_UNKNOWN`; the next request queries that ID before any retry. Unknown/unreachable state cannot be converted to success, stopped or a fresh run. Store/savepoint state is distinct from the stop request itself.
- [x] Synchronize source/target service/database/schema/table, pipeline and lineage through native repository/API abstractions under the authorized actor. Do not use the metadata DB as business storage, and do not conflate metadata sync with transfer success. Repeating sync is idempotent and a failure is retryable.
- [x] Run focused tests, scoped `spotless:apply` then `spotless:check`, compile/package Java with Maven Java 21. Report commands, counts and explicit limitations.

## Task 3 — Chinese workbench operations (UI write boundary)

**Create:** `openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/` containing page, form, run details, types/style and behavior tests; `src/rest/hospitalIntegrationAPI.ts`; `.impeccable/surfaces/hospital-data-integration.md`. **Modify:** native authenticated router, `src/constants/constants.ts`, `src/constants/LeftSidebar.constants.ts`, necessary sidebar enum, all locale JSONs. **Do not edit Java or deployment.**

- [x] Read user-requested default Taste and project Impeccable 4.5.1, incumbent DESIGN/PRODUCT, new-work/craft-floor and core component color docs. Inherit established hospital design and code build path; the approved operational flow fixes purpose and composition, so no new identity selection or permission gate.
- [x] Record six direction-contract blocks: task list → page form → run details, logical connections, explicit synthetic scope, real state/error recovery, and separate catalog status. No promotional modal, fabricated metrics or nonfunctional controls.
- [x] Use the shared typed API contract and native current-user/admin state. Keep API authorization authoritative; render an explanatory permission state for ordinary employees.
- [x] Add a visible “数据集成” sidebar entry. Main page has a descriptive Chinese heading, engine connection state, task table/list and one create action. Create/edit forms use labeled source/target/table/mode/primary-key/mapping inputs and inline validation. Keep form data on errors and use version to preserve concurrent edits.
- [x] Run details show exact job ID, current status, available source/sink counters, timestamps and redacted failures; controls implement validate/save/run/stop/resume/catalog retry with guarded loading and conflict handling. Poll only while relevant; cancel/ignore stale requests on task switch/unmount.
- [x] Tests prove validation blocks submission, 409 preserves the form, unavailable counters stay unavailable, 64-bit IDs retain exact strings, active/uncertain run controls cannot duplicate work, and catalog failure does not relabel a finished data run.
- [x] Add real translations in all 20 locales and run organize-imports → ESLint → Prettier. Run Jest and compare TypeScript diagnostics with existing baseline. Root performs production build after changes settle.

## Task 4 — Integration, review and deployment (root)

- [x] Wire Secret-based environment into only the application container; update `deploy/hospital/k8s/openmetadata.yaml` and a unique application image version after backend/UI artifacts are built. Keep existing migration and identity settings compatible.
- [x] Add an explicit synthetic HTTP browser boundary and capture actual new React components at 1440/1920 in one batch, including empty/error/form/run and permission states. Use live K8s engine/database verification separately; do not fabricate human portal authentication. The confirmed scope is PC only.
- [x] Review requirements against actual files first, then obtain fresh code/security review. Correct material findings and rerun affected verification.
- [x] Run one scoped Impeccable detector, obtain fresh finish reviewer, fix and recapture only its material findings within the bounded review workflow, then obtain fresh documenter. Preserve global design files for this established-world extension.
- [x] Build/push unique application image, server-side dry run/apply, wait for rollout, verify unauthenticated endpoints reject access, prior Integrate verification remains passing, raw engine is internal-only, and deployed assets match the built artifacts.
- [x] Record exact evidence in `docs/hospital/data-integration-verification.md` and operator instructions in `docs/hospital/data-integration-operations.md`, including rollback limited to new workloads and image/env changes. Preserve PVCs by default.
- [ ] Stage only task files, inspect diff/secrets, commit, push the existing fork branch and update attached draft PR with public code/test facts. Report delivered URL, actual verification and any employee-login acceptance still required.

## Plan self-review

The runtime task covers fixed versions, synthetic isolation, full/CDC/recovery and restricted K8s access. The Java task covers persistence, credentials, native permission, ambiguity handling and retryable native catalog linkage. The UI task covers Chinese configuration and truthful operation states in the existing Integrate session. Final integration covers deployment, review, rollback and evidence. No real HIS/LIS compatibility or exactly-once guarantee is claimed by this synthetic phase.
