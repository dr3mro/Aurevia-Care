# FIX-DELIVERY-CONCURRENCY-VALIDATION

## Summary

Restore Delivery runs that GitHub rejected at workflow validation (zero jobs, "This
run likely failed because of a workflow file issue") because `queue: max` was combined
with `cancel-in-progress` that evaluated to true on pull requests.

This story was already implemented, reviewed and merged before this worker run:
commit `611c2d9f` ("fix(ci): restore Delivery runs rejected by concurrency validation"),
PR https://github.com/dr3mro/Aurevia-Care/pull/1613, merged to main as `098354af`.
This worker rehydrated from the task file, verified the current checkout at `c06ca49c`
(exactly `origin/main`) and made **no production, test, workflow, skill or tracker
change**. The only file authored in this run is this checkpoint, which records the
verification and the documentation drift found on current main.

## Affected requirements

- OPS-06 — synthetic CI policy checks, monitored delivery, reversible changes.

## Risk class

Cross-boundary (CI validation and branch-protection check reporting). No patient data,
no migration, no runtime code.

## Review owner

Swarm integrator / repository owner. Read-only reviewers consulted in the earlier
merged run and re-consulted in this run; verdicts are listed under `## Review verdicts`.

## Data changes

None. No Flyway migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope; no audit event added or changed.

## Migration and rollout considerations

None. Workflow validation only; the change was merged and exercised by a real PR run
in the earlier task.

## Test evidence

Record whether the evidence came from a tool, reviewer, simulated user or human
participant: all commands below are **executed tool evidence** from this worker unless
explicitly labelled as a reviewer report. No simulated-user or human-participant
evidence exists for this story.

Verification commands run on `c06ca49c` (identical to `origin/main`, `0 0` ahead/behind):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
  → `Ran 6 tests in 0.220s` / `OK` (exit 0).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -q`
  → `Ran 39 tests in 0.042s` / `OK` (exit 0).
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I scripts/gen-opencode.py --check` → exit 1, confirmed **false positive**:
  `git ls-files -v .opencode/opencode.json` → `S` (skip-worktree CI profile copied over
  the worktree by `agent-run.sh`). `git show HEAD:.opencode/opencode.json` compared with
  a fresh `python3 -I scripts/gen-opencode.py` render → identical; the committed local
  profile is generated correctly.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1600 tests in 61.123s`
  / `FAILED (failures=4)`, exit 1 (second run confirmed the same summary). All four failures are in `scripts/tests/test_check_pack.py`
  (`test_cli_entry_point`, `test_main_reports_success`, `test_repository_pack_is_valid`,
  `test_simulated_user_skills_need_no_process_sections`) and are caused by the newly added
  `run-workflow` skill (commit `e010827c`) lacking `## Common rationalizations`, `## Red
  flags` and `## Verification` and not being in the specialists list. None of the files
  in this story's scope is involved; this is pre-existing main drift, recorded under
  `## Findings` and not fixed here (out of scope).
- `bash scripts/python-coverage.sh` → exit 1 on the same four unrelated
  `test_check_pack.py` failures; no script line was added or changed by this story.
- `git status --porcelain` → only `?? .agents/notes/FIX-DELIVERY-CONCURRENCY-VALIDATION-20261124-181906.md`;
  `git diff --stat origin/main...HEAD` → empty (no committed change on this branch).
- `git rev-list --left-right --count origin/main...HEAD` → `0 0`.
- `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers.
- `python3 -I scripts/check-verdicts.py .agents/notes/FIX-DELIVERY-CONCURRENCY-VALIDATION-20261124-181906.md --base origin/main`
  → `verdicts: only documentation changed; nothing to review`, exit 0.
- `actionlint` could not be installed in this worker (`python3 -m venv` fails in the
  image); the earlier merged run already recorded actionlint 1.7.12 with
  `-ignore 'unexpected key "queue" for "concurrency" section'` exit 0 at base `492d44b5`.

Earlier-run tool evidence (carried forward from the task file, not re-run here):
diagnosis `gh api repos/dr3mro/Aurevia-Care/actions/runs/45326930357` → `conclusion:
failure`, `jobs?per_page=100` → `total_count: 0`, no `Delivery gate` check on head
`342eae01`; last green run `45321478117`. Red test on the old workflow: 7 tests, 4
failures; green after the fix: 7 tests OK. Delivery run `45330751996` on PR head
`611c2d9f` created the `Policy and tooling` job and check, proving the workflow passed
server-side validation. PR 1613 merged as `098354af`; a later exact-SHA verdict was
unavailable from this worker environment behind the runner backlog, not a failure of
these changes.

Entry point to test trace: `pull_request` trigger in `.github/workflows/delivery.yml`
→ `concurrency` group/cancel/queue block → `scripts/tests/test_workflow_concurrency.py`
`test_delivery_queues_one_run_per_branch_without_cancelling`,
`test_delivery_reports_a_check_for_every_pull_request`,
`test_delivery_reports_a_check_for_every_branch_and_tag_push`.

## Coverage

No production Python, Java or application code was added or changed in this run and
none remains uncommitted from this story's earlier merged change. The only file written
here is a Markdown checkpoint, which has no executable branches. Coverage evidence for
the merged change is in the task file: `python3 -I -m unittest discover -s scripts/tests
-p 'test_workflow_concurrency.py' -v` → 7 tests OK, covering the exact group expression,
literal `cancel-in-progress: false` and `queue: max` on every queueing workflow, the
bare `pull_request` and `push` triggers, and the swarm per-run group. No suppression,
skip, exclusion, pragma or threshold change was introduced by this story. For this
exact SHA use the `## Test evidence` result of **6 tests OK**: the "7 tests" figure
is the earlier merged design, whose extra assertion was removed when a later owner
directive realigned the suite to branch-scoped queuing (coverage-auditor Optional).

## Review verdicts

This run (read-only, no files edited by reviewers):

- aurevia-github-workflow: GO
- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-devsecops: GO
- aurevia-coverage-auditor: GO

Earlier merged run (carried forward from the task file): aurevia-github-workflow GO,
aurevia-code-reviewer GO, aurevia-coverage-auditor GO, aurevia-devsecops GO. No
Critical or Required finding remained in either pass. All Optional findings are
recorded under `## Findings`.

## Findings

- **Already complete.** 611c2d9f / PR 1613 is an ancestor of HEAD and merged as
  098354af; the task file carries full evidence. Re-applying the fix would duplicate a
  merged change.
- **Design superseded by a later owner directive.** This story's decision (MEMORY.md
  line 51) used a per-SHA PR group (`github.event.pull_request.head.sha`). A later
  user directive (MEMORY.md line 55, commits `9501ac75`, `808dd61c`, `40235298`)
  deliberately changed Delivery to one queued run per branch
  (`${{ github.workflow }}-${{ github.repository }}-${{ github.head_ref || github.ref }}`,
  `cancel-in-progress: false`, `queue: max`) and aligned the test to it. The current
  config is valid and the in-scope tests pass. This worker did not undo that decision.
