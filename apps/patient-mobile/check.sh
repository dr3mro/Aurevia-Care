#!/usr/bin/env bash
# Canonical patient-mobile gate: pinned install, typecheck, lint, Jest and
# enforced 100% coverage over every production source file.
set -euo pipefail
cd "$(dirname "$0")"

command -v npm >/dev/null || { printf 'npm is required to check patient-mobile\n' >&2; exit 1; }

if command -v shellcheck >/dev/null; then
  shellcheck check.sh docs.sh
else
  printf 'shellcheck not installed: skipping module shell lint\n' >&2
fi

# npm ci keeps the toolchain deterministic and fails on package/lockfile drift.
npm ci --no-audit --no-fund
npm run typecheck
npm run lint
npm run test:coverage -- --ci
