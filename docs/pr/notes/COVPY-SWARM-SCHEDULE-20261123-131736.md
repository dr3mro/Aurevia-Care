# COVPY-SWARM-SCHEDULE — swarm.py schedule-slice coverage (OPS-06)

## Summary

Extended the existing synthetic test file `scripts/tests/test_swarm_schedule_coverage.py`
from 10 to 16 tests. The six new tests cover the two functions that are still uncovered
in the assigned `scripts/swarm.py` range (lines 700–1250) at this commit: `reconcile_tasks`
(status updates when a merged PR finishes a task) and `draft_triage` (stale-draft counting
and the single triage issue). All fixtures are synthetic; `gh`, `gh_json`, `subprocess`,
`glob` and the environment are mocked. No production file, existing test, threshold,
exclusion or dependency was changed.

The brief's candidate list (monitor selection/assignment, slot config, registry manifests)
was stale: those lines are already green in the measured report and were deliberately not
re-tested.

## Affected requirements

OPS-06 — synthetic fixtures and CI policy checks. Test-only; no product behavior changed.

## Risk class

Routine — one in-scope test file, no runtime, contract, schema or gate change.

## Review owner

aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer,
aurevia-coverage-auditor, aurevia-devsecops (read-only, advisory).

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit-event code touched. No real repository is
written: every `gh` and `subprocess` call in the new tests is replaced by a local mock.

## Migration and rollout considerations

None.

## Test evidence

All commands run from the repository root (Python 3.11.2, coverage.py 6.5.0, already
installed in this worker image). `COVERAGE_FILE=/tmp/opencode/.coverage` was used for the
explicit coverage runs so the tracked `.coverage` artifact stayed clean.

- `git merge --no-edit origin/main` → `Already up to date.` (exit 0); no conflict markers.
- Baseline coverage (before the new tests):
  `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → `Ran 1307 tests in 38.663s` / `FAILED (failures=6)`;
  `python3 -m coverage report -m --include=scripts/swarm.py` → 97%, with lines
  `829-869` and `875-898` uncovered. This is the red evidence for a coverage-only story.
- `python3 -I -m unittest discover -s scripts/tests` (TODO step 6) → final lines
  `Ran 1313 tests in 39.589s` / `FAILED (failures=6)` (exit 1); the six failures are
  the pre-existing `test_check_pack` ones named below, and all 16 scope-file tests pass.
- New tests only:
  `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_schedule_coverage.py' -q`
  → final lines `Ran 16 tests in 0.017s` / `OK` (exit 0); three consecutive repeats
  were green, so the new tests are deterministic.
- Combined suite:
  `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests`
  → final lines `Ran 1313 tests in 44.274s` / `FAILED (failures=6)`. All six failures are
  the pre-existing `test_check_pack` environment false positives listed under Findings;
  all 16 tests of this scope file pass.
- `python3 -m coverage report -m --include=scripts/swarm.py` → block reproduced under
  Coverage. Whole file 99%; the only misses inside the assigned range are two provably
  unreachable production spots documented under Why this is not finished.
