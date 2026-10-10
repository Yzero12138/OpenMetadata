# Hospital Drawers and Managed Data Sources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make create/edit forms open from the right on PC and let administrators maintain business data sources and select them for real SeaTunnel extraction tasks.

**Architecture:** Keep the existing OpenMetadata shell, hospital identity and Integrate authorization. Reuse the native slideout primitive for forms. Store managed connection definitions in a separate entity-extension namespace, encrypt credentials with the initialized native Fernet key, and resolve registered connection IDs on the server for JDBC inspection, job configuration and catalog projection. Preserve the two environment-managed synthetic connections and existing tasks.

**Tech Stack:** Java 21, Dropwizard/JDBI, native Fernet, JDBC, SeaTunnel 3.0.0, React 18/TypeScript, native UI core SlideoutMenu, Jest and Chromium PC captures.

## Accepted scope and boundaries

- The user confirmed that create/edit overlays are the primary issue, selected PC-only delivery, and requested data-source maintenance for business collection.
- Support Oracle 11g/19c, SQL Server 2016/2019, MySQL 8 and PostgreSQL connection definitions and full JDBC collection. Keep CDC available only for the verified PostgreSQL path in this phase; communicate the other families' full-only capability in the configuration UI. Do not enable untested connector modes merely because upstream provides a plugin.
- No real hospital credentials were supplied. All actual transfer verification remains isolated and synthetic; Isolated Oracle XE 11g, SQL Server 2019, MySQL 8.0.36 and PostgreSQL 17.10 instances have passed real full transfers. Oracle 19c, SQL Server 2016 and actual hospital endpoints remain unverified.
- Preserve existing employee identities, authentication, namespaces, volumes, governance data and task/savepoint state. Do not expand external database network access without concrete endpoints.
- No private environment details or credentials enter public source or new GitHub descriptions.

## Task 1: Shared right-side form chrome and existing form migration

**Files:**
- Create `openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/FormDrawer.tsx` and a focused test in that directory.
- Modify `AiFormModal.tsx`, `components/Modals/EntityNameModal/EntityNameModal.component.tsx` and the create/edit form modules selected by the native/legacy modal inventory.
- Record the exact migrated module inventory in `docs/hospital/form-drawers.md`.

- [x] First add tests for right placement, accessible title, Escape/focus return, fixed footer, retained form draft on rejected save, native form submit and close protection during submission.
- [x] Run the focused Jest file and observe failure before implementation.
- [x] Implement a reusable native slideout with this public shape:

```tsx
<FormDrawer isOpen={open} title={title} onClose={close} width={720}
  isSubmitting={saving} footer={actions} data-testid="form-drawer">
  {form}
</FormDrawer>
```

- [x] Migrate actual create/edit/configuration forms using native chrome; preserve IDs, validation, API callbacks, loading and cancel behavior. Leave destructive confirmation and read-only viewers under their existing semantics.
- [x] Run relevant focused existing form suites and the new shared tests. Format changed UI files using organize-imports, ESLint fix, then Prettier. Perform spec review followed by quality review and fix concrete findings before marking this task complete.

## Task 2: Managed connections and safe JDBC resolution

**Files:**
- Create `IntegrationConnectionStore.java`, `EntityExtensionConnectionStore.java`, `IntegrationConnections.java`, `JdbcDialect.java` and `JdbcInspector.java` under `openmetadata-service/src/main/java/org/openmetadata/service/integration/`.
- Modify `IntegrationModels.java`, `IntegrationConfiguration.java`, `IntegrationService.java`, `IntegrationValidator.java`, `SeaTunnelJobConfig.java`, `CatalogProjector.java`, `IntegrationException.java` and `resources/hospital/HospitalIntegrationResource.java`.
- Add `IntegrationConnectionsTest.java` and extend existing JDBC/configuration/resource tests under the matching test package.

