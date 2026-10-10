---
name: OpenMetadata Hospital Adaptation
description: Scoped Integrate entry and Chinese hospital governance workbench within the native OpenMetadata system.
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
rounded:
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
---

# Design System: OpenMetadata Hospital Adaptation

## Overview

**Creative North Star: "Calm, precise hospital operations"**

This record describes the shipped Integrate entry page and Chinese hospital governance workbench inside OpenMetadata. The confirmed direction uses Integrate's green accent, a Chinese system font stack, restrained native controls and a clear task hierarchy. The incumbent OpenMetadata core library remains the authority for shared widgets, outer navigation and the wider catalog, lineage and quality workflows.

The adaptation is scoped through the hospital wrapper. Local styles use semantic OpenMetadata variables; selected prefixed aliases connect the native controls to the hospital accent. Frontmatter records the observed light defaults and explicit hospital dark overrides. Native surface, text and status primitives continue to resolve through the incumbent semantic tokens at runtime.

**Key Characteristics:**

- Calm green emphasis with tinted page neutrals and legible text.
- Chinese system typography with compact native controls.
- Search, factual catalog totals and clear governance actions.
- Distinct loading, unavailable, error and empty states.

Authority: [hospital theme](openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less), [Integrate entry source](openmetadata-ui/src/main/resources/ui/src/pages/LoginPage/IntegrateLoginPage.tsx), [entry styles](openmetadata-ui/src/main/resources/ui/src/pages/LoginPage/hospital-login.less), [workbench source](openmetadata-ui/src/main/resources/ui/src/pages/MyDataPage/HospitalWorkbench.tsx), [workbench styles](openmetadata-ui/src/main/resources/ui/src/pages/MyDataPage/hospital-workbench.less), and the native [Button](openmetadata-ui-core-components/src/main/resources/ui/src/components/base/buttons/button.tsx), [Input](openmetadata-ui-core-components/src/main/resources/ui/src/components/base/input/input.tsx) and [global tokens](openmetadata-ui-core-components/src/main/resources/ui/src/styles/globals.css). The [surface brief](.impeccable/briefs/hospital-workbench.md) retains page strategy.

The supplied [capture report](.impeccable/review/capture-report.json) lists ten labelled synthetic previews at desktop and mobile widths in both themes. It records local visual checks and state behavior; the preview omits outer application navigation. This documentation pass checks source and that report, and makes no whole-application or end-to-end SSO validation claim.

## Colors

Integrate green marks actions and links; green-tinted page, text and divider neutrals organize the hospital surfaces. Native semantic neutrals and status colors continue to serve core controls.

### Primary

- **Integrate Green** (`primary`): primary action fill, local links, caret and focus accents.
- **Active Integrate Green** (`primary-hover`): native primary button hover fill.
- **Readable Dark Green Accent** (`primary-dark`): brand text, foreground and borders in the hospital dark scope. Solid action fills retain their existing green values.
- **Pale Green Selection and Icon Tint** (`selection`, `primary-icon`): selection highlight and the scoped native primary icon aliases. Selection also supplies the primary icon hover tint.

### Neutral

- **Hospital Page Tint** (`page`, `page-dark`): the entry canvas; the workbench itself has a transparent background within its native page layout.
- **Hospital Ink** (`ink`, `ink-dark`): local headings and foreground text.
- **Hospital Muted Ink** (`muted`, `muted-dark`): descriptions, metric labels, hints and asset metadata.
- **Hospital Divider** (`border`, `border-dark`): portal border, table rules and section separation.
- **Native Surface Neutrals** (`native-white`, `native-surface-subtle`, `native-skeleton`): light defaults for the portal, empty container and static skeletons.
- **Native Control Neutrals** (`native-ink`, `native-secondary`, `native-tertiary`, `native-control-border`): light defaults for core input, secondary button, retry link and control stroke.

### Semantic Status

- **Native Error Surface and Ink** (`error-surface`, `error-ink`): the entry alert uses the native error background and text tokens. Workbench asset failures use a labelled neutral alert container.

**The Scoped Alias Rule.** Apply hospital color overrides through the hospital wrapper. Preserve semantic status roles and the core component token system when adding a hospital surface.

The sidecar records the exact `--tw-*` relationships. It distinguishes the local `--color-*` variables from the property aliases used by `tw:` utilities; a local semantic override does not imply every prefixed neutral token has been rebound.

## Typography

**Body and heading font:** PingFang SC, Microsoft YaHei, Source Han Sans SC, system-ui, sans-serif, inherited from the hospital wrapper. The native library's separate global font definitions remain authoritative outside that scope.

**Character:** familiar Chinese operating-system typography, medium emphasis for controls and semibold hierarchy for headings and totals. This is an observed role-based ramp; it does not establish a mathematical scale for the wider product.

### Hierarchy

- **Entry headline:** `headline-login`, switching to `headline-login-mobile` at the observed mobile breakpoint.
- **Workbench headline:** `headline-workbench`, switching to `headline-workbench-mobile`.
- **Section titles:** `title-portal` for the entry action region and `title-workbench` for workbench sections.
- **Descriptions:** `body-intro` for the entry purpose and `body-description` for capability detail.
- **Controls:** native small controls and labels use `label`; the primary large controls use `button-lg`.
- **Totals:** `metric` with tabular numerals. Unavailable values use ordinary text at reduced size and weight.
- **Supporting type:** the table and metric labels use compact type (13px); fully qualified asset names use smaller metadata (11px). Hints and footer text follow their local source styles.

