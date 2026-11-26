- [x] SWARM-VAR-CONTRACT · Unify boolean repository-variable semantics
  - agent: orca
  - pr: none — maintainer-applied commits on `main`: `3321a422b68e08ae85e2bb7f8134eded41e6c8a3`, `eb447e7be58523f3591a08ec69dcdb1a9995f365`, `88243ec24a5a77ceb4306abc4686e8ab035627d9`, `d7feb2e186ac3c884a701005b8be1abda870ab67` (slot-fill polarity)
  - Tested revision: 9991b5ea239c36f669877b9a9214d4b4af6f98c8
  - scope: scripts/swarm_flags.py, scripts/swarm.py, scripts/tests/test_swarm_flags.py, scripts/tests/test_workflow_variable_contract.py, scripts/tests/test_swarm_kill_switch.py, .github/workflows/swarm.yml, .github/workflows/swarm-worker.yml, .github/workflows/swarm-issue-monitor.yml, .github/workflows/daily-notes.yml, .github/workflows/swarm-flags.yml, docs/SWARM.md, docs/reference/testing-guide.md
  - evidence:
    - Skills loaded (tool): `aurevia-delivery` (every story); no additional skill matched — this is Python tooling, workflow gates and operator docs, not a named domain slice. Concurrent specialists: code-reviewer, coverage-auditor, devsecops and qa-reviewer, all read-only.
    - Plan approved by aurevia-plan-reviewer (VERDICT: GO after re-review). No repository variables were changed.
    - Red first (tool): `/opt/homebrew/bin/python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_flags.py' -q` → `Ran 16 tests ... FAILED (errors=4)`, first error `AttributeError: module 'swarm_flags' has no attribute 'main'`; `... -p 'test_workflow_variable_contract.py' -q` → `Ran 1 test ... FAILED (errors=1)`, `AttributeError: module 'swarm_flags' has no attribute 'FLAG_SPECS'`. Neither test could import the intended API before implementation.
    - Green targeted (tool, `/opt/homebrew/bin/python3 -I -m unittest discover -s scripts/tests -p '<file>' -q`): test_swarm_flags.py `Ran 17 tests ... OK`; test_workflow_variable_contract.py `Ran 12 tests ... OK`; test_swarm_kill_switch.py `Ran 13 tests ... OK`; test_workflow_concurrency.py `Ran 14 tests ... OK`; test_swarm.py `Ran 365 tests ... OK` (existing workflow-graph assertions left unchanged).
    - Full Python suite (tool): `/opt/homebrew/bin/python3 -I -m unittest discover -s scripts/tests -q` → rc 0, `Ran 2010 tests in 190.533s`.
    - Coverage (tool): `REQUIRE_PY_COVERAGE=1 PATH="/opt/homebrew/bin:$PATH" COVERAGE_BASE=HEAD bash scripts/python-coverage.sh` → rc 0, `Ran 2010 tests in 211.682s`, final line `python coverage: every changed script line is covered`. No threshold, exclusion, pragma, skip or suppression was added.
    - Static (tool, each rc 0): `python3 -I scripts/check-docs.py`, `check-names.py`, `check-wiring.py`, `check-secrets.py`, `check-constraints.py`, `check-ownership.py`; `actionlint -ignore 'unexpected key "queue" for "concurrency" section' .github/workflows/*.yml`; `python3 -m py_compile` on changed Python files.
    - Reviewer evidence: first pass returned four NO-GO verdicts converging on padding parity; user chose "Add normalization jobs". All Critical/Required findings were resolved before the confirmations below. No simulated-user or human-participant feedback was used for this story.
    - `SWARM_ALLOW_PROTECTED` unchanged (tool): `git show 743e0b03:scripts/swarm.py` shows the old matcher was `.strip().lower() in {1,true,yes,on}`; the shared helper accepts exactly the same four enable tokens with the same normalization. The switch is excluded from `FLAG_SPECS` and remains Python-only.
    - Protected-file governance (tool + user decision): the protected workflow and script edits were applied directly on `main` by concurrent maintainer-applied swarm commits listed in `pr:` above, satisfying the maintainer-applied path. No `SWARM_ALLOW_PROTECTED` repository variable was changed. `gh run list --commit 88243ec24a5a77ceb4306abc4686e8ab035627d9` shows Delivery `cancelled` (superseded push) while Agent swarm and developer reference are `success`; the `flags / Resolve canonical flags` check is `success` on that revision. The owner explicitly accepted this caveat and authorized marking the task done without an exact-SHA Delivery green verdict.
  - details:
    Canonical lowercase value tokens: enable {1,true,yes,on}, disable {0,false,no,off}, absent/unknown/empty uses the documented default. `scripts/swarm_flags.py` is the single source of truth for tokens, defaults and case/padding normalization. A new reusable `swarm-flags.yml` job runs that helper once and emits canonical true/false outputs; `swarm.yml`, `swarm-worker.yml`, `swarm-issue-monitor.yml` and `daily-notes.yml` gate on those outputs, so a padded or mixed-case value cannot satisfy Python but bypass a workflow gate. `select` is named `Select task (PO)` and uses `aurevia-product-owner`; no scrum-master agent was added. Dead `SWARM_TASK_FILES` doc line removed; `REQUIRE_PY_COVERAGE` documented as env-only. Failing tests were written first; 100% changed-line coverage is proven above.

