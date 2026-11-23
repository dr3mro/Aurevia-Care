# COVPY-SWARM-INTEGRATOR — swarm.py integrator band coverage (re-dispatch, attempt 3)

## Summary

No new test code was needed. The scoped test file `scripts/tests/test_swarm_integrator_coverage.py`
already holds 21 passing tests (merged in commit `6b7e4ffa`) that cover every line and every branch
of `scripts/swarm.py` source lines 1850-2450. A fresh branch-and-line measurement in this worker
shows **zero missed lines and zero missed branches with a source or destination line in 1850-2450**.
The re-dispatch brief's premise — "add tests only for still-uncovered lines in the band" — is
therefore wrong: there are no still-uncovered lines. Adding a test for an already-covered line would
be "a test that only exists to touch a line" (AGENTS.md, quality bar §3), so the correct action is
to keep the existing file intact, document the measured evidence and hand the structural gap
outside this band to the integrator. No production script, existing test, tracker, workflow or
protected file was changed; the only committed file is this notes file.

## Affected requirements

- OPS-06 — synthetic CI tooling and checked delivery pipeline. Acceptance mapping:
  - Task step 1 (read `swarm.py` and the existing swarm tests, copy the fake `gh` helper): done in
    the already-merged scoped file; `FakeGh` is local to that file, nothing is imported from another
    test module.
  - Task step 2 (measure first, trust the report not the stale line list): done. Baseline measured
    in this worker: `scripts/swarm.py 2601 76 1344 9 97%`.
  - Task step 3 (new tests only in the scoped file, synthetic data, no network, both sides of
    every branch): satisfied by the existing 21 tests; no new test was warranted.
  - Task step 4 (paste the coverage lines of the range): done under `## Coverage`.
  - Scope: only this notes file changed in this run; the scoped test file is unchanged.

## Risk class

Routine — read-only measurement and documentation of a test-only CI tooling change. No runtime
behaviour, contract, tenant boundary or clinical workflow is touched.

## Review owner

`smartclinic-senior-python` is the area routing named in TODO.md step 2 for Python tooling. The
brief additionally named read-only specialists, which were consulted in stage order:
`smartclinic-context-optimizer` (which lines remain uncovered and which sibling tests cover them),
`smartclinic-plan-reviewer` (before deciding), `smartclinic-code-reviewer` and
`smartclinic-coverage-auditor`. `smartclinic-devsecops` was dropped per the brief (test-only change;
no credential, identity, tenant, audit or workflow-control code). All reviewers were bounded
simulated/static subagents; their verdicts are copied under `## Review verdicts`.

## Data changes

None. No migration, schema, backfill, lock or volume impact. All fixtures in the scoped test file
are synthetic (`FIX-100`, `CW-001`, `2026-11-19`) in temporary directories.

## Authorization boundaries and audit events

None added. The existing tests assert that a fix worker changing workflow files releases no claim
and pushes nothing, that a repair PR carries the `autofix` label, and that a rules patch outside the
allowed instruction documents is dropped before a PR opens — all through mocked `gh`/git with no
token and no network. No audit event or patient scope changed.

## Migration and rollout considerations

None. This run changes no test code; the integrator still runs the full gate on the merge result.

## Test evidence

Environment: Python 3.11, coverage 6.5.0 (already present; no dependency added). All commands below
were executed in this worker (tool evidence). `origin/main` was merged at the start (`Already up to
date.` at HEAD `deea5ca0`); no conflict markers exist in the scoped file. A temporary
`COVERAGE_FILE` under `/tmp/opencode` was used so the tracked `.coverage` stayed byte-identical
(`git status --short` was empty before this notes file was written).

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py' -v`
  → `Ran 21 tests in 0.042s` / `OK` (scoped suite green).
