# Stage H memory and failure-knowledge design

Design returned by a read-only memory manager; no implementation claim. Evidence source: tool file reads.

## Current classification

- Architecture decisions: `MEMORY.md:19,36,46,68`.
- Mandatory constraints: `MEMORY.md:17,22,24,64-66` and `tasks/_head.md:25-43`.
- Verified failures: `BUGS.md:12-14,33,56,66`.
- Domain knowledge: `MEMORY.md:20,36,49,70`.
- Conventions: `MEMORY.md:47,48,54-55`, `tasks/_head.md:19,33`.
- Operational knowledge: `WORKSPACE.md:9,11,13`, `MEMORY.md:41,56`.
- Temporary state: `MEMORY.md:3`, `WORKSPACE.md:6-7,14-20`, and 1,039 per-run notes under `.agents/notes/`.

## Scoped retrieval

The layering already exists in `01-inventory.md:53-56`: always-on docs are native, trackers and spec load by requirement ID, skill bodies load only on `skill` call. Start from the short checkpoints `MEMORY.md:3` and `WORKSPACE.md:6`, then read only the matching `tasks/<seq>-<ID>.md` directly via `scripts/taskstore.py:21-60`, not a joined dump. Apply `scripts/orca_core.py:58-88` `compile_context` budgets to selected memory rows; mandatory sections are never truncated.

Carry provenance as `{path:line, evidence kind, confidence, revision, fact}` using the patterns in `.agents/reports/2026-11-22.md:3`, `MEMORY.md:61` and `orca_core.py:150-170`. Current authoritative policies override superseded entries (`MEMORY.md:9,28,32,33,65`) and `evidence_fresh()` filters stale revisions. Confidence is derived from evidence kind, never stored as an unexplained number.

## Promotion and concurrency

Ladder: per-run note → `.agents/reports/<date>.md` → verified row in `BUGS.md` or decision row in `MEMORY.md`. Promotion requires reproducibility, exact fields and a named evidence source; hypotheses remain in notes with `fact=False`. `BUGS.md:3` admits findings only with observed evidence. `taskstore.py:25-135` gives one file per task and changed-file-only writes, but the read-modify-write is not a compare-and-set; atomic promotion needs the planner mutex and the task+ledger commit policy (`MEMORY.md:60`). Preserve user notes verbatim, append rows, retain superseded decisions with pointers, and never edit generated reports.

## Failure-knowledge schema

`BUGS.md:10` already carries `ID | Requirement | Severity | Environment | Steps | Expected | Observed | Evidence | Owner/status | Retest`, which maps to ORCA-031 symptoms, reproduction, affected versions, remediation, evidence and retest. Missing explicit columns: root cause and named regression test. `scripts/failure_classes.py:21-75` supplies deterministic class and catch layer but has no production caller (`01-inventory.md:74`); `orca_core.py:116-135` `preflight` checks tools, instructions and skills only. No scoped memory-retrieval module exists under `scripts/`; ORCA-029/030/031 remain open with no dedicated task.
