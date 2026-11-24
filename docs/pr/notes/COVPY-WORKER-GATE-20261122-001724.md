## Summary

Added `scripts/tests/test_worker_gate_coverage.py`, a new synthetic-data test module that drives `scripts/worker_gate.py` to **100% line and branch coverage independently** (94 tests, 0 failures). The baseline report already read 100% because `test_worker_gate.py` exists, so this file is written to be self-sufficient: run on its own it reports 163 statements / 0 missed, 66 branches / 0 partial. No production code, existing test, coverage threshold, exclusion or configuration was changed.

The historical uncovered list in the story (lines 30-32, 57-62, 91-100, 104 on 2026-11-21) no longer matches the current file; as the story directs, the live coverage report was used instead of that list.

## Affected requirements

OPS-06 — CI policy checks, tooling verification and enforced 100% coverage of tooling scripts.

## Risk class

Routine — test-only addition; no production behaviour, contract or data change.

## Review owner

Senior Python Tooling and Test Engineer skills were loaded (advisory). No separate reviewer was available in this worker; verification is worker-local and tool-measured. No simulated-user or human-participant evidence applies to this test-infrastructure change.

## Data changes

none

## Authorization boundaries and audit events

none. All fixtures are temporary directories, fabricated surefire XML, made-up environment dictionaries and mocked `subprocess`/`git` calls. No token, credential, tenant identifier, real domain name or patient data is present.

## Migration and rollout considerations

none

## Test evidence

Entry point → test trace: `worker_gate.main()` → `failing_classes()` (synthetic XML reports) → `decide()`/`related()` → `candidate_from_env()`/`validate_candidate()` (synthetic notes in a temporary directory) → structured JSON violations. The `if __name__ == "__main__"` entry point is exercised through `runpy.run_path(..., run_name="__main__")`.

Commands run (working directory `/__w/Aurevia-Care/Aurevia-Care`):

- `python3 -m coverage --version` → initially missing; `python3 -m pip`/`curl` are unavailable in this image. Installed coverage 7.17.4 via the bundled PyPy pip (`/__t/PyPy/3.11.17/x64/bin/python3 -m pip install --break-system-packages coverage`) and copied the pure-Python package into the system `python3` user site so `python3 -m coverage` works. No repo file was changed for this.
- Baseline `COVERAGE_FILE=/tmp/wg_base.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -q` + `coverage report -m --include=scripts/worker_gate.py` → `scripts/worker_gate.py 163 0 66 0 100%` before any edit.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate_coverage.py' -q` → final lines `Ran 94 tests in 0.031s` / `OK` (exit 0).
- New-file-only `COVERAGE_FILE=/tmp/wg_new.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests -p 'test_worker_gate_coverage.py' -q` + `coverage report -m --include=scripts/worker_gate.py` → `scripts/worker_gate.py 163 0 66 0 100%`.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 967 tests in 22.529s` / `FAILED (failures=6)` — see Findings; the six failures are all in `test_check_pack` and are pre-existing/environmental.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_pack.py' -q` (my file not loaded) → `Ran 31 tests` / `FAILED (failures=6)` — the identical six failures, proving they are unrelated to this change.
- `bash scripts/python-coverage.sh` → exit 1 on the same six `test_check_pack` failures; the coverage-diff gate itself passes: `python3 -I scripts/python_coverage.py /tmp/wg_gate.json --base origin/main` → `python coverage: every changed script line is covered` (exit 0).
- Fast policy checks: `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-wiring.py`, `check-migrations.py --require-baseline` → all exit 0.
- `git merge --no-edit origin/main` → `Already up to date.` (HEAD == origin/main at `41912a5`); no conflict markers were introduced by this change.
- `scripts/check.sh` was intentionally NOT run: it invokes `apps/api/check.sh` → `mvn -q -B verify`, which the task forbids (full integration build). The integrator and Delivery run that gate.

## Coverage

Command and result (new test file alone):