- `COVERAGE_FILE=/tmp/opencode/integrator.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → `Ran 1307 tests in 47.082s` / `FAILED (failures=6)`; `COVERAGE_RUN_EXIT=1`. All six failures are
  `test_check_pack` (`test_check_mode_detects_drift`, `test_cli_entry_point`,
  `test_main_reports_success`, `test_committed_opencode_json_is_generated`,
  `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`), the
  documented CI-profile false positive. Confirmed once:
  `git ls-files -v opencode.json` → `S opencode.json`, while `git show HEAD:opencode.json` is the
  correct committed local profile. Not re-diagnosed on a later pass; no protected file regenerated.
  (The earlier attempt's two `test_worker_gate*` failures did not recur.)
- `python3 -I -m unittest discover -s scripts/tests`
  → `Ran 1307 tests in 39.597s` / `FAILED (failures=6)` — the same six documented failures.
- `python3 -m coverage report -m --include=scripts/swarm.py`
  → `scripts/swarm.py 2601 76 1344 9 97%`.
- `COVERAGE_FILE=/tmp/opencode/integrator.coverage python3 -m coverage json -o /tmp/opencode/integrator.json -q`
  plus a JSON filter on source **and** destination line:
  `MISSING LINES in 1850-2450: []`, `MISSING BRANCHES in 1850-2450: []`.
- `bash scripts/python-coverage.sh` → `PY_COVERAGE_EXIT=1`; `bash scripts/python-coverage.sh --full`
  → `PY_COVERAGE_FULL_EXIT=1`. Both abort at the `coverage run` step because `set -e` sees the six
  documented `test_check_pack` failures, before the policy check runs. No failure came from the
  scoped file.
- `python3 -I scripts/python_coverage.py /tmp/opencode/integrator.json --base origin/main`
  → `python coverage: every changed script line is covered`, `POLICY_EXIT=0` (vacuous here: no
  production script line changed).
- `python3 -I scripts/python_coverage.py /tmp/opencode/integrator.json --base origin/main --full`
  → `python coverage: 443 uncovered; add tests for them in scripts/tests`, `POLICY_FULL_EXIT=1`.
  These are pre-existing gaps across many scripts; a filter of the same output for
  `swarm.py:(18xx|19xx|2[0-4]xx|2450)` returned **0** entries.
- `bash scripts/coverage-check.sh origin/main` → `coverage check: passed`,
  `COVERAGE_CHECK_EXIT=0`.
- `python3 - <<... swarm.request_entries(...) ...>` on a sample request with scope
  `scripts/tests/test_swarm_tail_coverage.py` → `scope_ok: False`, `entries: []` (tool evidence for
  the Findings item below).
- `git merge --no-edit origin/main` → `Already up to date.`; `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- scripts/tests/test_swarm_integrator_coverage.py` → none.
- `python3 -I scripts/check-docs.py` → `CHECK_DOCS_EXIT=0`; `python3 -I scripts/check-constraints.py --base origin/main` → `CHECK_CONSTRAINTS_EXIT=0`; `python3 -I scripts/check-verdicts.py <this note> --base origin/main --require <the four reviewers>` → `verdicts: only documentation changed; nothing to review`, `VERDICT_CHECK_EXIT=0`.

Evidence provenance: all commands above are tool evidence executed in this worker. The five
specialist reviews are simulated/static reviewer evidence. No simulated-user walkthrough and no
human or clinical participant was involved. There is no patient data, credential or raw log in
this note.

Entry-point-to-test trace: `scripts/swarm.py` public helpers (`release`, `publish`,
`note_worker_outcome`, notes helpers, `push_main`, `archive_day`, `notes_issue_entries`,
`open_rules_pr`) → `scripts/tests/test_swarm_integrator_coverage.py` 21 test methods → the coverage
JSON arcs listed under `## Coverage`.

## Coverage

Command:
`COVERAGE_FILE=/tmp/opencode/integrator.coverage python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
then `python3 -m coverage json -o /tmp/opencode/integrator.json -q`, filtered to lines whose source
or destination is in 1850-2450; plus `bash scripts/coverage-check.sh origin/main`.

Result: `scripts/swarm.py` 97% overall (2601 statements, 76 missed lines, 9 partial branches),
with **0 missed lines and 0 missed branches in 1850-2450**. This run added no production branch,
so there is no new branch to map; for completeness every executable unit in the band and the test
that takes it is listed below (verified by the context-optimizer's source↔test mapping and the
coverage auditor's assertion check):

| Lines (`swarm.py`) | Function / branch | Named test with assertion |
|---|---|---|
| 1860-1872 | `is_worker_job`, `worker_task_id` | `test_swarm.py` worker-id tests |
| 1875-1898 | `reclaim` (live stays / merged→done / open PR stays / only-closed→todo) | `test_swarm.py:754-794` |
| 1901-1905 | `release_claim` | `test_swarm.py:797-805` |
| 1908-1930 | `update_todo` (no-op edit, final push error re-raised) | `test_swarm.py:2896-2917`, `test_swarm_workers_coverage.py:73-105`, `test_swarm_cli_coverage.py:198-224` |
| 1933-1946 | `claim_open` (todo granted vs taken/done skipped) | `test_swarm.py:2700-2705`, `test_swarm_cli_coverage.py:218` |
| 1949-1970 | `prefix`, `overlaps`, `task_id_of` (named / bare / no match) | `test_swarm.py:810-811, 912-923` |
| 1973-2094 | `frontend_gate_rule` (empty / staff-web / patient-mobile), `task_text`, `priority_of` (explicit / HIGH PRIORITY / AUD- low), `plan_workers` | `test_swarm.py:626-645, 995, 156-167, 399, 481-527, 620, 651-733, 2414-2432` |
| 2097-2115 | `release` (step summary written / not configured) | `test_swarm_integrator_coverage.py:90-98` (`ReleaseSummaryTest`), `test_swarm.py` `ReleaseTests` |
| 2118-2242 | `read`, `publish` (MERGED/CLOSED, workflow-file guard, non-fast-forward, draft/incomplete, PR-create failure, commits-but-no-bundle), `note_worker_outcome` | `test_swarm_integrator_coverage.py:104-145` (`PublishGuardTest`), `test_swarm.py:2649-2821` |
| 2288-2436 | `new_york_now`, `note_day`, `referenced_notes`, `notes_collect`, `push_main`, `archive_day`, `store_daily`, `retire_dailies`, `notes_issue_entries` | `test_swarm_integrator_coverage.py:151-287` (`NotesClockTest`, `PushMainTest`, `ArchiveDayTest`, `NotesIssueEntriesTest`) |
| 2439-2450 | `rules_patch_ok`, `open_rules_pr` (symlink/binary/rename/allowed/archive; PR URL matched or not) | `test_swarm_integrator_coverage.py:317-348` (`RulesPullRequestTest`) |
| 2483-2516 | `notes_publish` (issue not opened, rules PR raises) | `test_swarm_integrator_coverage.py:351-396` (`NotesPublishFailureTest`) |

No threshold, exclusion, `# pragma: no cover`, `.skip`, `@unittest.skip` or generated-file marker
was added or weakened. The only `# noqa` in the band (`swarm.py:2264`, `BLE001`) is pre-existing and
byte-identical to `origin/main`; it is a lint suppression, not a coverage exclusion.

