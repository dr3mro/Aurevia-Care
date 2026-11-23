# COVPY-SWARM-WORKERS — swarm.py workers coverage

## Summary

Add one new test file, `scripts/tests/test_swarm_workers_coverage.py`, covering the still-uncovered
lines and branches of `scripts/swarm.py` in the workers region (roughly 1250-1860). Test-only
change: no production code, existing test, checker or configuration is edited. Continues the work
recorded in PR #906 / Issues #932 and #907; that earlier file is not present on this branch.

## Affected requirements

- OPS-06 (synthetic fixtures, CI policy checks and monitored tooling).

## Risk class

Routine (test-only tooling change; no contract, data or runtime behavior change).

## Review owner

smartclinic-code-reviewer, smartclinic-coverage-auditor, smartclinic-devsecops (after code);
smartclinic-plan-reviewer before coding; smartclinic-context-optimizer and smartclinic-test-engineer
for evidence and test design.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient scope; no audit events changed. Synthetic text fixtures only, no
real domains, identifiers, credentials or patient data.

## Migration and rollout considerations

None. Test-only change to the tooling suite.

## Test evidence

All evidence below is **tool evidence** from commands executed by the primary agent; reviewer
verdicts are marked separately under `## Review verdicts`. No human, clinical or patient
participant evidence is claimed.

- Branch `swarm/COVPY-SWARM-WORKERS-1099`; `git merge --no-edit origin/main` → `Already up to date.`
- `pip` was absent from the worker image; bootstrapped with `python3 /tmp/opencode/get-pip.py --break-system-packages`
  then `python3 -m pip install --break-system-packages coverage` → `Coverage.py, version 7.17.4 with C extension`.
  Environment-only repair; no repository dependency or file changed.
- RED (baseline, before the new file) — `COVERAGE_FILE=/tmp/opencode/cov_workers python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  then `COVERAGE_FILE=/tmp/opencode/cov_workers python3 -m coverage report -m --include=scripts/swarm.py`
  → `scripts/swarm.py 2427 152 940 58 93%`, with the five target items in the Missing column.
- GREEN (targeted): `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_workers_coverage.py' -v`
  → `Ran 5 tests in 0.003s` / `OK`.
- GREEN (full suite): `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1192 tests in 28.760s`
  and `FAILED (failures=6, errors=2)`. The identical failure set was reproduced with the new file
  temporarily removed: `Ran 1187 tests in 26.686s` / `FAILED (failures=6, errors=2)`. The delta is
  exactly the five new passing tests; the failures are pre-existing worker-image artifacts, not
  regressions: two `worker_gate` errors from a missing `apps/api/target/surefire-reports` directory
  and six `test_check_pack` failures from the CI-profile `opencode.json` under `skip-worktree`
  (`git ls-files -v opencode.json` → `S`; its worktree hash matches `.github/opencode.ci.json`,
  while `git show HEAD:opencode.json` differs). No protected file was edited or regenerated.
- `bash scripts/python-coverage.sh` → exit 1 for the same suite failures; its changed-line stage was
  run individually: `python3 -I scripts/check-constraints.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0 (0 findings); `python3 -I scripts/check-names.py`
  → exit 0 (0 findings); `python3 -I scripts/check-docs.py` → exit 0 (0 findings);
  `python3 -I scripts/check-constraints.py` → exit 0 (0 findings). Disposition: no suppression,
  exclusion or threshold change. `coverage.py 7.17.4` was installed into the worker environment
  only; it is not a repository dependency. `smartclinic-devsecops` independently re-scanned both
  scoped files against the secret patterns and the constraint patterns statically (its session had
  no shell) and found no credential or PHI-shaped fixture and no suppression.
- `git diff origin/main...HEAD --name-status` → empty; `git status --short` shows only the scoped
  new test file and this notes file. No push, tag or `gh` call was made.

Entry point to test trace: `scripts/swarm.py` worker helpers → loaded through
`from helpers import load; swarm = load("swarm")` → the five test methods in
`scripts/tests/test_swarm_workers_coverage.py` call the real public functions with mocked external
boundaries and assert the resulting text, return value, exception or call count.

## Coverage

Command: `COVERAGE_FILE=/tmp/opencode/cov_workers2 python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
then `COVERAGE_FILE=/tmp/opencode/cov_workers2 python3 -m coverage report -m --include=scripts/swarm.py`.

