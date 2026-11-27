- [x] NOTES-ARCHIVE-TEST · Stop the notes policy test from rejecting policy-mandated archives
  - agent: orca
  - attempts: 0
  - pr: #1796
  - requirements: OPS-06
  - scope: scripts/tests/test_notes_files.py, tasks/01345000-NOTES-ARCHIVE-TEST.md
  - evidence:
    Test evidence (tool, Python 3.14 venv): targeted `python3 -I -m unittest discover -s scripts/tests -p test_notes_files.py -v` -> `Ran 6 tests ... OK`; full `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2432 tests ... OK`; `COVERAGE_BASE=eaf8b5e REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`.
    Tested revision: eaf8b5eef6c398116a7b5676c5bf60af86298658 (HEAD when checks ran).
    Coverage: `is_daily_archive` recognises only `archive/YYYY-MM-DD.zip`; the per-run loop skips exactly that shape and keeps rejecting other non-`.md` paths, asserted by `test_daily_archives_keep_the_documented_dated_zip_shape`.
    Review verdicts (after evidence sections were filled):
    - code-reviewer: GO (after Required archive-.md finding fixed)
    - coverage-auditor: GO
    - devsecops: GO
  - details:
    Worktree `.workspaces/notes-archive-test`, branch `fix/notes-archive-test`, base `eaf8b5e`.
    Delivery runs 45960317263 and 45960810123 failed on
    `test_only_per_run_notes_are_tracked_under_the_new_directory` with
    `AssertionError: False is not true : .agents/notes/archive/2026-11-24.zip`.
    scripts/tests/test_notes_files.py:21-24 requires every tracked path under
    `.agents/notes` to end in `.md`, but scripts/swarm.py `archive_day()` intentionally
    creates `.agents/notes/archive/YYYY-MM-DD.zip` and `push_main` commits it.
    Fix the test, not the archive: allow only that exact archive shape and keep rejecting
    every other non-`.md` path. Add positive and negative synthetic assertions.
    No production code, threshold, exclusion or suppression change.
