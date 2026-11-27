- [x] RECONCILE-AMBIGUOUS · Let explicit PR references reconcile past repeated task titles
  - agent: orca
  - attempts: 0
  - pr: #1806
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm_schedule_coverage.py, scripts/tests/test_swarm.py, tasks/01351000-RECONCILE-AMBIGUOUS.md
  - evidence:
    Test evidence (tool): `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2460 tests ... OK`; `COVERAGE_BASE=32fffb6e REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`. Tested revision: 32fffb6e987e70f6353f266bf1839c81576373cb (HEAD when checks ran). Full CI to follow.
    Coverage: first duplicate sets ambiguous -> `test_ambiguous_task_id_without_explicit_pr_stays_open_with_a_warning`, `test_ambiguous_task_identity_stays_open_without_a_write`; later duplicate stays out of by_task -> same two tests; ambiguous PR retained in by_number and explicit PR match -> `test_ambiguous_task_id_with_explicit_pr_closes_that_exact_merge`; warning skip path -> both ambiguous tests; title-only success -> `test_only_open_tasks_named_by_a_merged_pr_are_closed`; malformed/duplicate-number/wrong-base paths unchanged. `test_malformed_or_duplicate_evidence_fails_before_write` was intentionally replaced by `test_ambiguous_task_identity_stays_open_without_a_write` because duplicate task identity is no longer fatal.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
  - details:
    Run 46003224655 failed with `reconcile: merged pull-request task identity is duplicated`.
    Full-history pagination now exposes repeated verification/re-dispatch PRs sharing a
    `<TASK-ID>:` title prefix. The old guard aborted every task. Keep duplicate PR
    number, malformed record, wrong base and non-MERGED fatal; track ambiguous task ids
    separately, remove them from title-only matching, warn with both PR numbers, and
    leave explicit `pr:` tasks bound to their exact merge.
