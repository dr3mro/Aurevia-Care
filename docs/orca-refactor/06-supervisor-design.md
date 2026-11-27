# Stages G/I/J design — native-only supervisor and reliability

Design returned by a read-only architect; no implementation claim. Evidence source: tool file reads.

## Configuration-only work

`.opencode/agents/orca.md` already defines the thin primary supervisor: auto-load skills by advertised description, one story at a time, `tasks/` as durable state, completion gates. Bind ORCA-020's lifecycle to task marks and evidence by editing that file, not by adding a supervisor process. Description-routed review skills supply plan, code, coverage, security and QA verdicts.

Existing gates already enforce ORCA-021/038/041/145 without new scripts: `gen-opencode.py:43-98`, `check-pack.py`, `check-docs.py`, `check-names.py`, `check-constraints.py`, `check-verdicts.py`, `quality-gate.sh`, `worker-check.sh`, `coverage-check.sh`, `frontend-typecheck-check.sh`, `check-wiring.py`, `check-delivery-gate.py`, `swarm_gate.py`. Routing and bounded prompt construction stay in `scripts/route-task.py:20-39` and `scripts/agent-run.sh:410-493`. `scripts/agent-run.sh:358-374` is the sole model path; `.opencode/config/slots.json` carries identity only.

Not achievable by configuration: trusted reviewer provenance (ORCA-040), runtime checkpoint resume (070/105), measured concurrent subagent intervals (116), and runtime scheduling/starvation/telemetry (129/130/139/140).

## Durable task state without a database

`tasks/<sequence>-<ID>.md` plus `tasks/_head.md` is the record of truth, owned by `scripts/taskstore.py` and bridged by `scripts/swarm.py`. `parse_tasks` reads id, status, requirements, depends, scope, reserved, aliases, pr, agent, evidence, attempts, priority and details; add no fields until a requirement proves need. Map the lifecycle onto `[ ]` open → `[-]` taken → `[x]` done with evidence in `.agents/notes/<task>-<stamp>.md`; `[!]` means incomplete, is not dispatchable or dependency-satisfying, keeps its scope reserved, and carries the reason and next action in `details:`. Reopening for retry is an explicit `[!]` → `[ ]` transition; no new store is needed.

Existing transitions cover the lifecycle: `claim_open` (`swarm.py:3049-3065`), `set_task_status` (`2911-2958`), `reclaim` (`2976-3006`), `release_claim` (`3009-3014`), `update_todo` (`3017-3046`) and `archive_done` (`2376-2424`). Idempotency comes from claiming only todo tasks, unchanged-text detection in `update_todo`, `taskstore.save()` rewriting only changed files, and archived ids/aliases satisfying dependencies. Ownership is one file per task; trackers are integrator-only (`swarm.py:104-111`); scope overlap is checked at `swarm.py:3072-3079`.

## Concurrency and dependencies — native only

GitHub Actions matrix is the scheduler: `.github/workflows/swarm.yml:55-167` with serialized plan, task-file claims, `max-parallel: 20`, `fail-fast: false` and per-run concurrency. Recovery lives in `swarm-worker.yml`, `swarm-integrator.yml`, `swarm-watchdog.yml` and `scripts/swarm.py:4863-4959`. The capacity ledger is `swarm.py:4811-4825` and `4868-4915`; no second ledger. Dependencies use the `depends` field and plan order (`swarm.py:3305-3325`).

Achievable natively: ORCA-117, 119/122, 120, 123, 124/126 and 128 via task files. Blocked without live runner or trusted evidence: 021/038/040, 070/105, 116, 127, 129, 130, 139, 140, 111 and 047.

## Fault-injection test plan

Use `tempfile.mkdtemp()` and `unittest.mock.patch.object` on `swarm.gh`, `git`, `live_workers`, `todo_read` and `todo_write` through `scripts/tests/helpers.py`; no live Git, GitHub or patient data. Already covered: failure classes (`test_failure_classes.py:62-121`), transient retries, stale claims, capacity, checkpoint corruption/freshness (`test_orca_core.py`), terminal agent failures, verdict gate, push race, scope and masking. Missing red tests: stale-lease fence, duplicate/invalid transition idempotency, two concurrent claims on one temp task file, `CapacityUnavailable` on gh failure, resume from a corrupt checkpoint, dependency cycle/missing dep, overlapping-scope double writer, partial publish consolidation, and stale-review verdict binding to reviewed HEAD (blocked until a trusted evidence producer exists).