- `python3 -m coverage json -q -o /tmp/opencode/cov_final.json` plus a JSON filter to
  `700 <= line <= 1250`: `missing lines [861]`,
  `missing branches [[860, 861], [863, 845]]`. Before the change that filter returned the
  whole `829-869` and `875-898` ranges.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov_final.json --base origin/main`
  → `python coverage: every changed script line is covered` (exit 0).
- `bash scripts/python-coverage.sh` → exit 1, stopping at its full unittest stage with
  `Ran 1313 tests in 43.937s` / `FAILED (failures=6)` — the same six pre-existing
  environment failures. The changed-line coverage gate itself was run separately and passes.
- `python3 -I scripts/check-constraints.py` → exit 0 (no output).
  `bash scripts/quality-gate.sh HEAD docs/pr/notes/COVPY-SWARM-SCHEDULE-20261123-131736.md`
  → exit 0 (constraints clean, all four review verdicts recorded).
- Security reviewer's administrative confirmations: `git status --porcelain` shows only the
  scope test file modified and this notes file untracked; `git show
  HEAD:scripts/tests/test_swarm_schedule_coverage.py` contains neither of the two new
  classes; `git ls-files -v opencode.json` → `S`, unmodified.
- `python3 -I scripts/gen-opencode.py --check` → `opencode.json is out of date: run scripts/gen-opencode.py`
  (exit 1). Confirmed false positive: `git ls-files -v opencode.json` → `S` (skip-worktree
  CI profile layered by `agent-run.sh`). The file was not regenerated or edited.
- `git status --short` → only `M scripts/tests/test_swarm_schedule_coverage.py`.

Entry point → test trace (public function → named test):

| Production entry point | Test |
|---|---|
| `reconcile_tasks` merged-PR list failure / malformed JSON | `test_a_failed_pr_list_leaves_the_task_untouched`, `test_a_malformed_pr_list_is_ignored` |
| `reconcile_tasks` id/PR reconciliation and skip paths | `test_only_open_tasks_named_by_a_merged_pr_are_closed` |
| `draft_triage` absent history | `test_no_run_history_does_not_open_an_issue` |
| `draft_triage` stale-draft issue | `test_only_old_drafts_count_and_trigger_an_issue` |
| `draft_triage` kill switch | `test_the_kill_switch_stops_the_issue` |

The full `scripts/check.sh` was intentionally not run: AGENTS.md forbids swarm workers from
running it because it delegates to `apps/api/check.sh` (full `mvn verify`, >10 minutes).
The integrator and Delivery own that gate.

## Coverage

Command and block (full file; only the assigned range 700–1250 is in scope):

```
$ python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests
$ python3 -m coverage report -m --include=scripts/swarm.py
Name               Stmts   Miss Branch BrPart  Cover   Missing
--------------------------------------------------------------
scripts/swarm.py    2601     18   1344     11    99%   581, 585, 861, 863->845, 3084, 3118, 3294-3296, 3759, 3808-3811, 3821->3858, 3848->exit, 3849->exit, 3900-3901, 3909-3910, 3935
--------------------------------------------------------------
TOTAL               2601     18   1344     11    99%
```

Every line and branch of `reconcile_tasks` (829–869) and `draft_triage` (875–898) is
covered except the two unreachable spots listed under Why this is not finished. All other
misses are outside this task's assigned range and belong to the sibling split tasks.

| Line / branch | Test that takes it |
|---|---|
| `830-835` `try` success (valid merged JSON) and `834` `except` from `check_output` raising | `test_only_open_tasks_named_by_a_merged_pr_are_closed`, `test_a_failed_pr_list_leaves_the_task_untouched` |
| `834` `except` from malformed JSON (`json.loads` ValueError) | `test_a_malformed_pr_list_is_ignored` |
| `838-842` loop over many merged PRs | `test_only_open_tasks_named_by_a_merged_pr_are_closed` (two PR records) |
| `843` `files = [file] if file else glob(...)` — explicit-file side | `test_a_failed_pr_list_leaves_the_task_untouched` |
| `843` glob/directory side | `test_only_open_tasks_named_by_a_merged_pr_are_closed` |
| `846-847` `not os.path.isfile(path)` → continue | same test (`missing.md` in the patched glob result) |
| `849-850` `not m` → continue | same test (`heading.md`) |
| `856` `pr_num` ternary, both sides | same test (files with and without `pr:`) |
| `857` `tid not in merged_ids` false (short-circuit), and true with `pr_num is None`, true with `pr_num` absent from merged set, false with `pr_num` present | same test (`by_id`, `unknown`, `wrong_pr`, `by_pr`) |
| `864-865` `evidence:` absent → auto-reconciled line added; present → preserved | same test (`by_id`, `has_evidence`) |
| `869` loop with zero files | `test_a_failed_pr_list_leaves_the_task_untouched` (empty merged set, single skipped file) |
| `875-876` `gh_json(...) or []` — falsy (`None`) and truthy (list) | `test_no_run_history_does_not_open_an_issue`, `test_only_old_drafts_count_and_trigger_an_issue` |
| `879-881` `not p.get("isDraft")` → continue | `test_only_old_drafts_count_and_trigger_an_issue` (non-draft PR) |
| `882-883` `not created` → continue | same test (PR without `createdAt`) |
| `885-887` `ValueError` → continue | same test (malformed `createdAt`) |
| `889-890` age check true (old) and false (fresh) | same test (old PR appended, fresh PR skipped) |
| `892` `len(old) > threshold` false and true | `test_no_run_history_does_not_open_an_issue`, `test_only_old_drafts_count_and_trigger_an_issue` |
| `892` `SWARM_ISSUES != "off"` false and true | `test_the_kill_switch_stops_the_issue`, `test_only_old_drafts_count_and_trigger_an_issue` |
| `893-896` issue creation body and `gh` call | `test_only_old_drafts_count_and_trigger_an_issue` (asserts `- #5 stale draft` in the call) |

