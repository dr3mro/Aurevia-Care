#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

scripts=(scripts/*.sh infra/bootstrap/start.sh infra/app/start.sh)
for file in "${scripts[@]}"; do bash -n "$file"; done
if command -v shellcheck >/dev/null; then
  shellcheck "${scripts[@]}"
else
  printf 'shellcheck not installed: skipping shell lint (CI runs it)\n' >&2
fi

# -I keeps the working directory and user site out of the import path.
python3 -I scripts/project-name.py displayName >/dev/null
python3 -I scripts/project-name.py repositorySlug >/dev/null
python3 -I scripts/check-pack.py
python3 -I scripts/check-docs.py
python3 -I scripts/check-secrets.py
python3 -I scripts/check-names.py
python3 -I scripts/check-wiring.py
python3 -I scripts/check-constraints.py
python3 -I scripts/check-migrations.py --require-baseline
bash scripts/python-coverage.sh
bash scripts/frontend-check.sh

for module in apps/api apps/staff-web apps/patient-mobile; do
  if [[ -f "$module/check.sh" ]]; then
    (cd "$module" && bash check.sh)
  elif find "$module" -type f ! -name README.md ! -name .gitkeep -print | awk 'END { exit NR == 0 }'; then
    printf 'Missing test and coverage entry point: %s/check.sh\n' "$module" >&2
    exit 1
  else
    printf '%s: implementation pending\n' "$module"
  fi
done
if find db/migrations -type f -name '*.sql' -print | awk 'END { exit NR == 0 }'; then
  if [[ ! -f db/check.sh ]]; then
    printf 'Missing PostgreSQL migration and policy check: db/check.sh\n' >&2
    exit 1
  fi
  (cd db && bash check.sh)
fi
