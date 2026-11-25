# Script ownership structure and canonical entry points

Source-verified inventory supporting deliverables 35 (updated repository directory
structure), 36 (subsystem dependency diagram) and 37 (canonical script entry point
documentation) of `orca-refactor.md` section 20. This is a record of the **current**
tree, not a migration claim: the three-directory target in section 12 is not in
place, and no script was moved by this document. Evidence source: tool commands
recorded in `tasks/01300000-ORCA-INFRASTRUCTURE-REFACTOR.md`.

## Current ownership roots

| Required root (section 12) | State | Evidence |
|---|---|---|
| `.github/script/` | ABSENT | `for d in .github/script .opencode/scripts scripts; do ...` → `.github/script: ABSENT` |
| `.opencode/scripts/` | ABSENT | same command → `.opencode/scripts: ABSENT` |
| `scripts/` | PRESENT; flat | same command → `scripts: PRESENT` |

`find scripts -maxdepth 1 -type f` by extension: 24 Python, 17 Bash, 2 JSON.
Tests live only in `scripts/tests/` (no tests under `.github/` or `.opencode/`).
Integration/GitHub operations, OpenCode configuration and project gates currently
share the one flat root (disposition ORCA-053 in `tasks/01300000`).

## Canonical verification entry points

`find` for `check.sh` outside `.git`/`node_modules` returns these owners:

| Path | Owner |
|---|---|
| `scripts/check.sh` | repository policy/tooling entry point |
| `apps/api/check.sh` | Java API module |
| `apps/staff-web/check.sh` | staff web module |
| `apps/patient-mobile/check.sh` | patient mobile module |
| `db/check.sh` | database module |
| `infra/capacity/check.sh` | capacity subsystem |
| `infra/delivery-safety/check.sh` | delivery-safety subsystem |
| `infra/onboarding/check.sh` | onboarding subsystem |
| `infra/ops-queues/check.sh` | operations-queues subsystem |
| `infra/recovery/check.sh` | recovery subsystem |
| `infra/tenant-lifecycle/check.sh` | tenant-lifecycle subsystem |

The repository entry point is `scripts/check.sh`. Module and infra modules keep
their own `check.sh`; none is a duplicate of the root policy entry point.

## Executable script entry points

`rg -l 'def main\(' scripts/*.py` lists the runnable Python scripts:

- Gates and validators: `check-constraints.py`, `check-delivery-gate.py`,
  `check-docs.py`, `check-migrations.py`, `check-names.py`, `check-ownership.py`,
  `check-pack.py`, `check-secrets.py`, `check-verdicts.py`, `check-wiring.py`,
  `python_coverage.py`.
- Orchestration and tooling: `gen-opencode.py`, `route-task.py`, `swarm.py`,
  `swarm_gate.py`, `taskstore.py`, `worker_gate.py`.

Boundaries are documented in `scripts/ownership.json` (the declared manifest for
`scripts/check-ownership.py`, task 01304000). The design for the three-directory
migration is in `07-script-ownership-design.md`; this document records the source
state that migration starts from.

## Dependency diagram (source level, ORCA-053)

```
scripts/  (flat root)
├── GitHub/Git operations ── swarm.py, swarm_gate.py, monitor.py
├── OpenCode configuration ── gen-opencode.py, route-task.py, check-pack.py
├── project gates ── check-*.py, python_coverage.py, worker_gate.py
└── offline ORCA analysis ── orca_core.py, orca_memory.py, orca_benchmark.py
        (analysis only: no dispatch, no swarm-state writes)
```

No circular Python imports were found (Stage A disposition, `tasks/01300000`).
Duplication noted there: `parse_tasks` in `monitor.py` and `swarm.py`; Git wrappers
in `swarm.py` and `check-constraints.py`; overlapping failure classifiers in
`failure_classes.py`, `swarm.py`. `failure_classes.py` has no production caller.

## Migration status (explicitly not done)

- [ ] `.github/script/` and `.opencode/scripts/` are not created.
- [ ] No script, caller or test was moved (ORCA-052 remains in progress).
- [ ] `check-ownership.py` validates the declared manifest only; it is not a
      migrated three-root layout.

A three-root move changes references in workflows, generated config and dozens of
callers; it requires an owner decision on the target layout and a single atomic PR
with updated callers and tests. It is not performed in this passive documentation
change. This closes deliverables 35–37 only as **source-verified current structure
and entry points**, with the migration remaining a separate, authorized change.