No new production branch was added by this change (test-only), so no new production branch
is left without a test.

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association, prescribing
or critical results.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- The brief's "read first" line list was stale. The measured report at this commit shows
  the monitor/slot functions already green; only `reconcile_tasks` and `draft_triage` were
  uncovered in 700–1250. No green branch was re-tested.
- The full Python suite has six pre-existing environment-only failures, all in
  `test_check_pack`, caused by `opencode.json is out of date` with `git ls-files -v
  opencode.json` → `S` (known skip-worktree CI-profile false positive, OPS-CW-003-01).
  They reproduce before and after the change; no protected file was edited for them.
- Production quirk found while writing the evidence assertion: at `swarm.py:865`, the
  auto-reconciled evidence line is inserted with
  `new.replace("- [x] " + tid + "\n", ...)`, which only matches a task line that has no
  trailing title (for example `- [x] CW-002`, not `- [x] CW-002 · title`). The test uses
  a bare line to reach that branch deterministically. Behavior itself was not changed;
  a person may want a separate story for the titled-line case.
- No conflict markers remain in the tree; the markers reported by earlier runs of this
  task have since been resolved in HEAD.

## Lessons for workers

- In a split-coverage task, measure with `coverage report -m` before writing anything and
  filter `coverage json` missing lines/branches to the assigned range; earlier runs' line
  numbers are stale after sibling merges.
- A bare `- [ ] ID` line and a titled `- [ ] ID · title` line take different replacement
  paths in `reconcile_tasks`; check the exact production `replace` pattern before asserting
  the resulting file content.
- `subprocess.check_output` can be patched with `mock.patch.object(subprocess, ...)` even
  when the function under test imports `subprocess` locally — it is the same module object.
- Set `COVERAGE_FILE=/tmp/opencode/.coverage` for explicit coverage runs: `.coverage` is a
  tracked file in this repository and a plain `coverage run` dirties the working tree.

## Why this is not finished

The test deliverable is complete and all reachable lines/branches in the assigned range are
covered, but **100% of range 700–1250 cannot be reached with a test-only change** because
two pre-existing production spots in `reconcile_tasks` are unreachable by any input:

- `scripts/swarm.py:861` — the `continue` in
  `if "- [-]" not in text and "- [ ]" not in text:`. The preceding
  `m = re.search(r'^- \[[- ]\] (\S+)', text, re.M)` can only match a line that literally
  contains `- [-]` or `- [ ]`, so at least one `not in` operand is always False and the
  body cannot run. Coverage reports it as missing line `861` and missing branch
  `[860, 861]`.
- `scripts/swarm.py:863` — the false arc `863->845` of `if new != text:`. The same matched
  line guarantees that one of the two `.replace("- [X] " + tid, "- [x] " + tid, 1)` calls
  changes the text, so `new == text` is impossible. Coverage reports the missing branch
  `[863, 845]`.

Command and result: `python3 -m coverage report -m --include=scripts/swarm.py` (block
above) and the JSON filter `missing lines 700-1250: [861]`,
`missing branches 700-1250: [[860, 861], [863, 845]]`.

Most likely cause: redundant guards left behind after the entry regex was tightened. What
was tried: a throwaway probe outside the repository exercised the success, failure,
explicit-file, glob and malformed-input paths of `reconcile_tasks`; only these two spots
stayed missed. No pragma, exclusion, skip or threshold change was added, and no unreachable
branch was faked with a mock.

Exact next step: a person or the integrator, in a change that is allowed to edit
`scripts/swarm.py`, delete the line-860 guard and fold the always-true `if new != text:`
into straight-line code, then re-run the coverage commands above; the same tests should
stay green because behavior does not change. The remaining red gate in this worker,
`bash scripts/python-coverage.sh` (`FAILED (failures=6)`), is the separate documented
skip-worktree `opencode.json` false positive and is owned by the integrator.
