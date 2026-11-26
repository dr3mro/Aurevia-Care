# FIX-TEST-WORKSPACE-RESET — worker hand-off

- Task: `01278000-FIX-TEST-WORKSPACE-RESET.md` (`[-]`, swarm worker, run 2026-11-25)
- Requirement: OPS-06 (separate test resources, synthetic fixtures, policy checks)
- Risk class: cross-boundary (tooling/test isolation); no production code changed
- Scope: `scripts/tests/test_swarm.py`, `scripts/tests/test_swarm_delivery_state.py`,
  `scripts/tests/test_workspace_git_isolation.py`, `.opencode/config/{BUGS,MEMORY,WORKSPACE}.md`,
  `tasks/01278000-FIX-TEST-WORKSPACE-RESET.md`; this notes file only.

## Summary

The full Python tooling suite mutated the real checkout twice at 14:26:36 -0500 because
conflict-path integrator tests reached production `integrate_batch` with an unmocked
`swarm.git`; `integrate_prepare` rollback runs `git reset --hard <base>` in the current
directory. The fix (present from prior attempts and re-verified here) makes every affected
scenario deterministic: all Git boundaries are mocked, and a no-subprocess regression
runs 13 integrator scenarios with all five subprocess entry points replaced by a raising
guard. This run closed two residual gaps: an **AST-based static completeness check**
enumerates every test caller of the Git-boundary helpers (following instance helpers,
module functions, `setUp` and `swarm.main(["integrate-batch", ...])` dispatch) and rejects
any caller that neither mocks the Git-spawning boundaries nor establishes a temporary
checkout, and the guard now also covers `subprocess.call`/`check_call`. A caller that
mocks only `swarm.git` is not accepted, because an unmocked `merge_local` still spawns
`git merge`. No production behaviour changed.

## Affected requirements

- OPS-06 — synthetic tooling checks must not touch the developer workspace or shared state.

## Skills loaded

- `aurevia-delivery` — persistent story/test/gate loop (matched: "working any story").
- No additional skill matched this test-only Python scope in this run's brief, which named
  only `delivery`. The task file records prior loads of `aurevia-debugger`,
  `aurevia-senior-python` and `aurevia-test-engineer`.

## Review owner

Primary agent (orca). Read-only reviewers: plan, code, coverage and devsecops (below).

## Data changes

None. No migration, schema, fixture or persisted data changed. All fixtures are synthetic
(`HEAD = "a"*40`, `OLD = "b"*40`, `base-sha`, `CW-001`, `WQUALITY-01`, temporary directories).

## Authorization boundaries and audit events

No tenant, role, patient-link or audit behaviour changed. No credentials, tokens or PHI
were used or recorded. The regression fails closed before any child process can start.

## Migration and rollout considerations

None. Test-only change; production rollback behaviour is intentionally unchanged.

## Test evidence

All commands run from the repository root with isolated Python (`-I`); this worker ran no
Git command (durable directive in MEMORY.md, 2026-11-24).

- `python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -v`
  → `Ran 5 tests in 0.545s` / `OK`. Inner suite: 13 scenarios, zero calls on all five
  guarded subprocess seams; static completeness, AST classifier, helper/cycle traversal
  and caller enumeration tests also green.
