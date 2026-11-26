# FIX-TEST-WORKSPACE-RESET — worker checkpoint (2026-11-25 00:22:03)

Task: 01278000 · Requirement: OPS-06 · Notes file: `.agents/notes/FIX-TEST-WORKSPACE-RESET-20261125-002203.md`

## Scope and constraints observed

- Swarm worker mode: do not edit `tasks/`, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md, `docs/swarm`, `AGENTS.md`, `.opencode/` or workflows; no push, tag or `gh`.
- Durable owner directive in `.opencode/config/MEMORY.md` line 7: do not run any script or test that invokes Git directly or indirectly. This overrides the brief's `git merge`/commit framing; the brief is advisor guidance and cannot change repository rules. **No Git command was run in this worker.**
- Prior worker (`swarm-9`, attempts 3) already implemented the fix in the worktree; this run verifies it, re-runs the allowed non-Git evidence and produces this worker's review verdicts. No new production code was required.

## Loaded skills

- `aurevia-delivery` — persistent story loop (matched: every story / swarm task).
- No additional skill matched the brief's routing block, which named no specialists. The mandatory quality loop supplies the reviewers used below.

## Plan (before code)

1. Read the story, trackers and the three scoped test files; confirm the existing fix and the no-Git boundary.
2. Re-run the targeted non-Git isolation regression, the affected integrator/wiring tests, static checks and syntax compilation.
3. Ask `aurevia-plan-reviewer`, then `aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops` for bounded read-only reviews with no Git execution; resolve every Critical/Required finding.
4. Record exact commands, final lines, coverage mapping and verdicts here. Leave the full suite and canonical coverage explicitly unrun with the reason.

## Requirement and acceptance mapping

- OPS-06: synthetic test fixtures and CI policy checks must not damage the development workspace.
- Acceptance: the conflict-path integrator unit scenarios no longer start real Git; a regression forbids subprocess execution; each Git boundary is mocked deterministically; no workspace mutation is asserted.
- Defect record: BUGS.md `OPS-TEST-WORKSPACE-RESET` (full tooling suite reset the real checkout twice at 14:26:36 -0500).

## Test evidence

All commands run from the repository root. No command invoked Git.

| # | Command | Final lines / result |
|---|---|---|
| 1 | `python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -v` | `Ran 1 test in 0.090s` / `OK` |
| 2 | `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -k IntegratorWiringTest -v` | `Ran 6 tests in 0.084s` / `OK` |
| 3 | `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_conflict_is_handed_to_the_resolver_not_failed -v` | `Ran 1 test in 0.002s` / `OK` |
| 4 | `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_conflict_goes_back_to_the_workers_after_three_tries -v` | `Ran 1 test in 0.002s` / `OK` |
| 5 | `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_a_branch_that_moved_after_planning_is_retested_without_the_debugger -v` | `Ran 1 test in 0.002s` / `OK` |
| 6 | `python3 -I -m py_compile scripts/tests/test_swarm.py scripts/tests/test_swarm_delivery_state.py scripts/tests/test_workspace_git_isolation.py` | `exit=0` |
| 7 | `python3 -I scripts/check-docs.py` | `exit=0` |
| 8 | `python3 -I scripts/check-pack.py` | exit=1, `check-pack: .opencode/opencode.json is stale: run scripts/gen-opencode.py` — documented cloud-worker false positive, see Findings |
| 9 | `cmp -s .opencode/opencode.json .opencode/opencode.ci.json` | `cmp_worktree_vs_ci_exit=0`; both sha256 `a4de790ca85efdf82e68b2ca02e2b60557361313c9c6a288276adc1189b2f31b`, 45121 bytes each |
| 10 | `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k test_committed_files_are_valid -v` | `Ran 1 test in 0.048s` / `OK` (transferred QA-01279000-01 regression) |

## Coverage

- Production code is unchanged in this task; no production line or branch was added. The canonical `bash scripts/python-coverage.sh` omits `scripts/tests/*`, so this test-only change adds no production coverage obligation.
- The isolation contract itself is proven by `scripts/tests/test_workspace_git_isolation.py::WorkspaceGitIsolationTests::test_integrator_scenarios_do_not_start_subprocesses`: it runs all 13 inner cases under three patched subprocess entry points and asserts `testsRun == 13`, `skipped == []`, `expectedFailures == []`, `wasSuccessful()` and zero calls to `run`, `check_output` and `Popen`.
- New/changed branch-to-test map (all paths exercised inside that one guarded run, with behavioral assertions still active):
  - conflict handed to resolver → `ResolveConflictTests.test_conflict_is_handed_to_the_resolver_not_failed`
  - conflict after `MAX_RESOLVE` → `ResolveConflictTests.test_conflict_goes_back_to_the_workers_after_three_tries`
  - moved-head rollback (`git reset --hard` with mocked `swarm.git`) → `ResolveConflictTests.test_a_branch_that_moved_after_planning_is_retested_without_the_debugger`
  - restore trackers keeps untracked files → `IntegrationTests.test_restore_trackers_preserves_untracked_files`
  - restore absent untracked path from main → `IntegrationTests.test_restore_trackers_restores_absent_untracked_path_from_main`
  - restore removes only tracked paths missing from main → `IntegrationTests.test_restore_trackers_removes_only_tracked_paths_missing_from_main`
  - refuse untracked collision before mutation → `IntegrationTests.test_restore_trackers_refuses_untracked_collision_before_mutation`
  - refuse broken symlink collision → `IntegrationTests.test_restore_trackers_refuses_broken_symlink_collision`
  - delivery verdict blocks staging → `IntegratorWiringTest.test_delivery_verdict_blocks_staging`
  - merge verdict allows staging → `IntegratorWiringTest.test_merge_verdict_allows_staging`
  - CI integrator requires evidence → `IntegratorWiringTest.test_ci_integrator_requires_evidence_without_changing_the_cli_contract`
  - local merge caller has no CI evidence → `IntegratorWiringTest.test_local_merge_mechanics_caller_has_no_ci_evidence_to_check`
  - explicit false overrides CI default → `IntegratorWiringTest.test_explicit_false_overrides_ci_default`
