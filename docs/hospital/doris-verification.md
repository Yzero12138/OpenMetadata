# Doris backend verification

Evidence recorded on 2026-10-20. All source rows used by these acceptance checks are synthetic. No HIS business rows, employee account, or deployed catalog record was read or modified by these helpers.

## Local checks

The focused Java run completed with 52 passing tests and two opt-in database cases skipped. The two opt-in cases were then run against the actual deployed Doris 4.0.8 FE/BE and a disposable local PostgreSQL 17 database: both passed with zero skips. Spotless and backend `git diff --check` passed before application packaging.

After the real engine check exposed the label constraint, the expanded focused suite completed with 63 passing tests, zero failures/errors, and the same two opt-in cases skipped. This includes the new label boundary tests and eight real HTTP SeaTunnel-client cases. The label-fix candidate service JAR SHA-256 was `3c66a48b88b15697813b24b3a32a8ebf89e4fcd6d64ccb68fdf216def1903f11`.

The final candidate also includes the savepoint-status fix described below. Its expanded suite passed 64 tests, zero failures/errors, with two opt-in cases skipped. Spotless and the service `package` goal succeeded; the final JAR SHA-256 is `25a92ed738a25c9e4757d8ee3955b2622aaa5c7dcd6a5cf21a37f2aa86a4420c`.

Both opt-in `DorisJdbcTest` methods were rerun after those final changes against the actual FE/BE and disposable PostgreSQL fixture: two passed, zero failures/errors/skips. Thus all 66 scoped cases were exercised successfully across the focused and real-database runs. The owned loopback Doris port forward was then stopped after verifying its process identity.

The HTTP fixture uses a real JDK HttpServer to check successful FE health, error/malformed/oversized bodies, HTTP 503, rejected redirects, and a slow response body bounded by the total deadline. Windows JDK selector tests required a short, per-process `jdk.net.unixdomain.tmpdir`; no global TEMP or production HTTP behavior was changed.

## Actual Doris JDBC and safe creation

The deployed FE writer was read only from its existing Secret file into process environment. Credentials were never printed or added to command arguments. SQL and HTTP access used loopback-only, time-bounded port forwards.

Actual JDBC checks passed for connection establishment, `setReadOnly(true)`, `isValid`, database-as-catalog table discovery, absent-table inspection, and the combined SQL/FE HTTP health test. No read-only compatibility exemption was necessary.

Both `DorisJdbcTest` methods executed successfully:

- Generated OLAP Unique Key DDL was accepted; inspection recovered the logical single-column primary key and conservative Unicode character capacity.
- Chinese text and supplementary Unicode emoji survived a real JDBC round trip. Repeating an INSERT with the same key left one row.
- Recreating an existing table returned `TABLE_ALREADY_EXISTS`; a Duplicate Key model was not misreported as a primary key.
- The production service recomputed live PostgreSQL metadata. A source length change and a registered connection version change each caused `TABLE_PREVIEW_STALE` before creation. A fresh preview created the new table and a further create attempt returned an existing-table conflict.

The source fixture was confined to a local PostgreSQL database named `integration_test` and removed by the tests. Newly created Doris validation tables were preserved.

Evidence: ignored `.cache/doris/acceptance/jdbc-readonly-probe.log` and `doris-real-jdbc-tests.log`. The fixture uses a random PostgreSQL password protected locally with Windows DPAPI, not the deployed metadata or source database.

## Actual native catalog projection

A separate local database `integration_catalog_test` was initialized from the existing schema-only native catalog export. A new loopback-only OpenSearch 3.3.0 container and unique test index prefix were used. These fixtures contain no copied users or native catalog rows from the deployed application.

The helper initialized the real native repositories, inspected a real PostgreSQL source table and the actual Doris target, and called the production catalog projection twice. Checks passed for:

- Native `Doris` database-service and table identity, renamed BIGINT primary key, and decimal precision/scale.
- Six mapped column lineages, the real pipeline reference, and durable task catalog status in native SQL storage.
- Stable table/pipeline IDs and unchanged column lineage on the second synchronization.
- No runtime connection or credential copy into native database-service records.
- A real Doris target document indexed in OpenSearch.

Preserved target: `ods_validation.ods_doris_catalog_eeaa4ac1c06248d7acb35c744be3381e`. The temporary PostgreSQL source table was removed. Evidence: ignored `.cache/doris/acceptance/doris-native-catalog.log`.

