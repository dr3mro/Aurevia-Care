- [x] INTEGRATOR-BASE-ADVANCE-RECOVERY · Recover the integrator batch when main advances before publish
  - agent: orca
  - attempts: 0
  - pr: #1798
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_swarm_cli_coverage.py, scripts/tests/test_delivery_workflow_gates.py, scripts/tests/test_agent_run.py, .github/workflows/swarm-integrator.yml, scripts/tests/test_workflow_concurrency.py, tasks/01344000-INTEGRATOR-BASE-ADVANCE-RECOVERY.md
  - evidence:
    Test evidence (tool, Python 3.14 venv): targeted modules `test_swarm_integrator_coverage` 71 tests OK, `test_swarm_cli_coverage` 78 tests OK, `test_workflow_concurrency` 27 tests OK, `test_delivery_workflow_gates` 24 tests OK. `COVERAGE_BASE=9ac6892d REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`. Full suite `Ran 2443 tests ... OK`; `shellcheck scripts/agent-run.sh` exit 0; `actionlint .github/workflows/swarm-integrator.yml` reports only the repository's known pre-existing `queue: max` warning.
    Tested revision: 9ac6892d3d4e4cff7b578a6711b912c786dcc1d8 (HEAD when checks ran).
    Coverage: recoverable base-advance classification, malformed base, default success push, terminal repo-rule rejection, exhausted cap, allow-listed retry PR ids, workflow inputs/outputs/gating, CR-neutralisation and external triage paths each have a named test.
    Review verdicts (after evidence sections were filled):
    - code-reviewer: GO (after Critical double-dispatch and Required undeclared-input fixes)
    - coverage-auditor: GO
    - devsecops: GO
  - details:
    Worktree `.workspaces/integrator-race`, branch `fix/integrator-race`, base `9ac6892d`.
    Evidence: Agent swarm run 45938117633, job 140358967676, step "Push only the exact
    validated commit SHAs" failed at 06:54Z with `! [rejected] HEAD -> main (non-fast-forward)`
    and `Main advanced with non-tracker changes; retry this batch from the new base.`
    (scripts/swarm.py:6900). PR #1787 stayed open; no stale-gated code was merged.

    Owner decision: do not stop at fail-closed. On a push rejection caused by a base advance,
    fetch the new main, merge/rebase, resolve conflicts with OpenCode, re-gate on fresh main,
    and retry the publication, with a bounded attempt cap and a terminal human handoff.
    Reuse scripts/agent-run.sh `cmd_merge_main` and the tokenless resolve job pattern in
    .github/workflows/swarm-integrator.yml. Candidate code must never run in a job with a
    write token; pass only ids/SHAs through job outputs; no force push, no weakened gate.

    Fused design (three specialists GO; contradictions resolved explicitly):
    - Keep the publish step exit status 1 (fail closed). Both terminal failure and retry
      signal are negative sentinels; only the emitted allow-listed signal authorises recovery.
      Reject the architect's exit-2 proposal: it adds CLI churn without stronger safety.
    - scripts/swarm.py: add BaseAdvanced carrying reason/new_base/PR ids only; classify
      non-fast-forward caused by a non-tracker base advance as recoverable, keep repo-rule
      rejection and other push errors terminal; delete the speculative reset --soft and
      recommit block in the write-token job; restore task/note snapshots on all failure
      paths; in integrate_publish emit `integration_retry` as validated JSON containing
      only {attempt, new_base (40-hex), prs (ints)} and `merged 0`. Never emit stderr,
      titles, bodies or secrets. Cap at ONE recovery per run; a second advance goes to
      the terminal needs-human handoff with PR ids only.
    - .github/workflows/swarm-integrator.yml: add publish outputs for the retry signal;
      add a tokenless `recover` job gated on failure() plus the signal and recovery_attempt<1
      that reuses the existing `resolve` job body (fetch main/PRs with a read token, then
      OpenCode merge/conflict resolution with no token), uploading `resolved-branches`.
      Add a trusted `recover-publish` job that reuses the existing `resolve-publish` body to
      verify bundles/exact heads and push the resolved PR branches, then re-dispatches ONE
      bounded integrator retry with `recovery_attempt=1` in the dispatch inputs; the fresh
      run performs pick/validate/seal/publish on the new base with its unchanged gate.
      This reuse is preferred over rebuilding a sealed batch in the recovery job: sealing's
      Delivery evidence is bound to exact PR heads, so a new seal cannot pass without a new
      CI run, and duplicating its policy checks would risk publishing stale evidence.
      Reconcile stays on publish success; terminal failures stay skipped. No force push,
      no gate change, no candidate code in a write-token job.
    - Tests first in scripts/tests/test_swarm_integrator_coverage.py, test_swarm_cli_coverage.py,
      test_workflow_concurrency.py and test_agent_run.py: recoverable classification,
      terminal GH013, exhausted cap, tracker-only vs non-tracker advance, malformed signal,
      no secrets in emitted JSON, job permission/cap/signal contract, agent conflict cap.
      Red tests must fail before production code.
    Gates: targeted and full Python unittest suites, REQUIRE_PY_COVERAGE=1 coverage gate,
    actionlint, shellcheck scripts/agent-run.sh, policy/constraint checks, plan/code/coverage/
    security reviewer verdicts, scoped commit and push, Delivery on the exact SHA.

    Plan-reviewer Required corrections (all adopted):
    1. Scope also includes scripts/tests/test_swarm_cli_coverage.py and scripts/tests/test_agent_run.py.
    2. A GitHub job DAG cannot loop and concurrency state does not survive runs, so the cap is
       ONE recovery per run (attempt 0 -> 1), not three. A second base advance after the
       single re-dispatched retry is terminal with needs-human. The attempt is carried from
       the publish step output into the recovery jobs and forwarded as a workflow_dispatch
       input capped at 1; the fresh run re-validates on the new base. No unbounded loop.
    3. The publish step gets an `id`, writes validated JSON to $GITHUB_OUTPUT before exit 1,
       and recover requires `failure() && needs.publish.outputs.integration_retry != '' &&
       needs.publish.outputs.recovery_attempt < 1`. A failed step's outputs remain readable.
    4. No in-run publish-retry job: `reconcile`, `resolve` and `resolve-publish` keep their
       current publish-success gating. The retry is a new bounded workflow run whose own
       pick/validate/seal/publish jobs re-gate on the new base; terminal failures stay skipped.
    5. BaseAdvanced is raised at the detection point in integrate_finalize when non-tracker
       paths moved, not inferred from push stderr. Terminal repo-rule rejection stays first;
       a red test uses both a GH013 stderr and a non-fast-forward stderr to prove routing.
    6. Recovery reuses the existing tokenless `resolve` job (per-PR branches/bundles) and
       trusted `resolve-publish` job, which already pin and verify exact PR head SHAs before
       pushing. No new `recover-batch` profile and no re-fetch of mutable refs after pinning.
    7. Named red tests for: repo-rule rejection on the recovery run, second base advance at
       the cap, gate failure or unresolved conflict in recovery (terminal, never published),
       and task/note snapshot restoration on the recoverable exit-1 path.
    8. Declare workflow_dispatch inputs `recovery_attempt` (string, default "0") and
       `recovery_prs` (comma-separated integer ids, default "") in swarm-integrator.yml;
       GitHub rejects undeclared dispatch inputs. The re-dispatch passes both, never task text.
    9. The retry run's `pick` consumes the allow-listed `recovery_prs` instead of the whole
       queue. Red test proves the retry validates only the original batch; a second base
       advance with recovery_attempt=1 is terminal.
    10. Wire the signal explicitly: `recover` sets `CONFLICTED` from the JSON `prs` in the
       publish output and gates on `failure() && integration_retry != '' && recovery_attempt < 1`;
       re-dispatch happens only from `recover-publish` when that signal exists, so ordinary
       conflict resolution never spawns extra integrator runs.
