# FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS — integrator tests and the guard_blockers seam

## Summary

The story asked to move integrator test fixtures to the `guard_blockers` seam so the full Python suite turns green. On the delivered base (`origin/main` at `5cedda1f`) that premise is obsolete: `integrate_prepare()` calls `guard(pr, is_repair(pr), report=False)` (`scripts/swarm.py:6007`), and `guard()` itself calls `guard_blockers()` and returns its `(kind, files, lines)` tuple for `report=False` (`scripts/swarm.py:5488-5498`). The four named conflict/branch-moved scenarios already pass and the analogous already-integrated-PR test is `test_an_already_integrated_pr_is_closed_and_task_reopened`, not the name in the story text.

One in-scope failure remained: `test_integrate_publish_rejection_restores_tasks_and_returns_nonzero` let a protected-main push rejection call `add_labels()` → real `gh api`, causing `CalledProcessError`. The fixture now mocks the `gh` boundary and the `emit` sink, mirroring its passing sibling at `test_swarm_integrator_coverage.py:565-591`, and asserts the human-notice and failure emission. No production code changed.

**full suite not green — in-scope tests only.** The 14 remaining failures are in out-of-scope files and are environmental in this cloud worker; they are itemised below. Task status and the full gate belong to the integrator.

## Affected requirements

OPS-06 — CI policy checks and synthetic test fixtures. No requirement behavior changed; this repair restores an isolated, network-free tooling test.

## Risk class

Routine (tooling test fixture only). No production, contract, migration, deployment or clinical surface changed.

## Review owner

Swarm worker `orca` on branch `swarm/FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS-1556`; read-only reviewers listed under `## Review verdicts`.

## Data changes

None. No migration, backfill, lock or volume impact; nothing to roll back.

## Authorization boundaries and audit events

None added or changed. The fixture keeps trusted GitHub writes (label, comment, issue) inside the mock; no token is read, logged or committed. Only synthetic PR 7 / CW-001 / temporary repositories are used.

## Migration and rollout considerations

None. Test-fixture-only change; no rollout step.

## Test evidence

Entry point → test trace: `integrate_publish()` (`scripts/swarm.py:6351`) → `integrate_finalize()` (`:6193`) → protected-main rejection branch (`:6253-6276`) → `add_labels`/`comment_best_effort`/`report_pr_stuck`/`emit` → `test_integrate_publish_rejection_restores_tasks_and_returns_nonzero` (`scripts/tests/test_swarm_integrator_coverage.py:593`).

Red (before the change), tool:
- `cd scripts/tests && python3 -m unittest -q -k test_integrate_publish_rejection_restores_tasks_and_returns_nonzero test_swarm test_swarm_integrator_coverage test_swarm_delivery_state` → `Ran 5 tests in 0.302s / FAILED (errors=1)`; traceback ended in `subprocess.CalledProcessError: Command '('api', '--method', 'POST', 'repos/dr3mro/Aurevia-Care/issues/7/labels', '-f', 'labels[]=needs-human')' returned non-zero exit status 4.` from `add_labels` at `scripts/swarm.py:6265`.
- `python3 -I -m unittest discover -s scripts/tests -q` → `EXIT=1`, `Ran 1681 tests in 49.153s / FAILED (failures=10, errors=5, skipped=1)`; the extra error was the in-scope one above.

Green (after the change), tool:
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py' -q -k test_integrate_publish_rejection_restores_tasks_and_returns_nonzero` → `Ran 1 test in 0.003s / OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm*.py' -q` → `EXIT=0`, `Ran 749 tests in 8.145s / OK (skipped=1)` (`skipped=1` pre-existing).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_delivery_workflow_gates.py' -q` → `Ran 15 tests in 0.240s / OK`.
- `python3 -I -m unittest discover -s scripts/tests -q` → `EXIT=1`, `Ran 1681 tests in 47.720s / FAILED (failures=10, errors=4, skipped=1)` — the in-scope error is gone; all 14 remaining are out-of-scope environmental failures listed under `## Findings`.

Policy checks, tool (exit code and final line):
- `python3 -I scripts/check-docs.py` → EXIT=0 (no output).
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → EXIT=0 (no output).
- `python3 -I scripts/check-secrets.py` → EXIT=0 (no output).
- `python3 -I scripts/check-names.py` → EXIT=0 (no output).
- `python3 -I scripts/gen-opencode.py --check` → EXIT=1, `.opencode/opencode.json is out of date: run scripts/gen-opencode.py`. Confirmed environmental: `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json` (CI profile installed under skip-worktree) and `git show HEAD:.opencode/opencode.json` parses as valid JSON. Per AGENTS.md the committed local profile is correct; no `.opencode/` file was edited.
- `git merge --no-edit origin/main` → `Already up to date.` at the start of the task.

`scripts/check.sh` was deliberately not run: per AGENTS.md it delegates to `apps/api/check.sh` (full `mvn verify`) and is reserved for the integrator; no Java file was touched. No reviewer, simulated user or human provided the test evidence above — it is all executed tool output.

## Coverage

