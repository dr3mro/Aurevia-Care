# FIX-DELIVERY-CONCURRENCY-VALIDATION — verification pass

## Summary

Story OPS-06: restore Delivery runs rejected by GitHub at workflow validation. GitHub rejects
`queue: max` combined with `cancel-in-progress: true`; because `delivery.yml` used an expression
that evaluated to true for every pull request, every PR Delivery run failed at creation with zero
jobs and never reported the required exact-SHA `Delivery gate` check.

This run is a **verification pass**. The scoped change was already present in the checkout when
this worker started: `delivery.yml` groups PR runs by `github.event.pull_request.head.sha`
(falling back to `github.ref`), sets `cancel-in-progress: false`, keeps `queue: max`, and keeps
bare `pull_request` and `push` triggers; `test_workflow_concurrency.py` asserts the contract;
the `aurevia-github-workflow` skill and the durable trackers already describe the decision.
The current checkout contains the complete implementation, so **no new production code, workflow
edit or test was authored in this run**. Per the newest reconciled lesson
(".agents/reports/2026-11-22.md", "One clean verification pass is enough"), the single trusted
next step is recorded below rather than re-doing the work.

## Affected requirements

- OPS-06 — CI policy checks, exact-SHA verification and reversible delivery. Spec line 139.

## Risk class

Cross-boundary (GitHub Actions delivery gate, branch protection and exact-SHA verdicts). No
clinical or patient data boundary is touched.

## Review owner

Orchestrator / integrator. Worker holds no write credentials and made no Git, `gh` or API call.

## Data changes

None. No migration, backfill, table or lock/volume impact; no rollback or forward-fix step.

## Authorization boundaries and audit events

Unchanged. `permissions: contents: read` remains on the Delivery workflow; no token or secret was
added to a PR-head context. No tenant, role or patient-link scope is involved and no audit event
was added or changed.

## Migration and rollout considerations

Workflow configuration only. The queueing group policy is documented in
`.opencode/skills/aurevia-github-workflow/SKILL.md`; a fresh PR head starts in its own group while
stale runs finish in theirs, so every exact SHA keeps its own verdict. No deployment action.

## Test evidence

Evidence source labels: **tool** = command executed in this checkout; **reviewer** = read-only
subagent report; **prior tool** = measurements recorded in the task file from earlier runs; no
simulated-user or human-participant evidence.

Tool commands run this pass (all from the repository root):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
  -> `Ran 8 tests in 0.223s / OK`. Entry point to test: `delivery.yml` top-level `concurrency`
  block -> `test_workflow_concurrency.WorkflowConcurrencyTest`. The extra
  `test_delivery_reports_a_check_for_every_branch_and_tag_push` makes 8 tests here; the brief's
  "7 tests" is an earlier snapshot (brief discrepancy noted below).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -v`
  -> `Ran 67 tests in 0.130s / OK`.
- `python3 -I scripts/check-docs.py` -> exit 0.
- `python3 -I scripts/check-secrets.py` -> exit 0.
- `python3 -I scripts/check-names.py` -> exit 0.
- `bash -n scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh` -> OK;
  `shellcheck` on the same files -> OK (`/usr/bin/shellcheck`).
- `grep -nE '^(<<<<<<<|>>>>>>>) '` over all scope files -> no conflict markers.
- `cmp -s .opencode/opencode.json .opencode/opencode.ci.json` -> identical; both
  `a4de790ca85efdf82e68b2ca02e2b60557361313c9c6a288276adc1189b2f31b`. This confirms the known
  cloud-worker artifact OPS-CW-003-01: `python3 -I scripts/gen-opencode.py --check` exited 1
  because the worktree file is the CI profile installed under `skip-worktree`, not the committed
  local profile. HEAD truth cannot be inspected without Git in this session (BLOCKED below);
  the false positive is not a defect of this change.
- `actionlint` -> not installed in this worker (recorded as unavailable, not a pass). The
  workflow's actionlint step uses `-ignore 'unexpected key "queue" for "concurrency" section'`;
  the narrow ignore is asserted by `scripts/tests/test_delivery_workflow_gates.py:244-258`
  (reviewer evidence), while the queue contract itself is asserted by
  `test_workflow_concurrency.py`. The actionlint gate was green in prior tool evidence at base
  `492d44b5`.
- `python3 -I scripts/gen-opencode.py --check` -> exit 1, explained above (OPS-CW-003-01).

Prior tool evidence in `tasks/01273500-FIX-DELIVERY-CONCURRENCY-VALIDATION.md`: red
`python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
-> `Ran 7 tests ... FAILED (failures=4)` on the old workflow, then green `Ran 7 tests ... OK`;
`actionlint` 1.7.12, `check-docs.py`, `check-constraints.py --base origin/main --require-base`,
`check-secrets.py`, `check-names.py`, `gen-opencode.py --check` all exit 0 at base `492d44b5`.
Delivery run 45330751996 on PR head `611c2d9f` created 1 job (`Policy and tooling`) where prior
PR runs had zero jobs; PR #1613 merged to main as `098354af`. Delivery run 45339545141 for main
SHA `5a86e60f` stayed pending with zero jobs behind 30+ queued runs; the exact-SHA verdict is
unavailable from this environment, not a failure of these changes. No check was bypassed.

