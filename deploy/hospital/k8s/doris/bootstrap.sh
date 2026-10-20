#!/usr/bin/env bash
set -euo pipefail

namespace="${POD_NAMESPACE:?POD_NAMESPACE is required}"
be_host="hospital-doris-be-0.hospital-doris-be-headless.$namespace.svc.cluster.local"
writer="$(cat /run/secrets/writer/username)"
writer_hash="$(cat /run/secrets/writer/password-hash)"
if [[ "$writer" != hospital_ods_writer || ! "$writer_hash" =~ ^\*[0-9A-F]{40}$ ]]; then
  echo 'Unexpected scoped writer identity or password hash.' >&2
  exit 1
fi

admin=(mysql --defaults-extra-file=/run/secrets/bootstrap/client.cnf --connect-timeout=5 --batch --skip-column-names)
writer_client=(mysql --defaults-extra-file=/run/secrets/writer/client.cnf --connect-timeout=5 --batch --skip-column-names)
error_file="$(mktemp /tmp/doris-bootstrap.XXXXXX)"
trap 'rm -f "$error_file"' EXIT

if ! "${admin[@]}" --execute='SHOW DATABASES' >/dev/null 2>"$error_file"; then
  echo 'Bootstrap authentication failed; retain the existing Secret and FE PVC and investigate without printing credentials.' >&2
  exit 1
fi

getent hosts "$be_host" >/dev/null
backend_state() {
  "${admin[@]}" --column-names --execute='SHOW BACKENDS' 2>"$error_file" |
    awk -F '\t' -v host="$be_host" '
      NR == 1 { for (i = 1; i <= NF; i++) { if ($i == "Host") h = i; if ($i == "Alive") a = i } }
      NR > 1 && h && a && $h == host { print $a; found = 1 }
      END { if (!found) print "MISSING" }
    '
}
registered="$(backend_state)"
if [[ "$registered" == MISSING ]]; then
  if ! printf 'ALTER SYSTEM ADD BACKEND "%s:9050";\n' "$be_host" | "${admin[@]}" >/dev/null 2>"$error_file"; then
    echo 'BE registration failed; inspect node health and FQDN resolution.' >&2
    exit 1
  fi
fi

if ! "${admin[@]}" >/dev/null 2>"$error_file" <<SQL
CREATE DATABASE IF NOT EXISTS ods_validation PROPERTIES ("replication_allocation" = "tag.location.default:1");
CREATE USER IF NOT EXISTS '$writer'@'%' IDENTIFIED BY PASSWORD '$writer_hash';
GRANT SELECT_PRIV, LOAD_PRIV, CREATE_PRIV ON internal.ods_validation.* TO '$writer'@'%';
SQL
then
  echo 'Doris database or scoped writer initialization failed; no credentials were printed.' >&2
  exit 1
fi
unset writer_hash

if ! "${writer_client[@]}" --database=ods_validation --execute='SHOW TABLES' >/dev/null 2>"$error_file"; then
  echo 'Scoped writer authentication failed; the existing user was not automatically reset.' >&2
  exit 1
fi

for attempt in {1..60}; do
  alive="$(backend_state)"
  if [[ "$alive" == true ]]; then
    "${admin[@]}" --execute="SELECT @@version_comment; SELECT Host, Alive FROM backends() WHERE Host = '$be_host';"
    echo 'ods_validation is ready; hospital_ods_writer is scoped to SELECT, LOAD and CREATE in that database.'
    exit 0
  fi
  sleep 5
done
echo 'BE did not become Alive within 5 minutes; no tables or source data were loaded.' >&2
exit 1
