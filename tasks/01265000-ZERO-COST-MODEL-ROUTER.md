- [-] ZERO-COST-MODEL-ROUTER · Centralized zero-cost model router with SWARM_FORCE_MODEL control
  - agent: main
  - branch: swarm/protected-integration-gates
  - scope: scripts/model-router.py, scripts/tests/test_model_router.py, scripts/agent-run.sh, .github/workflows/*.yml, docs/SWARM.md
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

## Progress
- [x] Audit completed
- [x] Router implemented (scripts/model-router.py, 100% coverage)
- [x] Workflows rewired (9 workflows; no vars.SWARM_MODEL || overrides)
- [x] Tests 34 router tests + 45 agent-run + 326 swarm; 100% router coverage
- [x] Docs updated (docs/SWARM.md, WORKSPACE.md)
- [ ] Committed / PR
