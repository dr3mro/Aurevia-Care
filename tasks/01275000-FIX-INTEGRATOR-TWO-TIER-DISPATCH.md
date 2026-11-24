- [-] FIX-INTEGRATOR-TWO-TIER-DISPATCH · Wire the validate/publish CLI and publish every decision
  - agent: swarm-1
  - attempts: 10
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_delivery_workflow_gates.py, .github/workflows/swarm-integrator.yml, tasks/01275000-FIX-INTEGRATOR-TWO-TIER-DISPATCH.md
  - depends: none
  - details:
    Owner said "fix all". Read-only reviewer evidence (devsecops, no tool
    execution) found that `integrate-validate` and `integrate-publish` are
    registered subparsers but absent from `main()` dispatch, so
    `swarm-integrator.yml` calls exit 0 without validating or publishing.
    The publish job also runs only when `accepted` or `conflicted` is non-empty,
    so `already`, `moved`, `rejected` and exhausted conflicts are never
    reported by the trusted publication job. Add failing tests for both CLI
    dispatch paths, for the validator's report output and for the workflow's
    publish condition; then wire the dispatch, emit the decision report and
    include it in the publish job condition. Keep the candidate job read-only.
  - evidence:
    Tool (red): four new tests failed before the fix — both CLI dispatch
    tests (`main()` exited without calling the handler), the validator report
    test (no `report` output) and the workflow publish test (no `report`
    output or publish condition).
    Tool (green): same four tests → `Ran 4 tests ... OK` after wiring
    `integrate-validate` / `integrate-publish` dispatch, emitting `report`
    with rejected + already + conflict_details, and adding the publish
    condition. Full `python3 -I -m unittest discover -s scripts/tests -q`
    → `Ran 1579 tests in 245.816s / OK`.
    Tool: `bash scripts/python-coverage.sh` →
    `python coverage: every changed script line is covered`; actionlint on all
    workflows and check-docs/check-constraints/check-secrets/check-names/
    gen-opencode --check each exit 0.
    Evidence sources: executed tool commands and read-only reviewer reports;
    no simulated walkthrough or human participant.
  - reviews: aurevia-github-workflow GO, aurevia-code-reviewer GO,
    aurevia-coverage-auditor GO, aurevia-devsecops GO (read-only reviewers,
    no Critical or Required finding remained at the final re-check).
  - ci-status: PR 1613 merged to main as 098354af. Tool: Delivery run
    45337601399 on head 190cf7dc had Policy, tooling, frontend and canonical
    frontend gates green; Java verification failed only on the upstream test
    compile error now fixed on main. Tool: Delivery run 45339545141 for the
    later main SHA 5a86e60f remained `pending` with zero jobs for over 40
    minutes behind 30+ queued main runs and one in_progress run on another
    branch (45342528638); the exact-SHA verdict is unavailable from this
    environment, not a failure of these changes. No check was bypassed.
