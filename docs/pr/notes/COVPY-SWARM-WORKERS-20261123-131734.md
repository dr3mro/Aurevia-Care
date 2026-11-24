# COVPY-SWARM-WORKERS — swarm.py worker coverage (attempt 3, re-verification)

## Summary

Attempt 3 of the OPS-06 task `Script tests: swarm.py coverage, workers part (to 100% together
with the other parts)`. The five-test module `scripts/tests/test_swarm_workers_coverage.py` and
its notes were already merged to main by commit `352ae2ae` (PR #906); at the start of this pass
`HEAD == origin/main == deea5ca` and `git diff origin/main...HEAD` was empty.

This pass is a re-verification with **zero code delta**: the authoritative coverage filter for the
assigned range `scripts/swarm.py:1250-1850` reports 0 missing lines and 0 missing branch arcs, so
per the brief ("if the range filter is already empty, add no tests") no new test was written and no
existing test, production script, threshold or protected file was changed. Only this notes file is
added. The documented cloud-worker false positives were confirmed once and recorded, not repaired.

## Affected requirements

OPS-06 (reproducible delivery tooling, synthetic fixtures and CI policy checks). This is one of
five parallel tasks splitting `scripts/swarm.py` by line range; this task owns only 1250-1850.

| Acceptance criterion | Evidence |
|---|---|
| Cover only uncovered lines/arcs in `scripts/swarm.py` 1250-1850 | Coverage JSON range filter: missing lines `[]`, missing arcs `[]`, 244 executed arcs |
| Test-only change in the single named `scope` file | `git show --stat 352ae2ae`: only the test file plus two notes files; no production/threshold/test change in this pass |
| No suppression, skip, exclusion or threshold change | `python3 -I scripts/check-constraints.py --base origin/main --require-base` exit 0; grep of scoped file found none |
| Synthetic data, no network/real `gh`/push | tests mock `authed_git`, `git`, `todo_read`/`todo_write`, `_checker`, `time.sleep`; synthetic fixtures only |
| 100% coverage of changed lines | `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main` exit 0; range filter is the proof for this split |

## Risk class

Routine — an isolated, already-merged test module with mocked boundaries; no production code,
contract, migration, deployment or patient-facing change (docs/WORKFLOW.md).

## Review owner

Senior Python Tooling (`aurevia-senior-python`) and Test Engineer (`aurevia-test-engineer`).
Evidence provenance: commands executed by the primary agent (tool evidence); read-only specialist
reviews below (simulated/static review); no simulated user and no human participant. No clinical or
real-patient evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None changed. All external boundaries are mocked (`authed_git`, `git`, `todo_read`, `todo_write`,
`_checker`, `time.sleep`); no token is read, no network call, no `gh` call and no push is performed;
no audit event is produced.

## Migration and rollout considerations

None. Test-only change; no rollout, production access or deployment gate.

## Test evidence

All commands were run from the repository root on branch `swarm/COVPY-SWARM-WORKERS-1176`.

- `git merge --no-edit origin/main` (at start and before finishing) -> `Already up to date.`
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_workers_coverage.py' -v`
  -> `Ran 5 tests in 0.003s` / `OK` (a second run at cleanup: `Ran 5 tests in 0.002s` / `OK`).
  Entry-point-to-test trace: the public functions `parse_tasks` (swarm.py:1392), `redact_secrets`
  (:1615), `set_task_status` (:1828) and `update_todo` (:1908) are driven by synthetic fixtures in
  `scripts/tests/test_swarm_workers_coverage.py` — no production change is needed for this pass.
- `python3 -m coverage erase && python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  -> process exit 1 solely from pre-existing environment failures (see Findings); all five scoped
  tests pass in that run.
- `python3 -I -m unittest discover -s scripts/tests` -> `Ran 1307 tests in 35.369s` /
  `FAILED (failures=6)`. All six are `test_check_pack.*` (`test_check_mode_detects_drift`,
  `test_cli_entry_point`, `test_main_reports_success`, `test_committed_opencode_json_is_generated`,
  `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`), each
  `AssertionError: 1 != 0` on a `gen.main(["--check"])` / exit-code check. Cause confirmed once:
  `git ls-files -v opencode.json` -> `S opencode.json` (CI profile installed under skip-worktree,
  OPS-CW-003-01). No `test_worker_gate` error occurred in this run. Documented cloud-worker false
  positives; no protected file was edited to "fix" them and they were not re-diagnosed later.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`
  -> `python coverage: every changed script line is covered` (exit 0). This gate scans
  `^scripts/[^/]+\.py$`, not `scripts/tests/`, so it complements but does not replace the range filter.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` -> exit 0
  (no suppression, exclusion, skip or weakened threshold).
- `python3 -I scripts/check-docs.py` -> exit 0 (Markdown/notes formatting).
- `python3 -I scripts/check-verdicts.py .agent/pr-notes/COVPY-SWARM-WORKERS-20261123-131734.md --base origin/main`
  -> `verdicts: only documentation changed; nothing to review` (exit 0). Because this pass has zero
  code delta, the only changed file is the notes file, so the checker takes its docs-only path; the
  three GO verdicts are still recorded below as the brief requires.
- `bash scripts/quality-gate.sh origin/main .agent/pr-notes/COVPY-SWARM-WORKERS-20261123-131734.md`
  -> `verdicts: only documentation changed; nothing to review` (exit 0; constraints check passed).
- `git show --name-status --format="" 352ae2ae` -> only
  `.agent/pr-notes/COVPY-SWARM-WORKERS-20261122-143326.md`,
  `.agent/pr-notes/COVPY-SWARM-WORKERS-fix-20261122-145156.md` and
  `scripts/tests/test_swarm_workers_coverage.py` were added. This closes the code reviewer's
  request to confirm the committed change is test-only.

Frontend checks are not applicable (no `apps/patient-mobile` or `apps/staff-web` change). The full
`scripts/check.sh` was intentionally not run: it delegates to `apps/api/check.sh` -> full `mvn
verify`, which AGENTS.md and this task forbid a swarm worker from running (over ten minutes, ends
the run as a timeout). The individual policy scripts were run instead; the integrator and Delivery
own the full gate and the Java/database 100% coverage check. A stray `.coverage` measurement
artifact was restored with `git restore .coverage`, leaving the new notes file as the only change.

## Coverage

Coverage.py 6.5.0 (Debian `python3-coverage`, installed in an earlier attempt as the available
method; no repository dependency added). Exact commands and results:

1. `python3 -m coverage erase`
2. `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
   — all five scoped tests passed; overall exit 1 only from the environment failures recorded above.
3. `python3 -m coverage report -m --include=scripts/swarm.py`
   — `scripts/swarm.py 2601 stmts, 76 miss, 1344 branch, 9 BrPart 97%`. No missing item lies in
   1250-1850; the whole-file percentage aggregates the other four tasks' ranges and pre-existing
   gaps, so it is **not** the proof for this split.
4. `python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/opencode/cov.json` plus an inline
   filter of `files['scripts/swarm.py']` to 1250-1850:
   `missing lines in range: []`, `missing branch arcs in range: []`,
   `executed branch arcs in range: 244`. **This range filter is the authoritative proof.**
5. `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`
   — `python coverage: every changed script line is covered` (exit 0).

Result for the assigned range: **0 missing lines, 0 missing branch arcs** (244 executed arcs).

Each previously uncovered branch and the named test with an outcome assertion (current line
numbers; the prose's 2026-11-21 numbers shifted in later merges):

| Branch (`scripts/swarm.py`) | Test | Assertion |
|---|---|---|
| :1423 `parse_tasks` `elif field.group(1) in current` — unknown-field false arc | `test_parse_tasks_ignores_an_unknown_field_and_keeps_reading` | `assertEqual` id/scope/agent and `assertNotIn("mystery", task)`; valid fields after the unknown one still parse |
| :1620-1621 `redact_secrets` `except (OSError, AttributeError, ImportError): return text` | `test_redact_secrets_returns_text_when_the_checker_cannot_load` | three subTests assert `redact_secrets("plain text") == "plain text"` after `_checker` raises each exception type |
| :1839-1840 `set_task_status` walk-back `while end > start + 1 and not lines[end - 1]` | `test_set_task_status_keeps_the_blank_separator_after_an_entry` | exact-string `assertEqual` proving the blank separator survives and fields are inserted before it |
| :1924-1925 `update_todo` `if attempt == attempts - 1: raise` | `test_update_todo_raises_when_every_push_fails` | `assertRaises(CalledProcessError)` and `assertEqual(len(pushes), 2)` (attempt 1 retries, attempt 2 raises) |
| :1910 / :1930 `update_todo` `for attempt in range(attempts)` zero iterations -> `return False` | `test_update_todo_with_no_attempts_does_nothing` | `assertFalse(...)` plus three `assert_not_called()` on `todo_read`/`todo_write`/`git` |

Notes: the last two tests exercise `update_todo` (:1908), now **outside** the assigned range;
that extra coverage is harmless and does not change the in-range `[]`/`[]` proof. The success side
of `redact_secrets` is exercised cross-file by
`scripts/tests/test_swarm_monitor.py::test_agent_text_cannot_trip_the_secret_check_on_main` (:207).
No threshold, exclusion, pragma, skip or existing test was changed; no unreachable production line
was found in the range, so nothing needed removal.

## Clinical and architecture review

Not required: no patient identity, results, orders, prescriptions, release of information, tenant
boundary or downtime behavior is touched.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

(Also: aurevia-plan-reviewer GO for the plan; aurevia-test-engineer confirmed no new case
is needed; aurevia-context-optimizer supplied the symbol/stale-prose briefing.)

## Findings

- **Stale prose (tool + context-optimizer):** the task detail names `plan_workers`, publish,
  instruction reconciler, notes files and repair planning as the range's content. `plan_workers`
  is now at `scripts/swarm.py:2025` and `publish` at `:2125`, outside 1250-1850. The range actually
  holds `parse_tasks` (1392), `redact_secrets` (1615), `set_task_status` (1828) and other helpers.
  The coverage range filter, not the prose, was used as the authority.
- **Zero code delta this pass:** the five-test module was already merged by `352ae2ae` (PR #906);
  HEAD equals origin/main. Adding a duplicate test would violate the brief's "add no tests" rule,
  so only this notes file is new.
- **Notes filename discrepancy (brief vs prompt):** the advisor brief carries a generic
  `-fix-<timestamp>` parenthetical and the task file's step 4 names a bare `<task-id>.md`, but the
  worker prompt names the exact path `.agent/pr-notes/COVPY-SWARM-WORKERS-20261123-131734.md`.
  AGENTS.md says the prompt path wins; it is a unique timestamped file (not a bare filename), so it
  was used verbatim and passed to `quality-gate.sh`. No additional notes file was created.
- Optional (code-reviewer): `_checker` is not patched in the two `update_todo` tests, so
  `todo_paths -> store() -> _checker("taskstore")` runs for real; the module import is pure and
  `git`/`todo_write` are mocked, so no data is read or written. Not a gate failure; no change made.
- Optional (coverage-auditor): the `set_task_status` walk-back branch is output-neutral — the exact
  string is the same with or without it — so the assertion proves overall correctness rather than
  isolating that branch. The branch is executed, which satisfies the gate; deciding whether it is
  removable would be a production change forbidden by this task's scope.
- Optional (devsecops): `redact_secrets` fails open when its checker cannot be loaded
  (`scripts/swarm.py:1618-1621`). This is pre-existing and out of scope; the test only injects a
  fault to cover the existing arc, and Delivery runs `check-secrets.py` as an independent gate. No
  task request is raised because this work does not need that change.
- **Environment (recorded, not repaired):** six `test_check_pack.*` failures in the full suite
  come from the CI `opencode.json` installed under skip-worktree (`git ls-files -v opencode.json`
  -> `S`); `apps/api/target/surefire-reports` is absent in the worker image. No protected file was
  edited and these were not re-diagnosed on a later pass (AGENTS.md known false positives).
- Dependency/static security scanning: OWASP Dependency-Check and SpotBugs are not applicable to a
  test-only Python addition (no Java dependency or artifact change); no PHI, credential or token
  appears in the change. Finding count for this change: none.

## Lessons for workers

- For a line-range coverage task in a five-way split, trust the coverage JSON range filter over
  the task's prose and its 2026-11-21 line numbers; function names and line positions shift in later
  merges.
- On a re-verification pass whose range filter is already `[]`/`[]`, add no tests — do not duplicate
  what an earlier attempt committed. The deliverable is a fresh notes file with real gate evidence.
- Use the exact notes path in the prompt, and include `## Review verdicts` with GO lines from
  code-reviewer, coverage-auditor and devsecops for any non-documentation change; the bare filename
  and missing verdicts were the documented cause of an earlier failed finish.
- `python_coverage.py` scans `scripts/*.py`, not `scripts/tests/`, so a green result there cannot
  prove a test-only change: the range filter is the proof.
- In a cloud worker, `git ls-files -v opencode.json` showing `S` explains `test_check_pack.*`
  failures; record the command and final line once, never regenerate the protected file, and do
  not run `scripts/check.sh` (it delegates to full `mvn verify` and times out workers).
