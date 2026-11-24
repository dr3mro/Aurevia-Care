# COVPY-GEN-OPENCODE — Script tests: 100% coverage of `scripts/gen-opencode.py`

## Summary

Adds a new, self-contained test module `scripts/tests/test_gen_opencode_coverage.py` that drives
every public function and both command-line modes of `scripts/gen-opencode.py` with synthetic
policy data and temporary directories. No production code, existing test, threshold, exclusion or
dependency was changed. The suite proves 100% line and branch coverage of the generator from this
one file alone, including its zero-item and many-item loops and every failure path.

Requirement mapping: OPS-06 (reproducible, validated tooling). Acceptance: (1) the new file is the
only scope change — verified by `git status`; (2) `coverage report -m --include=scripts/gen-opencode.py`
reads 100% with zero partial branches; (3) `python3 -I -m unittest discover -s scripts/tests` is green.
All three are met.

Evidence sources: all results below came from **executed tool commands** run in this worker. No
reviewer, simulated user or human participant evidence was used; no specialist review was requested
for a routine test-only change. The `orevia-delivery` and `orevia-senior-python` skills
were loaded.

## Affected requirements

OPS-06

## Risk class

Routine — a single new test file, no production code, contracts, migrations or deployment changes.

## Review owner

Senior Python Tooling (`orevia-senior-python` skill loaded). No separate reviewer needed for a
routine test-only change; no clinical, security or architecture boundary was altered.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None added or changed. The tests only assert that the generator's existing hard denies (force push,
`--no-verify`, `.env` reads, CI publication commands) and secret-read rules are emitted; they do not
change any permission or audit behavior.

## Migration and rollout considerations

None. Test-only change; no rollout or deployment gate affected.

## Test evidence

Commands run (working directory `/__w/Aurevia-Care/Aurevia-Care`, branch `swarm/COVPY-GEN-OPENCODE-510`):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_gen_opencode_coverage.py' -v`
  → `Ran 23 tests in 0.184s` / `OK` (23 new tests, all pass in isolation).