- [x] Add failing tests for encrypted persisted password and redacted responses, empty-password update retaining credentials, strict endpoint/identifier validation, role/schema checks, optimistic conflict, immutable environment seeds, blocked referenced deletion and blocked active/savepoint connection changes.
- [x] Implement administrator-only `POST /connections`, `GET /connections/{id}`, `PUT /connections/{id}` and `DELETE /connections/{id}?version=...`. Keep list/test/table routes compatible. Return typed error codes and bounded messages without JDBC exceptions or secret values.
- [x] Connection input fields are `name`, `displayName`, `role` (`SOURCE`/`TARGET`), `databaseType` (`Oracle`/`Mssql`/`Mysql`/`Postgres`), `databaseVersion`, `host`, `port`, `database`, `username`, optional `password`, `schemas`, `oracleConnectionType` (`SERVICE_NAME`/`SID` for Oracle), `tlsMode` (`DISABLED`/`VERIFY`), `enabled`, and `version` for updates. Public output adds `id`, `version`, `managed`, `synthetic`, `passwordSet`, timestamps and `supportedModes`; it never returns any password or ciphertext.
- [x] Build driver URLs and SQL quoting on the server; do not accept arbitrary JDBC URLs, SQL or driver properties from a browser. Inspect only explicitly configured schemas using read-only JDBC connections and bounded timeouts. Generate JDBC full-source configuration for each family and preserve verified PostgreSQL CDC configuration.
- [x] Use unique catalog service identities per connection, correct native service types and factual labels. Do not label a managed business source as synthetic.
- [x] Run scoped Java compilation/tests, then Spotless apply/check. Test the real persisted connection and full-transfer path against an isolated database without creating employee tokens.

## Task 3: Data-source maintenance and integration task drawers

**Files:**
- Modify `src/rest/hospitalIntegrationAPI.ts`, `src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx`, `IntegrationTaskForm.tsx`, interfaces, utilities and `hospital-integration.less` under `openmetadata-ui/src/main/resources/ui/`.
- Create `IntegrationConnectionForm.tsx` and focused tests in that page directory.
- Update all 20 `src/locale/languages/*.json` files with actual translations for new keys.
- Extend `tools/hospital-ui-preview/main.tsx` and its integration capture script.

- [x] Add failing tests for source creation/editing, password omission on unchanged edit, right drawer placement, list preservation, failed saves retaining draft, selectable source/target IDs, role filtering, connection-specific table reload and stale-request rejection.
- [x] Render parallel task/data-source tabs with a single context-specific create action. The source table exposes name, family/version, role, synthetic/environment-managed status and test/edit/delete actions with clear pending/error states.
- [x] Open source/task create and edit in `FormDrawer`. Keep underlying list/detail mounted. Put cancel/save in the fixed footer; long field mappings scroll in the body. Preserve active-task restrictions and existing 409 recovery behavior.
- [x] Load table options from the selected IDs, clear dependent mappings on connection change, and show only server-advertised collection modes. Do not claim a source is connected merely because its definition was saved.
- [x] Run the focused Jest suites, changed-file formatting, translation-key parity and TypeScript comparison against the recorded baseline.

## Task 4: Runtime drivers, deployment and bounded verification

**Files:**
- Modify `docker/hospital/Dockerfile`, `docker/hospital/build-image.ps1` and `docker/hospital/seatunnel/Dockerfile`; add a pinned driver manifest/build procedure under that engine directory.
- Update the portable deployment template and generic operations/verification docs under `deploy/hospital/k8s/` and `docs/hospital/` as needed.
- Update `.impeccable/surfaces/hospital-data-integration.md` and add a scoped form-drawer surface record.

- [x] Pin compatible Oracle, Microsoft SQL Server and MySQL JDBC artifacts/checksums for both application inspection and engine extraction; verify loaded driver classes. Preserve the official baseline distribution and existing PostgreSQL driver behavior.
- [x] Build final service/UI JARs and app/engine images. Keep private build and rollout records in ignored local logs.
- [x] Capture one PC batch at 1440 and 1920 covering task create/edit, source create/edit, long mapping scroll, connection failure, save conflict and pending submission. Inspect actual screenshots and keyboard behavior; fix the bounded batch of findings and allow at most one confirmation round.
- [x] Run the Impeccable detector once at completion, then fresh finish/documentation handoffs and independent code review. Report the exact verified scope and unverified real Oracle/SQL Server connectivity.
- [x] Publish only scoped public-safe source changes to the already authorized fork/Draft PR. Roll out guarded exact images to the existing test deployment, verify readiness, static assets, unauthenticated rejection, identity preservation and retained synthetic tasks/engine state. Do not expose business database traffic before actual endpoints are configured.

## Plan self-review

The plan covers shared create/edit forms, data-source CRUD, all four requested database families, task selection, credential protection, PC drawers, Chinese copy, runtime drivers, isolated extraction and test deployment. It preserves employee login and avoids asking for an already confirmed form direction again. Mutable connection and task operations share a server lock in the current single-replica deployment; multi-replica control remains a separate architecture change.
