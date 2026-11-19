#!/usr/bin/env bash
# TypeDoc hook for the developer reference.
# Usage: bash docs.sh <absolute-output-directory>
# Uses the locked local toolchain, fails on warnings and writes index.html.
set -euo pipefail
cd "$(dirname "$0")"

out=${1:-}
if [[ -z "$out" || "$out" != /* ]]; then
  printf 'docs.sh: expected an absolute output directory as $1\n' >&2
  exit 1
fi

if [[ ! -x node_modules/.bin/typedoc ]]; then
  printf 'docs.sh: dependencies missing; run npm ci in apps/staff-web\n' >&2
  exit 1
fi

mkdir -p "$out"
node_modules/.bin/typedoc --options typedoc.json --out "$out"
test -s "$out/index.html"
printf 'staff-web reference: %s/index.html\n' "$out"
