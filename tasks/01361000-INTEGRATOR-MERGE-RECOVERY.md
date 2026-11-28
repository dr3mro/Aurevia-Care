- [x] INTEGRATOR-MERGE-RECOVERY · Recover an integrator batch from repeated base advances by bounded fetch/merge/re-gate cycles
  - attempts: 0
  - requirements: OPS-06
  - pr: #1840
  - scope: scripts/swarm.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_swarm_cli_coverage.py, scripts/tests/test_workflow_concurrency.py, scripts/tests/test_delivery_workflow_gates.py, .github/workflows/swarm-integrator.yml, tasks/01361000-INTEGRATOR-MERGE-RECOVERY.md
  - evidence:
    Skills loaded: aurevia-delivery (every story), aurevia-senior-python (scripts/swarm.py), aurevia-test-engineer (red tests), aurevia-github-workflow (swarm workflows), aurevia-plan-reviewer (plan gate), aurevia-code-reviewer / aurevia-coverage-auditor / aurevia-devsecops (post-code verdicts).
    Review verdicts (reviewer evidence):
    - plan-reviewer: GO (after scope widened to test_delivery_workflow_gates.py, PR #1837 prerequisite documented and MEMORY.md excluded)
    - code-reviewer: GO (no Critical or Required findings; cap coherent across CLI/payload/workflow)
    - coverage-auditor: GO (after adding bool/non-int/lower-bound tests and recovery_attempt emit assertions)
    - devsecops: GO (write token confined to trusted jobs; tokenless fetch/merge/resolve; allow-listed retry JSON; bounded chain)
    RED first (tool): `python3 -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py'` -> `FAILED (failures=2)` (attempt 1 returned `INTEGRATION_FAILED`; payload rejected attempt 1); CLI module -> `FAILED (failures=1, errors=1)` (`SystemExit: recovery attempt must be 0 or 1`); workflow module -> `FAILED (failures=3)` (`< 1`, `^[01]$`, missing `-gt 2`).
    GREEN (tool): targeted modules `test_swarm_cli_coverage.py` 79 tests OK, `test_swarm_integrator_coverage.py` 72 tests OK, `test_workflow_concurrency.py` 28 tests OK, `test_delivery_workflow_gates.py` 26 tests OK. Full suite `PATH=/opt/homebrew/bin:$PATH python3 -m unittest discover -s scripts/tests -q` -> `Ran 2529 tests in 198.013s`, `OK`. `COVERAGE_BASE=326d57d8fc16a222bef40e2b1b850ac18ee94218 REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`. `actionlint .github/workflows/swarm-integrator.yml` -> only the pre-existing `queue: max` warning. `python3 -I scripts/{check-constraints,check-docs,check-secrets,check-names}.py` -> rc 0 each; `git diff --check` rc 0.
    Coverage: payload attempt 0/1 valid and 2/-1/bool/non-int invalid -> `test_retry_payload_rejects_malformed_or_secret_bearing_values`; attempt 0 retry -> `test_non_tracker_base_advance_emits_only_a_bounded_retry_signal`; attempt 1 retry with signal -> `test_second_base_advance_emits_the_next_recovery_cycle`; attempt 2 terminal handoff -> `test_third_base_advance_at_the_cap_is_terminal`; CLI accepts 0/1/2 -> `test_main_accepts_every_bounded_recovery_attempt`, rejects 3 -> `test_main_caps_the_recovery_attempt`; workflow `< 2`, regex `^[012]$`, increment and `-gt 2` -> `test_recovery_job_is_signal_gated_and_holds_no_write_token`, `test_retry_pick_is_allow_listed_and_redispatch_carries_only_ids`; input descriptions -> `test_dispatch_inputs_and_publish_retry_outputs_are_declared`; delivery-gates cap -> `test_follow_up_jobs_require_successful_publication`.
    Entry point -> test trace: `swarm-integrator.yml` publish step -> `swarm.py main` CLI guard -> `integrate_publish` -> `integrate_finalize` -> `integration_retry_payload` -> workflow `resolve.if` (fetch/merge/resolve on a tokenless runner) -> re-dispatch guard -> fresh `pick`/`validate`/`seal`/`publish` on allow-listed PR ids.
    Publication (tool): worktree `.workspaces/integrator-merge-recovery`, branch `fix/integrator-merge-recovery`, commits `991d5a01` (scoped change) and `13a85dd0` (coverage-review hardening); PR #1840. First-head CI run 46141527998/46141527944/46141527947 had Delivery gate, tooling, Java/database, Frontend, Developer reference, Policy and Swarm gate all pass; exact-head CI on `13a85dd0` (run 46143075953 plus 46143075925/46143075944/46143075965) had Delivery gate, tooling, Java/database, Frontend, Developer reference, Policy and Swarm gate all pass. PR merged as `9e37ac61d2dcb4d7e092c250a0ce2b50082d967d` at 12:23:44Z. PR #1837 remains open and conflicting against the new cap tests; its integrator must keep this change's cap tests and update the publication-state assertions.
  - details:
    Observed 2026-11-28: Agent swarm run 46121407297 and Swarm integrator runs
    46121544399 and 46134384966 all failed in "Publish the validated batch"
    with `! [rejected] HEAD -> main (non-fast-forward)` followed by
    `integrator: base advanced again after the bounded recovery; batch was not merged`.
    In each window main advanced twice during the tokenless gate cycle, so the
    single allowed recovery (task 01344000, scripts/swarm.py:7064) was exhausted.
    Owner clarification 2026-11-28: fetch main, merge it into the candidate
    branches and fix conflicts on the tokenless runner, then re-gate and publish,
    instead of stopping after one advance. Implement that as three bounded
    publication attempts (0, 1, 2): each recoverable advance emits the existing
    allow-listed integration_retry JSON and is resolved by the existing tokenless
    fetch/merge/resolve job; only the third consecutive advance is terminal.
    Candidate code never runs in the write-token publish job; no force push, no
    weakened gate, no unbounded loop. Prerequisite: open PR #1837 carries the
    publication-state contract on the same workflow expressions; this change
    keeps that contract and only widens the numeric cap, so the integrator can
    resolve the textual overlap when both land. Do not edit MEMORY.md or task
    01344000 here: record the superseded one-retry policy in the notes for the
    daily reconciliation.
    Tests first: CLI bound rejects 3 and accepts 0/1/2; retry payload valid for
    attempts 0 and 1 and invalid for 2; integrate_finalize emits retry at 0/1 and
    performs the terminal handoff at 2; workflow gates and re-dispatch allow the
    second cycle and stop at the third. Every changed branch gets a named test.
    Gates: targeted Python suites, full scripts suite, python-coverage.sh,
    actionlint, shellcheck where applicable, plan/code/coverage/security reviews,
    scoped commit and exact-SHA CI. No threshold, skip or suppression weakened.
