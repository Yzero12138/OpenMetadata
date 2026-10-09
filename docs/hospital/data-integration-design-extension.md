# Hospital data integration: implemented design extension

Recorded: 2026-10-09. Scope: the code-led `/hospital/integration` extension of the confirmed Integrate hospital workbench. This is a descriptive surface record, not a new global design system. [DESIGN.md](../../DESIGN.md) and its [sidecar](../../.impeccable/design.json) remain the incumbent authority and were preserved.

## Overview

The implemented task workspace follows the existing north star, “Calm, precise hospital operations.” A factual engine band and two logical connection summaries lead into the task list. A page form configures the transfer; detail separates execution, catalog registration, field mappings, run history and audit facts. The authenticated OpenMetadata router, PageLayoutV1 and native sidebar retain ownership of the surrounding application shell. The first stage uses administrator permissions and explicitly synthetic PostgreSQL connections.

Evidence is the [workspace](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx), [form](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskForm.tsx), [detail](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationTaskDetail.tsx), [local styles](../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less) and [typed API boundary](../../openmetadata-ui/src/main/resources/ui/src/rest/hospitalIntegrationAPI.ts). Native integration is recorded in the [authenticated router](../../openmetadata-ui/src/main/resources/ui/src/components/AppRouter/AuthenticatedAppRouter.tsx), [sidebar constants](../../openmetadata-ui/src/main/resources/ui/src/constants/LeftSidebar.constants.ts), [sidebar hook](../../openmetadata-ui/src/main/resources/ui/src/hooks/useSidebarItems.ts), and [English](../../openmetadata-ui/src/main/resources/ui/src/locale/languages/en-us.json) / [Chinese](../../openmetadata-ui/src/main/resources/ui/src/locale/languages/zh-cn.json) locale entries.

The supplied [capture report](../../.impeccable/review/integration/report.json) is dated `2026-10-09T09:23:38.066Z`. It records empty, configure, conflict, run with catalog error, stop failed, paused, permission and engine unavailable at both required PC viewports (1440 × 1000 and 1920 × 1080). All 16 report entries have viewport-width document scroll measurements and no recorded console errors. The implementation pass opened all 16 images. The [fresh finish review](../../.impeccable/review/integration/finish-review.md) reopened the replacement images and resolved its two scored findings; its ship verdict is limited to those fixes.

The packet uses actual React components with an explicit local synthetic HTTP boundary. It proves neither genuine employee authentication nor real engine execution or catalog writes. Real Kubernetes engine/database and native-catalog checks belong to the separate [implementation verification track](data-integration-verification.md). This documentation pass reads source and the supplied evidence only. It adds no browser, detector, build or test result. PC-only delivery is confirmed; mobile and dark-theme acceptance are not established by this packet.

## Colors

The workspace inherits Integrate green and hospital text/divider neutrals from the existing [hospital theme](../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less). Links, active execution text, carets and visible focus use the existing brand semantics. Finished transfers and registered catalogs use native success ink; failed states use native error ink and surface. Status labels carry the meaning alongside color.

The local stylesheet reads the incumbent semantic variables and falls back to their existing prefixed native properties for primary/secondary surfaces, success/error text and error backgrounds. No new color primitive or global alias is introduced. In the supplied compiled-style evidence, empty/permission surfaces resolve to the native subtle surface (`rgb(250, 250, 250)`); alerts resolve to the native error surface and ink (`rgb(254, 243, 242)` / `rgb(217, 45, 32)`). These are observations of the fixture's rendered mode, not new token definitions or proof of all theme variants.

**The Scoped Alias Rule.** The incumbent rule still applies: keep hospital accents scoped and retain the core semantic status roles. The repaired fallback resolution is a surface implementation detail, not a replacement palette.

## Typography

The hospital wrapper supplies the confirmed Chinese stack: PingFang SC, Microsoft YaHei, Source Han Sans SC, system-ui, sans-serif. The integration stylesheet adds no body or display family. Headings reuse the workbench hierarchy: page title (27px, weight 600, line height 1.4), section title (17px, weight 600, line height 1.5), and compact subheading (15px, weight 600). Table text is compact (13px); supporting facts, hints and statuses use smaller text (12px). Native control typography stays with the core components.

Run fact values use a modest emphasis (16px), while the exact job identifier uses a local technical monospace role (14px; ui-monospace, SFMono-Regular, Consolas, monospace). The workspace uses tabular numerals. These supporting measurements describe this operating surface and do not expand the global type token set.

