- [ ] COVPY-GATE · Script tests: switch the Python coverage gate from "changed lines" to "every script at 100%" once all scripts are covered
  - requirements: OPS-06
  - scope: scripts/python-coverage.sh, scripts/check.sh, scripts/quick-check.sh, scripts/tests/test_python_coverage_gate.py
  - depends: COVPY-CHECK-DOCS, COVPY-CHECK-MIGRATIONS, COVPY-CHECK-NAMES, COVPY-CHECK-PACK, COVPY-CHECK-SECRETS, COVPY-CHECK-WIRING, COVPY-GEN-OPENCODE, COVPY-PROJECT-NAME, COVPY-WORKER-GATE, COVPY-MONITOR-FETCH, COVPY-MONITOR-COMPOSE, COVPY-MONITOR-TUI, COVPY-MONITOR-CLI, COVPY-SWARM-PLAN, COVPY-SWARM-SCHEDULE, COVPY-SWARM-WORKERS, COVPY-SWARM-INTEGRATOR, COVPY-SWARM-CLI
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/python-coverage.sh`, `scripts/python_coverage.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: COVPY-SWARM-PLAN.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `scripts/tests/test_python_coverage_gate.py`.
    Route protected tooling edits through a maintainer.

    1. Read `scripts/python-coverage.sh`, `scripts/python_coverage.py`, `scripts/check.sh` and `scripts/quick-check.sh`. Today the gate (already active in both check scripts) fails a change only when a script line that the change adds is not covered; `--full` requires 100% for every script.
    2. Run `bash scripts/python-coverage.sh --full`. When it passes (every `COVPY-*` task is merged), change `scripts/check.sh` and `scripts/quick-check.sh` to call `bash scripts/python-coverage.sh --full` and make `REQUIRE_PY_COVERAGE=1` the default inside `python-coverage.sh`, so a missing `coverage` package fails instead of warning. If `--full` does not pass, list the remaining uncovered lines under `## Why this is not finished` and stop; do not weaken the gate.
    3. Write `scripts/tests/test_python_coverage_gate.py`: the script exits non-zero when a stub `coverage` reports a missed line in `--full` mode, exits zero at 100%, prints the install hint and exits non-zero when `coverage` is missing and `REQUIRE_PY_COVERAGE` is set, and falls back to plain unittest with a warning when it is not set.
    4. Run `bash scripts/check.sh` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

## Spec clause coverage: one task per sentence of the specification (new files only, no dependencies)

Every sentence of the requirement tables, the role matrix, the gate evidence, the cross-cutting scenarios and the architecture decisions in `SMARTCLINIC_V1_SPEC.md` has its own task. Each writes one test file (or only a note when the clause needs human, partner or cloud evidence) and one status note under `docs/spec/clauses`. A clause that is not built gets a skipped test marked `GAP:` and a gap note, so the next round of tasks is generated from these notes. The result is a measured, clause-by-clause view of how much of the specification is built.

