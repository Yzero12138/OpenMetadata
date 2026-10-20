# Doris target and managed test warehouse

Date: 2026-10-20. Approved scope: user's explicit instruction to deploy Doris and implement target type, target-table creation and field mapping, following the architecture proposed immediately beforehand. Routine implementation choices below execute that approval; no additional design gate is required.

## Architecture and choice

Independent Doris is the business warehouse; existing PostgreSQL remains the platform metadata store. SeaTunnel3.0.0 writes through its Doris Stream Load sink. Direct namespaced FE+BE StatefulSets fit this isolated single-replica test; a clusterwide operator/CRDs would add unnecessary shared infrastructure. Treating Doris as generic Mysql would omit Stream Load semantics, HTTP connectivity and Unique Key handling, so it is rejected. Replacing the hospital interface with another control plane would fragment the existing login and drawers, so the existing PC workspace is extended.

Doris4.0.8 is selected from the official Stable channel, fixed to verified image digests. Test topology: FE1+BE1 on k8s-worker01, requests FE1CPU/2Gi, BE2CPU/4Gi, limits FE2CPU/4Gi and BE4CPU/8Gi, subject to actual image startup verification. Dedicated local-path FE5Gi and BE20Gi volumes; preserve on rollback. ClusterIP service only, restricted application/engine ingress and component communication. Node vm.max_map_count must satisfy2000000; record prior65530 and apply only the selected node's prerequisite with a bounded helper, preserving the official startup check. Raise container nofile soft limit within existing hard limit instead of changing host process limits.

Bootstrap FE initial hashed password from dedicated Secret before startup, then register BE, create ods_validation and a scoped hospital_ods_writer with only SELECT/LOAD/CREATE on that database. Credentials stay in K8s Secret and encrypted server storage; never print them, never use an unauthenticated root. Existing hospital data is excluded from acceptance.

## Connection contract

Add databaseType Doris, TARGET only, dorisHttpPort required for Doris only. SQL9030 and HTTP8030 are distinct; first test connection uses internal DISABLED TLS and must not advertise unsupported verified transport. Database is a safe identifier and schemas exactly [database]. Existing four database families retain their behavior. Optional environment-managed synthetic target id doris-ods-validation is exposed only with HOSPITAL_DORIS_ENABLED=true and complete host/SQLport/HTTPport/database/user/password configuration; it does not replace the existing two synthetic PG definitions.

## Safe table creation API

POST /hospital/integration/connections/{id}/tables/preview accepts sourceConnectionId/sourceSchema/sourceTable/targetSchema/targetTable/fieldMappings. POST /hospital/integration/connections/{id}/tables additionally requires previewHash. Both use native admin authorization before bounded strict JSON parsing. Inputs never contain SQL. Response preview is {targetConnectionId,previewHash,ddl,table,fieldMappings}; create returns the real TableDefinition.

Derive target types from live source metadata with lossless numeric, Unicode and temporal capacity checks. Require one actual mapped nonnullable source key, safe unique names and1..128 columns. Refuse unsupported types or invalid precision instead of casting. Build Unique Key/Merge-on-Write table with key column first, hash distribution and HOSPITAL_DORIS_REPLICATION_NUM (test1; bounded default3). PreviewHash fingerprints source metadata including precision/scale, both connection versions, mappings and generatedDDL. Create recomputes and compares; changed source/target yields409 TABLE_PREVIEW_STALE. Existing targets return409 TABLE_ALREADY_EXISTS; no overwrite/alter/drop/truncate.

## Task and interface

Preserve the separate data-source/task menus and existing green Chinese PC language, shared FormDrawer, fixed footer and draft recovery. New Doris connection fields show SQL port and HTTP write port distinctly; Doris source role remains invalid. In the task's right drawer, offer an inline From-source-table action after source and Doris target selection, editable target names and explicit omitted columns, read-only previewDDL, then explicit create. Edits invalidate the preview, stale async results are ignored, pending creation prevents duplicate submits, failures retain inputs. Created table is selected immediately, metadata is refreshed and mappings populate without clearing other task fields.

Doris Sink uses its actual connector and a fresh label prefix per fresh run; no exactly-once claim (2PC disabled in first slice). Unique Key upserts and actual repeated full transfer are tested. Existing PostgreSQL CDC remains subject to actual sink compatibility tests; Oracle CDC is outside this slice. Native Doris catalog service registration and column lineage continue separately from transfer status.

## Verification

Meaningful tests cover strict input parsing, admin-only endpoints, invalid IDs/roles, SQL identifier rejection, unsupported metadata, stale previews, existing-table protection, Unique Key discovery, Doris sink config and frontend pending/error/stale response handling. Actual synthetic source-to-Doris run must verify exact Chinese values, numeric/time fidelity, mapping renames, repeated-key updates and no duplicate rows; no hospital table rows are read. Validate scoped writer permissions, FE/BE health, pinned images, mounted volumes, ingress/egress isolation, existing SSO/device-gate state and preserved Oracle NLS. UI captures grouped at1440x1000 and1920x1080, one detector after finish, at most one correction round. Build once after integration, review, publish fixed digests, record real deployment evidence and source commits.