## Coverage

- Command and result: `REQUIRE_PY_COVERAGE=1 COVERAGE_BASE=HEAD bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`.
- `FLAG_SPECS` initialization: `test_shared_flag_specs_are_complete_and_canonical`, `test_github_outputs_uses_documented_defaults`.
- `github_outputs` loop and its five enable/disable/default branches: `test_github_outputs_normalises_every_variable` (padded ` Off `, ` YES `, ` On `, `FALSE`, `1`) and `test_github_outputs_uses_documented_defaults` (empty environ → documented defaults for all five flags).
- `main` guard `if not path` false side, `open(..., "a")` and helper call: `test_main_writes_canonical_flags_to_github_output` (also proves append semantics with a sentinel line).
- `main` guard `if not path` true side and `SystemExit`: `test_main_requires_github_output`.
- `__main__` true arc: `test_running_the_script_writes_canonical_flags` via `runpy.run_path(..., run_name="__main__")`.
- Unchanged `enabled`/`disabled`/`_token` helpers and the four refactored `swarm.py` call sites keep their original named tests; mixed-case acceptance is locked by the four added cases in `test_swarm_kill_switch.py`.
- Workflow gates (YAML, no Python coverage metric): `test_workflow_variable_contract.py` asserts the flags outputs, every `if:`/`run:` site is free of raw boolean variables, kill/plan/wait/slot-fill/monitor/notes polarity, the canonical worker input and the plan-output forwarding; `test_workflow_concurrency.py` asserts the new resolver's per-run group.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-qa-reviewer: GO

## Findings

- Delivery caveat (tool + owner decision): Delivery CI was cancelled as a superseded push on each commit that carried this change, so no exact-SHA Delivery green verdict exists. `flags / Resolve canonical flags`, `Select task (PO)` and `Plan the wave` succeeded on `88243ec24a5a77ceb4306abc4686e8ab035627d9` (Agent swarm run 45759841712). The owner chose to accept maintainer-applied commits on `main` and mark the story done with this caveat recorded, rather than dispatch another Delivery run.
- A concurrent session applied most of this work to `main` while the review loop was running; the final refinements (per-run concurrency group, plan-output forwarding, fail-closed slot fill) were staged here and were subsequently committed by `88243ec2` and `d7feb2e1`. No repository variables were changed by any of these commits.
- The run-block contract check deliberately asserts the absence of `contains(fromJSON(...), vars|env.SWARM_*)` and `case "${SWARM_*"` patterns rather than every textual mention; canonical `${SWARM_ENABLED:-false}` reads from the flags job remain allowed.

## Lessons for workers

- A reusable flags job must have a per-run `concurrency` block or `test_workflow_concurrency.py` fails; forwarding a resolved flag through an existing job output preserves the workflow graph and avoids rewriting long-standing `needs` assertions.
- Every new `if __name__ == "__main__":` entry point needs a `runpy.run_path(..., run_name="__main__")` test or the changed-line branch gate fails.