- **Stale comment in `.github/workflows/delivery.yml` lines 17-21.** It still says
  "a newer run cancels the older one", "no queue is declared" and "a cancelled exact
  SHA on main receives no verdict", all contradicted by `cancel-in-progress: false`
  and `queue: max` on lines 24-25. It was written for the short-lived cancel/no-queue
  state in `9501ac75` and left behind by `808dd61c`. It is a documentation defect in
  this story's area, but belongs to the later superseding change; flagged for the
  integrator/owner rather than changed unilaterally in this re-run.
- **Second stale comment in `.github/workflows/delivery.yml` lines 47-49** says
  `test_workflow_concurrency.py` "asserts every workflow declares queue: max"; the
  test asserts that only for `delivery.yml` and asserts *no* `queue` key on other
  workflows (`test_workflow_concurrency.py:27-41`). The actionlint ignore is still
  justified as a tool-version compatibility measure; only the rationale sentence is
  inaccurate (devsecops Optional).
- **Stale skill `.opencode/skills/aurevia-github-workflow/SKILL.md` lines 13 and 26**
  still claim Delivery groups PR runs by head SHA; line 30's "any gate without
  `queue: max`" is also over-broad because other workflows deliberately have no
  `queue` key. Same supersession; flagged rather than edited because changing a
  protected `.opencode/` file in a re-run of a merged story can block the merge gate.
  A skill-body edit would not require regenerating `opencode.json` (the generator
  embeds agent names and instructions, not skill bodies), but the work still needs an
  owner-approved scoped task.
- **Tracker reference drift.** `MEMORY.md:51` cites task `01273000` but this story's
  task file is `tasks/01273500-FIX-DELIVERY-CONCURRENCY-VALIDATION.md`; `01273000` is
  an unrelated task. For the tracker reconciler, not this worker.
- **No `task-requests` block was filed.** `scripts/swarm.py` `PROTECTED` includes
  `.github/*` and `.opencode/*`, and `request_entries` drops requests whose scope is
  a protected path (see `scripts/tests/test_task_requests.py`), so a request for the
  two stale-doc files would be invalid. The findings are recorded here for the owner
  to route through an authorized change.
- **Task mark hand-off.** `tasks/01273500-...` remains `[-]`; the integrator must mark
  it `[x]` with `evidence:` and `pr: https://github.com/dr3mro/Aurevia-Care/pull/1613`.
  Workers do not edit `tasks/`.
- **Pre-existing unrelated red suite.** `test_check_pack.py` has 4 failures on current
  main from the `run-workflow` skill added in `e010827c`; out of this story's scope.
- **Environment limitation.** `actionlint` and a working Python venv cannot be created
  in this worker; CI and the integrator run that gate.

## Lessons for workers

- Rehydrate before editing: a story carrying full evidence in its task file may already
  be merged. Confirm with `git merge-base --is-ancestor` on the recorded commit before
  re-implementing anything.
- Check durable decisions chronologically: a later `MEMORY.md` entry can supersede the
  design chosen by the story in the task file. Do not undo a later owner decision to
  satisfy an older story description; record the conflict and ask the owner.
- `gen-opencode.py --check` failing in a cloud worker is only a false positive when
  `git ls-files -v` shows `S` and the committed profile matches a fresh render; verify
  both, do not regenerate or commit the CI profile.
