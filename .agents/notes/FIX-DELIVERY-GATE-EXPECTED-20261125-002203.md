# FIX-DELIVERY-GATE-EXPECTED — Report the required Delivery gate check for every PR head

Story/task: `01263000-FIX-DELIVERY-GATE-EXPECTED` (already `[-]`, `agent: swarm-3`, 22 attempts).
Requirement: OPS-06 (`.opencode/config/PROJECT_V1_SPEC.md:139` — CI policy checks and
monitored delivery gates).
Notes file: `.agents/notes/FIX-DELIVERY-GATE-EXPECTED-20261125-002203.md`.
Evidence sources below are labelled tool (executed command), reviewer (read-only
subagent), or prior-run evidence recorded in the task file. No simulated user or
human participant evidence was available in this run.

## Brief assessment

The brief names no read-only specialists in its Routing section, but the mandatory
quality loop in the execution prompt and AGENTS.md require `aurevia-plan-reviewer`,
`aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops`. I am
following AGENTS.md for those stages and recording the conflict here.

The brief instructs me to commit, merge `origin/main`, and use Git-backed checks.
The durable directive in `.opencode/config/MEMORY.md:7` (owner, 2026-11-24:
"dont run any script that use git") and the system directive "never run Git
commands or Git-backed operations" prohibit that. I have therefore run only
Git-free commands, made no commit and no merge, and did not call `gh`. The
brief is wrong on this point; I carried on from AGENTS.md and the durable
directive as instructed.

## Acceptance mapping

| Acceptance item | State | Evidence |
|---|---|---|
| Every PR head starts Delivery (no `pull_request` path filter) | Met before this run | `.github/workflows/delivery.yml:7`; `test_delivery_reports_a_check_for_every_pull_request` (tool, 8/8 OK) |
| Every push/tag starts Delivery (no push path filter) | Met before this run | `delivery.yml:11-13`; `test_delivery_reports_a_check_for_every_branch_and_tag_push` (tool) |
| A new PR head is verified immediately, not queued behind a stale head | Met before this run | `delivery.yml:21-24` group `${{ github.event.pull_request.head.sha || github.ref }}`, `cancel-in-progress: false`, `queue: max`; `test_delivery_queues_each_pr_head_and_ref_without_cancelling` (tool) |
| Main/tag/dispatch/merge-group runs keep every exact-SHA verdict | Met before this run | `delivery.yml:17-24`; `test_serialized_groups_reject_legacy_keys_and_invalid_queue` (tool) |
| Required `Delivery gate` context fails closed | Met before this run | `delivery.yml:267-286`, gate job `if: always()`; prior-run task evidence (reviewer/tool) |
| Defender comments, tests and docs updated in the same change | Workflow comments and tests present; stale doc sentence corrected in this run | `delivery.yml:4-10,17-20`; `docs/reference/testing-guide.md:74-79` (tool edit + `check-docs.py` green) |
| Live GitHub verdict on the exact SHA and a fresh PR head | Not verified in this run | `gh` forbidden by brief; Git forbidden by durable directive |

## Test evidence

Commands run in this worker (all Git-free), with final lines:

- `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
  → `Ran 8 tests in 0.160s` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -v`
  → `Ran 59 tests in 0.107s` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_delivery_gate.py' -v`
  → `Ran 20 tests in 0.007s` / `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_delivery_workflow_gates.py' -v`
  → `Ran 15 tests in 0.213s` / `OK`.
- `python3 -I scripts/check-docs.py` → exit 0 (run again after the doc edit: exit 0).

Prior-run evidence carried in the task file (not re-executed here): the red-first
run failed 3 concurrency tests before the fix, then 5 passed; full
`python3 -I -m unittest discover -s scripts/tests -q` ran 1386 tests OK;
`bash scripts/python-coverage.sh` reported every changed script line covered;
`actionlint -ignore 'unexpected key "queue" ...'` and all policy checks exited 0.

## Coverage

No production code, Python script or test was added or changed in this run, so
there are no new lines or branches to cover. The touched-area file is Markdown
(`docs/reference/testing-guide.md`), which no coverage tool measures. The four
Python suites above were re-run unchanged and are green. I did not run
`scripts/python-coverage.sh` or `scripts/coverage-check.sh` because both diff
against `origin/main` and therefore invoke Git, which the durable directive
forbids; the earlier run's coverage evidence is cited above.

