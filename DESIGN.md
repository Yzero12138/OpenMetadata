---
name: OpenMetadata Hospital Adaptation
description: Integrate identity and native controls across the shared Chinese hospital PC workspace, preserving incumbent inner-page systems.
colors:
  primary: "#12634b"
  primary-hover: "#087354"
  primary-dark: "#68cf99"
  page: "#eef3f1"
  page-dark: "#121c18"
  ink: "#182f29"
  ink-dark: "#e8f1ec"
  muted: "#53675f"
  muted-dark: "#b0c4b8"
  border: "#dce6e0"
  border-dark: "#354a3d"
  selection: "#e3f0e8"
  primary-icon: "#c4ead6"
  native-white: "#ffffff"
  native-surface-subtle: "#fafafa"
  native-skeleton: "#f5f5f5"
  native-ink: "#181d27"
  native-secondary: "#414651"
  native-tertiary: "#535862"
  native-control-border: "#d5d7da"
  error-surface: "#fef3f2"
  error-ink: "#d92d20"
  native-page-dark: "rgb(12 14 18)"
  native-surface-subtle-dark: "rgb(19 22 27)"
  native-surface-hover: "#f5f5f5"
  native-surface-hover-dark: "rgb(34 38 47)"
  navigation-selected: "color-mix(in srgb, var(--color-bg-brand-solid) 10%, var(--color-bg-primary))"
  utility-success-ink: "#067647"
  utility-success-ink-dark: "#75e0a7"
  utility-error-ink: "#b42318"
  utility-error-ink-dark: "#fda29b"
  error-surface-dark: "#55160c"
typography:
  headline-login:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "40px"
    fontWeight: 600
    lineHeight: 1.25
  headline-login-mobile:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "29px"
    fontWeight: 600
    lineHeight: 1.25
  headline-workbench:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "27px"
    fontWeight: 600
  headline-workbench-mobile:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "23px"
    fontWeight: 600
  title-portal:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.4
  title-workbench:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
  body-description:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "14px"
    lineHeight: 1.7
  body-intro:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "17px"
    lineHeight: 1.8
  label:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: "20px"
  button-lg:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: "24px"
  metric:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 600
  headline-page:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.4
  title-section:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.5
  title-drawer:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: "28px"
  title-brand:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1
  body-table:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "13px"
  label-status:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "12px"
  label-selected:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Source Han Sans SC', system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
rounded:
  navigation: "6px"
  skeleton: "4px"
  control: "8px"
  container: "12px"
spacing:
  native-unit: "4px"
  compact: "8px"
  control: "12px"
  regular: "16px"
  section-heading: "20px"
  section: "24px"
  workbench: "32px"
  portal: "36px"
  empty: "48px"
components:
  button-primary-lg:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.native-white}"
    typography: "{typography.button-lg}"
    rounded: "{rounded.control}"
    padding: "10px 16px"
  button-primary-lg-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.native-white}"
  button-secondary-sm:
    backgroundColor: "{colors.native-white}"
    textColor: "{colors.native-secondary}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  button-link-gray:
    textColor: "{colors.native-tertiary}"
    typography: "{typography.label}"
    rounded: "{rounded.skeleton}"
    padding: "0"
  input-sm:
    backgroundColor: "{colors.native-white}"
    textColor: "{colors.native-ink}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  portal-container:
    backgroundColor: "{colors.native-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.container}"
    padding: "{spacing.portal}"
  metric-total:
    textColor: "{colors.ink}"
    typography: "{typography.metric}"
  catalog-table:
    textColor: "{colors.ink}"
    width: "100%"
  governance-actions:
    textColor: "{colors.primary}"
    padding: "16px 0"
  empty-state:
    backgroundColor: "{colors.native-surface-subtle}"
    rounded: "{rounded.container}"
    padding: "48px 20px"
  login-error:
    backgroundColor: "{colors.error-surface}"
    textColor: "{colors.error-ink}"
    rounded: "{rounded.control}"
    padding: "12px 16px"
  loading-skeleton:
    backgroundColor: "{colors.native-skeleton}"
    rounded: "{rounded.skeleton}"
    width: "72px"
    height: "28px"
  button-primary-sm:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.native-white}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  workspace-sidebar-expanded:
    backgroundColor: "{colors.native-surface-subtle}"
    textColor: "{colors.muted}"
    width: "240px"
  workspace-sidebar-collapsed:
    backgroundColor: "{colors.native-surface-subtle}"
    textColor: "{colors.muted}"
    width: "72px"
  workspace-topbar:
    backgroundColor: "{colors.native-white}"
    textColor: "{colors.ink}"
    padding: "12px 24px"
    height: "64px"
  workspace-navigation-item:
    textColor: "{colors.muted}"
    typography: "{typography.label}"
    rounded: "{rounded.navigation}"
    padding: "0 12px"
    height: "40px"
  workspace-navigation-item-hover:
    backgroundColor: "{colors.native-surface-hover}"
    textColor: "{colors.ink}"
  workspace-navigation-item-selected:
    backgroundColor: "{colors.navigation-selected}"
    textColor: "{colors.primary}"
    typography: "{typography.label-selected}"
    rounded: "{rounded.navigation}"
    height: "40px"
  integration-page:
    textColor: "{colors.ink}"
    padding: "{spacing.section}"
    width: "100%"
  integration-table:
    textColor: "{colors.ink}"
    typography: "{typography.body-table}"
    width: "100%"
  status-success:
    textColor: "{colors.utility-success-ink}"
    typography: "{typography.label-status}"
  status-error:
    textColor: "{colors.utility-error-ink}"
    typography: "{typography.label-status}"
  integration-alert:
    backgroundColor: "{colors.error-surface}"
    textColor: "{colors.utility-error-ink}"
    rounded: "{rounded.control}"
    padding: "14px 16px"
  form-drawer:
    backgroundColor: "{colors.native-white}"
    textColor: "{colors.ink}"
    height: "100dvh"
  form-drawer-header:
    typography: "{typography.title-drawer}"
    padding: "20px 24px"
  form-drawer-body:
    padding: "{spacing.section}"
