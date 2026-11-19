#!/usr/bin/env bash
# Fast merge gate for the swarm integrator. Checks cheap, deterministic things
# on the merge result: policy (shell, pack, docs, secrets, Python tests), Java
# compile and unit tests. Heavy checks (OWASP, SpotBugs, JaCoCo coverage and
# real-PostgreSQL integration) run once per batch on main in heavy-check.yml.
set -euo pipefail
cd "$(dirname "$0")/.."

files=(scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh scripts/quick-check.sh)
for file in "${files[@]}"; do bash -n "$file"; done
if command -v shellcheck >/dev/null; then shellcheck "${files[@]}"; fi

python3 -I scripts/project-name.py displayName >/dev/null
python3 -I scripts/project-name.py repositorySlug >/dev/null
python3 -I scripts/check-pack.py
python3 -I scripts/check-docs.py
python3 -I scripts/check-secrets.py
python3 -I scripts/check-migrations.py --require-baseline
python3 -I -m unittest discover -s scripts/tests -q

# Compile and unit tests only. The no-security profile skips OWASP and SpotBugs;
# the test phase does not run JaCoCo's verify-phase coverage check, and the
# *IsolationTest integration tests need PostgreSQL, so they are excluded here.
mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='!*IsolationTest' test

printf 'quick check: policy, compile and unit tests passed\n'
