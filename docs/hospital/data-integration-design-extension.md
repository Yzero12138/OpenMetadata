# Hospital data integration: implemented design extension

Recorded: 2026-10-10. This is the current descriptive record of the code-led `/hospital/integration` extension and shared PC form drawers. The confirmed Integrate hospital workbench remains the visual authority; [DESIGN.md](../../DESIGN.md), its [sidecar](../../.impeccable/design.json) and PRODUCT.md are preserved. The 2026-10-09 implementation record is retained below as history.

## Current implementation — 2026-10-10

The Operate workspace now has parallel task and data-source tabs with one context-specific create action. The data-source table exposes family and declared version, source/target role, endpoint/database/schema scope, enabled and environment-managed or synthetic status, connection-test state and permitted edit/delete actions. Administrators maintain reusable definitions; task forms select their registered IDs, discover scoped tables and map actual source fields to target fields.

Definitions support Oracle 11g/19c, SQL Server 2016/2019, MySQL 8 and PostgreSQL. FULL is available for all four families; CDC remains limited to the verified PostgreSQL path advertised by the server. A saved definition and a declared version do not establish connectivity. Environment-managed synthetic definitions remain visible and immutable. Passwords are never returned; an unchanged edit leaves the field blank and omits the password from its update. The [maintenance record](data-source-maintenance.md) owns the detailed backend and credential rules.

### Right-side forms and recovery

| Form | Implemented width |
| --- | --- |
| Data-source create/edit | 720px |
| Task create | 880px |
| Task edit | 960px |

These forms use the shared native SlideoutMenu-based [FormDrawer](../../openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/FormDrawer.tsx): full-height at the right edge, accessible title, fixed header/footer and independently scrolling body. The underlying list or selected task detail remains mounted. Long field mappings scroll within the body without covering the action area. Native Input, Select and Button preserve their keyboard behavior, nested popup containment and form associations.

Pending saves disable fields, close/cancel and repeated submission, including Escape dismissal. A nested Select consumes Escape before the drawer. Error summaries receive keyboard focus and scroll into view when the failure changes; normal input typing does not reclaim that focus. Validation and HTTP 409 failures retain the draft. Data-source version conflicts describe the definition and offer explicit reload; task conflicts retain their task recovery. The source editor synchronously advances its generation guard on open/close, and aborts or ignores stale reload GET results. Source/target changes clear dependent tables and mappings and reject stale discovery results.

The implementation evidence is the [workspace](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx), [source list/editor](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnections.tsx), [source form](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationConnectionForm.tsx), [task form](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx), [error-summary component](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationDrawerAlert.tsx) and [typed API boundary](../../openmetadata-ui/src/main/resources/ui/src/rest/hospitalIntegrationAPI.ts). Task detail continues to separate transfer from catalog registration, preserve exact string job IDs and unavailable counters, and enforce active, uncertain and resumable-task restrictions.

### Visual continuity and shared migration

The [local integration styles](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less) retain the existing hospital green, Chinese system font stack, semantic status colors, thin rules and flat regions. The page remains centered at 1280px with 32px padding; its heading ramp remains 27px / 17px / 15px, with 13px table text and 12px supporting facts. Drawer field gaps are 20px / 24px; the [shared drawer styles](../../openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/form-drawer.less) give the body 24px padding. Native overlay and control depth remain authoritative. These are observed surface measurements, not new global tokens.

The [drawer inventory](form-drawers.md) records 58 migrated form-container sources and their preserved caller contracts. Its source/test inventory and the integration screenshots provide migration and representative shared-shell evidence. They do not constitute individual visual acceptance of all 58 hosts or conversion of every routed editor. Confirmations and read-only viewers retain their established semantics.

### Final evidence and verification boundary

