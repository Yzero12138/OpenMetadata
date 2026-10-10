---
primaryTarget: openmetadata-ui/src/main/resources/ui/src/components/MyData/LeftSidebar/LeftSidebar.component.tsx
relatedTargets:
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage.tsx
  - openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalDataSourcesPage.tsx
  - openmetadata-ui/src/main/resources/ui/src/components/AppContainer/AppContainer.tsx
mode: Operate
---

# Hospital PC navigation and integration pages

User approved the full Chinese information architecture and phase-one implementation on 2026-10-10. PC only; direct code; Integrate identity; native right-hand drawers. The approved plan is docs/hospital/ui-redesign-plan.md. Preserve existing APIs, history, credentials and permission checks.

## Direction contract

THESIS: Organize daily hospital data work into six stable menu groups. Sources and transfer tasks own distinct pages and URLs, with no top-level object-switching tabs.

OWN-WORLD: Expand the approved Integrate green and Chinese system typography across the workspace. Native semantic surfaces, compact neutral tables, restrained green selected navigation, and native form controls carry the identity. No replacement component library or decorative imagery.

STORY: Operators find a source, test or edit its definition, configure a transfer task, open its run history and follow the resulting catalog links. Source maintenance and task execution have separate failure boundaries.

FIRST VIEWPORT: A compact 240px sidebar with six groups and fixed bottom settings; native search/account bar above a full-width content region. Breadcrumb and 24px page title precede a single new action, compact filters, then actual source/task rows. Drawers keep underlying context with pinned actions.

FORM: User-pinned, explicitly approved work-domain structure, chosen over flat top-level sprawl and separate role workspaces. No visual-world seed is needed for this precisely approved expansion. Code-led build. Signature interaction is URL-backed sidebar/list/detail navigation with right-drawer edits and restored list context; brief native transitions respect reduced motion.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Review boundaries

Capture actual React shell and pages with explicitly synthetic HTTP fixtures, at 1366/1440/1920 PC widths plus reduced available width for scaling. One batched inspection and at most one confirmation round. Employee SSO and live engine are not established by UI fixtures. Phase two through four pages retain incumbent behavior apart from the approved navigation/frame styling; do not assert full redesign of those pages.


## Shipped scope and interaction contract

Phase one implements the shared PC navigation/frame and the source, task-list and task-detail pages. The exact six groups are 工作台 / 数据集成 / 数据资产 / 数据质量 / 数据治理 / 知识中心. The lower fixed menu contains 系统管理 and 退出; account controls remain in the top bar. Asset, quality, governance and knowledge interiors retain incumbent behavior and page composition.

The source page is `/hospital/integration/sources`; the task list is `/hospital/integration/tasks`; direct detail is `/hospital/integration/tasks/:taskId`. The legacy `/hospital/integration` entry redirects through the route helper. Sources and tasks are sibling menu destinations rather than object-switching tabs. URL-backed active ancestry opens the matching group, including direct details; the current expanded group is retained and collapsed navigation uses labelled native submenus.

The full-width shared frame uses a 240px expanded / 72px collapsed sidebar and a 64px top bar. Integration pages use a 24px gutter and page heading, one primary creation action, wrapped filters and compact neutral tables. Source/task tables preserve a 1000px minimum inside local horizontal scrolling. The 1093px capture width represents reduced PC working space at 125% scaling, not a separate mobile interface.

Source create/edit uses the native 720px right drawer; task creation uses 880px and task editing uses 960px. FormDrawer keeps title/actions outside its scrolling body, preserves native focus containment and portal behavior, and blocks dismissal during submission. Existing discard confirmation, password clearing on confirmed cancellation, drafts and browser history remain functional contracts. Restored list query context and direct detail navigation use shipped route/state helpers; the query allowlist is `q`, `type`, `role`, `enabled`, `source`, `target`, `mode`, `status`, `page`.

Global palette, inherited typography and reusable primitives are recorded in [DESIGN.md](../../DESIGN.md). The [design sidecar](../design.json) preserves source scope, native semantic dark mappings and exact layout/focus/motion extensions. Shared success/error-700 aliases remain distinct from brand selection and the incumbent entry alert text role.

## Review evidence and limits

The actual React shell/pages manifest [report.json](../review/pc-navigation/report.json), captured 2026-10-10T04:05:06.103Z, contains 44 captures at 1366×900, 1440×1000, 1920×1080 and reduced 1093×720 PC working space. It includes source/task/create/edit/detail/long-mapping, filtered/collapsed/menu/discard/error/missing and permission states. The directed correction manifest [finish-fix/report.json](../review/pc-navigation/finish-fix/report.json), captured 2026-10-10T04:17:34.553Z, contains five affected states. Root and the finish reviewer inspected the supplied captures; this documentation pass reads source and manifests and claims no additional visual review or detector run.

The [finish review](../review/pc-navigation/finish-review.md) records `disposition: ship` for its scored contrast and test-timing corrections. Computed directed observations are success state text at 5.41:1 and error text at 6.05:1 on the captured light backgrounds. These apply to the affected captures rather than the whole application or both themes. Shared utility success/error-700 semantics invert through the existing core dark mapping.

The combined Jest evidence passes 13 suites / 190 tests. The TypeScript comparison retains 550 pre-existing diagnostics, so it is not a clean typecheck. The final production Vite rebuild completed successfully after the directed correction (exit 0, built in 8m 2s); [production-build.txt](../review/pc-navigation/production-build.txt) is its release-gate evidence. This later build result resolves the gate that was still pending when the finish review was written.

UI evidence uses explicit synthetic HTTP fixtures and synthetic/example.invalid hosts. It establishes UI navigation and interaction within that boundary; employee SSO, JDBC/CDC extraction and live catalog writes are not established. No new raster assets ship with phase one. Existing logos, native SVG icons and upstream assets retain their incumbent provenance.