---

# Design System: OpenMetadata Hospital Adaptation

## Overview

**Creative North Star: "Calm, precise hospital operations"**

The approved Integrate identity now applies to the document hospital theme and shared authenticated PC workspace. Integrate green, inherited Chinese system typography, compact native controls and a clear task hierarchy connect the entry, workbench, navigation and integration surfaces. The incumbent OpenMetadata core library remains the authority for shared widgets and explicit native typography.

This expands the established identity and preserves the confirmed entry and workbench rules. Phase one changes the shared frame and navigation plus source, task-list and task-detail surfaces. Asset, quality, governance and knowledge interiors retain incumbent behavior and page composition. Their navigation membership does not establish an inner-page redesign.

Local styles use semantic OpenMetadata variables. Selected prefixed aliases connect native and retained Ant controls to the hospital accent. Frontmatter records observed light defaults and explicit dark primitives; the sidecar records scope, dark mappings, layout, focus and motion. Native surface, text and status primitives continue to resolve through the core semantic system at runtime. Page strategy remains in the surface briefs.

**Key Characteristics:**

- Calm green emphasis with tinted page neutrals and legible text.
- Chinese system typography, preserved native type roles and compact controls.
- Full-width PC navigation, factual data regions and native right-hand form drawers.
- Distinct loading, unavailable, error and empty states.

Authority: [hospital theme](openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less), [Integrate entry source](openmetadata-ui/src/main/resources/ui/src/pages/LoginPage/IntegrateLoginPage.tsx), [entry styles](openmetadata-ui/src/main/resources/ui/src/pages/LoginPage/hospital-login.less), [workbench source](openmetadata-ui/src/main/resources/ui/src/pages/MyDataPage/HospitalWorkbench.tsx), [workbench styles](openmetadata-ui/src/main/resources/ui/src/pages/MyDataPage/hospital-workbench.less), and the native [Button](openmetadata-ui-core-components/src/main/resources/ui/src/components/base/buttons/button.tsx), [Input](openmetadata-ui-core-components/src/main/resources/ui/src/components/base/input/input.tsx) and [global tokens](openmetadata-ui-core-components/src/main/resources/ui/src/styles/globals.css). The [surface brief](.impeccable/briefs/hospital-workbench.md) retains page strategy.

Additional authority: the [global theme entry](openmetadata-ui/src/main/resources/ui/src/index.tsx), [workspace frame](openmetadata-ui/src/main/resources/ui/src/styles/hospital-workspace.less), [sidebar](openmetadata-ui/src/main/resources/ui/src/components/MyData/LeftSidebar/left-sidebar.less), [integration styles](openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/hospital-integration.less), [native FormDrawer](openmetadata-ui/src/main/resources/ui/src/components/common/atoms/drawer/FormDrawer.tsx), and core [SlideoutMenu](openmetadata-ui-core-components/src/main/resources/ui/src/components/application/slideout-menus/slideout-menu.tsx). The [canonical color guide](openmetadata-ui/src/main/resources/ui/docs/colors.md) governs semantic color use. The [Hospital PC contract](.impeccable/surfaces/hospital-pc-navigation.md) retains exact information architecture and interaction scope.

