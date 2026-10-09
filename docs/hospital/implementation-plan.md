# Hospital governance first stage

The source baseline is OpenMetadata 2.0.4. Human sign-in happens in Integrate with employee number and password. The application portal opens the `datahub` application; OpenMetadata exchanges a one-time code without asking for email or password.

- [x] Fork the stable source to Yzero12138 and create the adaptation branch.
- [x] Install default Taste and complete Impeccable 4.5.1 companion files.
- [x] Implement a validated Integrate HTTP client and test issuer, audience, token shape, lifetime and inactive responses against a local HTTP server.
- [x] Store Integrate session material encrypted in OpenMetadata's persistent session store, with source expiry as an upper bound. Revalidate identity on authenticated API and socket access, and on token refresh.
- [x] Close native human password, signup and password-reset endpoints in Integrate mode. Keep bot authentication for ingestion.
- [x] Replace the sign-in surface with a portal entry and guarded popup handshake. Test forged origin/source/challenge, timeout, cancellation and replay.
- [x] Add a Chinese hospital workbench using actual catalog counts and tables, clear unavailable states, and native catalog/glossary/data-quality routes.
- [x] Provide isolated synthetic HIS/LIS UI fixtures and reproducible local validation. Supply deployment configuration without secrets; do not imply ingestion or a hospital glossary has been populated.
- [x] Compile, run focused authentication tests and browser validation; capture desktop/mobile/light/dark, fix in one batch, and finish the scoped design review.
- [x] Deploy the adapted image, ConfigMap, Secret references and new database/search volumes to the test Kubernetes environment. Validate actual service readiness, password-endpoint rejection, UI entry and the real Integrate backchannel after the user-approved limited NetworkPolicy change.
- [ ] Commit and push the changes to the user's fork with a reviewable change description.

Acceptance remains for a hospital-authorized device: real employee portal login, logout/revocation and first governance administrator assignment. The test cluster is running; no employee identity was simulated for live acceptance.

Integration addresses are environment configuration. No patient data, hospital credentials or Integrate working-tree changes belong in this fork.
