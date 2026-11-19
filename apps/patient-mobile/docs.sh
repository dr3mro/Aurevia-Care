#!/usr/bin/env bash
# TypeDoc hook for the developer reference. scripts/docs.sh calls this with an
# absolute output directory; the hook bootstraps the locked toolchain when CI
# has not installed it, fails on any TypeDoc warning and leaves index.html.
set -euo pipefail
cd "$(dirname "$0")"

out=${1:?usage: bash docs.sh <absolute-output-directory>}
case "$out" in
  /*) ;;
  *) printf 'docs.sh: output directory must be absolute: %s\n' "$out" >&2; exit 1 ;;
esac

mkdir -p "$out"

# No application source yet: keep the documented pending-module page instead of
# asking TypeDoc to resolve a missing entry point (CW-005 scaffold state).
if [[ ! -f src/index.ts ]]; then
  printf '<!doctype html><html lang="en"><meta charset="utf-8"><title>Reference pending</title><h1>Patient mobile reference pending</h1><p>Module implementation is pending.</p><p><a href="../index.html">Developer reference</a></p>\n' > "$out/index.html"
  printf 'TypeDoc reference pending: %s/index.html\n' "$out"
  exit 0
fi

if [[ ! -x node_modules/.bin/typedoc ]]; then
  npm ci --no-audit --no-fund
fi
./node_modules/.bin/typedoc --out "$out" --treatWarningsAsErrors
test -s "$out/index.html"
printf 'TypeDoc reference: %s/index.html\n' "$out"