The earlier [capture report](.impeccable/review/capture-report.json) lists ten synthetic entry/workbench previews and omits outer navigation. The phase-one [React manifest](.impeccable/review/pc-navigation/report.json) lists 44 PC captures; the [directed correction manifest](.impeccable/review/pc-navigation/finish-fix/report.json) lists five. The [finish review](.impeccable/review/pc-navigation/finish-review.md) records the inspected states and scored design disposition. The final [production build log](.impeccable/review/pc-navigation/production-build.txt) records the completed build. This documentation pass extracts source and reads supplied evidence; it does not claim a new visual review, employee SSO, JDBC extraction or live catalog writes. No new raster asset ships in the product.

## Colors

Integrate green marks actions and links; green-tinted page, text and divider neutrals organize the hospital surfaces. Native semantic neutrals and status colors continue to serve core controls.

### Primary

- **Integrate Green** (`primary`): primary action fill, local links, caret and focus accents.
- **Active Integrate Green** (`primary-hover`): native primary button hover fill.
- **Readable Dark Green Accent** (`primary-dark`): brand text, foreground and borders in the hospital dark scope. Solid action fills retain their existing green values.
- **Pale Green Selection and Icon Tint** (`selection`, `primary-icon`): selection highlight and the scoped native primary icon aliases. Selection also supplies the primary icon hover tint.
- **Workspace Selection Mix** (`navigation-selected`): a live semantic brand/surface mix for selected navigation and retained legacy control accents, distinct from text selection.

### Neutral

- **Hospital Page Tint** (`page`, `page-dark`): the incumbent entry canvas; authenticated workspace surfaces use native background semantics.
- **Hospital Ink** (`ink`, `ink-dark`): local headings and foreground text.
- **Hospital Muted Ink** (`muted`, `muted-dark`): descriptions, metric labels, hints and asset metadata.
- **Hospital Divider** (`border`, `border-dark`): portal border, table rules and section separation.
- **Native Surface Neutrals** (`native-white`, `native-surface-subtle`, `native-skeleton`): light defaults for primary/secondary surfaces and static placeholders.
- **Native Dark Surfaces** (`native-page-dark`, `native-surface-subtle-dark`, `native-surface-hover-dark`): the existing core dark mappings, preserving their source CSS color format.
- **Native Hover Surface** (`native-surface-hover`): secondary-surface hover treatment.
- **Native Control Neutrals** (`native-ink`, `native-secondary`, `native-tertiary`, `native-control-border`): light defaults for core input, secondary button, retry link and control stroke.

### Semantic Status

- **Native Error Surface and Ink** (`error-surface`, `error-ink`): the incumbent entry alert's native roles. Workbench asset failures retain a labelled neutral alert container.
- **Readable Status Ink** (`utility-success-ink`, `utility-error-ink` and their `-dark` counterparts): shared utility-success-700/error-700 semantics for completed/synchronized states, failures, integration errors and discard confirmations.
- **Dark Error Surface** (`error-surface-dark`): the core dark error-primary background.

**The Scoped Alias Rule.** Apply hospital color overrides through the document hospital theme and workspace wrappers. Preserve semantic status roles and the core component token system when adding a hospital surface.

**The Status Meaning Rule.** Use shared success/error utility aliases and their dark mappings for data-bound state text. Keep a readable label with the color.

The sidecar records the exact `--tw-*` and retained `--ant-*` relationships. It distinguishes the local `--color-*` variables from the property aliases used by `tw:` utilities; a local semantic override does not imply every prefixed neutral token has been rebound.

## Typography

**Body and inherited heading font:** PingFang SC, Microsoft YaHei, Source Han Sans SC, system-ui, sans-serif, applied by the document hospital theme and workspace wrapper. Explicit native component font definitions remain authoritative where set.

**Character:** familiar Chinese operating-system typography, medium emphasis for controls and semibold hierarchy for headings and totals. This is an observed role-based ramp; it does not establish a mathematical scale for the wider product.

### Hierarchy

