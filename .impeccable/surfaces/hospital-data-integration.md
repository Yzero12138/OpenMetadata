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
