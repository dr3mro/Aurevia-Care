- [-] FIX-DELIVERY-GATE-EXPECTED · Report the required Delivery gate check for every PR head
  - agent: main
  - evidence: Red first: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` failed 3 tests (paths-ignore present, cancel-in-progress False). Green after fix: 5 tests OK. `python3 -I -m unittest discover -s scripts/tests -q`: Ran 1386 tests OK. `bash scripts/python-coverage.sh`: "python coverage: every changed script line is covered". `bash scripts/quick-check.sh`: "quick check: policy, compile and unit tests passed". `actionlint -ignore 'unexpected key \"queue\" ...'` exit 0; check-pack/check-docs/check-secrets/check-names/check-wiring/check-constraints/check-migrations/gen-opencode --check all exit 0. smartclinic-github-workflow reviewer NO-GO findings (stale docs/comments, cancelled-run handling) closed in the same change. Committed c959c39b, merged into main as e33a2ae7, HEAD 69eafc47. GitHub CI verdict for these SHAs still queued at 23:35Z; REST rate limit resets 23:42Z. No production application code changed.
  - pr: none (direct scoped commit to main per WORKSPACE.md)
  - requirements: OPS-06
  - scope: .github/workflows/delivery.yml, scripts/tests/test_workflow_concurrency.py, scripts/tests/test_swarm_delivery_state.py, scripts/swarm.py, docs/reference/testing-guide.md, docs/swarm/EFFICIENCY.md, .opencode/skills/smartclinic-github-workflow/SKILL.md
  - attempts: 0
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
