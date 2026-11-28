#!/usr/bin/env bash
# Fast merge gate for the swarm integrator. Checks cheap, deterministic things
# on the merge result: policy (shell, pack, docs, secrets, Python tests), Java
# compile and unit tests. Heavy checks (OWASP, SpotBugs, JaCoCo coverage and
# real-PostgreSQL integration) run in the integrator gate and in Delivery.


set -euo pipefail
cd "$(dirname "$0")/.."

files=(scripts/*.sh infra/bootstrap/start.sh infra/app/start.sh apps/api/check.sh db/check.sh scripts/quick-check.sh)
for file in "${files[@]}"; do bash -n "$file"; done
if command -v shellcheck >/dev/null; then shellcheck "${files[@]}"; fi

python3 -I scripts/project-name.py displayName >/dev/null
python3 -I scripts/project-name.py repositorySlug >/dev/null
python3 -I scripts/check-pack.py
python3 -I scripts/check-docs.py
python3 -I scripts/check-secrets.py
python3 -I scripts/check-names.py
python3 -I scripts/check-wiring.py
python3 -I scripts/check-constraints.py
python3 -I scripts/check-migrations.py --require-baseline
# The fast merge gate needs the same tracker preflight as check.sh: a single
# malformed pr: field otherwise aborts reconciliation after branches are pushed.
python3 -I scripts/swarm_gate.py validate-tasks --dir tasks
bash scripts/python-coverage.sh

# Compile and unit tests only. The no-security profile skips OWASP and SpotBugs;
# the fast profile excludes @Tag("integration") suites that need PostgreSQL.
# New DB tests must carry the tag to stay out of this DB-less gate.
mvn -q -B -f apps/api/pom.xml -Pno-security,fast test

printf 'quick check: policy, compile and unit tests passed\n'
