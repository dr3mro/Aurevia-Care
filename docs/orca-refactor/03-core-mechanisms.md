# Offline ORCA core mechanisms — scope and evidence

`scripts/orca_core.py` is a standard-library analysis library chosen by the owner on 2026-11-24 (recorded in `.opencode/config/MEMORY.md`). It is **not** a runtime orchestrator: it never launches an agent, never writes swarm task state and does not replace native OpenCode, `scripts/agent-run.sh`, `scripts/swarm.py` or `scripts/route-task.py` as the single runtime authority for routing, scheduling and lifecycle. A structural test asserts that no production script imports it and that its source has no agent-launch or repository API usage.

## Analysis mechanisms provided

| ORCA item | What the module provides | What is still missing |
|---|---|---|
| ORCA-015 | `TaskContract` validation of id, objective, acceptance, scope and risk | Runtime contract enforcement in the supervisor |
| ORCA-016 | `compile_context()` selects sections under budgets and returns a manifest | Wiring into a native OpenCode task run |
| ORCA-017 | Per-section and default character budgets in the compiler | Runtime context-budget enforcement and compaction |
| ORCA-018 | Manifest with task id, section lists, content and a stable content hash | Formal schema and provenance validation |
| ORCA-021 | `preflight()` lists missing tools, instructions and skills | Fail-closed runtime preflight before implementation |
| ORCA-070 | `save_checkpoint()` / `load_checkpoint()` round-trip local JSON state | Integration with task lifecycle and interrupted-run resume |
| ORCA-080 | `readiness()` scores preparation from 0–100 with a mandatory gate | Runtime readiness gating before dispatch |
| ORCA-128 | In-memory `Blackboard` with task, agent, revision, evidence and fact/assumption fields | Persistence and cross-agent sharing |
| ORCA-145 | `evidence_fresh()` compares evidence revision with the current revision | Revision capture from the real repository and automatic invalidation |

ORCA-028 is already verified by the generated agent configuration and is not claimed by this module. ORCA-020 (persistent supervisor lifecycle), ORCA-105 (autonomous recovery), ORCA-130 (critical-path scheduling) and ORCA-139 (starvation prevention) remain runtime capabilities: the module does not implement or mark them complete. Routing and dependency scheduling stay owned by `scripts/route-task.py` and `scripts/swarm.py`.

## Verification

- `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → 23 tests, OK.
- `python3 -m coverage run --branch --source=scripts` with the same target, then `coverage report --include='scripts/orca_core.py' -m` → 96 statements, 48 branches, 0 missed, 100%.
- `python3 -I -m unittest discover -s scripts/tests -q` → 1768 tests, OK.
- `python3 -I scripts/check-constraints.py`, `check-docs.py`, `check-names.py` → exit 0.
- `bash scripts/python-coverage.sh` → exit 1 on **pre-existing changed lines in `scripts/swarm.py` relative to the unavailable `origin/main` base**; no `scripts/orca_core.py` line or branch was reported. The trusted integrator must rerun the changed-line gate from a checkout with the base ref. No threshold, exclusion or suppression was changed.

Evidence source: tool commands and read-only reviewer verdicts; no simulated user or human walkthrough.
