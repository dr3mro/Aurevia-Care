- [-] FIX-DELIVERY-GATE-EXPECTED · Report the required Delivery gate check for every PR head
  - agent: main
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