This verifies real native repository/SQL/search integration. It does not exercise the deployed application's authenticated catalog API or represent an employee action; the actor is a fixture label in the disposable catalog database.

## Mapped FULL engine acceptance

The first actual FULL job, `984918146341189797`, ran on the deployed new application and engine, then failed during Stream Load. Native Doris DDL and the six-column mapping were accepted. The engine log identified an expanded label longer than Doris's default 128-character limit. The failed UUID source fixture was removed and its empty Doris target was preserved. Retry metrics of 400 source/sink events are not successful row counts.

Inspection of the pinned connector 3.0.0 bytecode confirmed the final label formula: `configuredPrefix_database_table_engineJobId_subtask_millis`. The factory uses the configured target database/table with no schema component. The application fix uses a 17-character SHA-256-derived prefix based on task ID and run ID; fresh runs change it and resumes retain it. With fixed parallelism one, reserving 19 characters for each long and five separators leaves 67 characters for the database and table names together. Preview, create, task validation, and job construction reject incompatible names before mutation using `DORIS_LABEL_INVALID`; names use ASCII letters, digits, and underscores. The regression tests first reproduced the old failures, then passed at the exact 67/68-character boundary. [Doris documents the default label limit and character rules](https://doris.apache.org/docs/4.x/sql-manual/basic-element/object-identifiers/).

The trusted helper runs inside the new app pod using the production `IntegrationService`, `JdbcInspector`, `SeaTunnelJobConfig`, and `SeaTunnelClient`. It is guarded to the isolated `synthetic_source` and `ods_validation` endpoints, creates a UUID source fixture, and maps:

| PostgreSQL source | Doris target |
| --- | --- |
| `visit_id` BIGINT primary key | `event_id` |
| `patient_label` with Chinese and emoji | `display_name` |
| `charge_amount` NUMERIC(18,2) | `total_charge` |
| `updated_at` TIMESTAMP(6) | `modified_at` |
| `visit_day` DATE | `event_day` |
| `active` BOOLEAN | `is_active` |

All three FULL runs after the fix passed against the real engine and Doris:

| Job ID | Source / sink metrics | Exact mapped target rows | State |
| --- | --- | --- | --- |
| `7161219587029711561` | 100 / 100 | 100 | FINISHED |
| `8587135268982881321` | 105 / 105 | 105 after ten updates and five inserts | FINISHED |
| `4932364326073262133` | 105 / 105 | 105 on repeat, with 105 distinct keys | FINISHED |

Every row was compared across all six fields, including renamed BIGINT primary keys, Chinese/emoji strings, exact decimal values, microseconds, dates, and booleans. The three fresh runs used distinct label prefixes. Preserved target: `ods_validation.ods_doris_full_005ea952688a46529fdc16caaaee985a`. Only the helper's UUID source table was removed; row values and credentials were not logged.

These three FULL runs used the label-fix candidate service JAR SHA-256 `3c66a48b88b15697813b24b3a32a8ebf89e4fcd6d64ccb68fdf216def1903f11`, before the later savepoint-status change. It was loaded first in the helper's separate JVM inside the Ready app pod whose image was `sha256:ba285fb4af5e8140d81956a1413dca30371ff553e1f34b57ef6123662d6163e8`. The pod's application process and `/opt/openmetadata/libs` were untouched. Final published-image checks below exercised the final `25a92ed...` JAR directly. Evidence: ignored `candidate-mapped-full-real-engine.log` and `candidate-mapped-full-execution.state.json`.

Prepared artifacts and guarded execution commands live in ignored `.cache/doris/acceptance`. Execution requires the parent's readiness authorization and exact application image digest. Exactly-once delivery and a deployed authenticated UI/API workflow are not claimed by these helpers.

## Mapped PostgreSQL CDC engine acceptance

The first bounded CDC job, `1044512768162957256`, verified 100 exact snapshot rows and completed checkpoint 1; ten updates plus five inserts and three deletes converged to 102 exact rows, followed by completed checkpoint 2. The savepoint request then exposed a client status bug: SeaTunnel returned the legitimate transient `DOING_SAVEPOINT` state, which the status whitelist rejected. The helper treated that as a failure and canceled only its own job during cleanup. This was a false negative for the completed data/checkpoint checks, and a real compatibility defect in `SeaTunnelClient.info`.

The client now normalizes `DOING_SAVEPOINT` to the existing `STOPPING` state while still requiring the native `SAVEPOINT_DONE` terminal state and completed savepoint evidence. A real HTTP fixture reproduced the original failure and passed after the change; arbitrary unknown statuses remain rejected. The complete focused suite then passed 64 tests with the two opt-in database cases skipped. The failed check's source table, publication and replication slot were removed, and its 102-row Doris target was preserved.

The fresh fixture passed all checks with job `5382940488898877075` and the final candidate JAR `25a92ed...`:

| Check | Actual result |
| --- | --- |
| Initial snapshot | 100 exact rows; checkpoint 1 COMPLETED |
| Ten updates, five inserts, three deletes | 102 exact rows and 102 distinct keys; checkpoint 2 COMPLETED |
| First savepoint stop | STOPPING then SAVEPOINT_DONE; native completed savepoint confirmed |
| Resume | Same job ID and configuration reached RUNNING |
| Further source change after resume | One row changed by 0.25, one microsecond, and a Chinese suffix; 102 rows matched every field; checkpoint 4 COMPLETED |
| Final savepoint stop | STOPPING then SAVEPOINT_DONE; native savepoint 5 COMPLETED |

The final checkpoint API reported five triggered/five completed, zero failed, and zero in progress. Every comparison included BIGINT key renaming, Chinese/emoji, decimal precision, timestamp microseconds, dates, and booleans. The final target `ods_validation.ods_doris_cdc_b4b76a058ca145458698df555a201093` is preserved with 102 rows.

Independent SQL checks confirmed zero owned source tables, replication slots, and publications remaining across both FULL/CDC attempts. The final engine overview reported zero running and zero pending jobs. These checks used the same separate-JVM candidate deployment boundary described for FULL. No Oracle CDC or exactly-once claim is made.

Evidence: ignored `candidate-mapped-cdc-real-engine.log`, `candidate-mapped-cdc-execution.state.json`, `mapped-cdc-final-checkpoint-evidence.json`, `mapped-fixture-cleanup-verification.log`, and `mapped-engine-final-overview.json`.

## Final published-image verification

The Ready pod `hospital-openmetadata-7477df45f4-gl4qx` was verified with image ID `sha256:54e4d883d86b901cb1180a9ace36dbeaa1e465b5a81060cc6974a4843dad6baa`, zero main-container restarts, and `/opt/openmetadata/libs/openmetadata-service-2.0.4.jar` SHA-256 `25a92ed738a25c9e4757d8ee3955b2622aaa5c7dcd6a5cf21a37f2aa86a4420c`. Both final helpers used only the published `/opt/openmetadata/libs` and the helper JAR; no candidate-service override was present.

| Published-image check | Actual result |
| --- | --- |
| FULL job `805929965953660776` | FINISHED; source/sink counters 100/100; 100 exact rows and 100 distinct keys across all six fields |
| CDC job `8126037624087455134` snapshot | 100 exact rows; checkpoint 1 COMPLETED |
| CDC ten updates, five inserts, three deletes | 102 exact rows and distinct keys; checkpoint 2 COMPLETED |
| CDC first stop and resume | STOPPING → SAVEPOINT_DONE with completed savepoint; same job/configuration resumed to RUNNING |
| CDC update after resume | One synthetic row changed in decimal, microsecond timestamp and Chinese text; all 102 rows exact; checkpoint 4 COMPLETED |
| CDC final stop | STOPPING → SAVEPOINT_DONE; native savepoint 5 COMPLETED; five checkpoints completed, zero failed/in progress |

Preserved final targets are `ods_validation.ods_doris_full_d4adfe90681448289971a49ea9aad300` (100 rows) and `ods_validation.ods_doris_cdc_d8420d29d55c4aea8e9cf7d8f439b6f9` (102 rows). Independent SQL checks covering all six owned source tables and all three owned replication-slot/publication names returned zero remaining objects. The final engine overview reported `runningJobs=0` and `pendingJobs=0`.

After these final checks, the two local disposable fixtures were removed by exact container ID after confirming their names and `hospital.doris.acceptance=true` labels: `hospital-doris-acceptance-pg-69ed9913` and `hospital-doris-acceptance-search-d7dadd93`. Only their own anonymous volumes were removed. Both used the default Docker bridge; no dedicated network was created or deleted. Doris validation tables, cluster Secrets/PVCs, engine checkpoints, and historical jobs were retained.

Final evidence is in ignored `.cache/doris/acceptance/published-app-verification.json`, `mapped-full-real-engine.log`, `mapped-cdc-real-engine.log`, `published-cdc-final-checkpoint-evidence.json`, `published-fixture-cleanup-verification.log`, `published-engine-final-overview.json`, and `local-fixture-cleanup-completed.json`. Candidate evidence remains separately preserved with `candidate-mapped-*` names. The authenticated deployed catalog/UI workflow remains outside these helpers' scope; native catalog SQL/search was verified separately above.

## Final test-cluster publication

The final application is `harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:54e4d883d86b901cb1180a9ace36dbeaa1e465b5a81060cc6974a4843dad6baa` (tag `harbor.qcrmyy.local/coop/hospital-openmetadata:2.0.4-integrate-v11-doris-r2-20261020`), built from source `edb2c5e81c20f8b9fea8c9cf577586d413ae9620`. Service archive: `25a92ed738a25c9e4757d8ee3955b2622aaa5c7dcd6a5cf21a37f2aa86a4420c`. UI archive: `dd7715bf001fd03173cb659e324488fd3fd1a5291a426fe34dd3b6b0d3342e8a`. The exact published image was used for final FULL/CDC checks above, without a candidate classpath override.

The engine is `harbor.qcrmyy.local/coop/hospital-seatunnel@sha256:477f41ca16167c9fbec07f188235a781aa7bf6ec844de4826c31016b638367c0`. Doris FE/BE retain official 4.0.8 amd64 manifests mirrored to Harbor: `sha256:0994b0e017ca024116e9c721b74fbc7293b1edc18ada988cdb1abdfed60cfa2f` and `sha256:b56c70cbe32d4ccdea4a166ddf0ed55a38e82ce4eac5a8aceb80480c946ebf76`. Endpoints, Secret keys, retained FE5Gi/BE20Gi volumes and single-node test limits are in [deployment](doris-deployment.md); [build and use](../../docker/hospital/README.doris.md) describes the page workflow.

The final app rollout succeeded with a Ready main container and zero main restarts; its migration exited zero. Only the main image and dedicated Doris environment bindings were added to the initial deployment; the final correction changed only the main image. The migration image, original environment references, volume mounts and workload identity remain intact. A new application-only Egress policy initially isolated DNS/metadata traffic; it was removed from cluster and source. Final Doris manifests pass client/server dry-run as ten resources and three new policies.

13 HTTP/authentication checks and 138 byte-for-byte static resources passed against the final image. Anonymous integration routes remain 401, including malformed table-create/preview bodies before parsing; a nonexistent SSO ticket remains 401, and Integrate retains its issuer. The existing saved Oracle11g connection was opened and validated using packaged libraries and the unchanged Oracle NLS archive; no hospital rows were queried. Portal device gate remains NOT_ENFORCED, with anonymous identity still 401.

An unrelated temporary pod without credentials or a service-account token was denied FE9030/8030 and BE8040, then removed. The final app/engine supplied positive controls through actual metadata inspection and StreamLoad. Snapshot comparison preserved all original 38 workload identities, with only authorized app/engine specifications changed; original 44 ConfigMaps, 34 policies and 10 PVC identities/bindings were unchanged. Final totals: 40 workloads, 46 ConfigMaps, 37 policies and 12 PVCs. No existing checkpoint volume was removed.

UI behavior and PC visual evidence are recorded separately in [Doris UI extension](doris-ui-design-extension.md). The single detector returned []; the independent verdict scored the prior drawer-containment fix resolved. Synthetic UI and trusted-helper checks do not claim a real employee positive login or an authenticated live UI operation. PostgreSQL remains platform metadata storage; Doris is independent business storage. No real HIS business records were extracted.

## Final PC regression

The final five UI/API suites passed all 75 tests. New regressions cover the Doris label rejection, retained name/column drafts, focused actionable errors, shortening and re-preview, and the 4.0.8 version example. A full-suite focus failure was traced to the old test helper wrapping user-event in an extra asynchronous act: its focus-restoration frame ran before the focused button was removed. Only that continue-editing test click now uses user-event directly; all original assertions remain and production files were unchanged. TypeScript remains the recorded 550 baseline diagnostics with zero in the owned files; organize-imports, ESLint, Prettier and diff checks passed. The final production UI build and archive are those pinned above; the test-only correction required no rebuild.
