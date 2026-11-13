# Oracle CDC runtime and platform implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development, one implementation agent at a time, with spec review before quality review.

**Goal:** Add an honest, reusable Oracle CDC platform path, repair checkpointable Oracle full extraction, and expose source prerequisites and recovery evidence in the Chinese PC workbench. SQL Server remains unimplemented in this phase.

**Architecture:** A source adapter registry generates database-specific CDC configuration. Separate read-only preflight and engine client methods report actual capabilities. Runtime options are bounded and preserved on resume; native metadata/field validation remains mandatory. Existing PostgreSQL CDC and SQL T+1 remain compatible.

**Tech Stack:** Java21/Dropwizard/JDBC, SeaTunnel3.0.0 + Debezium1.9.8.Final, Doris4.0.8, existing React18/TypeScript core components.

## Contract shared by backend and UI

New public records belong in IntegrationCdcModels.java; TaskInput adds nullable cdc and runtime fields using these types. Existing task JSON remains valid.

```java
public record CdcOptions(String databaseName, String serverTimeZone,
                         String pdbName, String rootServiceName) {}
public record RuntimeOptions(Integer fetchSize, Integer splitSize,
    Integer sinkBatchSize, Integer checkpointIntervalSeconds,
    Integer checkpointTimeoutSeconds) {}
public record CdcCheck(String id, String status, String code, boolean required) {}
public record CdcPreflightInput(String schema, String table, List<String> columns,
    CdcOptions cdc, String targetConnectionId, String targetSchema, String targetTable) {}
public record CdcPreflightResult(String connectionId, String databaseType,
    String databaseVersion, String databaseName, boolean implemented,
    boolean ready, List<CdcCheck> checks) {}
public record CheckpointSummary(long triggered, long completed, long failed,
    long restored, Long latestCompletedAt, Long latestSavepointAt, Long updatedAt) {}
public record TargetRowCount(long count, long checkedAt) {}
```

POST /connections/{id}/cdc-preflight accepts CdcPreflightInput. Schema/table are both absent or both validated against the registered source. Optional columns default to all selected-table columns and must actually exist. Optional target fields are all absent or all present. Only admin may call it; authorization precedes body parsing. Response contains fixed codes and safe metadata, never raw JDBC/engine exceptions.

GET /tasks/{id}/target-count returns a bounded read-only COUNT of the registered target with checkedAt; explicit user refresh only, no source full scans or periodic target counting.

RunSummary adds nullable checkpoints and configurationFingerprint. Unavailable checkpoint data remains absent; do not synthesize zero or infer snapshot/incremental phase/committed SCN from RUNNING or counters.

## Task 1: Native engine inspection and Oracle connector packaging

**Files:** SeaTunnelClient.java/Test.java; docker/hospital/seatunnel artifact manifest, prepare/build scripts and a minimal inheriting Dockerfile; new opt-in connector smoke helper under docker/hospital/seatunnel.

- [ ] Test source plugin discovery against root GET /option-rules?type=source&plugin=Oracle-CDC. Require exact engineType=seatunnel, pluginType=source, pluginName=Oracle-CDC and object optionRule; reject404, malformed identities, oversized or redirected bodies. Reuse bounded HTTP handling.
- [ ] Test /jobs/checkpoints/{id} parsing of triggered/completed/failed/restored and confirmed completion times. Strip failureReason/history/private state; malformed or unavailable data is unknown rather than fabricated progress.
- [ ] Package connector-cdc-oracle3.0.0 (SHA256 a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f) against exact upstream checksum. Preserve connector-cdc-base3.0.0, ojdbc8/orai18n19.32, custom JDBC dialect, existing connectors and data paths. Do not add an older transitive driver to shared lib.
- [ ] Exercise factory discovery/classloading against exact released artifacts with no DB required. This is a loader test, not CDC data acceptance. No resident server or existing test workload is changed.
- [ ] Format, review spec then quality, commit scoped files.

## Task 2: Checkpointable Oracle full extraction and bounded runtime

**Files:** SeaTunnelJobConfig.java/Test.java; IntegrationModels.java/Test.java; new IntegrationRuntimeOptions.java/Test.java; validation and service run/restore guards.