- **Entry headline:** `headline-login`, switching to `headline-login-mobile` at the observed mobile breakpoint.
- **Workbench headline:** `headline-workbench`, switching to `headline-workbench-mobile`.
- **Section titles:** `title-portal` for the entry action region and `title-workbench` for workbench sections.
- **Descriptions:** `body-intro` for the entry purpose and `body-description` for capability detail.
- **PC page and section titles:** `headline-page` and `title-section` preserve the compact integration hierarchy.
- **Native drawer title and sidebar brand:** `title-drawer` follows core large text with semibold weight; `title-brand` serves the inherited Integrate wordmark text.
- **Controls:** native small controls and labels use `label`; the primary large controls use `button-lg`. Selected navigation uses `label-selected`.
- **Dense data and status:** `body-table` and `label-status` record recurring integration roles without inferring new global line heights.
- **Totals:** `metric` with tabular numerals. Unavailable values use ordinary text at reduced size and weight.
- **Supporting type:** the table and metric labels use compact type (13px); fully qualified asset names use smaller metadata (11px). Hints and footer text follow their local source styles.

**The Numeric Meaning Rule.** Keep tabular numbers for actual totals. Preserve the distinct unavailable text and loading skeleton so missing data never acquires the appearance of a numeric result.

## Layout

Delivery and acceptance scope is PC. The shared authenticated frame fills the available width, using the expanded/collapsed sidebar and topbar frontmatter primitives with a flexible content region. Main content and the upper menu scroll independently; the lower menu stays fixed within the sidebar. The sidecar records search constraints, exact dimensions and the compact-desktop adjustment at maximum width 1200px.

Integration pages use `integration-page` spacing and width, compact headers/filters and neutral tables. Source/task tables retain a local horizontal scroll region around their observed minimum width (1000px); wrapped filters and `min-width: 0` contain reduced PC working space. Native drawers keep title/actions outside the internally scrolling body. Exact page routes, list context and source/task drawer widths stay in the Hospital PC contract and scoped sidecar layout.

The following incumbent measurements remain in effect for the entry and workbench.

The entry fills the viewport (`100dvh`) with a bounded content area based on the existing login width (1120px). Desktop horizontal padding is the greater of the minimum gutter (24px) and the centered viewport calculation. Its introduction and portal region use a two-column grid (1.15fr / 1fr), a wide gap (80px) and vertical layout padding (72px). The portal container uses `portal` spacing and the primary action fills its width.

The workbench is centered at its observed maximum width (1280px), with `workbench` padding. Search occupies a bounded grid (840px maximum) with an input and large submit action. Four totals sit in a bordered band. Below, the catalog and action rail use a fluid column and a narrower column (2.2fr / 1fr, with a 240px minimum on the action side) separated by a gap (32px). A local horizontal scroll container preserves table access when needed; long identifiers can wrap.

The retained source breakpoint (maximum width 767px) is an incumbent fallback, not a mobile delivery or acceptance requirement. At that width, the entry becomes one column, reduces the headline and portal padding, and hides the supplementary capability descriptions. The workbench uses smaller gutters (24px / 16px), two metric columns and one content column; its action rail loses the left separator. Search retains its input/action grid with a reduced gap (8px). The breakpoint and region measurements are carried in the sidecar.

## Elevation & Depth

The hospital portal, metric band, catalog, action list and PC data regions use surface tones and thin separators. Their local resting styles add no decorative box shadow. Native right-hand drawers retain the core editing-layer shadow and semantic overlay. Core controls retain the incumbent treatment: the input uses the native low shadow and a stroke; buttons retain their native inner border construction. Focus is structural emphasis, with a scoped green outline (2px) and offset (3px), rather than decorative lift.

### Shadow Vocabulary

- **Native input shadow:** `native-xs` in the sidecar, copied from the core global token and used by the native input.
- **Native drawer shadow:** `native-xl` in the sidecar, copied from the core global token and used by SlideoutMenu. The footer retains its native inset semantic divider.

The native Button source requests `shadow-xs-skeuomorphic`, while the sampled global token is spelled `--shadow-xs-skeumorphic`. This incumbent source discrepancy is recorded as unconfirmed drift; it does not become a prescribed hospital button shadow and is not repaired in this documentation pass.

Native control transitions are brief (100ms, linear). Native slideouts retain their source entering/exiting timings. The hospital scopes and FormDrawer wrapper limit or remove animation and transitions for reduced motion; exact selectors and declarations are recorded in the sidecar. Local loading bars are static placeholders.

## Shapes

Navigation rows add the compact `navigation` radius; full-height native drawers remain edge panels. Controls and entry alerts use restrained rounded corners (`control`); portal and empty-state containers use the slightly larger `container` radius. Small skeletons and the native retry link use `skeleton` corners. Thin semantic borders establish regions and table rows. The hospital adaptation preserves the native SVG icon systems used by its source components.

