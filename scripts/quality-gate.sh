#!/usr/bin/env bash
# Quality gate for a finished worker task, run by agent-run.sh after the coverage and
# typecheck gates. It rejects the two shortcuts a gate cannot see by itself:
#   1. a change that switches a check off (a suppression, an exclusion, a skipped test or a
#      lowered threshold: scripts/check-constraints.py), and
#   2. a task whose read-only reviewers did not all answer GO in the notes file
#      (scripts/check-verdicts.py), and
#   3. a note whose canonical tested revision cannot be read (swarm_gate.py
#      validate-note), which would fail the swarm gate after publication.
#   All three checks are mandatory on every run.
# Usage: quality-gate.sh BASE_SHA NOTES_FILE
set -euo pipefail
cd "$(dirname "$0")/.."
base="${1:?usage: quality-gate.sh BASE_SHA NOTES_FILE}"
notes="${2:?usage: quality-gate.sh BASE_SHA NOTES_FILE}"

# A trailing explanation on the canonical line used to hide a valid revision
# from the swarm gate. Catch that shape here, while the worker can still rebind
# the line; this is offline validation and never proves ancestry or a check.
python3 -I scripts/swarm_gate.py validate-note --note-path "$notes"

python3 -I scripts/check-constraints.py --base "$base" --require-base
python3 -I scripts/check-verdicts.py "$notes" --base "$base" --require "aurevia-code-reviewer,aurevia-coverage-auditor,aurevia-devsecops,aurevia-qa-reviewer"
