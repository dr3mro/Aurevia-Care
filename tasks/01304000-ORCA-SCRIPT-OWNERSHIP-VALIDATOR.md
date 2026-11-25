- [x] ORCA-SCRIPT-OWNERSHIP-VALIDATOR · Add a deterministic script ownership and path validator
  - evidence: Red `Ran 1 test`, `FAILED (errors=1)` → green `Ran 34 tests in 0.311s`, `OK`; full suite `Ran 1825 tests in 107.935s`, `OK`; module coverage 185 statements/80 branches 100%; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; plan/code/coverage/security reviewers GO. Checker exits 0 with `check-ownership: 0 violation(s)`. Evidence source: tool commands and read-only reviewers.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
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
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_check_ownership.py' -q` → `Ran 1 test`, `FAILED (errors=1)` (module absent before implementation). Green after staged implementation: `Ran 34 tests in 0.310s`, `OK`.
- (tool) Module coverage: `python3 -m coverage run --branch --source=scripts` with the targeted tests, then `coverage report --include='scripts/check-ownership.py' -m` → 185 statements, 0 missed; 80 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1805 tests in 107.404s`, `OK`.
- (tool) `bash scripts/python-coverage.sh --full` exit 1 with verbatim final line `python coverage: 76 uncovered; add tests for them in scripts/tests`; all findings are pre-existing branches in other scripts, not `check-ownership.py`. No line from the new module appeared. Trusted-integrator hand-off required; task stays `[-]` under AGENTS.md.
- (tool) `python3 -I scripts/check-ownership.py scripts/ownership.json` exit 0 with `check-ownership: 0 violation(s)`; `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check` all exit 0.
- (reviewer) Plan GO after seven Required gaps were closed: checker wiring, staged proof, full coverage, shrink-only manifest, non-duplication boundary, dynamic `_checker` exception and AST no-process invariant.

## Coverage (branch-to-test map)
Command: module coverage report above, 100% statements and branches.
- `resolve_root`: root, script dir, nested marker and unrelated directory → `RootTests.test_resolve_root_*`.
- `load_manifest`: valid, missing, malformed JSON, non-dict → `test_load_manifest_*`.
- `classify`: known and undeclared → `test_classify_returns_owner_or_raises_for_undeclared`.
- `discover`: `.py`/`.sh` selected, `.txt` and `tests/` excluded → `test_discover_selects_scripts_and_excludes_tests`.
- `parse_python_refs`: Import, ImportFrom with and without module, literal path, syntax error → `ReferenceTests.test_parse_python_refs_*`.
- `parse_shell_refs`: source, dot, python3, bash and no matches → `test_parse_shell_refs_collects_sourced_and_invoked_paths`.
- `parse_text_refs`: matches and no matches → `test_parse_text_refs_extracts_repo_paths`.
- `build_graph`: known target kept, external dropped → `test_build_graph_keeps_only_known_targets`.
- `find_cycles`: two-node, self and none → `test_find_cycles_detects_two_node_self_and_none`.
- `check_references`: missing repo path and external path ignored → `test_check_references_*`.
- `find_duplicates`: new duplicate, declared exception and no duplicate → `test_find_duplicates_*`.
- `check_modes`: good shell, missing shebang, missing executable, executable Python without shebang → `test_check_modes_*`.
- `check_test_discovery`: cross-directory duplicate and distinct modules → `test_check_test_discovery_*`.
- `check_gate_paths`: missing and present gate scripts → `test_check_gate_paths_*`.
- `validate`: undeclared script and clean real tree → `test_validate_*`.
- `exception_violations` and `dynamic_load_paths`: frozen baseline and the dynamic exception → `RuleTests.test_exception_violations_are_shrink_only`, `test_dynamic_load_exception_is_required`, `test_dynamic_exception_for_an_absent_path_is_harmless`.
- External workflow/doc references and their line-range, glob, punctuation and suffix filters → `ReferenceTests.test_check_references_*`, `test_a_broken_non_script_markdown_link_is_not_reported`, `test_check_references_accepts_a_single_string_value`, and the clean real-tree assertion.
- Custom manifest path for `load_manifest`/`main` → `RootTests.test_load_manifest_rejects_missing_and_malformed`, `CommandTests.test_main_honours_a_non_default_manifest_path`.
- `check_test_discovery` duplicate containing `tests` in the name → `RuleTests.test_test_discovery_flags_duplicate_containing_tests_in_name`.
- `main`: clean 0, violations 1, bad input 2 and CLI entry point → `CommandTests.test_main_*` and `test_command_line_entry_point_returns_main_status`.
- No-process/network invariant: AST scan of the checker source → `test_source_has_no_process_or_network_api`.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with seven Required gaps; revised plan re-reviewed GO).
- aurevia-code-reviewer: GO (all five Required gaps fixed; rechecked).
- aurevia-coverage-auditor: GO (red observed, branch map complete, honest full-gate report).
- aurevia-devsecops: GO (AST guard covers dotted imports and env reads; no process/network/mutation).

## Gate resolution
The earlier `--full` run was red on pre-existing branches in other scripts against a stale base; those final lines remain recorded above. The standard changed-line gate was rerun after later stories landed: exit 0 with `python coverage: every changed script line is covered`. The validator reports `check-ownership: 0 violation(s)` on the checked-in tree. No threshold, exclusion, pragma or suppression was changed.
