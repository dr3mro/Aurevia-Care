# Stage F task intelligence design

Design returned by a read-only senior engineer; no implementation claim. Evidence source: tool file reads.

## Boundary and reuse

Implement only offline analysis in `scripts/orca_core.py` plus tests in `scripts/tests/test_orca_core.py`. Do not edit `route-task.py`, `swarm.py`, trackers, agents or workflows; `orca_core.py` stays passive with no production import. Routing reuses `route-task.py` (`ROUTES`, `pick`, `ALWAYS`, `existing`, `render`); task parsing and eligibility reuse `swarm.py` (`parse_tasks`, `task_text`, `plan_workers`). New helpers consume injected values, never copy those tables or parsers.

## Bounded additions

| Requirement | Already provided | Smallest missing piece |
|---|---|---|
| ORCA-014 | `TaskContract.risk`, route-task specialists, swarm priority | `classify(contract, scope, routed, validation)` returning sorted domain, type, components, expertise and validation plus complexity, risk and clinical/security booleans; reject incomplete contracts |
| ORCA-015 | contract field validation and parsed swarm fields | Extend `TaskContract` with default-empty skills, constraints, tests, definition-of-done and components; `contract_from_task(task, risk)` maps one `parse_tasks()` dict without reimplementing it |
| ORCA-019 | survey rule, route-task directory filter, wiring/name/doc checkers | Nothing; adding a scanner would duplicate existing enforcement |
| ORCA-064 | nothing explicit | `split_plan(contract, classification, max_parts)` producing per-scope children plus an integration child depending on all; return `None` for trivial one-scope plans; no dispatch |
| ORCA-065 | file discovery in three checkers and route-task | `index_sources(files, extractors)` producing path → hash/kind/ids; `stale_entries(index, files)` reusing `evidence_fresh()`; no Git |
| ORCA-066 | nothing explicit | `impact(changed_paths, index, routed)` mapping consumers, entities, web, mobile, contracts, tests, security and clinical boundaries; unknown paths go to `unmapped`, never dropped |
| ORCA-080 | `readiness()` 0–100 and `preflight()` missing-input list | `assess_readiness(contract, classification, preflight_problems, validation, environment)` returning score, band (`ready≥90`, `prepare≥70`, else `not_ready`) and blockers; any missing security check forces `not_ready` |

The passive context and preflight schemas are documented with a synthetic worked artifact in [`13-orca-schema.md`](13-orca-schema.md); `scripts/tests/test_orca_schema_doc.py` pins the documented fields and strings to the real helpers. Runtime context compilation, preflight enforcement and dispatch remain separate units.

## Order and evidence

Implement in this order, green before the next: extend `TaskContract` and add `contract_from_task`/`classify`; `split_plan`; `index_sources`/`stale_entries`; `impact`; `assess_readiness`. Each new public unit gets a docstring with inputs, outputs, errors and invariant; every branch gets a named test. Verification: targeted `test_orca_core*.py`, full Python suite, module coverage report at 100%, and `bash scripts/python-coverage.sh` with its stale-base result recorded, not suppressed. No runtime dispatch or tracker writes; those remain Stage G and blocked.
