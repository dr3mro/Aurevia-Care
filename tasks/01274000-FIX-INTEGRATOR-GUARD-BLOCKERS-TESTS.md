- [-] FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS · Update integrator tests to the guard_blockers seam
  - agent: orca
  - requirements: OPS-06
  - scope: scripts/tests/test_swarm.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_swarm_delivery_state.py, tasks/01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS.md
  - depends: none
  - details:
    Owner said "fix all". Full Python suite on pristine origin/main at
    17a9b1c8 has 15 failures/errors (1571 tests) in integrator and conflict
    planning tests. Tool evidence: `guard_blockers()` was split out of
    `guard()` so the read-only validator can judge a candidate without a write
    token, but `integrate_prepare()` now calls `guard_blockers` while the
    affected tests still patch `swarm.guard`. Real `gh` calls then raise
    `CalledProcessError` or return a MagicMock into `json.loads`. One conflict
    test also asserts the old `emit("conflicted", ...)` timing. Keep the
    production split; update test fixtures to patch the new seam with the
    same decisions, preserve every assertion, and make the full suite green.