- No production line, branch, method or class was added or changed; there are no new production branches to name. The change is confined to one test module.
- `bash scripts/python-coverage.sh` → EXIT=1 because it runs the full, still-red suite under coverage: final line `FAILED (failures=10, errors=4, skipped=1)` from the out-of-scope tests below. No production coverage was measured as failing.
- Scoped equivalent that does measure the changed-line gate: `COVERAGE_FILE=/tmp/opencode/scoped.coverage python3 -m coverage erase && python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -p 'test_swarm*.py' -q && python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/opencode/scoped.json && python3 -I scripts/python_coverage.py /tmp/opencode/scoped.json --base origin/main` → `python coverage: every changed script line is covered`.
- Path added in the fixture and the assertion that takes it: the protected-main push rejection reporting path (`emit("merged", 0)`, `needs-human` label) is asserted by `emit.assert_any_call("merged", 0)` and `any("needs-human" in str(arg) ...)` in the same test. The original `write.assert_called_once_with("", "before\n")` and `dispatch.assert_not_called()` are preserved unchanged. No threshold, exclusion, pragma or skip was added.

## Clinical and architecture review

Not required: tooling test fixture only, no patient data, clinical workflow, patient identity, release of information or tenant boundary.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

All four are read-only reviewer verdicts; no Critical or Required finding remained. Optional suggestions (tighten the needs-human call-shape assertion; comment wording about issue creation) do not block and were left unchanged to avoid churn after a green review.

## Findings

- The brief/story premise no longer reproduces on this base. `scripts/swarm.py:6007` uses `guard(..., report=False)`, and `guard()` delegates to `guard_blockers()` (`:5496`); existing fixtures patch `swarm.guard`, which is the seam actually called. The original 15 stale-seam failures from `17a9b1c8` were fixed upstream by commit `e63cad04`. I did not re-point fixtures to `guard_blockers` because that would bypass the reporting seam the production code really uses. The brief named only the `delivery` skill; `.opencode/setup/AGENTS.md` and `tasks/_head.md` also match `aurevia-senior-python` for work under `scripts/`, so that skill was loaded.
- Full suite remains red from 14 out-of-scope failures (tool, `python3 -I -m unittest discover -s scripts/tests -q` → `FAILED (failures=10, errors=4, skipped=1)`). All are in files outside the task scope; none was edited:
  - 7 × `test_check_pack.py` (`test_check_mode_detects_drift`, `test_cli_entry_point`, `test_main_reports_success`, `test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`, `test_system_namespace_skill_is_not_a_project_skill`): final line `.opencode/opencode.json is stale: run scripts/gen-opencode.py`; AGENTS.md known cloud-worker false positive (CI profile under skip-worktree, `S` flag confirmed).
  - 5 × `test_agent_run.py` (4 errors `test_publish_rejects_when_head_is_not_the_recorded_branch` / `test_publish_rejects_wrong_task_or_run_branch` in `AgentRunTests` and `DirectModelEnvironmentTests`; 1 failure `test_daily_review_success_emits_ok` role `devsecops`): `FileNotFoundError: .../gh.log` in a temp git fixture and `refusing to run in a dirty workspace` — sandbox fixture artifacts.
  - 2 × `test_hermetic_env.py` (`test_agent_runner_restores_config_and_auth_on_signals` with signal `INT` and `TERM`): final line `AssertionError: 1 != 130`/`1 != 143` with `ps: command not found` and `kill: ... not a pid` — sandbox lacks `ps`/`kill`.
- Running `cd scripts/tests && python3 -m unittest -q test_swarm ...` (wrong cwd) made `test_open_spec_tasks_are_dispatchable` fail with `0 not greater than or equal to 1` because `repo_tasks()` resolved a different path; from the repo root under `discover` it passes. No change was made for that cwd artifact.
- `tasks/01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS.md` is still `[-]` with 17 attempts and completion evidence tied to an older main. A swarm worker must not edit `tasks/`; the integrator owns reconciliation and the `[x]` mark.
- Memory note `MEMORY.md` line 7 records an owner directive not to run Git-using scripts or tests. This worker prompt explicitly requires `git merge --no-edit origin/main`, a scoped commit, and running the Python tests/coverage, so those were executed as the more specific authorization; the conflict is recorded here rather than silently resolved. No push, tag, `gh` call or destructive Git operation was performed.

## Lessons for workers

- Check the actual seam in the current source before trusting a story's traceback. Upstream commits can move the call from `guard_blockers()` back to `guard(report=False)`, which makes "patch the new seam" instructions stale; run the named tests first and fix only what is red.
- Mirror the passing sibling test when a fixture omits a trusted-boundary mock; add assertions for the reporting path instead of only silencing the exception, so the failure branch stays proven.
- In this cloud image a red `gen-opencode.py --check`, `test_check_pack.py` staleness, missing `gh.log`, dirty-workspace refusal and missing `ps`/`kill` are environmental. Verify once with `git ls-files -v` and record the exact final lines instead of editing protected files.
- Run Python test discovery from the repository root; `cd scripts/tests` changes how tracker paths resolve and produces a false failure in `test_open_spec_tasks_are_dispatchable`.