**The Numeric Meaning Rule.** Keep tabular numbers for actual totals. Preserve the distinct unavailable text and loading skeleton so missing data never acquires the appearance of a numeric result.

## Layout

The native page layout owns the workbench's surrounding navigation and scrolling. These measurements describe the two hospital surfaces.

The entry fills the viewport (`100dvh`) with a bounded content area based on the existing login width (1120px). Desktop horizontal padding is the greater of the minimum gutter (24px) and the centered viewport calculation. Its introduction and portal region use a two-column grid (1.15fr / 1fr), a wide gap (80px) and vertical layout padding (72px). The portal container uses `portal` spacing and the primary action fills its width.

The workbench is centered at its observed maximum width (1280px), with `workbench` padding. Search occupies a bounded grid (840px maximum) with an input and large submit action. Four totals sit in a bordered band. Below, the catalog and action rail use a fluid column and a narrower column (2.2fr / 1fr, with a 240px minimum on the action side) separated by a gap (32px). A local horizontal scroll container preserves table access when needed; long identifiers can wrap.

At the source breakpoint (maximum width 767px), the entry becomes one column, reduces the headline and portal padding, and hides the supplementary capability descriptions. The workbench uses smaller gutters (24px / 16px), two metric columns and one content column; its action rail loses the left separator. Search retains its input/action grid with a reduced gap (8px). The breakpoint and region measurements are carried in the sidecar.

## Elevation & Depth

The hospital portal, metric band, catalog and action list use surface tones and thin separators. Their local styles add no box shadow. Core controls retain the incumbent treatment: the input uses the native low shadow and a stroke; buttons retain their native inner border construction. Focus is structural emphasis, with a scoped green outline (2px) and offset (3px), rather than decorative lift.

### Shadow Vocabulary

- **Native input shadow:** `native-xs` in the sidecar, copied from the core global token and used by the native input.

The native Button source requests `shadow-xs-skeuomorphic`, while the sampled global token is spelled `--shadow-xs-skeumorphic`. This incumbent source discrepancy is recorded as unconfirmed drift; it does not become a prescribed hospital button shadow and is not repaired in this documentation pass.

Native control transitions are brief (100ms, linear). The hospital reduced-motion query disables transitions and animation within both surfaces. The local loading bars are static placeholders.

## Shapes

Controls and entry alerts use restrained rounded corners (`control`); portal and empty-state containers use the slightly larger `container` radius. Small skeletons and the native retry link use `skeleton` corners. Thin semantic borders establish regions and table rows. The hospital adaptation preserves the native SVG icon systems used by its source components.

## Components

### Buttons

Native primary large controls serve the Integrate portal action and catalog search. They use `button-primary-lg` with the observed native text span inset (2px per side) and change to the primary hover fill. The portal action has full width and becomes disabled during initialization or when portal configuration is unavailable. The native secondary small control reloads the workbench. The native link-gray control retries unavailable portal configuration. Their existing React Aria state and variant APIs remain the implementation authority.

### Inputs / Fields

The workbench uses the native small Input with a visible label and SearchLg leading icon. The base `input-sm` padding acquires the source leading-icon inset (40px on the left). Native focus-within increases the field stroke to the brand outline; the hospital focus-visible rule supplies its scoped green keyboard emphasis. Disabled and invalid styles remain the core component's semantic treatments. The sidecar previews the text-only native base primitive and records the search variant's inset separately.

### Cards / Containers

The portal container uses a primary native surface, semantic divider and `container` radius. It contains the portal explanation, connection state, action, recovery and account hint. The workbench uses flat regions with rules and an empty container rather than turning every region into a card.

### Catalog Totals and Assets

Four totals come from the corresponding catalog endpoints. A total of zero is a numeric result; an unresolved request shows a skeleton; a rejected request shows unavailable text. These states stay distinguishable.

The asset table shows the initial five requested catalog assets, their sources and owners. Rows follow the catalog API's order. The shipped Chinese heading says “目录中的数据资产”; the internal translation key `recent` supplies no recency promise. Asset names link to native table pages, with fully qualified names as secondary metadata.

### Governance Action Navigation

The workbench action rail uses ordinary native routes to business definitions, data quality and database services. Each row pairs a clear label with the source arrow icon, a semantic separator and hover underline. At narrow widths, the rail follows the catalog in the single column. The wider application's outer navigation remains under its incumbent components.

### Error, Loading and Empty States

The entry announces errors with `role="alert"` and connection placeholders with `role="status"`; its portal region exposes busy state. The asset region separately renders loading, failed and empty content. Failed assets show an alert message; a successful empty result explains the next setup step. Workbench reload retries the endpoint state. Static sidecar examples are explicitly synthetic.

**The State Honesty Rule.** Render the state returned by the request. Preserve real zero totals, unavailable values, failed assets and empty catalogs as separate meanings, with their existing text and semantic roles.

## Do's and Don'ts

### Do:

- **Do** use the confirmed Chinese system stack and Integrate green within the hospital scope.
- **Do** use native core Button and Input variants with semantic color aliases.
- **Do** preserve clear task hierarchy, compact data typography and visible keyboard focus.
- **Do** keep loading, unavailable, error, empty and zero states distinct.
- **Do** describe the asset rows as a catalog sample in API order.

### Don't:

- **Don't** broaden this scoped adaptation into a replacement for the wider OpenMetadata system.
- **Don't** add a marketing carousel or decorative data graphics to these operating surfaces.
- **Don't** present synthetic preview data as connected hospital data or imply unsupported asset recency.
- **Don't** canonize an unconfirmed native utility spelling discrepancy as a new elevation rule.
