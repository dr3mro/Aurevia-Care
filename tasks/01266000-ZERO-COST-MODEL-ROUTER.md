- [-] ZERO-COST-MODEL-ROUTER · Centralized zero-cost model router with SWARM_FORCE_MODEL control
  - agent: main
  - branch: swarm/zero-cost-model-router
  - scope: scripts/model-router.py, scripts/tests/test_model_router.py, scripts/agent-run.sh, .github/workflows/*.yml, docs/SWARM.md, .github/swarm/slots.json, scripts/swarm.py, scripts/gen-opencode.py, WORKSPACE.md, MEMORY.md
  - details: Audit found no SWARM_FORCE_MODEL anywhere and MODEL: ${{ vars.SWARM_MODEL || ... }}
    in 9 workflows, making SWARM_MODEL the primary override. Implement centralized Boolean
    config parsing, strict zero-cost enforcement, role/phase-aware routing, bounded failover
    and SWARM_MODEL-as-final-fallback at the single opencode run entry point.

## Requirements compliance (audit 2026-11-24, before change)

| ID | Requirement | State | Evidence |
|----|-------------|-------|----------|
| R1 | SWARM_FORCE_MODEL strict 0/1 | NOT_IMPLEMENTED | `rg SWARM_FORCE_MODEL` → no matches |
| R2 | Intelligent mode / role-aware routing | NOT_IMPLEMENTED | no router exists |
| R3 | SWARM_MODEL final fallback only | INCORRECTLY_IMPLEMENTED | 9 workflow `MODEL:` expressions put it first |
| R4 | Strict $0.00 input/output pricing | NOT_IMPLEMENTED | no pricing check before `--model` |
| R5 | Centralized production invocation | PARTIALLY_IMPLEMENTED | single `opencode run` at agent-run.sh:109 but no router |
| R6 | Bounded failover/rate-limit rotation | NOT_IMPLEMENTED | run_agent ends on rate_limited |
| R7 | Direct push to main eliminated | NOT_IMPLEMENTED | swarm.py:2021,2523,3901,4075,4190 |
| R8 | Swarm gate independent | IMPLEMENTED_BUT_UNVERIFIED | PR #1512; live gate blocked by bootstrap |
| R9 | Delivery gate fail-closed | IMPLEMENTED_AND_VERIFIED | check-delivery-gate.py, 20 tests |
| R10 | GitHub vars/rulesets verified | BLOCKED_BY_EXTERNAL_DEPENDENCY | API 403 rate limit |

## Follow-on decision (user, 2026-11-23)
- Remove the static model from all `.github/swarm/slots.json` entries. Slots keep identity and config only; the router in `scripts/model-router.py` chooses the model at runtime (auto by agent/phase or env `SWARM_MODEL`/`SWARM_FORCE_MODEL`), validated against `.github/swarm/model-pricing.json`. Acceptance: no production code reads a model from slots; baked slot config pins no model; runtime-profile model validation still fails closed against the verified registry; 100% coverage on every touched file.

## Progress
- [x] Audit completed
- [x] Router implemented (scripts/model-router.py, 100% coverage)
- [x] Workflows rewired (9 workflows; no vars.SWARM_MODEL || overrides)
- [x] Tests 34 router tests + 45 agent-run + 326 swarm; 100% router coverage
- [x] Docs updated (docs/SWARM.md, WORKSPACE.md)
- [x] Static slot model removed; runtime router is the sole selector
- [ ] Committed / PR

## Test evidence (2026-11-24, tool evidence)
- Red before implementation: `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm*.py' -q` → `FAILED (failures=3, errors=32)` with `KeyError: 'model'`; `python3 -I -m unittest discover -s scripts/tests -p 'test_*opencode*.py' -q` → `FAILED (failures=1, errors=44)` with `AttributeError: module 'gen_opencode' has no attribute 'MODEL_REGISTRY'`.
- Green: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1555 tests ... OK`.
- `bash scripts/quick-check.sh` → `quick check: policy, compile and unit tests passed`.
- `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`.
- `python3 -I scripts/gen-opencode.py --check` → exit 0 after regenerating the pre-existing stale `.github/opencode.ci.json` (`orca` → `aurevia-orchestrator`, 2 lines; local `opencode.json` was already current).
- `shellcheck scripts/agent-run.sh` → exit 0. `actionlint .github/workflows/*.yml` → exit 1 only for the pre-existing, repo-wide `queue: max` key unsupported by the installed actionlint; no other finding.
- Targeted router/wrapper suites: `test_agent_run.py` 93 tests OK, `test_model_router.py` 34 tests OK.

## Coverage
- `scripts/swarm.py`: `slot_config` no-model branch → `test_swarm.PlanTests.test_committed_files_are_valid`, `test_swarm_cli_coverage.test_main_slot_config_prints_the_config`, `test_module_main_guard_exits_with_zero`; `plan_workers` matrix without model → `test_swarm.LaunchTaskTests.test_planned_worker_has_no_pinned_model`; four synthetic slot literals → existing planner/schedule/daily tests.
- `scripts/gen-opencode.py`: registry read/missing → `test_gen_opencode_coverage.test_missing_registry_alone_returns_early`, `test_opencode_profile.test_missing_model_registry_is_reported`; non-dict `models` → `test_a_non_mapping_models_entry_fails_closed`; missing `models` → `test_a_registry_without_a_models_mapping_fails_closed`; allowed/disallowed declared model → `test_model_declaration_branches`, `test_allowed_agent_model_passes`, `test_disallowed_agent_model_is_reported`.
- No threshold, exclusion, skip or suppression added.

## Entry-point-to-test trace
`swarm.yml` hourly plan → `scripts/swarm.py plan-workers` (matrix carries `slot` only) → `swarm-worker.yml` env (`SWARM_MODEL`/`SWARM_FORCE_MODEL` only) → `scripts/agent-run.sh run_agent` → `scripts/model-router.py resolve` → `.github/swarm/model-pricing.json` → `opencode run --model`. Asserted by `test_swarm.ModelOverrideTests`, `LaunchTaskTests.test_planned_worker_has_no_pinned_model`, `test_swarm_cli_coverage` slot-config tests, `test_gen_opencode_coverage`/`test_opencode_profile` registry tests and `test_agent_run.DeterministicRoutingE2ETests`.

## Findings
- Bounded design reviews: `aurevia-senior-python` GO, `aurevia-github-workflow` GO (conditional on companion edits, all applied), `aurevia-senior-shell` NO-GO on story scope (its Critical Python blockers were fixed; shell scope needed comment-only corrections, applied).
- Pre-existing stale generated `.github/opencode.ci.json` from commit `b70e8969` blocked the pack gate; regenerated in this change (2 lines).
- Evidence source: executed local commands and read-only specialist agents; no human or clinical evidence.