**The Numeric Meaning Rule.** The incumbent rule extends to run facts: preserve zero as zero, render absent or nonfinite counters as unavailable, and keep 64-bit job identifiers as strings. The localized counter hint states that these are processed records or change events for a run, not current table row totals.

## Layout

The workspace shares the workbench's centered maximum width (1280px) and outer padding (32px). The header places one create action beside the title. The engine status uses a flat band with horizontal rules and vertical padding (16px). Source and ODS connection summaries use two equal columns with a wide gutter (32px).

The create/edit form groups task settings, source/target route choices and field mappings into ruled sections. Field groups use two equal columns with vertical/horizontal gaps (24px / 32px); mapping rows place the source description alongside a native target selector. The form is part of the page rather than a new modal pattern. Task and history tables keep a local horizontal-scroll boundary and allow long identifiers to wrap.

Detail starts with a three-column route summary. Run facts use two equal columns; the job ID spans the complete row. The corrected LESS preserves `grid-column: 1 / -1` as a literal span. The supplied report resolves the fact columns to equal tracks (592px / 592px) and records the full-width span at both PC widths. This is the intended layout, not a new global grid rule.

Local narrow-width rules remain in source, including a one-column form/route treatment below the existing breakpoint (767px). They are implementation fallback behavior only; this extension's confirmed delivery and acceptance targets remain the two PC viewports.

## Elevation & Depth

The workspace adds no local shadow. Thin semantic borders divide the engine band, form sections, mapping rows, facts and tables. Native controls keep their incumbent depth and interaction treatment. Keyboard focus uses the established brand outline (2px) and offset (3px). A local reduced-motion query disables transition and animation within this workspace, including its nested controls.

Pre-existing drift remains uncanonized: native Button requests `shadow-xs-skeuomorphic`, while core globals define `--shadow-xs-skeumorphic`. This source discrepancy is already recorded in the global design system; the current pass confirms it still exists and does not infer a new shadow rule or repair shared implementation.

## Shapes

Empty and permission containers retain the existing container radius (12px); error alerts use the existing control radius (8px). This surface's empty-state inset (40px / 24px) and alert inset (14px / 16px) reflect local content density, not changes to the incumbent global component tokens. Core Button, Input and Select own control shapes. Native SVG icons provide the action and route cues.

## Components

The task list distinguishes transfer and catalog states in separate columns. The heading action creates a task; each task name opens detail. Engine refresh and connection tests remain concrete operations with disabled/loading behavior. A successful empty list has its own explanatory region. The employee view shows a status explanation and returns before protected integration requests are issued.

The form uses native core Input, Select and Button. Source/target selectors use API-supplied table metadata, the mode chooses FULL or CDC, and CDC requires a mapped source primary key. Each source field has an explicit target selector or omit choice. Validation errors use a labelled alert region. Version, duplicate-name and duplicate-route conflicts preserve typed data and supply distinct recovery instructions.

Detail renders run controls according to the current task and engine state. Active or uncertain submissions block a fresh run. A recoverable paused CDC task blocks editing and a new run, and exposes resume only when backend `canResume` is true. Stop failure remains visible and retryable. Pending task requests are aborted or ignored when the selected task/view changes or the workspace unmounts.

Catalog registration remains a separate section with an independent retry. A FINISHED transfer can coexist with FAILED catalog registration. Asset links appear only for returned source, target and pipeline fully qualified names. Run history and version/update facts use returned values; the page invents no totals or success state.

**The State Honesty Rule.** The incumbent rule continues: display the returned operational state, preserve unavailable facts, and keep transport success independent of catalog registration. Synthetic visual evidence must retain its synthetic label.

## Do's and Don'ts

- **Do** reuse the existing hospital theme, Chinese typography and native core controls for related task views.
- **Do** retain the distinction between execution, catalog registration, permission, connection and unavailable-data states.
- **Do** preserve exact job identifiers, visible keyboard focus and the confirmed two PC acceptance targets.
- **Don't** promote this page's grid, local insets or technical identifier role into a new global token scale.
- **Don't** treat the synthetic capture packet as employee SSO, real transfer or real catalog acceptance.
- **Don't** canonize or repair the pre-existing native shadow spelling discrepancy during this scoped extension record.
