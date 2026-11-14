# Doris target table designer — PC visual evidence

2026-11-13. This is the capture/observation packet for a separate fresh finish reviewer. It does not make the final ship decision.

## Source and method

Actual `HospitalTargetTablesWorkspace`, `DorisTableDesigner`, `DorisFieldEditor`, `DorisTableStructureView`, shared `FormDrawer` and native core components, using the current Chinese locale, Tailwind tokens and hospital LESS. Static Vite output is loaded in headless Chrome using Playwright request interception at `https://doris-designer-fixture.invalid`. No listening local server, Docker, live database, employee login or cluster write is used. The synthetic notice in the images belongs only to the fixture wrapper.

Scripts and logs: `.cache/doris-designer/visual/`. Final product changes were rebuilt once after the single observation/fix batch. No detector was run by this capture agent; parent owns the single detector.

## Direction and quality evidence

Applicable direction: `docs/hospital/doris-table-designer-design.md`, `.impeccable/surfaces/hospital-data-integration.md`, `.impeccable/briefs/hospital-workbench.md`, and incumbent `DESIGN.md`. Operate mode; PC only; restrained hospital green, Chinese system typography, native controls; create/edit in right-side full-height drawers. No replacement visual system or mobile claim.

All screenshots are whole 1440×1080 or 1920×1080 viewports. Longer forms are scrolled within their drawer bodies; the frame is never cropped. Names explicitly include scenario and viewport width.

## Final observed results

- `report.json`: 14 captures, target list plus 6 drawer states at both widths. Create/append drawers are 1100×1080, positioned at x=340/820, always right-aligned. Root document has no horizontal overflow; every scenario reports zero `pageerror`.
- `structure-confirm.json`: independent existing-structure drawer is 1000×1080, right-aligned at x=440/920. The corrected summary is a 2-column grid with 16×24px gaps, 13px labels and 14px values. Four facts occupy 105px rather than the default vertical dt/dd stack.
- `computed.json`: 64-character field identifier stays inside the editable input (native internal horizontal scrolling), while the expanded field heading remains inside its 1020px column span. No page overflow at either width. Table header contrast 6.05:1, field settings heading 5.79:1, permission error 6.05:1, drawer title 14.21:1. These measured pairs meet the 4.5:1 body threshold.
- Actual observations: table/field settings, decimal precision/scale/default/comment, primary-key and nullability controls, generated read-only DDL, old read-only structure, new required field/default, pending structure change, permission recovery and retained drafts are legible. Header/footer remain pinned while the form body scrolls.

## One observation/fix batch

The initial `DorisTableStructureView` reused a `hospital-integration__detail-grid` class that has no stylesheet definition. Existing-table facts appeared as a default vertical dt/dd stack. Parent replaced it with scoped `hospital-table-design__summary` styles. The single confirmation rebuilt all 14 original states plus long-field/error and independent structure captures; actual image inspection confirms the corrected grid. Initial images/metrics remain in `before/` for traceability.

No further blocking visual finding was observed in the bounded inspected surface. This is distinct from final fresh-review approval and from native API/engine validation.

## Suggested finish-review entry images

- `target-list-1440.png`, `target-list-1920.png`
- `create-fields-1440.png`, `create-fields-1920.png`
- `create-preview-1440.png`, `create-preview-1920.png`
- `append-fields-1440.png`, `append-fields-1920.png`
- `append-submitted-1440.png`, `append-submitted-1920.png`
- `existing-structure-1440.png`, `existing-structure-1920.png`
- `long-field-error-1440.png`, `long-field-error-1920.png`

All 18 final images and SHA-256 hashes are listed in `provenance.json`. Two remaining pairs are append-preview and create-permission.

## F1 bounded confirmation

The fresh reviewer identified one factual copy issue: the shared CREATE hint promised automatic selection/mapping even when entered from independent target-table management. Parent changed only that hint to “确认后将创建表，不覆盖已有同名表；创建完成后可用于集成任务。” and added its real translations across all 20 locales.

After that change, only `create-preview-1440.png` and `create-preview-1920.png` were rebuilt/recaptured and actually inspected. Both show the new complete hint; the prior unconditional promise is absent. Each drawer remains 1100×1080 and right-aligned, without root horizontal overflow or page errors. The hint occupies 18px at y=929.58, with equal client/scroll width of 507px; the footer is unaffected. `f1-confirm.json` records these assertions. `before-f1/` retains the previous two images, and only the corresponding two entries in `provenance.json` changed. No other state or detector was rerun. Final scoring remains with the same reviewer, limited to F1.
