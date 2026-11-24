#!/usr/bin/env bash
set -euo pipefail
image=${1:?usage: smoke-image.sh IMAGE [VERSION]}
version=${2:-}
if [[ -n "$version" ]]; then
  test "$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.version"}}')" = "$version"
fi
container=$(docker run -d -p 127.0.0.1::8080 "$image")
trap 'docker rm -f "$container" >/dev/null' EXIT
address=$(docker port "$container" 8080/tcp)
wait_for_stack() {
  for ((attempt=0; attempt<30; attempt++)); do
    if body=$(curl -fsS --max-time 2 "http://$address/health.txt" 2>/dev/null) && [[ "$body" == 'orevia bootstrap ready' ]]; then
      docker exec "$container" psql -h 127.0.0.1 -U postgres -d orevia_test -Atqc 'SELECT 1' | grep -qx 1
      return 0
    fi
    sleep 1
  done
  docker logs "$container"
  return 1
}

wait_for_stack
docker exec "$container" psql -h 127.0.0.1 -U postgres -d orevia_test -qc 'CREATE TABLE restart_probe (id integer)'
docker restart "$container" >/dev/null
address=$(docker port "$container" 8080/tcp)
wait_for_stack
docker exec "$container" psql -h 127.0.0.1 -U postgres -d orevia_test -Atqc "SELECT to_regclass('public.restart_probe') IS NULL" | grep -qx t
