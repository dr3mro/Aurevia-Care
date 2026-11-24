- [-] REMOVE-MODEL-ROUTER · Restore direct SWARM_MODEL environment selection and remove force/router logic
  - agent: orca
  - scope: scripts/agent-run.sh, scripts/model-router.py, scripts/tests/test_model_router.py, scripts/tests/test_agent_run.py, scripts/tests/test_swarm.py, scripts/tests/test_swarm_cli_coverage.py, scripts/tests/helpers.py, scripts/gen-opencode.py, scripts/tests/test_gen_opencode_coverage.py, scripts/tests/test_opencode_profile.py, scripts/swarm.py, .github/workflows/*.yml, .github/agent-image/Dockerfile.slot, .opencode/model-pricing.json, .opencode/config/slots.json, .opencode/agents/orca.md, docs/SWARM.md, docs/operations/workflow-changes/quality-verification-checklist.md, docs/operations/workflow-changes/candidate-validation-isolation.md, .opencode/config/WORKSPACE.md, .opencode/config/MEMORY.md
  - details: User directive (2026-11-24) supersedes the centralized router design. Use only the
    SWARM_MODEL environment variable for the model name. Remove MODEL, SWARM_FORCE_MODEL,
    role/phase ranking, pricing-registry enforcement and model failover. Workflows pass SWARM_MODEL
    from the repository variable with opencode-go/hy4-flash-free as the default. The runner invokes
    that one model and keeps terminal rate-limit behavior. Slot config rejects model keys at any depth.

## Loaded skills
- aurevia-delivery — every story/task uses the persistent delivery loop.
- aurevia-senior-python — scripts/model-router.py, scripts/gen-opencode.py, scripts/swarm.py and tooling tests.
- aurevia-test-engineer — regression tests and branch/failure-path coverage.
- aurevia-plan-reviewer — pre-implementation plan review.
- aurevia-senior-shell — scripts/agent-run.sh invocation and failure semantics.
- aurevia-github-workflow — nine workflow environment blocks and exact model wiring.

## Progress
- [x] Owner clarified the sole model input is SWARM_MODEL.
- [x] Direct one-model runner restored; missing/empty values fail before invocation and every independently invocable agent command exits nonzero after restoring config, before further work.
- [x] Router, router tests and pricing registry removed.
- [x] Nine workflows export only SWARM_MODEL with the Hy4 Flash default.
- [x] Runtime-profile validation no longer reads a model registry.
- [x] Recursive slot-config model rejection added.
- [x] Docs, agent instructions and durable trackers updated.
- [ ] Git-dependent suites and changed-line coverage not rerun after the owner prohibited Git-using scripts.

## Test evidence
- Red before production edits: targeted router-era tests failed with `configuration_error`, missing workflow default, accepted nested slot model, and required model registry.
- Green before the workspace reset: `test_agent_run.DirectModelEnvironmentTests` 52 tests OK; `test_swarm.ModelEnvironmentTests` 7 tests OK; nested slot CLI test 1 OK; runtime-profile suites 23 + 18 tests OK; `bash -n scripts/agent-run.sh`, `shellcheck scripts/agent-run.sh`, `gen-opencode.py --check-runtime`, `gen-opencode.py --check`, policy scripts and actionlint passed.
- Full suite before the reset: 1,573 tests, one unrelated pre-existing planner assertion failure (`claim_open(..., force=False)` versus older expected call).
- After the reset, work was restored from the exact session diff and backed up to `/private/var/folders/5f/1bz7m8qs7t380l7r_08cbx540000gn/T/opencode/remove-model-router-backup.tgz`.
- Owner then prohibited all Git-using scripts. Non-Git green checks after restore: `py_compile` on every changed Python file; `bash -n scripts/agent-run.sh`; `shellcheck scripts/agent-run.sh`; `gen-opencode.py --check-runtime`; `gen-opencode.py --check`; `actionlint -ignore 'unexpected key "queue"' .github/workflows/*.yml`; `check-docs.py`; `check-pack.py`; runtime-profile suites 23 + 18 tests OK; `ModelEnvironmentTests` 7 OK; nested slot CLI test 1 OK; static YAML/JSON model-contract audit passed.
- Code review found fail-open `configuration_error` paths. Fixed `cmd_implement` and added terminal handling after `restore_config` to debugger, triage, reconciliation, audit, daily review, strategy, read-only review, merge-conflict resolution and `resolve`. The advisor call is skipped when the model is absent; review returns before workspace cleanup. Command-family tests assert nonzero exit, no invocation and restored config; implement tests also assert zero commits, no next segment and no repair logs.
- Final non-Git rerun after that fix: Python syntax, shell syntax/shellcheck, actionlint, `check-docs.py`, `check-pack.py`, both `gen-opencode.py` checks, runtime-profile suites, `ModelEnvironmentTests`, nested-slot CLI test, and direct changed-range coverage all passed again.
- Backup archive verified: 29 entries, SHA-256 `6c9d3ad0ab1744f7a0988209b268ea9385246629dba235c88260793003fdee4a` before the final checkpoint-only refresh.
- The full suite and `scripts/python-coverage.sh` were not rerun because both invoke Git.

## Coverage
- Direct non-Git coverage after restore: `gen-opencode.py` 90/90 lines and 44/44 branches; changed range has zero missing lines/branches. `swarm.py` changed range (`_contains_model_key`, `load_slots`, `slot_config` docstring area) has zero missing lines/branches under `ModelEnvironmentTests` and the nested-slot CLI test.
- `scripts/python-coverage.sh` was not run because it invokes `git diff` and the owner prohibited Git-using scripts.
- Named branch tests: direct pass-through, unset/empty model, terminal rate limit, generic failure, timeout precedence, recursive slot model rejection, allowed nested slot config, registry ignored, runtime permission/secret checks. Wrapper behavior tests are present but not rerun after restore because their fixture invokes Git.

## Entry-point-to-test trace
Workflow `SWARM_MODEL` env → `scripts/agent-run.sh run_agent` → one `opencode run --model "$SWARM_MODEL"` → `DirectModelEnvironmentTests`; slot image config → `scripts/swarm.py load_slots` → `ModelEnvironmentTests` and nested-model CLI test; runtime profile → `scripts/gen-opencode.py check_runtime` → runtime-profile suites.

## Review verdicts
- aurevia-coverage-auditor: GO (all terminal and success branches mapped)
- aurevia-devsecops: GO (all caller families fail closed)
- aurevia-code-reviewer: re-review pending after all-caller expansion

## Findings
- At 14:26:36 -0500 the workspace was reset twice to `be65c427`, discarding the first restored patch. The exact process was not proven; no intentional workspace reset was issued. All changes were reconstructed from the session diff.
- Owner then directed: do not run any script that uses Git. Git-dependent verification remains outstanding.
- Evidence sources: tool command output and read-only specialist reviews; no simulated user or human clinical feedback.

## Why this is not finished
The code and documentation are restored, non-Git checks pass, changed Python ranges have direct 100% line/branch coverage, and coverage/security re-reviews are GO. The canonical full tooling suite and `scripts/python-coverage.sh` have not been rerun after the reset because both invoke Git and the owner prohibited Git-using scripts. Final code re-review is pending. Next step: after owner authorization, run `python3 -I -m unittest discover -s scripts/tests -q` and `bash scripts/python-coverage.sh`, then record the final code-review verdict and close the task.
