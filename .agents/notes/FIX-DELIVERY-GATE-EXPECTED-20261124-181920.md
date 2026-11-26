## Summary

Reconciled the Delivery check-reporting fix with the owner-approved concurrency policy.
The original fix (bare `pull_request`, no path filters) was already on `main` from
commit `c959c39b`; a later owner decision replaced PR-only cancellation with a
per-branch queue (`cancel-in-progress: false`, `queue: max`, MEMORY.md 2026-11-24).
In-scope workflow comments, the operator guide, the workflow skill and their regression
tests now describe that live policy. No verification job, permission, trigger or gate
threshold was changed.

The brief's Routing section named no specialists, so I followed the worker quality loop
and AGENTS.md: plan-reviewer before the edit, then github-workflow, code-reviewer,
coverage-auditor and devsecops after it. The brief omitted the `docs/swarm` write ban
that is explicit in the worker prompt; I followed the prompt and reverted my initial
`docs/swarm/EFFICIENCY.md` edit.

## Affected requirements

OPS-06 (separate environments, CI policy checks, reversible deployment, monitored
rollout). No clinical requirement is touched; no patient data is used.

## Risk class

Cross-boundary CI/documentation (docs/WORKFLOW.md): workflow gate contract and operator
instructions. Not safety-critical clinical behavior.

## Review owner

aurevia-github-workflow (workflow semantics), aurevia-devsecops (defensive gate),
aurevia-code-reviewer and aurevia-coverage-auditor (change and tests), plan-reviewer
before coding.

## Data changes

None. No migration, fixture or database change.

## Authorization boundaries and audit events

None. Workflow keeps `permissions: contents: read`, `persist-credentials: false`,
write scopes only in the release job and `NVD_API_KEY` only as an action input.
No token, credential or PHI-shaped string was added.

## Migration and rollout considerations

Workflow configuration only: the next Delivery run on any PR or push uses the existing
file; no migration, backfill or rollback step is needed. A wrong comment or skill
instruction can cause a future worker to reintroduce a filter or cancellation, so four
defender assertions pin the prose to the parsed workflow.

## Test evidence

All commands run from `/__w/Aurevia-Care/Aurevia-Care` at base `c06ca49c`; no network,
`gh`, push or tag was used.

Red before the prose fixes (tool evidence):
`python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
→ `Ran 9 tests in 0.155s`, `FAILED (failures=3)`: the skill still named a per-SHA
group and both operator docs still promised `paths-ignore`.
Base-file grep proving the workflow-comment assertions would also fail red:
`git show HEAD:.github/workflows/delivery.yml | grep -n 'a newer run cancels'`
→ `17:# Two runs of this workflow on one branch are redundant: a newer run cancels`;
`git show HEAD:.github/workflows/delivery.yml | grep -n -E 'no queue is declared|asserts every workflow declares'`
→ `20:# GitHub rejects queue:max together with cancellation, so no queue is declared;`
and `49:          # asserts every workflow declares queue: max.`

Green after the fixes (tool):
`python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
→ `Ran 10 tests in 0.162s`, `OK`.

Full tooling suite (tool):
`bash scripts/python-coverage.sh` → `Ran 1600 tests in 56.793s`, `FAILED (failures=6)`.
All six failures are in `test_check_pack.py`
(`test_check_mode_detects_drift`, `test_cli_entry_point`, `test_main_reports_success`,
`test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`,
`test_simulated_user_skills_need_no_process_sections`); none is in a changed file.
Pre-existing worker-image artifact: `git ls-files -v .opencode/opencode.json` → `S`,
the worktree file is the CI profile copied under `skip-worktree` (OPS-CW-003-01), and
the pack check also reports `skill run-workflow: missing the section "## Common
rationalizations"` from the recently added, out-of-scope skill. `bash scripts/quick-check.sh`
before the edit printed the same check-pack errors. No threshold, exclusion or
suppression was added to make this pass.

