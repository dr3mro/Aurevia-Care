#!/usr/bin/env bash
# Checked-in generator: derives client types from the authoritative contract.
# Never edit src/api/generated/openapi.d.ts by hand.
set -euo pipefail
cd "$(dirname "$0")/.."

spec=../../contracts/openapi.yaml
out=src/api/generated/openapi.d.ts
cli=node_modules/.bin/openapi-typescript

if [[ ! -x "$cli" ]]; then
  printf 'gen:api: dependencies missing; run npm ci in apps/staff-web\n' >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"

if [[ "${1:-}" == "--check" ]]; then
  "$cli" "$spec" --output "$out" --check
  printf 'gen:api: generated client matches the contract\n'
else
  "$cli" "$spec" --output "$out"
  printf 'gen:api: wrote %s\n' "$out"
fi