## Components

### Buttons

Native primary large controls serve the Integrate portal action and catalog search. They use `button-primary-lg` with the observed native text span inset (2px per side) and change to the primary hover fill. The portal action has full width and becomes disabled during initialization or when portal configuration is unavailable. The native secondary small control reloads the workbench. The native link-gray control retries unavailable portal configuration. Their existing React Aria state and variant APIs remain the implementation authority.

The PC creation action uses the native small default `button-primary-sm`, with existing busy/permission state controlling availability. Native core controls and their variant APIs remain authoritative.

### Inputs / Fields

The workbench uses the native small Input with a visible label and SearchLg leading icon. The base `input-sm` padding acquires the source leading-icon inset (40px on the left). Native focus-within increases the field stroke to the brand outline; the hospital focus-visible rule supplies its scoped green keyboard emphasis. Disabled and invalid styles remain the core component's semantic treatments. The sidecar previews the text-only native base primitive and records the search variant's inset separately.

### Cards / Containers

The portal container uses a primary native surface, semantic divider and `container` radius. It contains the portal explanation, connection state, action, recovery and account hint. The workbench uses flat regions with rules and an empty container rather than turning every region into a card.

### Catalog Totals and Assets

Four totals come from the corresponding catalog endpoints. A total of zero is a numeric result; an unresolved request shows a skeleton; a rejected request shows unavailable text. These states stay distinguishable.

The asset table shows the initial five requested catalog assets, their sources and owners. Rows follow the catalog API's order. The shipped Chinese heading says “目录中的数据资产”; the internal translation key `recent` supplies no recency promise. Asset names link to native table pages, with fully qualified names as secondary metadata.

### Governance Action Navigation

The workbench action rail uses ordinary native routes to business definitions, data quality and database services. Each row pairs a clear label with the source arrow icon, a semantic separator and hover underline. At narrow widths, the rail follows the catalog in the single column. The shared PC sidebar is documented separately below; the task rail retains its incumbent surface role.

### Shared PC Navigation

The sidebar uses compact rounded rows, native outline SVG icons, neutral resting/hover treatments and a semantic green selected state with semibold text. The main menu scrolls above fixed lower actions. Collapsed navigation retains labelled native submenus. URL-backed active ancestry, menu customization and permission paths remain implementation authority. The exact six Chinese groups and lower entries stay in the Hospital PC contract.

### Integration Tables and Status

`integration-table` preserves compact neutral headers, semantic row separators, secondary metadata, labelled actions and contained horizontal overflow. Finished/synchronized and failed state text use `status-success` and `status-error`; running text uses the brand role, while unknown/unavailable states remain neutral. The stronger integration error role does not replace the incumbent entry error text role.

### Native Right-Hand Form Drawer

FormDrawer wraps the core SlideoutMenu, preserving a stable title region, internally scrolling body and persistent native footer actions. Fields and popups remain in the native portal system. Native focus containment, Escape handling, pending-state dismissal guards and discard confirmation remain component behavior. Exact widths, draft retention and list/history restoration are local surface commitments.

### Error, Loading and Empty States

The entry announces errors with `role="alert"` and connection placeholders with `role="status"`; its portal region exposes busy state. The asset region separately renders loading, failed and empty content. Failed assets show an alert message; a successful empty result explains the next setup step. Workbench reload retries the endpoint state. Static sidecar examples are explicitly synthetic.

**The State Honesty Rule.** Render the state returned by the request. Preserve real zero totals, unavailable values, failed assets and empty catalogs as separate meanings, with their existing text and semantic roles.

## Do's and Don'ts

### Do:

- **Do** use the confirmed Chinese system stack and Integrate green through the hospital theme and shared workspace.
- **Do** use native core Button, Input and FormDrawer behavior with semantic color aliases and their dark mappings.
- **Do** preserve explicit native type roles and contain table/form overflow so shared navigation and drawer actions stay reachable.
- **Do** preserve clear task hierarchy, compact data typography and visible keyboard focus.
- **Do** keep loading, unavailable, error, empty and zero states distinct.
- **Do** describe the asset rows as a catalog sample in API order.

### Don't:

- **Don't** treat the shared frame redesign as evidence that every inner page has been redesigned.
- **Don't** add a marketing carousel or decorative data graphics to these operating surfaces.
- **Don't** present synthetic preview data as connected hospital data or imply unsupported asset recency.
- **Don't** hardcode a screenshot background or status hue in place of the established semantic role.
- **Don't** canonize an unconfirmed native utility spelling discrepancy as a new elevation rule.
