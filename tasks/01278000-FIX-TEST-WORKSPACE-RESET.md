- [-] FIX-TEST-WORKSPACE-RESET · Prevent tooling tests from resetting the real workspace
  - agent: swarm-6
  - attempts: 26
  - requirements: OPS-06
  - scope: scripts/tests/test_swarm.py, scripts/tests/test_swarm_delivery_state.py, scripts/tests/test_workspace_git_isolation.py, .opencode/config/BUGS.md, .opencode/config/MEMORY.md, .opencode/config/WORKSPACE.md, tasks/01278000-FIX-TEST-WORKSPACE-RESET.md
  - details: Full tooling suite reset the real checkout twice at 14:26:36 -0500. Root cause: conflict-path integrator tests call real integrate_batch without mocking swarm.git; production rollback calls git reset --hard in the current directory. Add a regression that forbids subprocess execution, mock every Git boundary in affected tests, verify no workspace mutation, and record non-Git evidence.

## Loaded skills
- aurevia-delivery — persistent task/checkpoint loop.
- aurevia-debugger — reproducible root-cause tracing.
- aurevia-senior-python — tooling test isolation and Python coverage.
- aurevia-test-engineer — red regression and branch coverage.

## Progress
- [x] Root cause localized to unmocked integrator Git calls in test_swarm.py conflict scenarios and test_swarm_delivery_state.py wiring scenarios.
- [x] Added no-subprocess regression covering all eight affected scenarios.
- [x] Mocked deterministic Git boundaries in all affected integrator tests.
- [x] Ran targeted non-Git tests and static checks.
- [ ] Independent review and final checkpoint.

## Plan review
- Initial NO-GO findings resolved: deterministic `git(return_value="base-sha\n")` and `pr_already_in_main(return_value=False)` mocks; regression asserts eight inner tests ran successfully and guarded subprocess call count is zero; exact targeted non-Git commands recorded below.

## Test evidence
- Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_workspace_git_isolation.py' -v` ran eight inner scenarios; all eight failed at guarded `subprocess.run` before any Git process started.
- Green: same command → outer test OK; inner `testsRun == 8`, `wasSuccessful() == True`, guarded subprocess call count zero.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -k IntegratorWiringTest -v` → 6 tests OK.
- Three exact `test_swarm.ResolveConflictTests` methods via `unittest discover ... -k ...` → 1 test OK each.
- `python3 -I -m py_compile scripts/tests/test_swarm.py scripts/tests/test_swarm_delivery_state.py scripts/tests/test_workspace_git_isolation.py` → exit 0.
- `python3 -I scripts/check-docs.py` → exit 0; `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`.

## Coverage
- Production code is unchanged. The new regression covers the isolation contract itself: all eight scenarios execute, every intended behavioral assertion still runs, and no subprocess may start.
- Full-suite and canonical Python coverage remain pending owner authorization because they invoke Git.

## Entry-point-to-test trace
`test_workspace_git_isolation.WorkspaceGitIsolationTests` → eight existing integrator test methods → `integrate_batch` → `integrate_prepare` with deterministic `git`/`pr_already_in_main` mocks → conflict, moved-head, evidence-block and successful-finalization assertions.

## Review verdicts
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Why this is not finished
The fix is locally verified with the no-subprocess regression, all eight affected scenarios, the affected wiring class, static checks and three GO reviews. The full tooling suite and canonical Python coverage remain unrun solely because the owner prohibited Git-using scripts and tests. Next step after authorization: run `python3 -I -m unittest discover -s scripts/tests -q` and `bash scripts/python-coverage.sh`.
