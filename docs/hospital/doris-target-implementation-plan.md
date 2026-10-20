# Doris Target Implementation Plan

> Agentic workers: execute focused domain tasks with superpowers:executing-plans; apply test-driven-development and review before completion. Independent infrastructure/backend/frontend ownership is managed by dispatching-parallel-agents.

**Goal:** Run an isolated Doris test warehouse with usable target configuration, safe target creation and actual mapped SeaTunnel transfer.
**Architecture:** Existing metadata and authentication stay authoritative; add independent FE/BE, native Stream Load sink and server-generated targetDDL.
**Tech Stack:** Doris4.0.8 Stable, SeaTunnel3.0.0, OpenMetadata2.0.4 Java21, React18/TypeScript, current native UI components, K8s namespaced StatefulSets.

## Task1 Infrastructure
- [x] Create deploy/hospital/k8s/doris/{kustomization.yaml,fe-extra.conf,be-extra.conf,prepare.sh,start.sh,bootstrap.sh,services.yaml,statefulsets.yaml,network-policies.yaml,initialize-secrets.ps1} and docs/hospital/doris-deployment.md using inspected exact image layouts.
- [x] Check node prerequisites and generated secret boundaries; review before applying.
- [x] Run kubectl apply --dry-run=server -k deploy/hospital/k8s/doris; create only dedicated resources; wait FE/BE readiness.
- [x] Bootstrap the scoped writer and confirm SQL and authenticated Stream Load endpoints. Preserve dedicatedPVCs on rollback.

## Task2 Backend
- [x] Extend IntegrationModels, IntegrationConfiguration, IntegrationConnections, JdbcDialect/JdbcInspector, SeaTunnelJobConfig, CatalogProjector and HospitalIntegrationResource; keep DDL generation/execution in focused new classes.
- [x] Add meaningful failing connection/DDL/model/resource/sink tests before implementations, run targeted Maven module tests, then real Doris fixture.
- [x] Preview/create contract is exactly the accepted design. Confirm hash drift and preexisting-table failures, no SQL body, no credentials in payloads/logs.
- [x] Run mvn -pl openmetadata-service spotless:apply after Java edits.

## Task3 PC interface
- [x] Extend hospitalIntegrationAPI.ts typed Doris/httpPort/table-preview APIs.
- [x] Extend IntegrationConnectionForm, IntegrationConnections, IntegrationTaskForm and metadata hook; add focused Doris table builder component.
- [x] Test defaults/role guards, draft preservation, editable mappings, explicit preview/create, stale async invalidation and automatic new-table selection.
- [x] Add real locale translations for new keys; run organize-imports-cli, eslint --fix, prettier --write on changed targets.
- [x] Run relevant Jest groups and one integrated production UI build.

## Task4 Integration and delivery
- [x] Prepare fixed engine Doris connector dependencies without losing current Oracle NLS/JDBC dialect support.
- [x] Exercise actual synthetic PostgreSQL -> Doris FULL and mapped/repeated-update transfers; compare exact target rows.
- [x] Review domains and integrated result, capture PC UI states in one batch, run one mechanical detector and bounded corrections.
- [x] Build/push exact app+engine images; record before state; upgrade only authorized test resources.
- [x] Verify database, network, auth, UI, source commits and persistent volumes; update docs and GitHub.

## Evidence
Store redacted runtime evidence in ignored .cache/doris. Test success does not stand in for real hospital sourceCDC or productionHA acceptance.
