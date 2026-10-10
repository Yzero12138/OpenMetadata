# Hospital Data Governance

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Hospital internal users. The first stage serves the teams responsible for finding hospital data, maintaining definitions and investigating data quality incidents. Specific department roles remain an open deployment decision.

## Product Purpose

Adapt OpenMetadata into an internal hospital data governance platform. The first stage provides unified access from Integrate, a Chinese governance workbench, a data catalog, hospital business definitions and a reproducible validation environment.

## Operating Context

The existing Kubernetes test environment was inspected. Integrate is in `coop-dev`; the metadata adaptation is isolated in `datahub-open-test`. Its addresses and internal identity backchannel are stored in the deployment ConfigMap. Integrate already implements the two-party `datahub` ticket protocol; this is a portal application code, not a dependency on the DataHub project.

## Capabilities and Constraints

- Baseline: OpenMetadata 2.0.4 release, Java 21, React 18, TypeScript and Vite 7.
- Integrate remains the identity and session authority. OpenMetadata keeps responsibility for its own governance permissions.
- Never expose the Integrate client secret or opaque session token to the browser.
- Hospital source credentials and representative data have not been supplied. Validation data must be explicitly synthetic.
- The user approved the next integration phase: independent SeaTunnel 3.0.0, isolated synthetic PostgreSQL source and ODS, and Chinese task control in the existing workbench. Doris remains a later independent analytics-storage phase.
- The user confirmed PC-only delivery for the complete service; mobile interfaces are outside the required delivery and acceptance scope.
- Preserve upstream licenses and existing data catalog, lineage and incident workflows.

## Brand Commitments

Use the user-requested default `design-taste-frontend` and the latest Impeccable with its complete companion files. Integrate has an established hospital workbench identity in `F:/my/Integrate/DESIGN.md`; this adaptation follows that identity. The user selected direct code implementation instead of image-first mockups.

## Evidence on Hand

- Integrate's current SSO configuration, service, controller and popup handshake code were inspected locally without modifying its working tree.
- OpenMetadata's stable source has been forked to `Yzero12138/OpenMetadata`.
- No real hospital patient data is included in this repository.

## Product Principles

1. Keep source identity authoritative through expiry, logout and permission changes.
2. Help operators find the next useful governance action with clear language and actual data.
3. Show honest empty, loading and failed states; never imply a hospital source has been connected when it has not.
4. Keep the adaptation narrow enough to continue taking upstream releases.
