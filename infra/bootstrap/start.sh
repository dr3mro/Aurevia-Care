#!/usr/bin/env bash
set -euo pipefail
state=$(mktemp -d /tmp/aurevia.XXXXXXXX)
pg_pid=
web_pid=
# The cleanup function is invoked indirectly through the EXIT trap; newer
# ShellCheck versions misclassify its body as unreachable (SC2317).
# shellcheck disable=SC2317,SC2329
cleanup() {
    if [[ -n "$web_pid" ]]; then kill -TERM "$web_pid" 2>/dev/null || true; fi
    if [[ -n "$pg_pid" ]]; then kill -INT "$pg_pid" 2>/dev/null || true; fi
    wait 2>/dev/null || true
    rm -rf -- "$state"
}
trap cleanup EXIT
trap 'exit 0' TERM INT
initdb -D "$state/data" --username=postgres --auth=trust >/dev/null
postgres -D "$state/data" -h 127.0.0.1 -k "$state" &
pg_pid=$!
for ((attempt=0; attempt<30; attempt++)); do
    if pg_isready -q -h 127.0.0.1 -U postgres; then break; fi
    kill -0 "$pg_pid"
    sleep 1
done
createdb -h 127.0.0.1 -U postgres aurevia_test
nginx -c /opt/aurevia/nginx.conf -g 'daemon off;' &
web_pid=$!
wait -n "$pg_pid" "$web_pid"
exit 1
