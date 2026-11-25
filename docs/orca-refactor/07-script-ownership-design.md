# Stage D design — script ownership and validator

Design returned by a read-only senior Python reviewer; no implementation claim. Evidence source: tool file reads.

## Current state

`.github/script/` and `.opencode/scripts/` do not exist. All 38 flat scripts (21 Python, 17 Bash) plus `scripts/aurevia/aurevia.sh` live under `scripts/`. Root computation is flat-path dependent: ten scripts use `Path(__file__).resolve().parents[1]`, fourteen shell scripts use `cd "$(dirname "$0")/.."`. The directive says internal subdirectories are proposed, not mandatory, so PROJECT scripts need no move.

## Ownership table (grouped)

| Group | Scripts | Proposed destination | Notes |
|---|---|---|---|
| GITHUB | `swarm.py`, `swarm_gate.py`, `check-delivery-gate.py`, `monitor.py`/`monitor.sh`, `integrator-gate.sh`, `repair-gate.sh` | `.github/script/common/`, `integration/`, `delivery/`, `watchdog/` | `swarm.py` has callers in 15 workflows plus `agent-run.sh:235,239`; its `_checker` dynamically loads sibling helpers, so it needs an explicit repo-root resolver before any move |
| OPENCODE | `agent-run.sh`, `route-task.py`, `quality-gate.sh`, `check-verdicts.py`, `gen-opencode.py`, `check-pack.py`, `taskstore.py` | `.opencode/scripts/orchestration/`, `routing/`, `validation/`, `common/` | `gen-opencode.py` and `check-pack.py` reference each other by flat path; regenerate config after any move |
| PROJECT | 12 Bash and 10 Python check/build scripts listed in `01-inventory.md` | stay at `scripts/` | invoked by `check.sh`, `quick-check.sh`, `build-and-test.sh`, `delivery.yml` and `agent-run.sh` |

Exceptions: `failure_classes.py` has no production caller and should be resolved as dead code, not moved; `orca_core.py` stays flat by owner decision (`MEMORY.md` and `03-core-mechanisms.md`); `scripts/aurevia/aurevia.sh` is already nested; `process_env.py` is imported by six scripts across layers and needs shared-utility consolidation first.

## Minimal validator

Implement `scripts/check-ownership.py` with `scripts/ownership.json` manifest (path → owner plus shrink-only exceptions), so the existing `python-coverage.sh` and `unittest discover -s scripts/tests` machinery measure it. Functions: `resolve_root`, `load_manifest`, `classify`, `discover`, `scan_ownership`, `parse_python_refs`, `parse_shell_refs`, `parse_text_refs`, `build_graph`, `find_cycles`, `find_duplicates`, `check_references`, `check_modes`, `check_test_discovery`, `check_gate_paths`, `validate`, `main`. Exit codes: 0 clean, 1 violations, 2 bad input. No subprocess, network or secrets.

Rules: wrong owner root, missing or unmanaged script, broken Python/Bash/workflow/doc reference, import or source cycles, newly duplicated basenames or top-level definitions (existing duplicates in `monitor.py:287`/`swarm.py:2212`, Git wrappers and failure classifiers are shrink-only exceptions), missing shebang/executable consistency, CRLF, companion test discovery and gate-path globs. Do not duplicate `check-names.py`, `check-pack.py`, `check-docs.py` or `check-constraints.py`; extend `check-constraints.py:31-34` when tests move because its `EXEMPT` paths hard-code `^scripts/tests/`.

## Safe first slice

Move four scripts atomically: `check-verdicts.py` and `quality-gate.sh` → `.opencode/scripts/validation/`; `route-task.py` → `.opencode/scripts/routing/`; `check-delivery-gate.py` → `.github/script/delivery/`. Each has one or two live callers and one small test module. Required edits include `quality-gate.sh` root depth and its two checker paths, `check-verdicts.py` sys.path/ROOT, `route-task.py` ROOT, `agent-run.sh:413,612`, `delivery.yml:283`, and path text in `docs/SWARM.md`, `README-SETUP.md` and the delivery skill. Static validation is possible offline; SHA-linked CI verification remains a trusted-integrator hand-off.

Every validator branch has a named test in the reviewer report, including root resolution from four CWDs, manifest/classify errors, wrong-root and unmanaged scripts, Python/AST and shell reference parsing, two-node and self cycles, duplicate detection with allow-listed debt, broken references and retired flat paths, executable/shebang/CRLF checks, test-discovery gaps, gate-glob gaps, three `main` exit paths, and a no-subprocess invariant test.