Individual gates (tool; exact final lines are recorded in the task evidence):
`python3 -I scripts/check-docs.py` → exit 0;
`python3 -I scripts/check-secrets.py` → exit 0;
`python3 -I scripts/check-names.py` → exit 0;
`python3 -I scripts/check-wiring.py` → exit 0;
`python3 -I scripts/check-migrations.py --require-baseline` → exit 0;
`python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0
(each of these six checks printed no output and exited 0);
`actionlint` is not installed in this worker (`command -v actionlint` printed nothing),
so the workflow lint is left to CI.
`python3 -I scripts/gen-opencode.py --check` and `python3 -I scripts/check-pack.py`
reproduce the documented `skip-worktree` artifact above and were not "fixed" by
editing a protected generated file.

Review gate (tool): `bash scripts/quality-gate.sh origin/main .agents/notes/FIX-DELIVERY-GATE-EXPECTED-20261124-181920.md`
→ `quality gate exit: 0` (constraints clean, all four required reviewer verdicts GO).
`git diff --check` → `git diff --check: clean`.

Entry point to test trace: `.github/workflows/delivery.yml` triggers → `gate` job
(`Delivery gate`, `if: always()`, all seven jobs in `needs`) →
`scripts/check-delivery-gate.py` fail-closed verdict →
`scripts/tests/test_workflow_concurrency.py::DeliveryCheckContractTest`.
Producer behavior of the gate itself is covered by existing
`scripts/tests/test_check_delivery_gate.py`; this change adds only contract guards.

## Coverage

Production Python/script lines added: none. The only source edit is a test module
(the workflow file contains comments only) and Markdown documentation.

`bash scripts/python-coverage.sh` → the coverage stage reached `Ran 1600 tests in
56.793s`; the six failures above are pre-existing pack-profile artifacts, so the
script exited 1 before printing its final `python coverage:` line. No changed
production script line exists to cover; `scripts/tests/test_workflow_concurrency.py`
is excluded by `--omit='scripts/tests/*'` by design. No threshold, omit entry or
skip was changed.

New branches and their tests:
- gate job name/`if`/seven-job `needs` → `test_delivery_gate_is_unconditional_and_depends_on_all_jobs`;
- stale cancellation/"no queue" comments → `test_workflow_comments_match_the_queueing_policy`;
- stale per-SHA group in the skill → `test_skill_documents_the_live_concurrency_group_without_cancellation`;
- stale path filter in the operator guide → `test_testing_guide_describes_the_current_delivery_triggers`;
- unchanged trigger/group/queue invariants → six pre-existing `WorkflowConcurrencyTest` methods.

`scripts/swarm.py` and `scripts/tests/test_swarm_delivery_state.py` are in scope but
were not edited: their cancelled-run classifier and docs-only exception already match
the current no-filter policy (`delivery_state` treats a cancelled head as `failed`,
`markdown_only` is a defensive historical exception).

## Clinical and architecture review

Not applicable: no patient identity, result, order, prescription or release behavior
changed.

## Review verdicts

- aurevia-plan-reviewer: GO (all earlier Required gaps closed)
- aurevia-github-workflow: GO (R1 out-of-scope docs filed as task-requests)
- aurevia-code-reviewer: GO (no Critical or Required finding on the reapplied tree)
- aurevia-coverage-auditor: GO (10 guard tests, no changed production script lines)
- aurevia-devsecops: GO (no gate, permission or secret regression; task-request disposition accepted)


## Findings

- Landing path: `scripts/swarm_gate.py::validate_protected_files` classifies a
  `swarm/...` branch as WORKER and rejects workflow/`scripts/`/`.opencode/` changes in
  an automated PR. This story's scope explicitly grants those four files, but the
  trusted integrator must land them (or classify the PR as human); do not auto-merge
  the worker branch.
- `docs/swarm/EFFICIENCY.md` is forbidden to this worker (worker prompt and
  `TRACKERS` in `scripts/swarm_gate.py`). Proposed replacement for line 60:
  `| Delivery per push to main (docs-only) | 7 | 7 | 0 |`.
  Proposed replacement for line 76:
  `| 6 | No Delivery path filter | 0 | An earlier path filter on `push` saved ~6 min per docs-only commit but left the required check unreported on affected SHAs. Every push and pull request now runs the full chain so `Delivery gate` is always reported. Waves still run only on tasks/ changes or manual dispatch. |`
  A `task-requests` entry below files this for the integrator.
- `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md`
  is outside scope. Line 18 claims `paths-ignore: ['docs/**', '**/*.md']` on the push
  trigger and line 30 repeats that Delivery ignores docs paths. Both are false on
  `c06ca49c` (push has only `branches`/`tags`; PR is bare). Also filed below.
- `.opencode/config/MEMORY.md:51` still records the superseded per-SHA PR group;
  line 55 records the current per-branch queue. This tracker is forbidden to workers;
  the integrator should mark line 51 superseded so a rehydrating worker does not
  restore it.
- Worktree stability: during the first review loop an external actor ran
  `git reset --hard c06ca49c` three times (reflog 18:24:29, 18:27:57, 18:29:00 UTC),
  discarding the uncommitted patch while read-only reviewers were reading it. The
  patch was reapplied from an exact-string reapply script and re-reviewed. Evidence:
  `git reflog` and repeated `git status --short`.

## Lessons for workers

- When a fix already on `main` is still open, check the current branch policy before
  re-implementing it: a later owner decision may have superseded the story's original
  mechanism (per-SHA cancellation was replaced by per-branch queueing here).
- A workflow fix is not done while operator docs, skill instructions and inline
  comments still describe the old trigger or concurrency policy; stale prose has
  twice led a worker to reintroduce the defect.
- In a shared cloud worktree, verify `git status` and `git diff` immediately before
  handing a change to read-only reviewers; an external reset can make a real patch
  look absent and produce a false NO-GO.

## Why this is not finished

- GitHub exact-SHA verdict is unavailable to this worker (no `gh`, token or push).
  After the branch lands, confirm `Delivery gate` is reported on a fresh PR head and
  on the exact SHA; close task `01263000` only with that run evidence.
- The full `scripts/python-coverage.sh` gate exits 1 on six pre-existing
  `test_check_pack.py` failures (CI `opencode.json` profile under `skip-worktree` and
  the out-of-scope `run-workflow` skill's missing sections). This is the documented
  worker-image false positive, not a failure of the changed files; the integrator's
  tooling environment runs these gates.
- Two stale documentation files are outside this worker's scope; the exact proposed
  replacements are above and in `task-requests`.

## task-requests

```task-requests
[
  {
    "title": "Reconcile swarm efficiency baseline with the Delivery trigger policy",
    "details": "At docs/swarm/EFFICIENCY.md line 60 set the docs-only push row to '| Delivery per push to main (docs-only) | 7 | 7 | 0 |'. At line 76 replace item 6 with '| 6 | No Delivery path filter | 0 | An earlier path filter on push saved ~6 min per docs-only commit but left the required check unreported on affected SHAs. Every push and pull request now runs the full chain so Delivery gate is always reported. Waves still run only on tasks/ changes or manual dispatch. |'. Keep the rest of the file unchanged. Requirement OPS-06; evidence in .agents/notes/FIX-DELIVERY-GATE-EXPECTED-20261124-181920.md.",
    "scope": ["docs/swarm/EFFICIENCY.md"],
    "depends": ["FIX-DELIVERY-GATE-EXPECTED"]
  },
  {
    "title": "Correct stale Delivery path-filter claims in WEB-12 clause evidence",
    "details": "In docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md line 18 remove the claim that the push trigger has paths-ignore for docs/** and Markdown, and that docs-only pushes skip Delivery; state that push (main, v* tags) and pull_request have no path filter. In line 30 remove the matching 'Delivery ignores docs/** and **/*.md' claim. Refresh the cited workflow line numbers. Requirement OPS-06/WEB-12.",
    "scope": ["docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md"],
    "depends": ["FIX-DELIVERY-GATE-EXPECTED"]
  }
]
```