## Review verdicts

- aurevia-plan-reviewer: GO (no Critical or Required gap; optional exact-wording refinement applied)
- aurevia-code-reviewer: GO (no Critical or Required finding; two Optional doc-accuracy notes applied; follow-up recheck of the final wording also GO)
- aurevia-coverage-auditor: GO (no executable line or branch changed; no coverage obligation; no threshold, exclusion or skip touched)
- aurevia-devsecops: GO (0 Critical, 0 Required, 2 Optional; no credential or PHI introduced; fail-closed gate and exact-SHA control accurately described)

Reviewers were read-only and were told not to run Git or `gh`. The final wording at
`docs/reference/testing-guide.md:75-79` was tightened after review: it no longer
implies a live docs-only exception (`scripts/swarm.py:4087-4090` has none) and it
qualifies pushes that actually start a run (`[skip ci]` commits do not).

## Findings

- The workflow fix described by the story is already on `main`; no production
  change was needed in this run. This is stated plainly rather than claiming a
  new implementation.
- `docs/reference/testing-guide.md:75-76` said "`paths-ignore` on `push` still
  skips docs-only commits that are already on `main`". The `delivery.yml` push
  trigger has no path filter, so the sentence was false and contradicted
  `delivery.yml:8-10`. Corrected in this change and re-checked with `check-docs.py`.
- Out of my editable scope (recorded for the integrator, not edited):
  `docs/swarm/EFFICIENCY.md:76` and
  `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md:18`
  both still claim a push `paths-ignore` exists.
- No live docs-only merge exception exists: `scripts/swarm.py:4087-4090` maps a
  skipped run to `missing` and `request_delivery_rerun` reruns the exact PR run.
  My first draft of `testing-guide.md` implied such an exception; the code
  reviewer caught it and the final wording was corrected and re-reviewed GO.
- The task file's `details` still say "PR-only cancel-in-progress", which is
  stale against `delivery.yml:17-24` (per-PR-head group, no cancellation) and
  MEMORY.md:54. Workers may not edit `tasks/`; integrator reconciliation needed.
- Reviewer Optional nuance: `request_delivery_rerun` can only comment-and-wait
  when there is no valid completed PR run or one awaiting approval, so the
  guide's "reruns the exact pull-request run" is a policy statement, not an
  unconditional action. No gate impact; left as written.

## Lessons for workers

- When a story's workflow fix has already landed, verify the exact trigger and
  concurrency invariants with the targeted no-Git tests and spend the run on
  the remaining in-scope drift, instead of re-implementing the fix.
- A workflow doc can stay stale after the workflow is corrected: grep for
  `paths-ignore` across the docs whenever a path filter is removed.
- Read the durable directive in `.opencode/config/MEMORY.md` before a brief that
  asks for Git operations; the directive wins and the conflict belongs in the
  notes file.

## Why this is not finished

The code fix is complete, but two acceptance items cannot be closed in this
worker:

1. Confirming the exact-SHA Delivery verdict on GitHub. Command that would be
   needed: `gh run view <run-id>` / `gh pr checks`. Not run: the brief forbids
   `gh`, and `MEMORY.md:59` only authorises it for the orca maintainer. The
   prior task file recorded runs 45289808826 and 45289961489 as pending behind
   a runner backlog at 23:43Z; no newer verdict is available here.
2. Confirming a newly created or rebased PR reports `Delivery gate` on its head.
   Not run: no GitHub access and Git operations are forbidden.

Exact next step for the integrator or maintainer: on a worker with authorised
`gh` access, check that the newest open PR Delivery run reports the `Delivery
gate` check on the PR head SHA, confirm both pending runs reached success, then
mark task `01263000` `[x]` with those run ids. The documentation correction in
this change must be committed by the integrator because workers may not run Git.

```task-requests
[
  {
    "title": "Remove stale push paths-ignore claims from delivery docs",
    "details": "docs/swarm/EFFICIENCY.md:76 and docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md:18 still say a push paths-ignore skips docs-only Delivery runs. delivery.yml now has a bare pull_request trigger and a push trigger with only branches/tags. Correct both sentences and re-run check-docs.py.",
    "scope": ["docs/swarm/EFFICIENCY.md", "docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md"],
    "depends": ["01263000"]
  }
]
```
