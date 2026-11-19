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

if [[ ! -x node_modules/.bin/typedoc ]]; then
  npm ci --no-audit --no-fund
fi

mkdir -p "$out"
./node_modules/.bin/typedoc --out "$out" --treatWarningsAsErrors
test -s "$out/index.html"
printf 'TypeDoc reference: %s/index.html\n' "$out"
