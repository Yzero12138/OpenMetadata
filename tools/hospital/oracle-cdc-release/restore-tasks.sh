#!/bin/sh
# Restores only the reviewed old-format extension after an approved, idle platform freeze.
# Caller must independently verify app replicas=0 and zero running/pending engine jobs.
set -eu
umask 077
fail() { printf '%s\n' "$1" >&2; exit 2; }
[ "${HOSPITAL_PLATFORM_FROZEN:-}" = YES ] || fail PLATFORM_FREEZE_REQUIRED
[ "${HOSPITAL_ROLLBACK_APPROVED:-}" = YES ] || fail EXPLICIT_ROLLBACK_REQUIRED
[ "$#" -eq 2 ] || fail ORIGINAL_AND_ARCHIVE_RELEASE_REQUIRED
original=$1
archive=$2
for release in "$original" "$archive"; do
  [ "${#release}" -eq 12 ] || fail STRICT_RELEASE_REQUIRED
  case "$release" in *[!0-9a-f]*) fail STRICT_RELEASE_REQUIRED;; esac
done
[ "$original" != "$archive" ] || fail DISTINCT_ARCHIVE_REQUIRED
[ "${POSTGRES_DB:-}" = openmetadata_db ] || fail EXACT_METADATA_DATABASE_REQUIRED
base=/var/lib/postgresql/data/hospital-integration-upgrades
[ ! -L "$base" ] && [ "$(readlink -f "$base")" = "$base" ] || fail EXACT_PVC_BACKUP_PATH_REQUIRED
folder=$base/$original
[ -d "$folder" ] && [ ! -L "$folder" ] || fail ORIGINAL_SNAPSHOT_REQUIRED
[ -f "$folder/manifest.txt" ] && [ ! -L "$folder/manifest.txt" ] || fail SNAPSHOT_MANIFEST_REQUIRED
grep -qx 'scope=hospital.integration.task' "$folder/manifest.txt" || fail EXACT_EXTENSION_REQUIRED
grep -qx "release=$original" "$folder/manifest.txt" || fail RELEASE_MISMATCH
grep -qx 'purpose=pre-upgrade' "$folder/manifest.txt" || fail PRE_UPGRADE_SNAPSHOT_REQUIRED
records=$(sed -n 's/^records=//p' "$folder/manifest.txt")
digest=$(sed -n 's/^sha256=//p' "$folder/manifest.txt")
case "$records" in ''|*[!0-9]*) fail EXACT_RECORD_COUNT_REQUIRED;; esac
[ "$records" -le 200 ] || fail TASK_CAPACITY_REVIEW_REQUIRED
[ "${#digest}" -eq 64 ] || fail EXACT_SHA_REQUIRED
case "$digest" in *[!0-9a-f]*) fail EXACT_SHA_REQUIRED;; esac
[ -f "$folder/tasks.csv" ] && [ ! -L "$folder/tasks.csv" ] || fail SNAPSHOT_FILE_REQUIRED
[ "$(sha256sum "$folder/tasks.csv" | cut -d ' ' -f 1)" = "$digest" ] || fail SNAPSHOT_HASH_MISMATCH
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sh "$script_dir/backup-tasks.sh" "$archive" before-rollback
archive_folder=$base/$archive
[ -f "$archive_folder/manifest.txt" ] && [ -f "$archive_folder/tasks.csv" ] || fail CURRENT_RECORD_ARCHIVE_REQUIRED
cat > "$archive_folder/restore.sql" <<SQL
BEGIN;
CREATE TEMP TABLE hospital_task_restore AS SELECT id, extension, jsonschema, json FROM public.entity_extension WITH NO DATA;
\\copy hospital_task_restore(id, extension, jsonschema, json) FROM '$folder/tasks.csv' WITH (FORMAT CSV, HEADER TRUE)
DO \$guard\$
BEGIN
  IF (SELECT count(*) FROM hospital_task_restore) <> $records THEN
    RAISE EXCEPTION 'SNAPSHOT_COUNT_MISMATCH';
  END IF;
  IF EXISTS (SELECT 1 FROM hospital_task_restore WHERE extension IS DISTINCT FROM 'hospital.integration.task'
      OR jsonschema IS DISTINCT FROM 'hospitalIntegrationTask' OR json IS NULL
      OR jsonb_typeof(json::jsonb) <> 'object'
      OR json::jsonb ?| ARRAY['cdc','runtime']
      OR COALESCE(json::jsonb->'latestRun','{}'::jsonb) ?| ARRAY['checkpoints','checkpointsCheckedAt','configurationFingerprint']
      OR jsonb_typeof(json::jsonb->'runs') IS DISTINCT FROM 'array') THEN
    RAISE EXCEPTION 'OLD_FORMAT_COMPATIBILITY_REQUIRED';
  END IF;
  IF EXISTS (SELECT 1 FROM hospital_task_restore t, LATERAL jsonb_array_elements(t.json::jsonb->'runs') r
      WHERE r ?| ARRAY['checkpoints','checkpointsCheckedAt','configurationFingerprint']) THEN
    RAISE EXCEPTION 'OLD_RUN_FORMAT_REQUIRED';
  END IF;
END \$guard\$;
DELETE FROM public.entity_extension WHERE extension='hospital.integration.task';
INSERT INTO public.entity_extension(id,extension,jsonschema,json) SELECT id,extension,jsonschema,json FROM hospital_task_restore;
SELECT count(*) FROM public.entity_extension WHERE extension='hospital.integration.task';
COMMIT;
SQL
if ! restored=$(psql -X -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f "$archive_folder/restore.sql" 2> "$archive_folder/psql-restore-error.log"); then
  fail METADATA_RESTORE_FAILED
fi
[ "$restored" = "$records" ] || fail RESTORE_COUNT_MISMATCH
chmod 600 "$archive_folder/restore.sql"
printf 'RESTORE_COMPLETE original=%s retained_current=%s records=%s\n' "$original" "$archive" "$restored"
