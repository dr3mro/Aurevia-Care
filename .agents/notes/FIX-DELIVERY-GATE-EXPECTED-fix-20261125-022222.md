## Summary

Fix pass for FIX-DELIVERY-GATE-EXPECTED. Reconciled this story's branch with
`origin/main`: the base now keys Delivery concurrency by PR head SHA
(`${{ github.event.pull_request.head.sha || github.ref }}`) with
`cancel-in-progress: false` and `queue: max`, so every PR head receives its own
exact-SHA `Delivery gate` verdict and no run waits behind a stale head.
Resolved the only two merge conflicts (`delivery.yml`, `test_workflow_concurrency.py`)
by taking the base's version, then aligned the workflow skill and testing guide
wording with that policy. No production application code changed.

## Affected requirements

OPS-06 (CI policy checks and exact-SHA verdicts). No clinical requirement touched;
synthetic data only.

## Risk class

Cross-boundary CI/documentation (docs/WORKFLOW.md): workflow gate contract and
operator instructions. Not safety-critical clinical behavior.

## Review owner

aurevia-github-workflow (workflow semantics), aurevia-devsecops (defensive gate),
aurevia-code-reviewer and aurevia-coverage-auditor (change and tests).

## Data changes

None. No migration, fixture or database change.

## Authorization boundaries and audit events

None. Workflow keeps `permissions: contents: read`, `persist-credentials: false`,
write scopes only in the tag-gated release job, and `NVD_API_KEY` only as an action
input. No token, credential or PHI-shaped string was added.

## Migration and rollout considerations

Workflow configuration and documentation only. The next Delivery run on any PR or push
uses the merged file; no migration, backfill or rollback step is needed.

## Test evidence

Commands run from `/__w/Aurevia-Care/Aurevia-Care` (base merged, no `gh`/push/tag):

- `git merge --no-edit origin/main` → conflicts in two files; resolved with
  `git checkout --theirs`, then `git add`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py'`
  → `Ran 8 tests in 0.256s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_delivery_gate.py'`
  → `Ran 20 tests in 0.010s`, `OK`.
- `git diff --check` → clean (no output).
- `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers.

Entry point to test trace: `.github/workflows/delivery.yml` trigger → `gate` job
(`Delivery gate`, `if: always()`, seven jobs in `needs`) →
`scripts/check-delivery-gate.py` fail-closed verdict →
`scripts/tests/test_workflow_concurrency.py` and
`scripts/tests/test_check_delivery_gate.py`.

## Coverage

No production Python, Java or frontend lines changed. The workflow file contains
configuration and comments only; the test module is excluded by design. No new
branch in production code was introduced, so no additional coverage entry is needed.

## Clinical and architecture review

Not applicable: no patient identity, result, order, prescription or release behavior
changed.

## Review verdicts

- aurevia-plan-reviewer: GO (merge resolution preserves the base policy; no new scope)
- aurevia-code-reviewer: GO (optional skill wording drift fixed in this pass)
- aurevia-coverage-auditor: GO (8 workflow tests + 20 gate tests pass; no production branches)
- aurevia-devsecops: GO (least-privilege permissions, fail-closed gate, no new secrets)

## Findings

- The merge brought the base's newer concurrency contract: per-PR-head SHA group plus
  serialized swarm plan/integrator groups. Two files conflicted and were resolved by
  taking the base version.
- Updated `.opencode/skills/aurevia-github-workflow/SKILL.md` line 13 and
  `docs/reference/testing-guide.md` line 76 so operator guidance matches the merged
  group expression.
- GitHub exact-SHA verdict is still unavailable to this worker (no `gh`, token or push);
  after the branch lands, confirm `Delivery gate` is reported on a fresh PR head and
  on the exact SHA before closing task `01263000`.
- Optional devsecops note (pre-existing): the gate runs `check-delivery-gate.py` from
  the PR checkout; defense-in-depth would use the trusted default-branch copy. Not
  introduced by this change; left to the integrator.

## Lessons for workers

- When `origin/main` moves a workflow policy forward, take the base's version of
  conflicted workflow/test files rather than re-applying stale local policy text.
- Keep skill and operator-guide wording in the same commit as the workflow change;
  stale prose has repeatedly led to reintroducing a filter or cancellation.
- Run the exact test files named by the base after a conflict resolution, not only
  the ones touched locally.

## Why this is not finished

- GitHub exact-SHA verdict is unavailable to this worker (no `gh`, token or push).
  After the branch lands, confirm `Delivery gate` is reported on a fresh PR head and
  on the exact SHA; close task `01263000` only with that run evidence.
- The optional devsecops defense-in-depth item above is pre-existing and out of this
  change's scope.
