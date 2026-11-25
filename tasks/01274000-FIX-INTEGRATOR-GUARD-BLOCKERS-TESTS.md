- [ ] FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS · Update integrator tests to the guard_blockers seam
  - attempts: 43
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_swarm_delivery_state.py, scripts/tests/test_delivery_workflow_gates.py, tasks/01274000-FIX-INTEGRATOR-GUARD-BLOCKERS-TESTS.md
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
  - evidence:
    Tool (red): full suite on pristine origin/main 17a9b1c8 reproduced 15
    failures/errors in `test_swarm.py`, `test_swarm_integrator_coverage.py`
    and `test_swarm_delivery_state.py`; the first traceback showed
    `guard_blockers()` calling real `gh pr diff` in a temp repo because tests
    still patched the old `guard` seam.
    Tool (red): after patching the seam, three production decisions remained
    broken and failed the existing tests: already-merged PRs were never
    closed (`close_integrated_pr` called 0 times), moved heads reported the
    generic `validation rejected this branch`, and an exhausted conflict was
    still queued for the resolver (`emit('conflicted', '[5]')`).
    Tool (green): `cd scripts/tests && python3 -m unittest -q ...` on the nine
    affected tests → `Ran 9 tests ... OK`; full
    `python3 -I -m unittest discover -s scripts/tests -q` →
    final merged tree `Ran 1579 tests in 245.816s / OK`.
    Tool: `bash scripts/python-coverage.sh` →
    `python coverage: every changed script line is covered`;
    `actionlint ... .github/workflows/*.yml` exit 0; `check-docs.py`,
    `check-constraints.py --base origin/main --require-base`,
    `check-secrets.py`, `check-names.py`, `gen-opencode.py --check` each exit 0.
    Changed production branches and their tests: exhausted conflict keeps the
    PR out of the resolver queue (`test_conflict_goes_back_to_the_workers_after_three_tries`);
    a conflict below the limit stays queued (`test_conflict_is_handed_to_the_resolver_not_failed`);
    moved head keeps its reason and simple failure
    (`test_a_branch_that_moved_after_planning_is_retested_without_the_debugger`);
    already-present PR prints the notice and is closed
    (`test_an_already_integrated_pr_is_closed_and_marked_done`, implemented
    upstream during the merge and kept once); wiring prune
    cases still gate the exact merge tip (seven subtests of
    `test_integrator_prunes_wiring_before_gating_the_merge_commit`).
    Evidence sources: executed tool commands and GitHub Actions API reads;
    no reviewer verdict, simulated walkthrough or human feedback in this entry.
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