## Clinical and architecture review

Not required. The change is confined to reading and documenting tests for CI tooling; no signed
data, cross-organization sharing, patient association, prescribing or critical result is involved.

## Review verdicts

- smartclinic-context-optimizer: GO
- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: omitted per brief (test-only; no code change)

## Findings

- **Brief premise is stale.** It says to "add tests only for still-uncovered lines in the band", but
  the fresh measurement shows zero uncovered lines and zero partial branches in 1850-2450. The
  earlier merged 21 tests already achieved this; no new test was added rather than fabricate a
  coverage-only test. Recorded per the brief's instruction to say when the brief is wrong.
- **Whole-file coverage is 97%, not 100%.** The remaining gaps belong to other bands: 581-585
  (PLAN, 1-700) and 829-898 (SCHEDULE, 700-1250) are still owned by `[-]` sibling tasks. The rest —
  3084, 3118, 3294-3296, 3759, 3808-3811, branch 3821→3858, 3848→exit, 3849→exit, 3900-3901,
  3909-3910, 3935 — lie **beyond line 2800**, where all five declared bands stop, although
  `scripts/swarm.py` has 4230 physical lines. That region is unowned by any COVPY task, so
  OPS-06's whole-file 100% gate cannot close from these five tasks alone. This is outside this
  task's scope and was not edited.
- The worker task-request mechanism cannot create the follow-up: `swarm.scope_ok` rejects a scope
  under `scripts/` because `scripts/*` is in `PROTECTED` (verified: `scope_ok: False`, `entries: []`
  above). The integrator or a person must create that follow-up task manually; no `task-requests`
  block is included because the validator would silently drop it.
- The task file's descriptive function list ("guard, merge, gate, fix and repair, resolve
  conflicts, delivery dispatch and the kill switch") is stale: those functions now sit at roughly
  lines 2734-3880, outside 1850-2450. The authoritative split is the line ranges
  (PLAN 1-700 / SCHEDULE 700-1250 / WORKERS 1250-1850 / INTEGRATOR 1850-2450 / CLI 2450-2800).
  Tracker reconciliation should fix the description; workers must not edit `tasks/`.
- The six `test_check_pack` failures are the documented `opencode.json` CI-profile `skip-worktree`
  artifact (OPS-CW-003-01), confirmed once and not re-diagnosed. They make `python-coverage.sh`,
  its `--full` mode and the plain full unittest exit 1; the scoped suite, the changed-line policy
  check and `coverage-check.sh` are green. The integrator owns the full gate.
- No defect in the script under test was found; `.coverage` (a tracked file) was left untouched by
  using a temporary `COVERAGE_FILE`.

## Lessons for workers

- Measure before trusting a re-dispatch brief or a stale "uncovered lines" list. A task can already
  be complete from a merged earlier attempt; verify the band before writing any test.
- Filter the coverage JSON on both the source **and** destination line of each missing arc. A
  whole-file percentage (97%) is not a per-band verdict; zero misses in the band is.
- `python_coverage.py --full` reports pre-existing gaps across every script. When a change adds no
  production line, the meaningful evidence is the changed-line policy result plus a zero count in
  your own band, not the global 443-gap total.
- `task-requests` cannot target `scripts/` paths: `scope_ok` rejects protected files. Raise a
  structural gap under `## Findings` for the integrator instead of writing a block that the
  validator silently drops.
- Keep the tracked `.coverage` byte-identical by exporting `COVERAGE_FILE` to `/tmp/opencode` for
  every coverage command; check `git status --short` before committing.
