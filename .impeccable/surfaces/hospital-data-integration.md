---
version: 1
slug: hospital-data-integration
primary_target: "openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx"
related_targets: ["openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnections.tsx", "openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnectionForm.tsx", "openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less"]
---
# Hospital data integration

Mode: Operate. Code-led extension of the confirmed Integrate hospital workbench, route /hospital/integration. PC only, administrator operations; other employees see the permission explanation without loading protected data.

## THESIS

Maintain reusable source/target definitions, then configure extraction using registered IDs, real tables and explicit field mappings. The four requested database families are Oracle 11g/19c, SQL Server 2016/2019, MySQL 8 and PostgreSQL. Offer FULL for all four and CDC only for the verified PostgreSQL path. Real hospital endpoints and credentials have not been supplied.

## OWN-WORLD

Keep the incumbent Chinese system typography, restrained hospital green, native core controls, semantic tokens, thin rules and flat regions. Native OpenMetadata shell remains authoritative. This is an extension, with no replacement component library or global design-system changes. Keep DESIGN.md and design.json unchanged.

## STORY

Parallel task/data-source tabs share one context-specific create action. The data-source table exposes family/version, role, endpoint/schema, environment-managed or synthetic truth, connection-test state and permitted edit/delete actions. Definition save does not establish connectivity. Task detail separates transfer and catalog status and retains the exact string job ID, available counters, recovery permission, history and audit facts.

## FIRST VIEWPORT

At 1440 and 1920 desktop widths, show heading, create action, tabs, engine connectivity and list. Preserve underlying lists/detail while configuring in a full-height right drawer. Use 720px for source create/edit, 880px for task create and 960px for task edit. Pin header and footer while long configuration/mapping content scrolls in the body. PC keyboard and focus behavior are in scope; mobile and dark-theme acceptance are not asserted.

## FORM

Native Input, Select and Button controls own keyboard, disabled and pending behavior. Passwords never return in responses and an unchanged edit omits the password. Validation and rejected saves retain drafts. Bring error summaries into view and keyboard focus; connection version conflict uses data-source wording and explicit reload, task conflict retains its existing recovery. Guard closing while saving, including Escape, while nested Select Escape closes the select first. On source/target changes clear dependent table/mappings and reject stale discovery responses. Show only server-advertised modes. Preserve active-task and uncertain-submission restrictions.

## FINISH

Verify focused UI/API behavior, all 20 actual locale translations, and no additional owned TypeScript diagnostics over the recorded baseline. Parent owns one batched PC inspection plus at most one confirmation after the batch fixes, the production build, one detector pass and fresh finish/documentation handoffs. Screenshots use actual React with an explicitly synthetic local HTTP boundary; engine/database/native catalog tests are separate evidence. No claim of employee login, real hospital collection, all-family CDC, or exactly-once delivery.

## Implemented — 2026-10-10

Task/data-source tabs and context-specific creation now use registered reusable definitions for Oracle 11g/19c, SQL Server 2016/2019, MySQL 8 and PostgreSQL. The source table exposes role, family/version, endpoint/scope, managed/synthetic truth and connection-test state. FULL is available across the four families; CDC remains the server-advertised verified PostgreSQL path. Passwords are not returned, unchanged edits omit them, and definition save is separate from connectivity.

The native shared FormDrawer provides 720px source create/edit, 880px task create and 960px task edit, with fixed header/footer, independently scrolling mappings and retained underlying list/detail. Pending state protects closing and submission. Error summaries focus and scroll into view without refocusing during typing; validation/409 preserve drafts, source conflicts use definition-specific copy and explicit reload, and synchronous generation guards reject stale source reloads. Incumbent system files remain unchanged.

## Evidence — 2026-10-10

The [current design record](../../docs/hospital/data-integration-design-extension.md) separates this scope from the 2026-10-09 history. The [final packet](../review/integration/report.json) contains 30 actual-React synthetic-HTTP captures, 15 states at each required PC viewport, plus two passing recorded interaction checks. The [fresh finish review](../review/integration/finish-managed-sources.md) returns `disposition: ship`, all five required sections, no material fixes. Two screenshot rounds are complete; the formal single [detector output](../../.logs/impeccable-managed-sources-detector.json) is `[]`.

Parent verification records 55/55 integration UI tests, shared groups of 287 + 97 + 14 tests, and 550 baseline TypeScript diagnostics with zero owned diagnostics. Final UI build/package and local application/engine images passed; subsequent publication and test-deployment evidence belongs to the [implementation verification track](../../docs/hospital/data-integration-verification.md). Independent four-database 100-row/native-catalog tests are recorded in [data-source maintenance](../../docs/hospital/data-source-maintenance.md), not established by screenshots or rerun by this documenter. No employee-login, real hospital endpoint, all-family CDC, mobile or dark-theme acceptance claim.

## Direction contract — Doris target extension, 2026-10-20

THESIS: Configure a reusable Doris target, then create its table from real source metadata without leaving the task drawer. Database choices retain all four existing source families; Doris is target-only.

OWN-WORLD: Extend the incumbent restrained green, Chinese system typography, native core fields and flat section rules. Preserve DESIGN.md and design.json. No new design package or decorative motion.

STORY: Keep the existing separate data-source and task menus/routes. The administrator registers Doris SQL/HTTP endpoints, selects a real source table, edits suggested safe lowercase target names and explicitly includes or skips columns. Source primary-key columns stay included. The server previews generated Doris types, keys and read-only DDL before the explicit create action.

FIRST VIEWPORT: Preserve task/source navigation, the underlying list, and the 880px/960px right drawer. Put the creation disclosure directly below route selection. The mapping rows and generated DDL scroll inside the existing drawer body; native header/footer stay fixed.

FORM: Code-led local extension; no seed applies. Preview edits invalidate confirmation, stale requests cannot replace a newer draft, errors retain edits, and pending operations block repeated creation and dismissal. Successful creation refreshes target choices, selects the table and initializes mappings while preserving the task draft.

FINISH: Parent owns the single detector, batched PC captures, fresh finish review and final documentation handoff. Verify typed endpoints, Doris defaults/role/ports, stale preview guards, retry and post-create mapping. No live database or production operation belongs to this UI subtask.

## UI implementation evidence — 2026-10-20

The typed Doris connection extension and structured table preview/create endpoints are implemented. Target-only validation, SQL/HTTP defaults, family-setting cleanup, database scope, TLS-disabled-only choices, Doris list filtering and endpoint facts preserve the existing source/task menus and right drawers. Inline creation previews generated types, primary keys and read-only DDL; explicit skips, preview invalidation, stale-source guards, failure retry, parent pending locks, automatic table selection and returned field mappings are covered. Task draft fields remain intact.

The final owned integration/API group passed 73/73 tests in five suites; the earlier missing-Doris and missing-table-creation regressions were observed failing first. The required organize-imports → ESLint --fix → Prettier sequence completed. TypeScript reports the existing 550 project diagnostics with zero diagnostics in the owned hospital integration/API files. yarn i18n ran and all 20 locale files contain the new messages, with real non-English translations and preserved interpolation tokens. Parent owns the one detector, production build, batched visual review and finish/documentation handoffs; these UI tests make no database or production deployment claim.