- [ ] Regression test the previous single-split Oracle query path: query without partition_column is one split, whose read holds the source checkpoint lock until completion. The new query retains exact quoted source names and mapped aliases.
- [ ] Generate native dynamic table_list source configuration for Oracle FULL, preserving HospitalOracle and descriptor URL:

```java
source.put("table_list", List.of(Map.of("query", select,
    "partition_column", mappedPrimaryKeyAlias)));
source.put("split.size", options.splitSize());
source.put("split.allow-sampling", false);
source.put("skip_analyze", true);
source.put("use_select_count", true);
source.put("enable_concurrent_read", true);
```

Do not also set top-level query/partition_column, which selects the fixed splitter. partition_column is the exact original query output alias, without quote characters. Numeric boundaries remain native BigDecimal/Object/setObject, never application double or narrowed int.

- [ ] New FULL defaults: fetch1000, split8192, sink10000, checkpoint interval60seconds and timeout900seconds. New CDC defaults: fetch1000, split8192, sink1000, interval30seconds and timeout300seconds. Bounds: fetch100..10000; split1000..100000; sink100..50000; interval10..300; timeout60..3600 and timeout>=2*interval. Validate integers strictly and reject mode-inappropriate settings.
- [ ] Preserve SQL T+1 query/date/initialization contracts. Existing PostgreSQL savepoint resumes without a new fingerprint use the unchanged legacy generated configuration; fresh runs use the new resolved defaults. Changing options cannot silently resume an incompatible graph.
- [ ] Doris remains strict, zero filtered rows tolerated; add num_as_string=true for JSON numeric precision, preserve DELETE behavior for CDC, and do not truncate LOBs. Test large18digit IDs/small decimals and failure on oversize.
- [ ] Fingerprint new CDC runs from resolved non-secret settings, source/target routes and actual mapped column shape. Check it before resume. No credentials in fingerprints/logs; connection-password rotation alone is not a graph change.
- [ ] Guard concurrent writers to the same registered target during all run paths, including full/SQL/CDC/unknown submissions and savepoint-resumable runs. Keep current single-app deployment assumptions explicit; do not claim a new distributed lease protocol.
- [ ] Run focused failing tests before implementation, then existing job/config/initialization regressions; review and commit.

## Task 3: Source adapter and read-only prerequisites

**Files:** new CdcSourceAdapter.java, CdcAdapters.java, OracleCdcAdapter.java, PostgresCdcAdapter.java, IntegrationCdcPreflight.java and tests; IntegrationConnections.java, IntegrationService.java, HospitalIntegrationResource.java and focused tests.

- [ ] Registry preserves existing PostgreSQL source configuration, adds Oracle, and leaves Mssql FULL-only. Source capability is calculated from current code, not permanently frozen in stored supportedModes. Distinguish implemented, prerequisite-ready, and real runtime acceptance.
- [ ] Oracle source uses startup.mode=initial, explicit timezone and time.precision.mode=adaptive. Preserve descriptor URL and TLS validation. Build trusted explicit schema from inspected mapped source columns plus actual PK, bypassing the release's Oracle catalog descriptor parser:

```java
source.put("schema", Map.of("table", database + "." + owner + "." + table,
    "schema_first", true, "fields", trustedFields,
    "primaryKey", Map.of("name", "hospital_pk", "columnNames", List.of(key))));
```

