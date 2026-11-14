#!/usr/bin/env bash
set -euo pipefail

role="${1:?Specify fe or be}"
case "$role" in
  fe|be) ;;
  *) echo 'Unsupported Doris role.' >&2; exit 1 ;;
esac

if ! ulimit -n 65535; then
  echo 'Doris requires a container hard nofile limit >= 65535.' >&2
  exit 1
fi

export SKIP_CHECK_ULIMIT=false
unset http_proxy HTTP_PROXY https_proxy HTTPS_PROXY
mkdir -p /tmp/doris
"/opt/apache-doris/$role/bin/start_$role.sh" --console &
launcher=$!

terminate() {
  trap '' TERM INT
  mapfile -t children < <(pgrep -P "$launcher" || true)
  if (( ${#children[@]} )); then
    kill -TERM "${children[@]}" 2>/dev/null || true
  else
    kill -TERM "$launcher" 2>/dev/null || true
  fi
  wait "$launcher" || true
  exit 143
}

trap terminate TERM INT
wait "$launcher"