BLOCKED in this session (durable no-Git directive, MEMORY.md line 64/67): `git merge --no-edit
origin/main`, scoped commit, `check-constraints.py --require-base` (needs a Git diff), `git show
HEAD:.opencode/opencode.json`, live `gh`/Actions observation and any fresh PR run. These are
recorded as BLOCKED with missing evidence, not as passes or failures.

## Coverage

Command: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
-> 8 tests OK; `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py'
-v` -> 67 tests OK.

No production Python or application code changed in this story, so no JaCoCo/Vitest/Jest
coverage gate applies. The only executable additions are test assertions, all of which ran:

| New/changed behaviour | Test |
|---|---|
| Delivery group is exactly `${{ github.event.pull_request.head.sha || github.ref }}` | `test_delivery_queues_each_pr_head_and_ref_without_cancelling` |
| PR head wins over ref; two heads differ; empty head falls back to ref | `test_delivery_queues_each_pr_head_and_ref_without_cancelling` |
| `cancel-in-progress: false` is a literal false on the Delivery group | `test_delivery_queues_each_pr_head_and_ref_without_cancelling`, `assert_queue` |
| `queue: max` on Delivery, swarm plan and integrator | `test_delivery_queues_each_pr_head_and_ref_without_cancelling`, `test_swarm_plan_serializes_all_waves_in_the_repository`, `test_integrator_serializes_called_and_dispatched_runs` |
| Bare `pull_request` trigger (no filter) | `test_delivery_reports_a_check_for_every_pull_request` (`assertIsNone`) |
| Bare `push` trigger with no path filters | `test_delivery_reports_a_check_for_every_branch_and_tag_push` |
| Swarm plan group serializes per repository | `test_swarm_plan_serializes_all_waves_in_the_repository` |
| Integrator group covers workflow_call and workflow_dispatch | `test_integrator_serializes_called_and_dispatched_runs` |
| Other workflows group per run without queue | `test_other_workflows_run_concurrently_per_run` |
| Swarm worker group is per run and per slot | `test_swarm_workers_have_a_group_per_slot` |
| Legacy `github.run_id` / `github.head_ref` keys and invalid queue rejected | `test_serialized_groups_reject_legacy_keys_and_invalid_queue` |
| Delivery classifier handles passed/failed/pending/missing/approval states | `DeliveryStateTest` (67 tests) |

No suppression, skip, exclusion, `@Disabled`, lowered threshold or vacuous assertion was added.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or
critical results. An independent architecture concern (concurrency group keyed by head SHA) was
reviewed by the workflow and code reviewers below.

## Brief discrepancies

- The brief names `tasks/01273000-FIX-DELIVERY-CONCURRENCY-VALIDATION.md`; the tracked file is
  `tasks/01273500-FIX-DELIVERY-CONCURRENCY-VALIDATION.md` and its `scope` field repeats the wrong
  `01273000` name. No file was renamed outside this worker's scope.
- The brief records "Ran 7 tests"; the current checkout has 8 tests in
  `test_workflow_concurrency.py` (an added push-trigger test). Current tree is authoritative.
- The brief's "Consult these read-only specialists" list is empty, while its quality-loop
  paragraph names the four reviewers consulted below; the story evidence names the same four.
- The brief asks for `git merge --no-edit origin/main` and a scoped commit. The durable directive
  in MEMORY.md (lines 64, 67) and this session's rules forbid Git and Git-backed operations, and
  the brief is guidance that cannot change those rules. Those steps are BLOCKED, not skipped.

## Skills loaded

- `aurevia-delivery` — every story runs the delivery loop; matched "working any story or swarm task".
- `aurevia-github-workflow` — the task touches `.github/workflows/delivery.yml`, a gate/concurrency
  and branch-protection change; the brief also lists its SKILL.md under "Read first". Loaded via
  the skill tool. Its concurrency invariants are reflected in the acceptance checks; no stale
  `cancel-in-progress` guidance remains at lines 13 and 26.

## Review verdicts

