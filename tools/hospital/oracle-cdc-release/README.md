# Hospital CDC release packaging

These tools prepare a local application image and metadata-only rollback files. They do not authorize image publication, deployment, source database changes, or rollback execution.

The historic `oracle-cdc` filenames, helper names, and `oracle-cdc-production-dist-v1` manifest algorithm are retained for compatibility and now package the current combined Oracle and SQL Server CDC application. The default local image is `hospital-openmetadata:2.0.4-integrate-v16-sqlserver-cdc-20261114`, with application version label `2.0.4-integrate-v16-sqlserver-cdc`. Historical v15 candidate manifests remain frozen evidence for that earlier release. The CDC engine uses the separate `docker/hospital/seatunnel/Dockerfile.cdc` and approved engine source `72274243`.

`docker/hospital/Dockerfile.oracle-cdc` inherits the exact current Integrate/SSO/PC/Doris/NLS/JWT baseline, `harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:f9fac7c3c8230093c501e9730be4486234fd17e1e498b928fc9e36b9f559b518`. It replaces only `openmetadata-service-2.0.4.jar` and `openmetadata-ui-2.0.4.jar`, verifies their SHA256 values during the image build, and keeps user 1000 and the inherited entrypoint, configuration, drivers, and other assets.

The builder requires a 40-character lower-case revision equal to current Git HEAD and no tracked or untracked changes in the scoped production modules, root build inputs, or these release tools. Documentation, ignored cache/build outputs, and unrelated root untracked files do not block packaging. Commit reviewed production and packaging changes before invoking it. The default builds the service with offline Maven and the actual UI with its installed local Vite. `NodeExecutable`, `JarExecutable`, and `MavenExecutable` accept explicit tool paths. The exact base digest must already exist in the local Docker daemon. The builder uses `--pull=false --network=none` and temporarily selects the legacy Docker builder to prevent BuildKit registry metadata resolution; an engine without that builder fails rather than using a network fallback.

After independently building and verifying both production artifacts, calculate proofs from those exact files and use them to avoid duplicate builds. Run from the repository root:

```powershell
. ./tools/hospital/oracle-cdc-release/ReleaseInputs.ps1
$repository = (Get-Location).Path
$serviceHash = Get-OracleCdcFileSha256 -Path (Join-Path $repository 'openmetadata-service/target/openmetadata-service-2.0.4.jar')
$distManifest = Get-OracleCdcDistManifest -Repository $repository -Dist (Join-Path $repository 'openmetadata-ui/src/main/resources/ui/dist')
$revision = (& git rev-parse HEAD).Trim()
./docker/hospital/build-oracle-cdc-image.ps1 -Revision $revision -SkipBackendBuild -SkipUiBuild -ExpectedServiceSha256 $serviceHash -ExpectedDistManifestSha256 $distManifest.sha256
```

The dist fingerprint uses UTF-8 without a BOM and LF newlines. Its first line is `oracle-cdc-production-dist-v1`. Each following line is `relative/path<TAB>byte-length<TAB>lower-case-sha256`, ending in LF. Paths use `/` and sort with ordinal comparison; the fingerprint is the SHA256 of the complete text. Every dist file, including hidden files, participates. The helper checks a local JavaScript module entrypoint, including the exact inert `${basePath}` prefix emitted by the production Vite transform. It rejects `synthetic-ui-review` in filenames, raw file content, and decoded gzip/Brotli content; ordinary mock types and libraries are allowed. Each raw or decoded asset is limited to 64 MiB. Native decoding errors, truncation, and size mismatches are rejected. Gzip uses the native .NET parser with a narrow Vite header/trailer check; that parser permits some trailing bytes, which still undergo the raw fixture-marker scan. The helper enables .NET strict compression validation and verifies that the runtime rejects truncated streams; use a fresh supported PowerShell process if another caller previously cached a weaker setting. The helper rejects symlinks, junctions, paths escaping the repository, and control characters in asset names. Fixed service and dist output paths are checked before any build tool runs, including when they do not exist yet. This fingerprint proves file identity; the caller remains responsible for verifying the source build and UI behavior.