The final [capture packet](../../.impeccable/review/integration/report.json), captured at `2026-10-10T02:14:44.192Z`, contains 30 screenshots: 1440 × 1000 and 1920 × 1080 each cover `empty`, `sources`, `source-create`, `source-save-error`, `source-pending`, `source-connection-error`, `source-version-conflict`, `source-edit`, `task-create`, `task-long-mapping`, `task-conflict`, `task-edit`, `run-catalog-error`, `permission` and `engine-unavailable`. Its two recorded interaction checks pass, with no recorded console errors or document-width overflow. Source-connection and run/catalog-error captures intentionally scroll to their errors; they do not claim document-top full-page captures.

The [fresh finish review](../../.impeccable/review/integration/finish-managed-sources.md) opened all 30 images and returns `disposition: ship`, with the five required contract sections and no material fixes. This verdict covers the reviewed interface scope. The parent completed the batched inspection and confirmation rounds; this documenter pass creates no additional captures, detector runs, builds or test runs. The formal single detector output is [`.logs/impeccable-managed-sources-detector.json`](../../.logs/impeccable-managed-sources-detector.json), containing `[]`. The review-root detector file has the same parsed empty result; the older integration-subdirectory detector remains a historical record.

| Evidence track | Recorded outcome and limit |
| --- | --- |
| Integrated UI and shared forms | Parent-verified integration UI tests: 55/55. Existing shared/form compatibility groups: 287 + 97 + 14 passing tests. The full application TypeScript check retains 550 baseline diagnostics with zero owned diagnostics. These results are transcribed from the parent verification, not rerun here. |
| Visual and interaction packet | Actual React components with an explicit local synthetic HTTP boundary, example.invalid endpoints and synthetic credentials. Static images show rendered states; source and the packet's recorded checks support interaction claims. They do not establish employee login, live engine execution or native catalog writes. PC only; mobile and dark-theme acceptance are outside this packet. |
| Independent database/catalog tests | The [synthetic matrix](data-source-maintenance.md#verified-synthetic-matrix) separately records exact 100-row transfers and native catalog/lineage checks for Oracle XE 11g, SQL Server 2019, MySQL 8.0.36 and PostgreSQL 17.10. This documenter did not run those tests. Oracle 19c, SQL Server 2016 and real hospital endpoints still need business-system acceptance. Four-family FULL does not establish four-family CDC. |
| Local delivery artifacts | The final [UI build](../../.logs/managed-connections-ui-final-build.log) and [UI package](../../.logs/managed-connections-ui-final-package.log) passed. The parent verified hashes for 16 resources and 39 classes, built the local [application](../../.logs/managed-connections-app-build-v6.log) and [engine](../../.logs/managed-connections-engine-build-v3.log) images, and recorded a network-disabled [four-family application driver probe](../../.logs/managed-connections-app-driver-runtime.log), retaining official PostgreSQL 42.7.12. This design handoff records local artifacts; subsequent registry and test-deployment checks belong to the separate [implementation verification track](data-integration-verification.md). A visual ship verdict is not a release or real hospital acceptance claim. |

No real hospital endpoints or credentials have been supplied. This pass checks the incumbent system, implementation samples and supplied evidence only. The known native button-shadow spelling discrepancy remains uncanonized and unrepaired; it supplies no new elevation rule.

## Historical implementation record — 2026-10-09

The following preserves the earlier inline task-form snapshot and its then-current 16-capture evidence. The shared `report.json` path now holds the 2026-10-10 packet described above; historical references and limited verdict claims below apply to the earlier snapshot.

Recorded: 2026-10-09. Scope: the code-led `/hospital/integration` extension of the confirmed Integrate hospital workbench. This is a descriptive surface record, not a new global design system. [DESIGN.md](../../DESIGN.md) and its [sidecar](../../.impeccable/design.json) remain the incumbent authority and were preserved.

### Overview

The implemented task workspace follows the existing north star, “Calm, precise hospital operations.” A factual engine band and two logical connection summaries lead into the task list. A page form configures the transfer; detail separates execution, catalog registration, field mappings, run history and audit facts. The authenticated OpenMetadata router, PageLayoutV1 and native sidebar retain ownership of the surrounding application shell. The first stage uses administrator permissions and explicitly synthetic PostgreSQL connections.

Evidence is the [workspace](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx), [form](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx), [detail](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskDetail.tsx), [local styles](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less) and [typed API boundary](../../openmetadata-ui/src/main/resources/ui/src/rest/hospitalIntegrationAPI.ts). Native integration is recorded in the [authenticated router](../../openmetadata-ui/src/main/resources/ui/src/components/AppRouter/AuthenticatedAppRouter.tsx), [sidebar constants](../../openmetadata-ui/src/main/resources/ui/src/constants/LeftSidebar.constants.ts), [sidebar hook](../../openmetadata-ui/src/main/resources/ui/src/hooks/useSidebarItems.ts), and [English](../../openmetadata-ui/src/main/resources/ui/src/locale/languages/en-us.json) / [Chinese](../../openmetadata-ui/src/main/resources/ui/src/locale/languages/zh-cn.json) locale entries.

The supplied [capture report](../../.impeccable/review/integration/report.json) is dated `2026-10-09T09:23:38.066Z`. It records empty, configure, conflict, run with catalog error, stop failed, paused, permission and engine unavailable at both required PC viewports (1440 × 1000 and 1920 × 1080). All 16 report entries have viewport-width document scroll measurements and no recorded console errors. The implementation pass opened all 16 images. The [fresh finish review](../../.impeccable/review/integration/finish-review.md) reopened the replacement images and resolved its two scored findings; its ship verdict is limited to those fixes.

The packet uses actual React components with an explicit local synthetic HTTP boundary. It proves neither genuine employee authentication nor real engine execution or catalog writes. Real Kubernetes engine/database and native-catalog checks belong to the separate [implementation verification track](data-integration-verification.md). This documentation pass reads source and the supplied evidence only. It adds no browser, detector, build or test result. PC-only delivery is confirmed; mobile and dark-theme acceptance are not established by this packet.

### Colors

The workspace inherits Integrate green and hospital text/divider neutrals from the existing [hospital theme](../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less). Links, active execution text, carets and visible focus use the existing brand semantics. Finished transfers and registered catalogs use native success ink; failed states use native error ink and surface. Status labels carry the meaning alongside color.

The local stylesheet reads the incumbent semantic variables and falls back to their existing prefixed native properties for primary/secondary surfaces, success/error text and error backgrounds. No new color primitive or global alias is introduced. In the supplied compiled-style evidence, empty/permission surfaces resolve to the native subtle surface (`rgb(250, 250, 250)`); alerts resolve to the native error surface and ink (`rgb(254, 243, 242)` / `rgb(217, 45, 32)`). These are observations of the fixture's rendered mode, not new token definitions or proof of all theme variants.

**The Scoped Alias Rule.** The incumbent rule still applies: keep hospital accents scoped and retain the core semantic status roles. The repaired fallback resolution is a surface implementation detail, not a replacement palette.

### Typography

The hospital wrapper supplies the confirmed Chinese stack: PingFang SC, Microsoft YaHei, Source Han Sans SC, system-ui, sans-serif. The integration stylesheet adds no body or display family. Headings reuse the workbench hierarchy: page title (27px, weight 600, line height 1.4), section title (17px, weight 600, line height 1.5), and compact subheading (15px, weight 600). Table text is compact (13px); supporting facts, hints and statuses use smaller text (12px). Native control typography stays with the core components.

Run fact values use a modest emphasis (16px), while the exact job identifier uses a local technical monospace role (14px; ui-monospace, SFMono-Regular, Consolas, monospace). The workspace uses tabular numerals. These supporting measurements describe this operating surface and do not expand the global type token set.

**The Numeric Meaning Rule.** The incumbent rule extends to run facts: preserve zero as zero, render absent or nonfinite counters as unavailable, and keep 64-bit job identifiers as strings. The localized counter hint states that these are processed records or change events for a run, not current table row totals.

### Layout

The workspace shares the workbench's centered maximum width (1280px) and outer padding (32px). The header places one create action beside the title. The engine status uses a flat band with horizontal rules and vertical padding (16px). Source and ODS connection summaries use two equal columns with a wide gutter (32px).

The create/edit form groups task settings, source/target route choices and field mappings into ruled sections. Field groups use two equal columns with vertical/horizontal gaps (24px / 32px); mapping rows place the source description alongside a native target selector. The form is part of the page rather than a new modal pattern. Task and history tables keep a local horizontal-scroll boundary and allow long identifiers to wrap.

Detail starts with a three-column route summary. Run facts use two equal columns; the job ID spans the complete row. The corrected LESS preserves `grid-column: 1 / -1` as a literal span. The supplied report resolves the fact columns to equal tracks (592px / 592px) and records the full-width span at both PC widths. This is the intended layout, not a new global grid rule.

Local narrow-width rules remain in source, including a one-column form/route treatment below the existing breakpoint (767px). They are implementation fallback behavior only; this extension's confirmed delivery and acceptance targets remain the two PC viewports.

### Elevation & Depth

The workspace adds no local shadow. Thin semantic borders divide the engine band, form sections, mapping rows, facts and tables. Native controls keep their incumbent depth and interaction treatment. Keyboard focus uses the established brand outline (2px) and offset (3px). A local reduced-motion query disables transition and animation within this workspace, including its nested controls.

Pre-existing drift remains uncanonized: native Button requests `shadow-xs-skeuomorphic`, while core globals define `--shadow-xs-skeumorphic`. This source discrepancy is already recorded in the global design system; the current pass confirms it still exists and does not infer a new shadow rule or repair shared implementation.

### Shapes

Empty and permission containers retain the existing container radius (12px); error alerts use the existing control radius (8px). This surface's empty-state inset (40px / 24px) and alert inset (14px / 16px) reflect local content density, not changes to the incumbent global component tokens. Core Button, Input and Select own control shapes. Native SVG icons provide the action and route cues.

### Components

The task list distinguishes transfer and catalog states in separate columns. The heading action creates a task; each task name opens detail. Engine refresh and connection tests remain concrete operations with disabled/loading behavior. A successful empty list has its own explanatory region. The employee view shows a status explanation and returns before protected integration requests are issued.

The form uses native core Input, Select and Button. Source/target selectors use API-supplied table metadata, the mode chooses FULL or CDC, and CDC requires a mapped source primary key. Each source field has an explicit target selector or omit choice. Validation errors use a labelled alert region. Version, duplicate-name and duplicate-route conflicts preserve typed data and supply distinct recovery instructions.

Detail renders run controls according to the current task and engine state. Active or uncertain submissions block a fresh run. A recoverable paused CDC task blocks editing and a new run, and exposes resume only when backend `canResume` is true. Stop failure remains visible and retryable. Pending task requests are aborted or ignored when the selected task/view changes or the workspace unmounts.

Catalog registration remains a separate section with an independent retry. A FINISHED transfer can coexist with FAILED catalog registration. Asset links appear only for returned source, target and pipeline fully qualified names. Run history and version/update facts use returned values; the page invents no totals or success state.

**The State Honesty Rule.** The incumbent rule continues: display the returned operational state, preserve unavailable facts, and keep transport success independent of catalog registration. Synthetic visual evidence must retain its synthetic label.

### Do's and Don'ts

- **Do** reuse the existing hospital theme, Chinese typography and native core controls for related task views.
- **Do** retain the distinction between execution, catalog registration, permission, connection and unavailable-data states.
- **Do** preserve exact job identifiers, visible keyboard focus and the confirmed two PC acceptance targets.
- **Don't** promote this page's grid, local insets or technical identifier role into a new global token scale.
- **Don't** treat the synthetic capture packet as employee SSO, real transfer or real catalog acceptance.
- **Don't** canonize or repair the pre-existing native shadow spelling discrepancy during this scoped extension record.