After the new file: `scripts/swarm.py 2427 147 940 54 93%` (from `2427 152 940 58 93%`). The
Missing column no longer contains any line or branch in the workers region 1250-1860; every
remaining miss is in another swarm part's range (1-700, 700-1250, 1850-2450, 2450-3986). Full-file
100% is reached when the other four swarm test files are merged, which the integrator owns.

Each previously uncovered item and the test that takes it:

| Branch | Test | Assertion that fails if the branch is wrong |
|---|---|---|
| `1336->1320` — unknown field falls through to the next line in `parse_tasks` | `test_parse_tasks_ignores_unknown_fields` | `assertNotIn("unknownfield", tasks[0])` and the following `requirements: IAM-02` is parsed |
| `1533-1534` — `redact_secrets` returns text when the checker raises `OSError`/`AttributeError`/`ImportError` | `test_redact_secrets_returns_text_when_the_checker_cannot_load` | `assertEqual(swarm.redact_secrets(text), text)` for each exception subTest |
| `1753` — `set_task_status` walks the end pointer back over a trailing blank line | `test_set_task_status_keeps_the_blank_separator_after_an_entry` | exact expected output with the blank preserved and `CW-002` appearing exactly once |
| `1838` — `update_todo` re-raises the final rejected push | `test_update_todo_raises_when_every_push_fails` | `assertIs(raised.exception, error)` plus `['push', 'fetch', 'push']`, 2 writes and 1 sleep |
| `1843` — `update_todo` returns False when the loop never runs | `test_update_todo_with_no_attempts_does_nothing` | `assertIs(..., False)` and all editor/read/write/git/authed mocks `assert_not_called()` for `attempts=0` and `-1` |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or
critical results.

## Findings

- The brief's long list of functions in this region (failure_report, request_entries, docs_safe,
  daily_publish, strategy_publish, reclaim, claim_open and others) is **already covered** in the
  measured baseline. The brief also states the story's old line/function list is stale and that the
  coverage report is authoritative. This file therefore targets only the uncovered items the
  report actually shows in the workers region.
- Conflict markers exist in committed Java files (`exchange/hl7/*`) in this worktree; they predate
  and are unrelated to this change, are outside scope, and were not touched.
- A prior PR (#906) was flagged for changing a protected path (`scripts/tests/...`). This worker does
  not work around that; the integrator owns `SWARM_ALLOW_PROTECTED`. Only the scoped new test file
  and this notes file are changed.
- `smartclinic-code-reviewer` (Optional, tool evidence plus source trace): the walk-back at
  `scripts/swarm.py:1752-1753` is behaviorally redundant with the field loop that re-appends blank
  lines, so the new `set_task_status` test drives that line but its output would be identical without
  it. The test docstring now states this; removing the redundant production line needs a separate
  production-code task and is outside this task's scope. The other four tests are discriminating.
- The full-suite command cannot exit OK in this worker image (six `test_check_pack` failures and two
  `worker_gate` errors reproduce with the new file removed). These are the documented
  CI-profile/`skip-worktree` and missing-Maven-reports false positives; the integrator owns the
  full gate. No protected file was edited or regenerated to mask them.

## Lessons for workers

- For a coverage-only task, trust the freshly measured coverage report over a stale story or brief
  function list: the brief named many already-covered functions, while the report showed exactly
  five real gaps in this region, one of which (`1336->1320`) the earlier PR's notes missed.
- When testing `update_todo` retries, make the mocked `authed_git` raise only for the `push`
  subcommand — `fetch` at line 1841 sits outside the `try/except`. With `attempts=2` and an edit that
  always changes the text, `todo_write` runs twice; asserting one write fails for the wrong reason.

## Review verdicts

- smartclinic-plan-reviewer: GO (after two Required gaps were fixed: push-only failure injection in
  the retry test and the correct two-write count)
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO
