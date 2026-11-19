#!/usr/bin/env bash
# Canonical staff-web check: generated-client drift, strict types, lint and
# 100%-coverage unit tests over every hand-written source file.
# Synthetic fixtures only; no application endpoint is contacted.
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -x node_modules/.bin/vitest ]]; then
  printf 'staff-web: installing pinned dependencies with npm ci\n' >&2
  npm ci --no-audit --no-fund
fi

npm run gen:api:check
npm run typecheck
npm run lint
npm run test:coverage
