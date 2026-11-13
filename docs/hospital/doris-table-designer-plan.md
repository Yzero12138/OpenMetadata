# Doris Table Designer Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for isolated implementation and sequential specification/quality review. User has authorized creation, existing-table additions, testing and the established test delivery workflow.

**Goal:** Design Doris target tables and append fields through the hospital PC workspace, then use the actual table in integration without backend manual DDL.

**Architecture:** Keep source-derived creation unchanged. Add typed manual CREATE/ADD preview and execution, a real-structure reader, one reusable designer, standalone target-table management and task-drawer integration.

**Tech Stack:** Existing Java21/Dropwizard/JDBC, React18/TypeScript/Vite7/core-components, Doris4.0.8 and SeaTunnel3.0.0. No new frontend packages or local servers.

## Backend and contract

Files: create `openmetadata-service/src/main/java/org/openmetadata/service/integration/DorisTableDesignModels.java`, `DorisTableDesign.java`, `DorisTableDesignService.java`; extend IntegrationService, IntegrationException and resources/hospital/HospitalIntegrationResource. Tests beside current integration suites.

- [x] Add failing pure tests for manually configured `visit_id BIGINT NOT NULL` key, `amount DECIMAL(12,2) DEFAULT 0`, `department VARCHAR(256)` and Chinese comments; assert generated CREATE contains UNIQUE KEY and preserves configured types/defaults.
- [x] Add failing ADD tests: nullable field succeeds; NOT NULL without non-null default rejects; duplicate/existing key/type/parameter/size/overflow/invalid-date/injection inputs reject; stale structure/connection fingerprints differ.
- [x] Implement the exact models/routes in doris-table-designer-design.md, strict canonicalization and generated single DDL. Check real table metadata before applying; preserve old public constructors and source-derived API.
- [x] Assert resource authorization before malformed body, database scope, existing-table refusal, active-task protection and actual asynchronous completion handling. Use isolated JUnit/java compilation; never invoke broad Maven tests/Testcontainers. Reuse cached classpath at `F:/datacenter/_scratch/doris-backend-classpath.txt` and isolated `.cache/doris-designer/backend` outputs.
- [x] Sequential spec review, fix gaps, then independent quality/security review before final integration.

## PC UI and task integration

Files: create `src/pages/HospitalTargetTablesPage/` and shared designer components/interfaces/utils/tests; extend rest/hospitalIntegrationAPI.ts, routes, workspace navigation and task drawer entry. Preserve original IntegrationTargetTableForm behavior.

- [x] Write failing component behavior tests for blank create without source, editable field settings, primary-key validation, preview invalidation, duplicate-submit guard and errors preserving input.
- [x] Implement target connection/table list, search, actual structure, new-table and add-columns right drawers with native core controls and semantic table; use current FormDrawer, incumbent tokens and Chinese text.
- [x] Write failing source-import/task-draft tests; implement using safe live source metadata/SQL result and preserve parent task values; created table/matching mappings are immediately selected. Standalone action preselects connection and target table in a new task.
- [x] Test ADD non-null/default constraints, stale results and APPLIED/SUBMITTED distinction; poll only while relevant drawer is open, cancel stale reads and do not retry a mutation automatically.
- [x] Reuse established locale labels; translate all new messages across20locales with matching interpolation. Run imports→ESLint→Prettier and related Jest. Compare TSC against550 pre-existing diagnostics, claim no baseline pass.

## Runtime and release

- [x] Read existing grants with credentials only in memory; add only ALTER_PRIV on ods_validation.* to the existing writer and update bounded bootstrap source.
- [x] Run native synthetic CREATE/ADD tests with actual Doris: rows survive, default values appear, null fields remain nullable, concurrent/stale previews reject, and tasks can discover/map/write the designed target. Retain target fixtures if no DROP permission.
- [x] Check real PC output once at1440/1920 and batch any correction, then one confirmation. No local HTTP server; static request-routed fixtures only.
- [ ] Root alone runs scoped Spotless and production builds, packages two JARs and publishes daemonless Harbor layer over current v13 digest. Upgrade only application in datahub-open-test after engine idle, verify all8pods and identity/Secret/PVC/NLS preservation.
- [ ] Verify deployed static resources, anonymous API guards and native published service acceptance; update user guide/release evidence, pin actual digest, push existingforkbranch and update existingdraftPR2. Confirm tracked worktree clean and no active owned acceptance jobs.