Each invocation creates a distinct `.cache/oracle-cdc/release/<unique-id>/` without deleting or replacing prior runs. UI JAR entries use the sorted file list, the fixed `SourceDate` timestamp (default `2026-11-14T00:00:00Z`), and no generated manifest. An argument file avoids Windows command-line length limits. Staged service bytes, staged dist, and every UI JAR entry are checked against the input hashes. The Docker context contains only the two allowlisted JARs and an allowlist `.dockerignore`. The saved and returned `image-manifest.json` includes the base digest, exact source revision, actual local image ID, both JAR hashes, dist fingerprint and manifest path, context path, archive date, and creation time. No tool pulls, pushes, or tags a remote repository.

## Metadata-only rollback scripts

`backup-tasks.sh` and `restore-tasks.sh` are exact promotions of the independently reviewed rollout scripts. They are intended only for the existing metadata PostgreSQL pod and its existing data PVC. The caller must obtain explicit approval and independently verify application replicas are zero and the engine has no running or pending jobs before acknowledging `HOSPITAL_PLATFORM_FROZEN=YES`. Restore additionally requires explicit rollback approval acknowledged by `HOSPITAL_ROLLBACK_APPROVED=YES`; these variables are acknowledgments, not automated freeze or authorization checks.

Backup accepts a new 12-character lower-case hexadecimal release ID and purpose `pre-upgrade` or `before-rollback`. It exports only `public.entity_extension` records with `extension='hospital.integration.task'`, in a read-only repeatable-read transaction, into `/var/lib/postgresql/data/hospital-integration-upgrades/<release>/`. It refuses reuse, symlinks, an unexpected database, and more than 200 records; directories are private and archived files are mode 0600. The manifest records the scope, purpose, count, and SHA256.

Restore requires the original pre-upgrade release and a distinct new archive release. It verifies the original scope, purpose, hash, and bounded count, then archives current task records before attempting any restore. Its transaction rejects new `cdc`/`runtime` fields and checkpoint/fingerprint fields in saved runs, validates the old record shape, and restores only the exact task extension. Connections, secrets, engine checkpoints, and every other extension remain outside its SQL scope. These scripts have not been executed against a database as part of packaging verification.

## Offline guard checks

```powershell
./tools/hospital/oracle-cdc-release/test-build-inputs.ps1
# Also exercise JDK archive packaging and absent/empty/set environment restoration:
./tools/hospital/oracle-cdc-release/test-build-inputs.ps1 -CheckArchivePackaging -JarExecutable jar
# Verify absent/preexisting operator globals on successful and failing same-session runs:
./tools/hospital/oracle-cdc-release/test-build-inputs.ps1 -CheckSessionIsolation
```

The checks create isolated disposable Git fixtures under the ignored release cache and retain them for inspection. They accept the actual production basePath template and exercise malformed/mismatched revisions, absent/stale artifact proofs, fixture identity in names/raw/gzip/Brotli assets, malformed/truncated/oversized compression, output junctions before build tools, and tracked/untracked production changes. Raw ASCII byte matching ignores BOM prefixes, preserves case-insensitive matching across 64 KiB chunks, and runs alongside decoded-text matching for legitimate UTF16 assets. Creating test junctions may require an environment that permits reparse points. Build tools and Docker are replaced only at the external boundary, and every rejection must occur with zero build calls. Mocks and their closure state belong to the test script, preserving operator global functions and counters; an outer finally restores the exact prior existence and value of global LASTEXITCODE. The session-isolation check verifies absent and preexisting global bindings after successful and deliberately failing runs in isolated in-process PowerShell sessions. The optional archive check invokes the specified local JDK JAR tool on tiny fixture files and verifies three identical archives plus exact Docker builder environment restoration after a mocked build failure. No service, Docker process, container, or database is started.
