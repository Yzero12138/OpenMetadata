# Governance workflow and Chinese interface corrections

The user reported a missing workflow creation action, untranslated domain types and English interface text, and asked to remove the GitHub star promotion. Preserve the existing hospital operating interface and Integrate authentication.

## Implementation

- [x] Enable the existing event-based visual workflow editor, with the same administrator-protected routes and native server authorization.
- [x] Collect a technical name, Chinese-capable display name and description. Keep the new definition in browser navigation state until the user configures and validates its graph. Create with POST on first save so an existing workflow cannot be overwritten by a name collision; navigate to the saved definition afterward.
- [x] Translate domain type labels in creation, editing, detail, listing and filtering while preserving the API enum values.
- [x] Translate outstanding Simplified Chinese UI resources and hardcoded text on the workflow and principal governance surfaces. Translate built-in workflow labels and descriptions only when they match the shipped defaults; preserve custom metadata.
- [x] Remove the registered GitHub star promotion without relying on a per-browser dismissal flag.

## Verification and delivery

- [x] Reproduce the missing creation action, untranslated domain labels and resource placeholders with focused tests before implementation.
- [x] Exercise local draft navigation, first-save POST, duplicate-name rejection, graph validation and existing-definition update behavior.
- [x] Run scoped UI import organization, ESLint and Prettier in order; run relevant regression tests and the production build.
- [x] Inspect desktop and mobile workflow/domain states in one batched visual pass; run the Impeccable detector and the required independent review.
- [x] Build the next hospital image, deploy to the already authorized test Kubernetes environment and verify readiness, retained SSO protections and Chinese interface assets.
- [ ] Push the verified changes to the existing fork branch and update its draft PR using only public code and source-test information.

Technical acronyms, product names, API identifiers and user-entered asset metadata can remain in their original form. No employee session is fabricated for acceptance.

## Source verification

Thirteen focused Jest suites passed all 85 tests. A later responsive correction reran the three affected suites, all 15 tests passing. Import organization, ESLint and final Prettier passed for 74 UI source/resource files. The full TypeScript check remains at the existing 550 diagnostics; comparison by source file and diagnostic code adds none. This is not a passing full-repository type check.

Simplified Chinese resources replace 214 existing English fallback strings and repair 27 interpolation/markup mismatches. Chinese key and interpolation parity tests pass. Built-in workflow projection preserves customized names/descriptions, including empty values. New workflow/node/task-form keys are synchronized across all 20 locale files. Remaining English-equal resource entries are technical names, acronyms or interpolation-only templates.

Independent source review identified unsupported policy-agent palette nodes, unrecoverable duplicate-name drafts, draft title edits requiring an ID and incomplete no-op palette gating. All four fixes were independently confirmed resolved. Overlapping saves are guarded and a regression proves only one POST while a request is pending.

The local synthetic browser harness runs real UI components against explicit fixture HTTP responses. Desktop checks cover a configured start/end graph, stable serialized node names, initial POST conflict, graph retention, local technical/display name editing and successful retry. Narrow-screen checks cover creation and start configuration; they do not claim a complete mobile graph-editing acceptance. The standalone preview rejects mutations unless a verification script explicitly intercepts its synthetic boundary.

The initial batched visual review found clipped narrow-screen header controls, an obstructing node palette and vertically wrapped pagination. The responsive fix separates header content and actions, adds an accessible narrow-screen palette toggle and keeps page text unbroken. Explicit grid column start/end declarations avoid Less arithmetic creating an implicit pagination column. The final eight desktop/mobile captures are valid; pagination element bounds do not overlap. Independent finish review returned `disposition: ship` for these three corrections, all resolved with no remaining material fixes. Its scope does not extend to authenticated hospital workflow execution. The detector ran once on changed targets and returned no findings; task-form targets had their own clean scan.

## Test deployment verification

The final UI JAR matches the verified entry, Chinese bundle, workflow bundles and responsive styles. Hospital image `2.0.4-integrate-v4` was built and pushed, and the application manifest was applied to the authorized test cluster. The migration container exited 0 and the deployment rolled out successfully. Its running image digest matches the pushed image.

All 17 live portal/authentication-protection checks and all 7 read-only identity-preservation checks passed. The latter retain the mapped UUID, username, administrator flag, canonical email, roles, teams and relationships. Five deployed JS/CSS resources match the final local production build by SHA-256. The deployed HTML also matches the original server template after its documented base-path, per-request nonce and line-ending transformations. No employee login was synthesized and no hospital workflow or business metadata was written by these checks.