```
COVERAGE_FILE=/tmp/wg_new.coverage python3 -m coverage run --branch --source=scripts \
  -m unittest discover -s scripts/tests -p 'test_worker_gate_coverage.py' -q
COVERAGE_FILE=/tmp/wg_new.coverage python3 -m coverage report -m --include='scripts/worker_gate.py'
Name                     Stmts   Miss Branch BrPart  Cover   Missing
scripts/worker_gate.py     163      0     66      0   100%
```

Full-suite report line (all tests) is identical: `scripts/worker_gate.py 163 0 66 0 100%`. `coverage json` confirms `missing_lines: []` and `missing_branches: []`. No pragma, exclusion, threshold change or skipped test was used.

Every branch of `scripts/worker_gate.py` and the test that takes it:

- `failing_classes` — empty glob / `ET.ParseError` / `failures>0` / `errors>0` with failures=0 / missing `name` and counter defaults / many reports: `test_empty_directory_returns_empty_set`, `test_parse_error_uses_the_report_stem`, `test_failures_counter_marks_a_class`, `test_errors_counter_marks_a_class_when_failures_is_zero`, `test_no_counter_and_no_name_defaults`, `test_many_reports_accumulate_all_failing_classes`.
- `_stem` — optional `(Integration|Isolation|Flow)` prefix and `(Test|IT)` suffix: `test_stem_strips_test_suffixes`.
- `related` — empty failing set; `cls in names`; `core in s`; `s in core`; falsy `core`; empty `stems`; non-match; `.java/.sql` filter and `discard("")`; multiple failing classes: `test_empty_failing_set_has_no_related_classes`, `test_changed_class_name_matches_directly`, `test_full_stem_substring_matches_changed_source`, `test_changed_stem_inside_class_core_matches`, `test_empty_core_does_not_match`, `test_no_source_paths_gives_empty_stems`, `test_non_matching_core_is_excluded`, `test_only_java_and_sql_paths_contribute_stems`, `test_many_failing_classes_keep_only_the_related_ones`.
- `changed_paths` — two git calls, diff+dirty merge, custom base, short dirty-line filter: `test_changed_paths_combines_diff_and_long_dirty_lines`, `test_changed_paths_honours_a_custom_base`, `test_changed_paths_drops_short_dirty_lines`.
- `head_commit` — returncode 0 / non-zero: `test_head_commit_returns_stripped_sha`, `test_head_commit_returns_empty_on_nonzero_exit`.
- `find_notes_file` — empty story id, no glob match, newest match: `test_find_notes_file_returns_empty_for_empty_story_id`, `test_find_notes_file_returns_empty_when_no_match`, `test_find_notes_file_returns_newest_match`.
- `read_notes` — empty path, missing file, present file: `test_read_notes_returns_none_for_empty_path`, `test_read_notes_returns_none_for_missing_file`, `test_read_notes_returns_file_contents`.
- `named_classes` — text and `None` (`text or ""`): `test_named_classes_extracts_backticked_classes`, `test_named_classes_handles_none`.
- `decide` — no failures, strict no named class, strict still-failing, strict other class, ordinary related, ordinary unrelated: `test_no_failures_passes`, `test_strict_task_without_named_classes_is_strict`, `test_strict_task_with_a_still_failing_named_class_fails`, `test_strict_task_ignores_failure_in_another_named_class`, `test_ordinary_task_fails_on_related_failure`, `test_ordinary_task_warns_on_unrelated_failure`.
- `parse_scope` — comma split/trim, empty part discard, no match, `None`: `test_parse_scope_splits_and_trims`, `test_parse_scope_discards_empty_parts`, `test_parse_scope_without_match_returns_empty`, `test_parse_scope_with_none_returns_empty`.
- `is_notes_file` — match, `None`, non-notes paths: `test_is_notes_file_recognises_repository_relative_notes`, `test_is_notes_file_handles_none`, `test_is_notes_file_rejects_other_paths`.
- `under_scope` — empty scope, exact file and directory child, sibling/prefix lookalikes: `test_under_scope_handles_an_empty_scope`, `test_under_scope_matches_exact_file_and_directory_children`, `test_under_scope_rejects_sibling_and_prefix_lookalikes`.
- `section_body` — next-heading stop, case-insensitive heading, absent, empty, `None`/`""`, non-heading lines, heading at EOF: `test_section_body_stops_at_the_next_heading`, `test_section_body_is_case_insensitive`, `test_section_body_returns_none_when_absent`, `test_section_body_returns_empty_string_for_empty_section`, `test_section_body_handles_none_and_empty_notes`, `test_section_body_ignores_non_heading_lines_and_other_headings`, `test_section_body_heading_at_end_has_empty_body`.
- `has_placeholder` — match, comparisons/plain text, `None`: `test_has_placeholder_detects_angle_bracket_placeholders`, `test_has_placeholder_ignores_comparisons_and_plain_text`, `test_has_placeholder_handles_none`.
- `validate_candidate` — `candidate_sha` None/empty/mismatch/equal, empty `changed`, in/out of scope, notes allowance, tracker short-circuit, each notes rule (`notes-heading`, `notes-empty`, `notes-placeholder`, `notes-unfinished`), `finished` both ways, all violations together: the eleven `ValidateCandidateTest` methods.
- `candidate_from_env` — environ given/`None`, no scope, two HEAD samples, named notes, finished flag (whitespace and `"0"`), notes fallback by story id, no notes file, default `changed_paths`: the nine `CandidateFromEnvTest` methods.
- `main` — pinned/unpinned scope, Maven status via argv/sys.argv/defaults, reports present/absent, structured candidate-changed output, scope violation, and module `__main__` guard: the eleven `MainTest` methods including `test_module_main_guard_exits_with_the_gate_result`.

