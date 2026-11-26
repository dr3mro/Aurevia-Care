# FIX-DELIVERY-GATE-EXPECTED — 2026-11-25 00:37 UTC

## Summary

Resumed task 01263000 (requirement OPS-06): the required `Delivery gate` context must be
reported for every pull-request head. Source inspection shows the production fix and its
regression tests are already present at HEAD; this run closes an in-scope documentation
drift gap that still describes the retired push `paths-ignore`, and records the remaining
live-GitHub evidence as unavailable in this worker.

## Affected requirements

- OPS-06 (separate environments, CI policy checks and monitored rollouts): `.github/workflows/delivery.yml` reports the gate on every PR.

## Risk class

Cross-boundary (workflow documentation and tooling tests); no application or patient-facing code.

## Review owner

aurevia-github-workflow (brief names no read-only specialists; see Findings).

## Data changes

None. No migration, backfill, lock or rollback impact. Synthetic test data only.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit behavior changed.

## Migration and rollout considerations

None. Documentation-only correction plus one regression assertion; the workflow itself is unchanged.

## Loaded skills

- `aurevia-delivery` — task clause "Run the delivery loop for every story".
- `aurevia-github-workflow` — task clause "workflow/gate change" (scope includes `.github/workflows/delivery.yml`).

## Brief assessment

- The brief's read-only-specialist list is empty, while the worker prompt mandates the plan/code/coverage/security quality loop. The mandated reviewers were consulted instead; this is recorded as an incomplete brief, not as a rule change.
- The brief asks for `git merge --no-edit origin/main` and a local commit, but the top-level session authority forbids all Git and Git-backed operations (`.opencode/config/MEMORY.md` line 64, line 67). No `git`, `gh`, push, tag or tracker edit was run; live CI evidence is therefore BLOCKED, not passed.

## Test evidence

Entry-point-to-test trace: the workflow trigger/concurrency block at
`.github/workflows/delivery.yml:2-24` is the entry point; the guard is
`scripts/tests/test_workflow_concurrency.py` (`test_delivery_reports_a_check_for_every_pull_request`,
`test_delivery_queues_each_pr_head_and_ref_without_cancelling`,
`test_delivery_reports_a_check_for_every_branch_and_tag_push` and the new
`test_operator_docs_do_not_describe_a_filtered_delivery_trigger`).

