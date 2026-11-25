- [-] ORCA-SCRIPT-OWNERSHIP-VALIDATOR · Add a deterministic script ownership and path validator
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-057, ORCA-060
  - scope: scripts/check-ownership.py, scripts/ownership.json, scripts/tests/test_check_ownership.py, tasks/01304000-ORCA-SCRIPT-OWNERSHIP-VALIDATOR.md
  - details: Implement the minimal validator designed in `docs/orca-refactor/07-script-ownership-design.md`. It enforces declared ownership, broken references, import/source cycles, duplicate debt, executable modes, test discovery and gate-path globs. Do NOT move any script, change agent or skill IDs, or modify check-constraints.py EXEMPT paths. No subprocess, network or secrets; every added line and branch needs a named test.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Stage 1 — red tests then implementation for `resolve_root`, `load_manifest`, `classify`, `discover`; prove with `python3 -I -m unittest discover -s scripts/tests -p 'test_check_ownership.py' -q`. Stage 2 — `parse_python_refs`, `parse_shell_refs`, `parse_text_refs`, `build_graph`, `find_cycles`, `find_duplicates`; rerun the same command. Stage 3 — `check_references`, `check_modes`, `check_test_discovery`, `check_gate_paths`, `validate`, `main`; rerun. Leave the task `[-]` after any unfinished stage; do not claim the whole validator green.
2. Keep the validator additive and passive: no script moves, no edits to other checkers, no Git calls. Manifests declare ownership; the validator never infers intent from callers.
3. Wire and prove the checker: `python3 -I scripts/check-ownership.py scripts/ownership.json` must exit 0 on this tree, with its exact output recorded. `scripts/check.sh` is out of scope; if permanent wiring is needed, write a task request instead of editing it.
4. Shrink-only manifest: `scripts/ownership.json` exceptions may only be removed, never added by this change. A named test asserts that adding a duplicate or exception fails the validator and removing one passes, mirroring `wiring-baseline.json`.
5. Non-duplication boundaries: the doc-reference rule covers only retired script paths, not general Markdown links owned by `check-docs.py`; test-discovery and gate-glob rules do not re-check `check-names.py`/`check-pack.py`. Add a negative test that a broken non-script Markdown link is not reported, and assert both sides of the duplicate and gate-glob checks plus the exact exit-2 value.
6. Dynamic-reference blind spot: `swarm.py:_checker` loads sibling helpers dynamically, so static parsing cannot see that edge. Record it as a named exception in `ownership.json` with a test that the exception is required and that removing it fails; do not assert dynamic edges are statically proven.
7. No-subprocess/no-network invariant: an AST test parses `check-ownership.py` source and fails on `subprocess`, `Popen`, `os.system`, `os.popen`, `socket`, `urllib`, `requests` or token-style environment reads — not a vacuous mock.
8. Gates: targeted tests per stage; full Python suite; `bash scripts/python-coverage.sh --full` with the coverage report opened and zero missing lines/branches for `check-ownership.py` recorded (never cite a "changed-line check was skipped" line as evidence); `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`. If any gate is red on pre-existing files, record the verbatim final line and leave the task `[-]` for the trusted integrator; no threshold, exclusion, pragma or skip.
9. Evidence goes in this task file and a new `.agents/notes/01304000-<stamp>.md` with the AGENTS.md headings; reviewers' verdicts are copied into both.

## Evidence log
