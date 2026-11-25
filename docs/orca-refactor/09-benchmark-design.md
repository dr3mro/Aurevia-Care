# Stage C/N benchmark harness design

Design returned by a read-only test engineer; no implementation claim. Evidence source: tool file reads.

## Passive measurement API

Proposed `scripts/orca_benchmark.py`, with the same analysis-only boundary as `scripts/orca_core.py`: no `subprocess`, Git, network, tokeniser or wall-clock timing.

- `count_files(root, pattern) -> int`, `count_bytes(paths) -> int`: deterministic file and byte counts, never token counts.
- `doc_load_count(profile) -> int`: length of the generated `instructions[]` array, baseline 5 (`.opencode/opencode.json:4-10`).
- `count_tests(test_dir) -> int`: AST count of `def test_*`; baseline 24 for `test_orca_core.py`.
- `count_agents(root)` and `count_skills(root)`: 39 agent files and 40 skill files (`01-inventory.md:62-63`).
- `measure(root) -> dict`: measured integers only plus an explicit `unavailable` list.
- `EvidenceSource`: `TOOL`, `REVIEWER`, `SIMULATED_USER`, `HUMAN_PARTICIPANT`, `UNAVAILABLE`.
- `record(task_id, metric, value, source) -> dict`: evidence row that refuses a numeric value for an unavailable metric.
- `first_attempt(row) -> bool`: true only when acceptance passed and `repair_cycles == 0`; missing fields fail closed.

## Metrics that must stay unavailable

Initial and total tokens, document token cost (no tokeniser), real or median duration, agent invocations, tool calls, duplicate reads, actual skill activation, context compaction, model fallback (owner-disproven), provider failures, runner consumption, integration conflicts, live repair cycles, live first-attempt success, exact-SHA and branch-protection evidence, and real clinician feedback. Runtime benchmark categories (Java, React, Mobile, DB, clinical, security, concurrency) cannot be measured by a static harness.

## Representative measurements

| Requirement | Command | First-attempt criterion |
|---|---|---|
| ORCA-002 | `measure()['bytes']` | instruction 183008, agent 14331, skill 96998 bytes match `01-inventory.md:84`; token fields marked unavailable |
| ORCA-007 | `test_instruction_paths*.py` | 5 native entries including CONSTRAINTS.md, no retired paths, no broken refs |
| ORCA-028 | `measure()['agents']` + `check-pack.py` | 39 files equal 39 config keys |
| ORCA-081 | `measure()['skills']` + `test_gen_opencode*.py` | 40 skills, directory equals frontmatter name, no duplicates |
| ORCA-051 | `measure()['files']` | 21 Python and 17 Bash scripts in `scripts/` |
| ORCA-045 | `test_orca_core*.py` | 24 tests green, AST count equals executed count, zero skips |
| ORCA-017/018 | same core tests | context budget branches pass; manifest hash stable |
| ORCA-092 | `test_instruction_paths*.py` | zero broken links, repaired paths present |
| ORCA-059 | `measure()` on `apps/*/check.sh` | three module check entry points |
| ORCA-044 | `measure()` + `record(source=TOOL)` | percentages labelled targets, unavailable metrics not numeric |

## Anti-fabrication rules

Ratios come from two integers in the same `measure()` call and are stored with numerator and denominator. The 40/30/25/50/90 percent figures in `orca-refactor.md:1002-1004` are targets, not results. `record()` emits `"unavailable"` for denied metrics; `first_attempt()` fails closed on missing repair data. Every row carries an evidence source, reports never average across source types, and no wall-clock duration is claimed. Revision pinning is a trusted-integrator task.
