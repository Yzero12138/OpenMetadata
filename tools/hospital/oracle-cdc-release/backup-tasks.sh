#!/bin/sh
# Run inside the existing metadata PostgreSQL pod only, after approved platform freeze.
set -eu
umask 077
fail() { printf '%s\n' "$1" >&2; exit 2; }
[ "${HOSPITAL_PLATFORM_FROZEN:-}" = YES ] || fail PLATFORM_FREEZE_REQUIRED
[ "$#" -eq 2 ] || fail RELEASE_AND_PURPOSE_REQUIRED
release=$1
purpose=$2
[ "${#release}" -eq 12 ] || fail STRICT_RELEASE_REQUIRED
case "$release" in *[!0-9a-f]*) fail STRICT_RELEASE_REQUIRED;; esac
case "$purpose" in pre-upgrade|before-rollback) ;; *) fail STRICT_PURPOSE_REQUIRED;; esac
[ "${POSTGRES_DB:-}" = openmetadata_db ] || fail EXACT_METADATA_DATABASE_REQUIRED
base=/var/lib/postgresql/data/hospital-integration-upgrades
[ ! -L "$base" ] || fail NO_BACKUP_SYMLINK_ALLOWED
if [ ! -d "$base" ]; then mkdir -m 700 "$base"; fi
[ "$(readlink -f "$base")" = "$base" ] || fail EXACT_PVC_BACKUP_PATH_REQUIRED
folder=$base/$release
mkdir -m 700 "$folder" || fail NEW_UPGRADE_DIRECTORY_REQUIRED
export_file=$folder/tasks.csv.pending
cat > "$folder/export.sql" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\\copy (SELECT id, extension, jsonschema, json FROM public.entity_extension WHERE extension='hospital.integration.task' ORDER BY id) TO '$export_file' WITH (FORMAT CSV, HEADER TRUE)
SELECT count(*) FROM public.entity_extension WHERE extension='hospital.integration.task';
COMMIT;
SQL
if ! records=$(psql -X -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f "$folder/export.sql" 2> "$folder/psql-error.log"); then
  fail METADATA_BACKUP_FAILED
fi
case "$records" in ''|*[!0-9]*) fail EXACT_RECORD_COUNT_REQUIRED;; esac
[ "$records" -le 200 ] || fail TASK_CAPACITY_REVIEW_REQUIRED
[ -f "$export_file" ] && [ ! -L "$export_file" ] || fail BACKUP_FILE_REQUIRED
mv "$export_file" "$folder/tasks.csv"
digest=$(sha256sum "$folder/tasks.csv" | cut -d ' ' -f 1)
printf 'scope=hospital.integration.task\nrelease=%s\npurpose=%s\nrecords=%s\nsha256=%s\n' "$release" "$purpose" "$records" "$digest" > "$folder/manifest.txt"
chmod 600 "$folder/tasks.csv" "$folder/manifest.txt" "$folder/export.sql"
printf 'BACKUP_COMPLETE release=%s purpose=%s records=%s sha256=%s\n' "$release" "$purpose" "$records" "$digest"
