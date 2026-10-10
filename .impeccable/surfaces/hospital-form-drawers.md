---
version: 1
slug: hospital-form-drawers
primary_target: "openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/FormDrawer.tsx"
related_targets: ["openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/form-drawer.less", "docs/hospital/form-drawers.md"]
---
# Hospital create and edit drawers

Mode: Operate. Refinement of the existing PC hospital workbench, preserving its native shell, typography, semantic colors and form content. The user requests right-side drawers for create, edit and configuration forms. Existing destructive confirmations and read-only viewers keep their established semantics.

Use the shared native SlideoutMenu-based FormDrawer, with a full-height right edge, accessible title, independently scrolling body and fixed header/footer. Keep form IDs, validation, submit callbacks and caller loading behavior. Pending submission protects the draft from accidental close. Maintain Escape handling, focus return, nested editor/select portals and exactly one real form submission. Existing content, default widths and local host contracts remain authoritative.

The source-backed inventory in docs/hospital/form-drawers.md identifies 58 form containers. Focused shared/form suites and independent specification/quality review validate those migrations. The integration capture packet visually samples the shared drawer at 1440 and 1920; it does not claim a screenshot of every migrated form. PC-only acceptance; preserve global DESIGN.md and .impeccable/design.json.

## Implemented — 2026-10-10

The shared native SlideoutMenu-based FormDrawer and adapters retain caller fields, form associations, widths and validation while placing create/edit/configuration forms at the full-height right edge. Fixed header/footer and body scrolling preserve long forms; pending saves guard close and duplicate submit, and native/editor popups remain in the focus scope. The integration uses 720px source, 880px new-task and 960px edit-task drawers and retains the underlying list/detail. Global design files remain preserved.

## Evidence — 2026-10-10

The [58-container inventory and verification](../../docs/hospital/form-drawers.md) records source migrations and existing compatibility groups of 287 + 97 + 14 passing tests; it is not 58-host individual visual acceptance. The [30-capture PC integration packet](../review/integration/report.json) samples the actual shared shell, including pending close protection, errors/conflicts, long mapping and retained context at 1440×1000 and 1920×1080. Its two recorded interaction checks pass; the [fresh finish review](../review/integration/finish-managed-sources.md) returns `disposition: ship` with no material fixes.

The [current design record](../../docs/hospital/data-integration-design-extension.md) distinguishes source/test evidence, synthetic visual/interaction evidence and independent database/catalog tests. Both screenshot rounds and the single [detector pass](../../.logs/impeccable-managed-sources-detector.json) (`[]`) are complete. No additional capture, detector or production-source change was made by this documenter; local build/image completion is not an upload or deployment claim.