- Red probe for the completeness check (in-memory scratch directory, no repo file
  written): 11 synthetic callers mock the boundary helper, one calls
  `swarm.integrate_batch` unmocked and without a temp checkout → the test failed with
  `unisolated Git-boundary callers: ['test_synthetic.py:Sample.test_exposed_caller']`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -k IntegratorWiringTest`
  → `Ran 6 tests` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k ResolveConflictTests`
  → `Ran 9 tests in 0.035s` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k restore_trackers`
  → `Ran 5 tests in 0.007s` / `OK`.
- Non-Git workspace-mutation check: SHA-256 of all seven scope files captured before and
  after the isolation regression → `scope files unchanged: True`, exit 0, final line `OK`.
- Conflict-marker scan of the three test files → no matches.
- `python3 -I -m py_compile scripts/tests/test_swarm.py scripts/tests/test_swarm_delivery_state.py scripts/tests/test_workspace_git_isolation.py`
  → exit 0 / `py_compile OK`.
- `python3 -I scripts/check-docs.py` → exit 0; `python3 -I scripts/check-constraints.py` → exit 0;
  `python3 -I scripts/check-names.py` → exit 0 (no findings).
- `python3 -I scripts/check-pack.py` → exit 1, final line
  `check-pack: .opencode/opencode.json is stale: run scripts/gen-opencode.py`.
  This is the documented cloud-worker false positive OPS-CW-003-01: `agent-run.sh`
  installs the CI profile over the worktree file under `skip-worktree`; the committed
  local profile is generated correctly and the task file records a prior green
  `Delivery tooling validated: 39 agents, 39 skills`. Both results are recorded with
  their environments in Findings; Git-based confirmation was not run (worker no-Git rule).

Entry-point-to-test trace:
`test_workspace_git_isolation.WorkspaceGitIsolationTests.test_integrator_scenarios_do_not_start_subprocesses`
→ 13 inner scenarios (`ResolveConflictTests` ×3, `IntegrationTests.restore_trackers` ×5,
`IntegratorWiringTest` ×5) → `swarm.integrate_batch` → `integrate_prepare` (mocked
`swarm.git` / `pr_already_in_main` / `authed_git` / `merge_local`), with all five
`swarm.subprocess` seams guarded so a missing mock fails before any `git reset --hard`
process can start. Completeness trace:
`test_every_git_boundary_caller_is_isolated_from_the_real_checkout` → AST scan of every
`scripts/tests/test_*.py` method (plus helpers, `setUp` and `swarm.main` dispatch) →
callers classified as temp-checkout, all-spawning-boundaries-mocked, or
boundary-helper-mocked.

Full-suite and canonical Python coverage commands were not run: the owner prohibited the
full suite after the live reset, and this worker is forbidden from Git/Git-backed
operations. Disposition: BLOCKED, not failed.

## Coverage

- Production code unchanged: no new or changed `scripts/*.py` production line or branch,
  so `bash scripts/python-coverage.sh` does not apply to this change (and its scope omits
  `scripts/tests/*`). It was not run; disposition BLOCKED with the same reasons as above.
- New test branches and their tests (all in `test_isolation_classification_covers_mocks_and_temporary_checkouts`
  unless named otherwise):
  - all three spawning boundaries mocked (`git`, `pr_already_in_main`, `merge_local`) → true case.
  - boundary helper mocked (`integrate_batch`) → CLI-dispatch true case.
  - temp checkout (`mkdtemp` + `chdir`) → true case.
  - no mocks → false case (`swarm.integrate_batch([7])`).
  - partial mock (`git` only) → false case, closes the unmocked-`merge_local` hole.
  - `chdir` without temp creation → false case.
  - temp creation without `chdir` → false case.
  - decoy string resembling `patch.object(swarm, 'git')` in a docstring → false case (AST classification).
  - `patched_boundaries`: non-`object` call target, missing `swarm` argument, and names
    outside the relevant sets → exercised by the false cases above and the real scan.
  - helper / `setUp` / module-function traversal and cycle guard →
    `test_isolation_source_follows_helpers_setup_and_cycles` (`scratch()` → `self.helper()`
    → `setUp`, plus `cyclic_a`/`cyclic_b`).
  - direct, helper-mediated and `swarm.main(["integrate-batch", ...])` caller detection →
    `test_boundary_callers_follow_helpers_and_main_dispatch` and the real scan.
  - completeness failure paths → scratch probe with too few callers failed
    `assertGreater(len(callers), 10)`; exposed path failed with the exact caller label.
  - five subprocess seams → `assert_not_called()` on run/check_output/Popen/call/check_call.
- No threshold lowered, no exclusion, no `pragma: no cover`, no skipped test, no suppression.
- `bash scripts/quality-gate.sh` requires a BASE_SHA obtained via Git and was not run by
  this worker (BLOCKED by the durable no-Git directive). Its Git-free constituents were
  run: `check-constraints.py` (exit 0) and `check-verdicts.py` (below).

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- Prior-run fix was already in the working tree; this run re-verified it and closed two
  residual gaps (completeness scan and the two extra subprocess seams). No production
  file changed.
- **Tracker drift — integrator action required (worker scope forbids these edits).** The
  regression asserts and executes **13** inner scenarios, but
  `tasks/01278000-FIX-TEST-WORKSPACE-RESET.md:16,22,25,26,33,37,45`,
  `.opencode/config/BUGS.md:56` and `.opencode/config/MEMORY.md:7` still say **eight** /
  `testsRun == 8` / `8/8`; only `.opencode/config/WORKSPACE.md:7` says 13. Update those
  counts and name the five added scenarios
  (`restore_trackers_restores_absent_untracked_path_from_main`,
  `restore_trackers_removes_only_tracked_paths_missing_from_main`,
  `restore_trackers_refuses_untracked_collision_before_mutation`,
  `restore_trackers_refuses_broken_symlink_collision`,
  `IntegratorWiringTest` ×5 are already listed in the notes trace) so a rehydrating
  agent does not under-trust the guard. Also add the plan-reviewer verdict to the task
  file's verdict list if the integrator keeps that section.
- **Conflicting tool evidence — integrator action required.** The task file line 30 records
  `check-pack.py` green (`Delivery tooling validated: 39 agents, 39 skills`) from a run
  with the committed local profile. This run's red result is the CI-profile artefact
  OPS-CW-003-01 described above. Record both with their environments rather than leaving
  contradictory tool results as durable evidence.
- Maintenance boundaries (review findings, Optional): the static scan enumerates
  `integrate_batch`/`restore_trackers` and direct `swarm.main(["integrate-batch", ...])`
  dispatch. Other Git-writing helpers such as `integrate_finalize`, helper-mediated CLI
  dispatch, boundary calls in `setUp`/`tearDown`, and new spawning boundaries beyond
  `git`/`pr_already_in_main`/`merge_local` are not enumerated today; their current
  callers are isolated, but a future scoped change should widen the boundary set and
  entry points before adding such a caller. Prefer extending the runtime five-seam guard
  to every AST-discovered caller over growing a static name list.
- Full-suite verification, canonical Python coverage and exact-SHA CI verdict remain the
  integrator's responsibility. No simulated-user or human evidence is claimed.

## Lessons for workers

- A "no subprocess" regression must guard `run`, `check_output`, `Popen`, `call` and
  `check_call` at the module object production code actually calls, and must assert the
  inner suite ran and succeeded; otherwise an empty suite passes vacuously.
- Mock `pr_already_in_main` and `git` as well as `merge_local`: a deterministic `git`
  return prevents a hidden real reset in the rollback path, and a deterministic
  `pr_already_in_main=False` keeps the conflict path under test.
- A hand-picked scenario list goes stale silently. Pair it with a static call-site scan
  that fails closed on any new caller of a Git-boundary helper, and follow helpers,
  setUp and module functions before classifying a caller as isolated.
- Detection follows helpers in the scanned class/module only. A future test using an
  inherited or cross-module base fixture would be reported exposed (fail closed, not a
  hidden reset); resolve through the class MRO if such fixtures are introduced.
- In a cloud worker, treat a `check-pack.py` "opencode.json is stale" result as a
  CI-profile measurement (OPS-CW-003-01); do not regenerate a protected config from a
  worker scope.

## Why this is not finished

No code work remains: the isolation guard, the 13-scenario regression, the AST
completeness check and all targeted/static checks are green, all four reviewers answered
GO, and `python3 -I scripts/check-verdicts.py .agents/notes/FIX-TEST-WORKSPACE-RESET-20261125-004358.md`
exits 0. Publication and full verification are outside this worker's authority:
Git/GitHub commands are forbidden by the durable directive, the owner prohibited the full
Python suite after it reset the real workspace, and tracker files cannot be edited by a
swarm worker. Next step for the trusted integrator: reconcile the tracker counts to 13
and the check-pack evidence above; on an authorized checkout run
`python3 -I -m unittest discover -s scripts/tests -q` and `bash scripts/python-coverage.sh`
under a workspace-mutation check; then commit these scoped files and observe the
exact-SHA Delivery verdict.