Tool evidence (this worker):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` (baseline) → `Ran 8 tests in 0.174s`, `OK`.
- Same command with the new doc-drift assertion *before* the doc edits → `Ran 9 tests in 0.177s`, `FAILED (failures=2)`; both failures were `AssertionError: 'paths-ignore' unexpectedly found` in the two scoped docs (red first).
- Same command *after* the doc edits → `Ran 9 tests in 0.175s`, `OK` (green); after the final exactness tweak (a second `paths:` token assertion and precise push wording) → `Ran 9 tests in 0.176s`, `OK`, exit 0.
- `python3 -I scripts/check-docs.py` → exit 0, final line empty, before and after the doc edits.
- `python3 -I -m unittest discover -s scripts/tests -q > /tmp/opencode/aurevia-full-tests.log 2>&1` → exit 1, `Ran 1742 tests in 70.457s`, `FAILED (failures=9, skipped=1)`. Seven failures are `test_check_pack.*` on the CI `opencode.json` profile installed under `skip-worktree` (for example `test_check_mode_detects_drift`: `AssertionError: 1 != 0`), and two are `test_hermetic_env` signal tests ending in `AssertionError: 1 != 130 : cat: .agent/base-sha: No such file or directory` plus `ps: command not found` in the temp git shim. The seven pack failures are the documented CI-profile-under-`skip-worktree` false positive (AGENTS.md "Known worker-environment false positives"); the two signal failures are a worker-image limit (`ps` absent in the temp git shim and no `.agent/base-sha` fixture). None is in `test_workflow_concurrency.py` or in a file changed by this run. The integrator runs the trusted full gate.
- `command -v actionlint` → no output, exit 1: actionlint is not installed in this worker. The workflow file was not changed, and the source-level trigger assertions above cover the contract here.
- Final rerun after the notes were completed: targeted suite `Ran 9 tests in 0.174s`, `OK`; `check-docs.py` exit 0; `check-secrets.py` exit 0.
- `python3 -I scripts/check-verdicts.py .agents/notes/FIX-DELIVERY-GATE-EXPECTED-20261125-003739.md` → exit 0 (no `--base`, so the check ran without Git; all four verdict lines parse and no unresolved Critical/Required finding is recorded).
- `python3 -I scripts/check-constraints.py`, `bash scripts/python-coverage.sh`, `git merge --no-edit origin/main`, `git commit` and any `gh`/GitHub API call: NOT RUN — these are Git-backed or GitHub-backed operations, forbidden by the session authority (`.opencode/config/MEMORY.md:64,67`) and by the brief for `gh`. No suppression, exclusion, skipped test or lowered threshold was added. The integrator's `scripts/quality-gate.sh` must run the constraint half on an authorized checkout.

Source evidence (file reads, not reviewer or human evidence):

- `.github/workflows/delivery.yml:2-24` — bare `pull_request:` trigger, `push:` limited to `branches: [main]` and `tags: ['v*']`, `concurrency.group: ${{ github.event.pull_request.head.sha || github.ref }}`, `cancel-in-progress: false`, `queue: max`.
- `scripts/tests/test_workflow_concurrency.py:32-85` — tests assert the per-head group, no cancellation, `queue: max`, and no path filters on either trigger.
- `.opencode/config/MEMORY.md:11,52` — decisions that the gate reports on every PR head and that per-SHA groups replaced PR-only cancellation because GitHub rejects `queue: max` with cancellation.
- `tasks/01263000-FIX-DELIVERY-GATE-EXPECTED.md:1-35` — 25 prior attempts; implementation committed `c959c39b`, merged as `e33a2ae7`; only live GitHub verdicts were still pending.

No simulated-user or authorized human feedback was available for this story.

## Coverage

No production source file was changed by this run. The only code touched is a test-only assertion; test files are omitted from `scripts/python-coverage.sh` (`--omit='scripts/tests/*'`), so there is no production line or branch to cover. The relevant gate is the targeted unittest suite recorded under Test evidence.

New assertion and the branch it takes:

- `test_operator_docs_do_not_describe_a_filtered_delivery_trigger`, `assertNotIn("paths-ignore", text)` false side (stale claim present): red on both docs before the fix, recorded above.
- The same assertion's true side (no stale claim): green for `docs/reference/testing-guide.md` and `docs/swarm/EFFICIENCY.md` after the fix.
- The companion `assertIn("delivery gate", text.lower())` is true in both docs and keeps the operator guidance tied to the required context name.
- `assertNotIn("paths:", text)` covers the second filter spelling mirrored from the workflow assertions; true in both docs after the edits, and it would fail if a future doc reintroduced a `paths:` claim (not exercised red here because no such claim existed at any point).

No coverage threshold, exclusion or suppression was changed.

## Review verdicts

- aurevia-plan-reviewer: GO (after the mid-flight duplicate-test finding and the out-of-scope clause deferral were evidenced)
- aurevia-code-reviewer: GO (its two Optional accuracy suggestions were applied, the suite rerun green, and the reviewer rechecked the final two lines with GO)
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

Reviewer evidence source: four read-only `subagent` specialists (plan, correctness, coverage and defensive security) reported findings and final verdicts; they ran no commands and edited no files. Tool evidence above comes from commands executed in this worker. No simulated-user walkthrough or authorized human feedback was available.

## Findings

- The production fix was already present at HEAD before this run: the workflow has a bare PR trigger and per-head-SHA concurrency, with tests. No workflow or production script change was needed.
- Stale operator documentation in two scoped files still described the retired push path filter; that is the only source change in this run.
- Out-of-scope stale specification evidence: `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md:18,30` still claims a push `paths-ignore: ['docs/**', '**/*.md']` and that Delivery ignores docs paths. This file is outside this task's scope and is owned by a closed task; it is deferred to the integrator via the task request below, not edited here.
- The plan reviewer's first verdict was NO-GO because it observed the new test and the corrected docs mid-edit and read step 1 as a duplicate. Timeline evidence in Test evidence shows the test was added first and failed red on both docs before they were corrected; there is one test, not two. The out-of-scope documentation finding was accepted and is handled by the task request below.
- Live GitHub verification remains the only unmet acceptance item from task 01263000: a PR head must be observed reporting the exact `Delivery gate` check. No `gh`, `git` or GitHub API call is available in this session, so this evidence is BLOCKED, not passed.

## Lessons for workers

Detect stale operator docs with a deterministic assertion over the exact workflow invariant they describe: a path-filter claim that no longer matches `delivery.yml` is cheaper to catch in a unit test than in a production incident where branch protection waits forever. When a reviewer reads files while an edit is in flight, record the red-then-green command timeline so duplicate-work findings can be resolved from evidence.

## Why this is not finished

The source fix and the scoped documentation correction are green, but the story's live acceptance evidence is unavailable here. `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` is green; the missing evidence is a GitHub Actions run on a pull-request head reporting `Delivery gate`. The reason is the session authority forbidding Git/Git-backed operations (`.opencode/config/MEMORY.md:64,67`) and the brief forbidding `gh`; no GitHub token, live run lookup, merge or commit was used, and task 01263000 was left `[-]` for the integrator. What was tried: source and test inspection, targeted red/green tests, full tooling suite (nine documented worker-environment failures unrelated to this change), `check-docs.py`. Exact next step for the integrator: on a current PR head, confirm `Delivery gate` reports on the exact head SHA after a bare-trigger Delivery run, confirm no queueing behind a stale head, then mark task 01263000 `[x]` with the run id and fold in the task request below.

## task-requests

```task-requests
[
  {
    "title": "Correct stale Delivery push filter claim in vulnerability-management clause",
    "details": "docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md:18 and :30 still assert that .github/workflows/delivery.yml has a push paths-ignore for docs/** and **/*.md. The workflow is unfiltered on both push and pull_request. Update the prose and cite the current line numbers; add a regression guard if the spec-clause suite supports it.",
    "scope": ["docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md"],
    "depends": []
  }
]
```
