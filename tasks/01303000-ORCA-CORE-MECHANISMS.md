- [-] ORCA-CORE-MECHANISMS · Implement one offline deterministic ORCA core library
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-015,016,017,018,021,070,080,093,128,145 (analysis helpers only; runtime capability still blocked where noted)
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, docs/orca-refactor/03-core-mechanisms.md
  - details: Owner chose the offline-core mode on 2026-11-24, recorded in `MEMORY.md` by the orchestrator. Build one standard-library Python analysis module with task-contract validation, context compilation under budgets with a manifest, preflight analysis, readiness scoring, a task-scoped blackboard, evidence freshness and checkpoint I/O. Routing, scheduling and lifecycle remain owned by `scripts/route-task.py`, `scripts/swarm.py` and native OpenCode. Never launch agents or write swarm state. Document analysis helper versus runtime capability per ORCA id. Every added line and branch needs a named test; no pragma, skip or threshold change.

## Loaded skills
- aurevia-delivery — mandatory story loop.
- aurevia-senior-python — standard-library Python module in `scripts/`.
- aurevia-test-engineer — red tests first, branch-to-test map.
- aurevia-plan-reviewer — plan before code.
- aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops — verdicts after green tests.

## Plan (revised after plan-reviewer NO-GO)
1. Scope/authority: this module is an **offline analysis library only**. It never launches agents, never writes task state used by the swarm, and does not replace native OpenCode, `scripts/swarm.py`, `scripts/route-task.py` or `scripts/agent-run.sh` as the single runtime authority. Trackers are updated by the orchestrator outside this story's scope; the owner's offline-core mode is recorded in `MEMORY.md` with that explicit boundary.
2. Reuse before invention: drop `route_skills` and dependency resolution because `scripts/route-task.py:24-70` already owns scope-to-specialist routing; drop `schedule` and `can_transition` because dependency scheduling and lifecycle belong to `scripts/swarm.py` and native OpenCode. Keep only helpers with no current owner: `TaskContract` validation, `compile_context` (content budgets and manifest), `readiness` scoring, `preflight` analysis, `Blackboard`, `evidence_fresh`, `save_checkpoint`/`load_checkpoint`.
3. Honesty split in `docs/orca-refactor/03-core-mechanisms.md`: for each ORCA id, distinguish "analysis mechanism provided" from "runtime capability still blocked". ORCA-020, 021, 070, 105, 130 and 139 remain runtime-blocked even though the module offers analysis helpers. ORCA-028 is already `[x]` via generated config and is not claimed here.
4. Structural boundary test: assert no production script under `scripts/*.py` imports `orca_core`, and that the module source contains no `subprocess`, agent-launch or Git API usage. A reviewer can trace import → assertion without any runtime wiring.
5. Red first: write `scripts/tests/test_orca_core.py` before the module. Named tests cover invalid contract fields (each field), cyclic skill-free dependency data is not used, budget overrun and mandatory sections, readiness low/mandatory-fail/high, preflight missing and present inputs, illegal/legal blackboard entries, stale and fresh evidence, and missing/corrupt checkpoint files. Use synthetic data and injected `Path` boundaries only; no clock or network.
6. Gates: targeted `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` (red then green); full suite; `REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` with its real final line recorded (the gate diffs against `origin/main`, which this runtime cannot supply, so any skip/failure is reported honestly); `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-names.py`. Map every new line and branch to a named test under `## Coverage`. No pragma, skip, exclusion or threshold change.
7. After green, the orchestrator updates both checklists (`tasks/01300000-...` and `orca-refactor.md`) as separate tracker work and records the owner mode in `MEMORY.md`.

## Evidence log
