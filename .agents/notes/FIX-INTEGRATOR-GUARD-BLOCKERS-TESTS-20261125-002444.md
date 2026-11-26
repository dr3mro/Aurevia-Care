# FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS — worker hand-off

Notes file for the resumed run. Evidence sources are labelled: **static** = file reads in this
session, **prior tool / reviewer** = evidence already recorded in the task file from earlier
runs and not re-executed here, **this tool** = a command I ran in this session. No simulated
user walkthrough and no authorized human feedback were available or used for this note.

## Summary

The requested change is already present on the current tree; there was no new production or
test code to write. The only file I created is this notes file; I made no commit. The task file
`tasks/01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS.md` already carries full `evidence:`,
`reviews:` and `ci-status:` fields (attempts: 22), and the durable decision is recorded in
`.opencode/config/MEMORY.md` (row beginning "Owner decision (2026-11-24): publication replays
every validation decision, FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS").

Static inspection confirmed the final design is in place:

- `scripts/swarm.py:5456` defines `guard_blockers(pr, repair=False)` as the read-only half;
  `guard(pr, repair=False, report=True)` at line 5488 calls it and returns a
  `(kind, files, lines)` tuple when `report=False`.
- `integrate_prepare()` at `scripts/swarm.py:5982` uses `guard(pr, is_repair(pr), report=False)`
  at line 6007 and handles both the tuple (read-only seam) and the legacy list return, so the
  existing `mock.patch.object(swarm, "guard", return_value=[])` fixtures stay valid.
- `report_validation()` at `scripts/swarm.py:6096` replays every decision with the write token:
  it closes already-integrated PRs (line 6126), keeps the moved-head reason with `simple=True`
  (lines 6056-6064, 6110-6118), and only queues a conflict while `tries < MAX_RESOLVE`
  (line 6049, 6129).
- The three named tests are on the final assertions:
  `scripts/tests/test_swarm.py:2627` expects `emit("conflicted", "[]")` after three tries;
  `:2611` expects `emit("conflicted", "[5]")` below the limit; `:2641` expects the precise
  moved-head reason with `simple=True`. `test_swarm_integrator_coverage.py:505` expects
  `close_integrated_pr` called once with `(7, "CW-001")`.

Because the tree already holds the fix and the branch `main` is reconciled with `origin/main`
per `WORKSPACE.md`, a new commit would be empty or redundant, so I did not create one.

## Affected requirements

OPS-06 — red-main repair priority and Delivery tooling repair. No clinical requirement was
touched.

## Risk class

Routine (test-fixture seam update plus integrator decision replay); no data, tenant, patient
identity or release-policy contract changed.

## Review owner

Prior read-only reviewers are recorded under `## Review verdicts`. No new review was requested
for this resumed no-change run.

## Data changes

None. No migration, backfill or schema change.

## Authorization boundaries and audit events

None. Tenant, role and patient-link scope are unchanged; no audit event was added or changed.

## Migration and rollout considerations

None. No deployment or configuration change.

## Test evidence

I did **not** execute test, coverage or Git-backed commands in this session. The owner's durable
directive in `.opencode/config/MEMORY.md` ("do not run any script that use git" — do not run
tests, coverage commands or scripts that invoke Git directly or indirectly, including
disposable temporary-repository fixtures and checks that call `git diff`") applies to this
story: its fixtures create temporary repositories, and the repository-wide Python suite and
coverage gate invoke Git. I also did not run any `git` command, including `git merge` or
`git commit`, because the same durable directive forbids Git-backed operations and the brief's
instruction to commit and merge is advisor guidance that cannot override it.

Prior tool evidence already recorded in the task file (not re-run here, attributed to earlier
runs):

- Red: full suite on pristine `origin/main` at `17a9b1c8` → 15 failures/errors across
  `test_swarm.py`, `test_swarm_integrator_coverage.py` and `test_swarm_delivery_state.py`;
  first traceback showed `guard_blockers()` reaching a real `gh pr diff`.
- Red after the first seam patch: already-merged PRs were not closed (`close_integrated_pr`
  called 0 times), moved heads used the generic `validation rejected this branch`, and an
  exhausted conflict was still emitted as `emit('conflicted', '[5]')`.
- Green: `cd scripts/tests && python3 -m unittest -q <nine affected tests>` → `Ran 9 tests ... OK`.
- Green: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1579 tests in 245.816s / OK`.
- Static policy tools exited 0: `actionlint ... .github/workflows/*.yml`, `check-docs.py`,
  `check-constraints.py --base origin/main --require-base`, `check-secrets.py`, `check-names.py`,
  `gen-opencode.py --check`.
- CI: PR 1613 merged to main as `098354af`; Delivery run `45337601399` on head `190cf7dc` had
  green policy/tooling/frontend/canonical-frontend gates and only an upstream Java test-compile
  failure that was fixed on main. Run `45339545141` on main `5a86e60f` stayed `pending` with zero
  jobs behind a queue, so the exact-SHA verdict was unavailable in that environment, not a
  failure of these changes. No check was bypassed.

Entry-point-to-test trace (static): `integrate-validate`/`integrate_batch` → `integrate_prepare`
(read-only guard via `guard(..., report=False)`) → manifest/report → `integrate-publish` →
`report_validation` (write-token replay). The tests named above exercise that path with mocked
`gh`, `git` and `merge_local` boundaries.

## Coverage

No production line, branch, method or class was added or changed in this session, so there are
no new branches to map to new tests here.

Prior tool evidence in the task file: `bash scripts/python-coverage.sh` →
`python coverage: every changed script line is covered`. The changed production branches and
their existing tests, as recorded earlier:

- exhausted conflict stays out of the resolver queue —
  `test_conflict_goes_back_to_the_workers_after_three_tries`;
- conflict below the limit stays queued — `test_conflict_is_handed_to_the_resolver_not_failed`;
- moved head keeps its precise reason and `simple=True` —
  `test_a_branch_that_moved_after_planning_is_retested_without_the_debugger`;
- already-present PR prints the notice and is closed —
  `test_an_already_integrated_pr_is_closed_and_marked_done` / the local variant
  `test_an_already_integrated_pr_is_closed_and_task_reopened`;
- wiring prune cases still gate the exact merge tip — seven subtests of
  `test_integrator_prunes_wiring_before_gating_the_merge_commit`.

## Clinical and architecture review

None. This is an infrastructure/tooling test-seam repair with no signed data, cross-organization
sharing, patient association, prescribing or critical-result behavior.

## Review verdicts

Copied from the task file's prior read-only review record; these are not new reviews produced
by this resumed run:

- aurevia-github-workflow: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

No Critical or Required finding remained at the prior final re-check. The brief named no
consulting specialists for this task, so none were consulted.

## Findings

1. **The brief's seam wording does not match the final merged design.** It asks to patch
   `guard_blockers` directly. The final tree instead keeps `integrate_prepare()` calling
   `guard(pr, is_repair(pr), report=False)` (the read-only seam that returns the tuple) and
   leaves `guard_blockers` as the pure blocker calculator called inside `guard`. This is the
   design recorded in `.opencode/config/MEMORY.md` row 53. Patching `guard_blockers` with the
   old list fixtures would break `kind, files, lines = guard_blockers(...)` unpacking, so I did
   not apply the brief's literal instruction. This is recorded as a brief discrepancy, not a
   defect.
2. The task file is still marked `[-]` with `agent: swarm-1` and `attempts: 22` although it has
   complete evidence, four GO verdicts and a merged PR. Per the swarm rules an integrator, not a
   worker, marks it `[x]`; I left the tracker untouched.
3. Prior CI evidence records that the exact-SHA verdict for main `5a86e60f` was unavailable
   because the run stayed queued with zero jobs. That is an environment limitation already
   documented in the task file, not a suppressed or bypassed check.
4. I could not independently re-confirm the green suite in this session because of the durable
   no-Git directive. The green result above is prior tool evidence, not a fresh measurement.

## Lessons for workers

- Read the task file's `evidence:`, `reviews:` and `ci-status:` before implementing. A task on
  the current branch that already has all three fields and a durable decision in MEMORY.md is
  complete; do not redo it or invent a new commit.
- Treat the advisor brief as a bounded plan, not a contract. When its named seam differs from
  the final merged design, follow the code and the durable decision and record the discrepancy.
- Respect the owner's "do not run any script that use git" directive for swarm tooling stories:
  the `scripts/tests` fixtures create temporary Git repositories, so the full suite, the Python
  coverage gate and `--base origin/main` constraint checks are all out of bounds for a worker.
  Static file inspection is still allowed and is enough to confirm an already-merged fix.

## Why this is not finished

The code work is already finished on the current tree and was merged by PR 1613. I stopped
without producing a fresh test/coverage run or a new commit:

- **What did not happen:** no `python3 -I -m unittest ...` suite, no
  `bash scripts/python-coverage.sh`, no `git merge --no-edit origin/main`, no commit.
- **Reason:** the owner's durable directive in `.opencode/config/MEMORY.md` forbids running
  scripts or tests that use Git and forbids Git-backed operations; the brief's request to merge
  and commit cannot override that directive. The green suite, coverage and CI evidence already
  recorded in the task file are prior tool evidence from earlier runs.
- **Most likely cause of any confusion:** the task is still marked `[-]` with 22 attempts and
  the advisor brief was re-dispatched against an already-complete story.
- **Exact next step:** the trusted integrator should confirm the task-file evidence against the
  current `origin/main` SHA in an environment where Git and GitHub execution are permitted,
  then mark the task `[x]`. If a person lifts the no-Git directive, re-run
  `python3 -I -m unittest discover -s scripts/tests -q` and `bash scripts/python-coverage.sh`
  and append the fresh final lines here; no code change is expected.