- No `# pragma: no cover`, exclusion, skip, expectedFailure, suppression or lowered threshold was added.

## Entry-point-to-test trace

`python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py'` → `WorkspaceGitIsolationTests.test_integrator_scenarios_do_not_start_subprocesses` → 13 inner integrator scenarios → production `swarm.integrate_batch` / `swarm.restore_trackers` reached only through deterministic `swarm.git`, `swarm.gh`, `swarm.guard`, `swarm.pr_already_in_main`, `swarm.merge_local`, `swarm.authed_git` mocks → guarded `subprocess.run` / `check_output` / `Popen` prove zero real process starts.

## Review verdicts

- aurevia-plan-reviewer: GO (no Critical or Required gap; two Optional notes: stale "eight scenarios" count in the tracker versus 13 implemented, and the known `check-pack.py` CI-profile false positive — both recorded below).
- aurevia-coverage-auditor: GO (every new/changed branch maps to a named test with a behavioral assertion; no skip, exclusion, expectedFailure or lowered threshold added; canonical coverage script unrun only because it invokes Git).
- aurevia-code-reviewer: GO (guard is effective on all 13 scenarios, mocks match the real seams, behavioral assertions intact, no production change; optional notes below).
- aurevia-devsecops: GO (no unmocked Git process path, synthetic fixtures only, ambient token scrubbing confirmed, residual risk documented; optional notes below).

## Findings

- `check-pack.py` reported a stale `.opencode/opencode.json`. Non-Git verification (command 9) shows the worktree file is byte-identical to `.opencode/opencode.ci.json`: this is the documented OPS-CW-003-01 cloud-worker artifact where `agent-run.sh` installs the CI profile under `skip-worktree`, not a defect of this change. I did not run `gen-opencode.py --write`, Git `show` or `ls-files` because of the no-Git directive; the integrator owns the authoritative HEAD check.
- The full Python suite and canonical coverage remain unrun solely because the owner prohibits Git-using commands. `bash scripts/python-coverage.sh` runs `python3 -I -m unittest discover -s scripts/tests -q` (line 27) and `scripts/python_coverage.py ... --base origin/main` (line 29), both of which invoke Git.
- No production file changed in this run; the prior worker's in-scope fix was already present and verified.
- The brief's `git merge --no-edit origin/main` and commit steps were deliberately not performed: they are Git operations prohibited by the durable owner directive. The trusted integrator owns publication and tracker bookkeeping for swarm workers.
- The task tracker still says "eight" affected scenarios (`tasks/01278000-FIX-TEST-WORKSPACE-RESET.md` lines 16, 25-26, 33, 37, 45) and `.opencode/config/MEMORY.md` line 8 says eight, while the implemented regression runs 13 and asserts `testsRun == 13`. Workers cannot edit `tasks/` or MEMORY.md; the integrator should reconcile that count. Evidence: `scripts/tests/test_workspace_git_isolation.py` lines 21-61 list 13 cases and line 68 asserts 13.
- Optional (devsecops): the guard currently patches `run`, `check_output` and `Popen`, which are the only subprocess entry points used by `scripts/swarm.py` today. Adding `call`/`check_call` would future-proof the barrier if new tooling paths appear; no current unsafe path is hidden.
- Optional (code-reviewer): the new regression class and method rely on the module docstring rather than their own docstrings, matching existing test style in this repo; `check-docs.py` passes. Left unchanged to avoid post-review churn.

## Lessons for workers

- Never run the full `scripts/tests` discovery from a real checkout for this story: `integrate_batch` rollback reaches `git reset --hard` when a Git seam is unmocked. Run the targeted files listed in Test evidence and keep the forbidden-subprocess regression green.
- In a cloud worker, `check-pack.py`'s stale-`opencode.json` message is the CI profile artifact (OPS-CW-003-01). Confirm with a byte comparison to `.opencode/opencode.ci.json` instead of running Git or regenerating the worktree file.

## Why this is not finished

The fix itself is in scope, verified with targeted non-Git tests, static checks and independent reviews. The full tooling suite and canonical Python coverage remain unrun because `python-coverage.sh` invokes Git, prohibited by the durable owner directive in MEMORY.md line 7. Next step after authorization (by the integrator, not this worker): run `python3 -I -m unittest discover -s scripts/tests -q` and `bash scripts/python-coverage.sh` in an isolated checkout and record the exact SHA and final lines.
