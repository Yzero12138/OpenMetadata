#!/usr/bin/env bash
set -euo pipefail

role="${1:?Specify fe or be}"
case "$role" in
  fe) heap=2048m ;;
  be)
    heap=512m
    if (( $(cat /proc/sys/vm/max_map_count) < 2000000 )); then
      echo 'Doris BE requires host vm.max_map_count >= 2000000; no host setting was changed.' >&2
      exit 1
    fi
    ;;
  *) echo 'Unsupported Doris role.' >&2; exit 1 ;;
esac

if [[ ! -w /var/lib/doris ]]; then
  echo 'The dedicated Doris PVC must be writable by uid/gid 1000; no root init container is used.' >&2
  exit 1
fi

cp -R "/opt/apache-doris/$role/conf/." /work/conf/
sed -i -E 's|^[[:space:]]*LOG_DIR[[:space:]]*=.*|LOG_DIR = /var/log/doris|' "/work/conf/$role.conf"
sed -i -E "/^[[:space:]]*JAVA_OPTS/ { s/-Xmx[0-9]+[mMgG]/-Xmx$heap/g; s/-Xms[0-9]+[mMgG]/-Xms256m/g; }" "/work/conf/$role.conf"
printf '\n' >> "/work/conf/$role.conf"
cat "/config/$role-extra.conf" >> "/work/conf/$role.conf"

if [[ "$role" == fe ]]; then
  root_hash="$(cat /run/secrets/bootstrap/initial-root-password)"
  if [[ ! "$root_hash" =~ ^\*[0-9A-F]{40}$ ]]; then
    echo 'The bootstrap Secret must contain a valid Doris initial root password hash.' >&2
    exit 1
  fi
  printf '\ninitial_root_password = %s\n' "$root_hash" >> /work/conf/fe.conf
  unset root_hash
  mkdir -p /var/lib/doris/{meta,plugins,jdbc_drivers,small_files}
else
  mkdir -p /var/lib/doris/{storage,user_functions,small_files,jdbc_drivers,spill}
fi