- `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → suite completed; report below.
- `python3 -m coverage report -m --include=scripts/gen-opencode.py`
  → final line: `scripts/gen-opencode.py      98      0     48      0   100%`
- `COVERAGE_FILE=/tmp/opencode/cov-alone python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p 'test_gen_opencode_coverage.py'`
  followed by the same `coverage report -m --include=...`
  → `Ran 23 tests in 0.246s` / `OK` and the **same 100% report line from this file alone**. The new
  tests are self-sufficient and do not rely on pre-existing tests for coverage.
- `python3 -I -m unittest discover -s scripts/tests`
  → `Ran 896 tests in 20.248s` / `OK` (full suite green after the local worktree repair noted under
  Findings; 873 baseline tests plus these 23).
- `bash scripts/python-coverage.sh`
  → exit 0, final line: `python coverage: every changed script line is covered`.
- Targeted `scripts/check.sh` components, all `PASS`: `project-name.py displayName`, `check-pack.py`,
  `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`,
  `check-migrations.py --require-baseline`.
- `bash scripts/check.sh` was started but exceeded the 5-minute worker limit and was stopped
  (exit 143, SIGTERM). It runs the full Java/Node/PostgreSQL module suites, which are unrelated to a
  Python test-only change; the targeted subset above was run instead, as instructed.

Entry-point-to-test trace: `gen-opencode.py` public functions `rule`, `build`, `render`, `_read_json`,
`check_runtime`, `main` and the `__main__` guard are each called directly (or via `runpy.run_path`
for the entry point) from `test_gen_opencode_coverage.py`; assertions are on returned config dicts,
rendered JSON, violation lists and `main` exit status/stderr.

## Coverage

Command and result:

```
python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests
python3 -m coverage report -m --include=scripts/gen-opencode.py
Name                      Stmts   Miss Branch BrPart  Cover   Missing
---------------------------------------------------------------------
scripts/gen-opencode.py      98      0     48      0   100%
---------------------------------------------------------------------
TOTAL                        98      0     48      0   100%
```

No line or branch is missing; `BrPart = 0` means all 48 branch outcomes were taken. The same 100%
result is produced by `test_gen_opencode_coverage.py` alone. Every branch and its covering test:

| Branch / path in `gen-opencode.py` | Test in `test_gen_opencode_coverage.py` |
|---|---|
| `rule()` mapping | `test_rule_returns_a_permission_mapping` |
| `build()`: zero specialists (`set(names) - {PRIMARY}` empty) | `test_build_with_no_specialists_has_no_subagent_grants` |
| `build()`: many/duplicate/unsorted names, both specialist loops | `test_build_with_many_specialists_grants_each_one` |
| `build()`: `ci` False and True (`CI_SHELL_DENY` in/out) | `test_build_with_no_specialists_has_no_subagent_grants`, `test_render_handles_empty_and_populated_agent_directories` |
| `render()`: empty `.opencode/agents` glob; populated glob; both `ci` values | `test_render_handles_empty_and_populated_agent_directories` |
| `_read_json()`: valid JSON success | `test_valid_json_is_returned_without_violation` |
| `_read_json()`: `except OSError` (missing file) | `test_missing_file_adds_a_labelled_violation` |
| `_read_json()`: `except ValueError` (malformed JSON) | `test_malformed_json_adds_a_labelled_violation` |
| `check_runtime()`: `profile is None` short-circuit | `test_missing_profile_and_slots_accumulate_both_violations` |
| `check_runtime()`: `slots is None` with profile present | `test_missing_slots_alone_returns_early` |
| `check_runtime()`: neither None (happy path) | `test_clean_synthetic_profile_has_no_violations`, `test_repository_runtime_profile_is_clean` |
| instruction file missing / present / zero instructions | `test_missing_and_empty_instruction_lists`, `test_absent_optional_keys_are_treated_as_empty` |
| `declared is not None` false (model key absent) | `test_clean_synthetic_profile_has_no_violations` |
| `declared not in models` true (agent and profile models) | `test_model_declaration_branches` |
| `declared in models` false (allowed model) | `test_model_declaration_branches` |
| `reviewer` true / false | `test_reviewer_permission_branches`, `test_clean_synthetic_profile_has_no_violations` |
| reviewer allow of non-read-only action (true) | `test_reviewer_permission_branches` |
| reviewer allow of read-only action / deny (false) | `test_reviewer_permission_branches` |
| `elif not bash_rules` true / false | `test_editor_bash_list_branches`, `test_clean_synthetic_profile_has_no_violations` |
| write action + allow effect true / either guard false | `test_write_permission_guards` |
| `fnmatchcase(protected, resource)` true / false | `test_write_protection_for_every_protected_file`, `test_write_permission_guards` |
| secret patterns 1–5 in agent config (all true and false) | `test_all_secret_patterns_in_agents_and_profile_metadata` |
| secret patterns 1–5 in profile metadata (all true and false) | `test_all_secret_patterns_in_agents_and_profile_metadata` |
| absent `instructions` / `agents` / `slots` keys (zero loops) | `test_absent_optional_keys_are_treated_as_empty` |
| `main()`: `--check-runtime`, zero violations → 0 | `test_check_runtime_flag_success_and_failure` |
| `main()`: `--check-runtime`, many violations → 1, both printed | `test_check_runtime_flag_success_and_failure` |
| `main()`: generate mode writes both outputs (`ci` False and True) | `test_generate_mode_writes_both_profiles` |
| `--check`: target missing (`not target.exists()` true) | `test_check_mode_clean_stale_and_missing` |
| `--check`: target stale (`read_text() != text` true) | `test_check_mode_clean_stale_and_missing` |
| `--check`: both targets current (false) | `test_check_mode_clean_stale_and_missing` |
| `__main__` guard `sys.exit(main())` | `test_script_entry_point_exits_cleanly` |

## Clinical and architecture review

Not required: no clinical data, patient identity, prescribing, results release or architecture
boundary was touched. No production code changed.

## Findings

- **Pre-existing environment artifact — worktree `opencode.json` was the CI profile.** `git ls-files -v
  opencode.json` showed flag `S` (skip-worktree) and the file was byte-identical to
  `.github/opencode.ci.json`, not to `gen.render(ROOT)`. This made six tests in
  `scripts/tests/test_check_pack.py` fail before any change from this worker. Proven pre-existing by
  removing the new test file and re-running: `Ran 873 tests ... FAILED (failures=6)` with the same six
  names (`test_check_mode_detects_drift`, `test_cli_entry_point`, `test_main_reports_success`,
  `test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`,
  `test_simulated_user_skills_need_no_process_sections`). `git show HEAD:opencode.json` equals
  `gen.render(ROOT)`, so I restored that committed content into the worktree as a local test repair.
  Because the file is skip-worktree, this produced **no git diff**; `git status` showed only the new
  untracked test file. After the repair the full suite was `Ran 896 tests ... OK`. This is the known
  worker-image skip-worktree measurement artifact documented in MEMORY.md and `orevia-delivery`;
  no file under `scripts/` or `.opencode/` was edited.
- The coverage run touched the tracked `.coverage` artifact; I restored it with `git restore .coverage`.
  Final `git status --short` was `?? scripts/tests/test_gen_opencode_coverage.py` only (plus this
  notes file once created).
- **Pre-existing committed conflict markers (outside scope).** `git grep -nE '^(<<<<<<<|>>>>>>>) '`
  finds markers in three tracked Java files: `Hl7ParseException.java`, `Hl7Parser.java` and
  `Hl7Segment.java` under `apps/api/src/main/java/com/aurevia/exchange/hl7/`. `git diff
  --name-only --diff-filter=U` is empty and `git merge --no-edit origin/main` reported "Already up to
  date", so these markers are already committed in HEAD (an earlier swarm merge), not a conflict from
  this worker. They are outside this task's scope and were left untouched. The next worker/integrator
  should decide whether to clean them (they look like duplicate Javadoc lines in a merge resolution).
- No defect in `scripts/gen-opencode.py` was found. The dependency `WQUALITY-05` (commit `9aaa67ef`)
  had already brought the script to 100% coverage; this task's dedicated test file locks that in with
  an independent suite. No unreachable line was found, so nothing was listed for removal.
- `gen-opencode.py` does not call `gh` or `subprocess`, so there was nothing of that kind to mock;
  the tests mock `gen.check_runtime` and `gen.ROOT` where needed and use temporary directories. No
  network calls occur.

## Lessons for workers

- In a cloud worker, check `git ls-files -v opencode.json` before trusting a red `check-pack.py` or
  `gen-opencode.py --check`. Flag `S` means the worktree holds the CI profile; six pack failures then
  are a measurement artifact, not a regression. Compare against `git show HEAD:opencode.json` and
  restore that content locally (no git diff) before running the full suite.
- A coverage report that is already 100% does not mean the story is empty: a dedicated test file can
  still add value by proving coverage without relying on other test modules. Verify this by running
  coverage with `-p '<new-test-file>'` alone.
- `python3 -m coverage run --source=...` writes the tracked `.coverage` file; restore it afterwards
  so the scoped commit stays clean.
- `bash scripts/check.sh` runs full Java/Node/Postgres module suites and can exceed a worker's
  5-minute cap. For a Python test-only change, run the fast components (`check-pack.py`,
  `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py`, python-coverage.sh,
  full unittest) instead and record why.
