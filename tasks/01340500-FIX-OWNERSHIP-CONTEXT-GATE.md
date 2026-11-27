- [-] FIX-OWNERSHIP-CONTEXT-GATE · Resolve contextual script references and explain Delivery skips
  - agent: orca
  - attempts: 0
  - scope: scripts/check-ownership.py, scripts/check-delivery-gate.py, scripts/tests/test_check_ownership.py, scripts/tests/test_check_delivery_gate.py, scripts/tests/test_delivery_workflow_gates.py, tasks/01340500-FIX-OWNERSHIP-CONTEXT-GATE.md
  - requirements: OPS-06
  - depends: 01339000-FIX-45920447774
  - details:
    Follow-up to the completed documentation repair in task 01339000. Implement the owner-requested behavior that repair did not include: resolve package script references from the containing package directory, resolve workflow references from each run step's effective working-directory, fall back to the repository root, and report a broken reference only when neither candidate exists. Keep root-level package behavior and the three existing real-tree ownership tests green. Do not edit package.json.
    Also make Delivery's failed script-tests root cause appear before dependent frontend/java-verify/docs/verify skips, without changing workflow topology or any pass/fail rule. Prove changed-line coverage at 100% and run the full script test suite.

## Loaded skills
- aurevia-delivery (every story), aurevia-senior-python (`scripts/check-ownership.py`), aurevia-github-workflow (Delivery dependency diagnostics), aurevia-plan-reviewer (pre-code review), aurevia-test-engineer (red-first regressions), aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops, aurevia-qa-reviewer, aurevia-debugger (full-suite process-cleanup error).

## Plan and review checkpoint
- (reviewer) `plan-reviewer`: GO after three revisions required package/workflow source context, malformed-shape handling, exact gate DAG/order evidence and provisioned PyYAML commands.
- (reviewer) `senior-python`, `github-workflow`, `code-reviewer`, `coverage-auditor`, `devsecops` and `qa-reviewer`: final GO after red-first hardening for source confinement, bounded ingestion, malformed inputs, workflow env/with context, canonical `./` references, mode rereads and manifest trust.

## Evidence log
- (tool) Baseline ownership reproduction: `Ran 34 tests`, `FAILED (failures=3)` on `broken reference: scripts/gen-api-types.sh`. Initial new regressions then produced `Ran 46 tests`, `FAILED (failures=6, errors=5)`; gate regressions produced `Ran 28 tests`, `FAILED (failures=12)`.
- (tool) Hardening red runs: `Ran 51 tests`, `FAILED (failures=11, errors=3)`; `Ran 57 tests`, `FAILED (failures=4, errors=3)`; `Ran 61 tests`, `FAILED (failures=4, errors=2)`. Each red run preceded the corresponding production fix.
- (tool) Green targeted: ownership `Ran 61 tests`, `OK`; gate `Ran 29 tests`, `OK`; workflow gates `Ran 24 tests`, `OK`.
- (tool) Full suites during development: `Ran 2399 tests`, `OK`; `Ran 2405 tests`, `OK`; `Ran 2410 tests`, `OK`.
- (tool) Latest full suite `Ran 2414 tests`, `FAILED (errors=1)` only in unrelated `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant`: `os.killpg` raised `PermissionError: Operation not permitted`. The isolated rerun passed `Ran 1 test`, `OK`; final post-rebase full rerun pending.
- (tool) `PATH=/opt/homebrew/bin:$PATH python3 -I scripts/check-ownership.py scripts/ownership.json` → `check-ownership: 0 violation(s)`.
- (tool) `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-pack.py` and `check-constraints.py --require-base` → exit 0; pack reported `Delivery tooling validated: 39 agents, 39 skills`; `git diff --check` → exit 0.

## Coverage
- Command: targeted `coverage run --branch --source=scripts` across `test_check_ownership.py`, `test_check_delivery_gate.py` and `test_delivery_workflow_gates.py`, then `coverage report --include='scripts/check-ownership.py,scripts/check-delivery-gate.py' -m`.
- Result: `check-ownership.py` 368 statements / 162 branches, 0 missed or partial, 100%; `check-delivery-gate.py` 88 statements / 44 branches, 0 missed or partial, 100%.
- `load_manifest`: relative confinement, explicit absolute trust, size bound and malformed/missing input → `test_load_manifest_confines_relative_paths_but_trusts_explicit_absolute_paths`, `test_load_manifest_rejects_missing_and_malformed`.
- `normalize_reference` / parsers / `build_graph`: citation cleanup, `./` canonicalization and cycle detection → `test_parse_text_refs_extracts_repo_paths`, `test_build_graph_canonicalizes_dot_prefixed_targets_before_cycle_detection`, `test_dot_prefixed_missing_reference_is_normalized`.
- `_mapping`, `_mapping_field`, `_string_refs`: valid, missing and wrong-shaped fields plus string/non-string values → workflow wrong-shape, non-string-default and metadata tests.
- `package_references`: invalid JSON, non-object JSON, missing/wrong/empty scripts, valid strings and non-string values → `test_package_references_read_only_string_script_values`, `test_package_discovery_is_bounded_and_ignores_unrelated_json_fields`.
- `working_directory` / `workflow_references`: literal, dynamic and non-string directories; workflow/job/step inheritance; missing/empty/wrong steps; invalid YAML/shapes; env/with refs, collision-safe keys and empty refs → workflow context, metadata, empty-step, wrong-shape, collision and dynamic-directory tests.
- `_exists_within` / `check_references`: local hit, root fallback, missing-both, unrelated source/package, external/absolute/`..` rejection, symlink escape, root/candidate resolve errors, embedded NUL and stat errors, bare-set/string/set inputs → package/root/fallback/source-isolation, dot-prefixed, external-path and existing reference tests.
- `_read_source`: confined source, oversized source, embedded-NUL/resolve failure and read failure → `test_symlinked_sources_outside_checkout_are_not_read`, `test_oversized_and_unreadable_sources_fail_without_parsing`.
- `check_modes`: supplied content, rejected-source skip, direct bounded read failure and stat failure → `test_check_modes_reports_stat_errors_for_supplied_content`, `test_validate_reports_unreadable_script_without_mode_reread`, `test_oversized_and_unreadable_sources_fail_without_parsing`.
- `check_gate_paths`: regular in-checkout file, missing file, external symlink and directory → `test_check_gate_paths_reports_missing_and_accepts_present`, `test_check_gate_paths_rejects_external_symlinks_and_directories`.
- `validate`: package present/absent/refs/no-refs, internal rejected sources, workflow entries/problems, Markdown/YAML included and non-files/non-workflows excluded → bounded discovery, workflow and document-reference tests.
- Gate dependency roots/order/event rules: direct, transitive, multiple, unrelated, cancelled, unknown, missing and event-legal skips → dependency tests in `test_check_delivery_gate.py`; real DAG parity → `test_delivery_dependency_chain_matches_gate_diagnostic_order`.
- Gate `main` reporting: absent known job omitted, expected jobs in workflow order and extra jobs sorted afterward → `test_missing_job_fails_the_gate`, `test_unknown_job_fails_the_gate`, `test_script_tests_failure_precedes_dependent_skips`.

## Review verdicts
- (reviewer) `plan-reviewer`: GO.
- (reviewer) `senior-python`: GO.
- (reviewer) `github-workflow`: GO.
- (reviewer) `qa-reviewer`: GO.
- (reviewer) `code-reviewer`: GO.
- (reviewer) `coverage-auditor`: GO.
- (reviewer) `devsecops`: GO.
