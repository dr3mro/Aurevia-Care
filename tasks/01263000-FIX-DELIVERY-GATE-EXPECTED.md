- [-] FIX-DELIVERY-GATE-EXPECTED · Report the required Delivery gate check for every PR head
  - agent: swarm-6
  - attempts: 27
  - evidence: Red first: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` failed 3 tests (paths-ignore present, cancel-in-progress False). Green after fix: 5 tests OK. `python3 -I -m unittest discover -s scripts/tests -q`: Ran 1386 tests OK. `bash scripts/python-coverage.sh`: "python coverage: every changed script line is covered". `bash scripts/quick-check.sh`: "quick check: policy, compile and unit tests passed". `actionlint -ignore 'unexpected key \"queue\" ...'` exit 0; check-pack/check-docs/check-secrets/check-names/check-wiring/check-constraints/check-migrations/gen-opencode --check all exit 0. aurevia-github-workflow reviewer NO-GO findings (stale docs/comments, cancelled-run handling) closed in the same change. Committed c959c39b, merged into main as e33a2ae7, HEAD 69eafc47. GitHub CI: Delivery run 45289808826 (push, e33a2ae7) and 45289961489 (push, 69eafc47) both PENDING/queued at 23:38Z; REST rate limit resets 23:42Z. aurevia-github-workflow re-review: VERDICT GO (all three prior Required findings closed). No production application code changed.
  - requirements: OPS-06
  - scope: .github/workflows/delivery.yml, scripts/tests/test_workflow_concurrency.py, scripts/tests/test_swarm_delivery_state.py, scripts/swarm.py, docs/reference/testing-guide.md, docs/swarm/EFFICIENCY.md, .opencode/skills/aurevia-github-workflow/SKILL.md
  - details:
    Observed 2026-11-23 on dr3mro/Aurevia-Care. Ruleset PROTECT (id 27599666)
    requires exact context `Delivery gate` with strict status checks on main.
    Open PR heads had no `Delivery gate` check run, so GitHub showed
    "Expected — Waiting for status to be reported" and blocked merge.
    Tool evidence: PR #1489 head c7452d1 had only auto-merge, Validate fix PR
    and Hand off the fix PR check runs; Delivery run 45287492800 for c7452d1 was
    pending behind in_progress run 45287455388 for stale head f8557c1 in the
    per-ref concurrency group. Docs-only PRs #1488, #1486, #1476 and #1443
    matched pull_request paths-ignore and never started Delivery at all.
    Fix: bare pull_request trigger (no path filter) and PR-only
    cancel-in-progress so a new head is verified immediately. Main, tag,
    dispatch and merge-group pushes still queue and keep every exact-SHA
    verdict. Defender comments, tests and docs updated in the same change.

## Why this is not finished

    The fix is on main but the GitHub verdict is not yet available. At
    23:43Z Delivery runs 45289808826 (e33a2ae7) and 45289961489 (69eafc47)
    were still pending behind a pre-existing backlog: 37 of the last 60
    Delivery runs were non-terminal, with roughly 10 PR runs in_progress in
    separate per-ref concurrency groups. This is runner capacity, not a
    failing check. No PR run has yet used the new workflow: the newest PR
    Delivery run was created at 23:31:28Z, before the fix landed at 23:31:30Z.
    GitHub docs confirm pull_request runs the workflow from the PR merge
    commit, so open PRs inherit the fix from main on their next run.
    Next step: after the backlog clears, confirm both runs report success,
    confirm a newly created or rebased PR reports `Delivery gate` on its head,
    then change this entry to [x] with the run id.