- aurevia-github-workflow: GO — contract fully verified (per-SHA group, literal `false`, `queue: max`, bare triggers, swarm per-run/per-slot groups, `Delivery gate` reachability); no vacuous assertions, no Critical or Required finding. Optional: skill line 30 should say "queueing gate" not "any gate"; push-trigger test could pin branches/tags; dead `DeliveryGateTest.gate` helper; backlog is operational, not a defect.
- aurevia-code-reviewer: GO — core contract consistent and reachable, assertions non-vacuous; four Optional findings only (skill line 17 overstates push coverage; push-trigger test could also assert branches/tags; WORKSPACE.md line 12 generalises queueing to all workflows; notes evidence initially named the wrong test for the actionlint ignore — corrected in this file). No Critical or Required finding.
- aurevia-coverage-auditor: GO — every workflow-contract behaviour maps to a named, non-vacuous test; all 8 + 67 tests carry real assertions; no `.skip`, `pragma: no cover`, exclusion or threshold change. Reviewer noted it verified current file state rather than a Git diff (no Git access) and was not given the notes path, so the `## Coverage` section could not be reviewed directly.
- aurevia-devsecops: GO — no Critical/Required/Optional findings; `permissions: contents: read` intact, NVD key still push-only (`:133`), bare PR trigger, no path filter, per-SHA group preserves exact-SHA verdicts, actionlint ignore narrowly scoped and asserted. Evidence: reviewer file reads and read-only searches.

## Findings

- The scoped change, its tests, the skill text and both durable trackers (`MEMORY.md` line 52,
  `BUGS.md` line 14) were already correct at the start of this run. Task
  `01273500-FIX-DELIVERY-CONCURRENCY-VALIDATION.md` is `[-]` with `agent: swarm-10` and
  `attempts: 22`; per the worker rules the integrator marks it `[x]` after merge, so no tracker
  edit was made here.
- `gen-opencode.py --check` exit 1 is the documented OPS-CW-003-01 cloud-worker artifact,
  confirmed by byte-identical worktree config and CI profile. Do not regenerate or commit it.
- `actionlint` is not installed in this worker; the integrator/Delivery own that gate.
- Git-dependent verification (base refresh, commit, exact-SHA CI observation) is unavailable in
  this session and must be done by the trusted integrator.
- Optional, non-blocking improvements raised by the code reviewer (not changed in this
  verification pass): (a) `aurevia-github-workflow/SKILL.md:17` says "every push and pull request
  runs Delivery", but pushes are limited to `main` and `v*` tags — the accurate statement is
  "every push to the protected branch or tag and every pull request" (no *path* filter);
  (b) `test_workflow_concurrency.py:81-85` could also assert `branches == ["main"]` and
  `tags == ["v*"]`; (c) `.opencode/config/WORKSPACE.md:12` wrongly generalises `queue: max` to
  all workflows, while the test requires most workflows to be per-run with no `queue` key —
  a tracker edit owned by the integrator. None affects acceptance.
- Optional from the workflow reviewer: (d) `aurevia-github-workflow/SKILL.md:30` red flag says
  "any gate without `queue: max`", which would wrongly flag valid per-run workflows — qualify as
  "any **queueing** gate"; (e) `test_swarm_delivery_state.py:368-374` has an uncalled
  `DeliveryGateTest.gate` helper (tidiness only); (f) the unbounded `queue: max` backlog is an
  operational capacity matter for the integrator, not a defect — do not replace queueing with
  cancellation because that drops exact-SHA verdicts.

## Lessons for workers

- On a re-dispatched story, survey the working tree, task evidence and newest reconciled report
  first. If the scoped fix and its tests are already green, contribute the missing hand-off only;
  re-implementing the same YAML contract adds churn and merge conflicts.
- A green `gen-opencode.py --check` failure is not always staleness: compare the worktree file to
  `.opencode/opencode.ci.json` before blaming the change (OPS-CW-003-01).
- A worker notes file is the hand-off and the pull request description; if it is absent, the task
  can be re-dispatched indefinitely even when the code is green.
- Keep exact-SHA PR groups: grouping by branch name makes a new head queue behind a stale one and
  leaves the required `Delivery gate` check unreported.

## Why this is not finished

The scoped change is complete and all locally runnable gates are green, but this run ends with a
trusted hand-off because Git/GitHub evidence cannot be produced in this session. Missing command
and result: `git merge --no-edit origin/main`, the scoped `git commit`, and any fresh `gh`/Actions
observation were **not run** — no error was produced because the durable directive
(`.opencode/config/MEMORY.md:64,67`) forbids Git and Git-backed operations here, and the brief is
advisory and cannot change that rule. Most likely cause: this session's runtime authority, not a
defect in the change. What was tried instead: direct file reads of the workflow, tests, skill and
trackers; targeted unittest suites (8 + 67 green); file-based policy checks; `cmp` of the worktree
config against the CI profile to explain the `gen-opencode.py --check` exit 1; and four independent
read-only reviews (all GO). Exact next step for the trusted integrator: refresh the branch from
`origin/main`, commit the scoped files together with this notes file, and observe the required
checks on the exact head SHA — in particular that a fresh PR Delivery run creates jobs and reports
`Delivery gate` (prior tool evidence: run 45330751996 on `611c2d9f` created 1 job where earlier PR
runs had zero; PR #1613 merged as `098354af`). Do not mark the task `[x]` until that exact-SHA
evidence is recorded.
