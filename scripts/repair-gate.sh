#!/usr/bin/env bash
# Merge gate for repair pull requests (FIX- and DLV- tasks) in the swarm integrator.
#
# While main is red, the full gate (scripts/check.sh) fails every pull request on
# test classes that other repair tasks still have to fix, so no repair could ever
# land. A repair is judged instead on the classes its task names: the quick gate,
# then the PostgreSQL integration tier through scripts/worker_gate.py (STORY_ID and
# STORY_BODY come from the task), then the database policy checks and the developer
# reference. OWASP, SpotBugs and JaCoCo are skipped here; Delivery runs the full
# tier on main right after the merge, and a red result becomes the next repair.
set -euo pipefail
cd "$(dirname "$0")/.."

# The integrator job has a PostgreSQL service. Never pass without running the tier.
export REQUIRE_POSTGRES=1
bash scripts/worker-check.sh
bash scripts/docs.sh