Use original source field names. NUMBER18 remains exact DECIMAL(18,0); Oracle DATE is TIMESTAMP; only verified supported mappings are emitted. Driver settings forwarded under debezium database.oracle.* prefixes. Never downgrade TCPS to a plain TCP URL to avoid the catalog parser.
- [ ] Probe actual Oracle version, database name, ARCHIVELOG, database minimal supplemental logging, selected-table all-column logging or demonstrably covering unconditional groups, current LogMiner package/view access, source PK and mapped types. Queries are read-only, parameterized and bounded. Lack of access is UNKNOWN/fail-closed, never assumed enabled.
- [ ] Non-CDB 11g/19c is the initial tested path. CDB/PDB options must be validated as a pair against source container and root service with the same credentials; do not claim such tasks ready when no proven root path exists. Unknown patch/driver combinations show an explicit compatibility warning; 11.2.0.1.0 is uncertified even though JDBC currently connects.
- [ ] Block mapped CLOB/NCLOB Oracle CDC with ORACLE_CDC_LOB_UNVERIFIED. Debezium1.9.8 unchanged-LOB placeholders are not safe for whole-row Doris updates even with lob.enabled=true. Full LOB support remains enabled; unmapped LOBs are not silently added back into the emitted schema. No sentinel-to-NULL substitution.
- [ ] Mandatory engine check uses actual option-rule discovery. App JDBC success is reported separately from engine-source access. There is no REST dry-run connection endpoint: engine-source network remains a nonblocking UNKNOWN warning until actual controlled runtime proof, not a false PASS.
- [ ] For task checks, require actual single non-null PK, supported selected fields, compatible Doris Unique Key target and mappings. Required FAIL/UNKNOWN blocks ready and run; nonblocking compatibility/network warnings remain visible. Saved drafts can exist while DB prerequisites are missing, but run/resume performs fresh mandatory checks before submitting.
- [ ] Expose admin endpoints using the contract above, with negative tests for unauthorized-before-parse, schema escapes, unknown columns, malformed JSON, missing connector/logs/privileges and exception redaction. Existing PG behavior remains covered.
- [ ] Refresh checkpoint evidence with bounded remaining time; source/target counts stay correctly labeled as cumulative attempts versus an explicit actual target count sample. Native APIs cannot establish snapshot/incremental phase or committed SCN, so return unknown for those signals.
- [ ] Review spec and quality independently and commit.

## Task 4: Chinese PC operations

**Files:** hospitalIntegrationAPI.ts; HospitalIntegrationPage task/source components and focused tests; all20locale files; existing hospital-integration.less only where required.

- [ ] Use existing core components, deep-green workbench tokens, independent data source/task menus and right-side FormDrawer. No mobile scope or centered edit modals.
- [ ] Add source CDC preflight action and task preflight panel; display actual version, implemented state, required blockers, optional warnings and unknown engine-source access. Editing any source/table/columns/options invalidates old readiness. Ignore stale async responses, cancel on navigation and preserve unsaved-form guards.
- [ ] Add bounded runtime settings and Oracle database/timezone fields. Default database name may be filled from the actual preflight response; changing source clears the old Oracle settings. Keep SQL T+1 fields separate.
- [ ] Disable starting/resuming when required checks fail or the result is stale. Server remains authoritative. Surface ORACLE_CDC_LOB_UNVERIFIED and missing archive/supplemental/connector clearly in Chinese.
- [ ] Show checkpoint completion/failure/restore counts and actual target count with sampling time; label source/sink counters cumulative. No inferred incremental-ready or committed SCN display.
- [ ] Add meaningful component tests for blocker/reset/stale-response/permissions and input bounds, plus20real locale translations. Organize imports→ESLint fix→Prettier; build production bundle. Inspect rendered PC states once, fix defects as a batch, confirm once.
- [ ] Follow user-selected direct-code workflow with Taste/Impeccable4.7.0. Parent has already run context once for this surface; read DESIGN.md, surface brief and craft-floor before editing, do not rerun context. Current explicit user authorization for this HIS table supersedes older PRODUCT.md synthetic-only wording.
- [ ] Review and commit after backend contract is stable.

## Task 5: Final evidence and concrete rollout

- [ ] Run all owned backend suites and UI suites, build candidate service/UI archives, compile and verify engine context and hashes. Do not count opt-in skipped real tests as passed.
- [ ] Allowed read-only HIS preflight verifies the actual missing logging conditions and classifies11.2.0.1.0 honestly. Controlled candidate FULL to a fresh target may be run under the original ZYYS_YZXX full-test authorization, preserving source records and existing targets; candidate/new-class execution must be labeled accurately.
- [ ] Real Oracle CDC awaits DBA log changes and exact test record range. No source writes or restart/grant changes without those concrete approvals. Do not hide these blockers by treating SQL polling as CDC or by marking unsupported LOB CDC ready.
- [ ] Perform final independent spec/quality review, preserve existingSSO/NLS/Secrets/PVC/checkpoints, and prepare fixed-digest images plus exact rollout/rollback diff. This development approval does not itself perform HIS administration or arbitrary runtime upgrades.
