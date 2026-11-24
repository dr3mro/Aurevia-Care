- [-] FIX-INTEGRATOR-TWO-TIER-DISPATCH · Wire the validate/publish CLI and publish every decision
  - agent: orca
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