## Clinical and architecture review

Not required — no signed data, cross-organization sharing, patient association, prescribing or critical results.

## Findings

- The full `scripts/tests` suite has six pre-existing failures, all in `test_check_pack`: `test_check_mode_detects_drift`, `test_cli_entry_point`, `test_main_reports_success`, `test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`. They reproduce with only `test_check_pack.py` loaded, so they are not caused by this change. Cause: the worktree `opencode.json` is the CI profile under `skip-worktree` (`git ls-files -v opencode.json` → `S ...`), so its content differs from `scripts/gen-opencode.py`'s local render — the exact false finding documented in MEMORY.md and the delivery skill for cloud workers. Out of scope here; no repo file was touched to work around it. The integrator's profile should resolve it.
- Three committed Java files under `apps/api/src/main/java/com/aurevia/exchange/hl7/` (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`) contain literal `<<<<<<< HEAD` / `>>>>>>> origin/swarm/BUILD-37-469` markers inside Javadoc in `main` at `41912a5`. Pre-existing, unrelated to this task and outside its scope; flagged for the integrator or a dedicated repair task.
- The story's historical list of uncovered lines was stale (baseline was already 100%); the live report was used as instructed.

## Lessons for workers

- In this image `python3 -m pip` and `curl` are unavailable, but the bundled PyPy at `/__t/PyPy/3.11.17/x64/bin/python3` has a working pip; `coverage` is pure Python, so installing it there and copying `coverage/` plus its `.dist-info` into `/github/home/.local/lib/python3.11/site-packages` makes `python3 -m coverage` usable without touching the repo.
- When the full `scripts/tests` suite is red on `test_check_pack` in a cloud worker, check `git ls-files -v opencode.json` before assuming your change broke it: an `S` flag means the CI profile is active under skip-worktree and those six failures are a known measurement artifact, not a defect in your diff.
- `scripts/check.sh` is not safe as a worker-final check here: it runs `mvn -q -B verify`. Run the fast Python checks and the targeted coverage gate, and let the integrator run the full build.
