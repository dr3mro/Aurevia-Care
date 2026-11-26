- [-] FIX-DAILY-NOTES-RECONCILE · Reconcile must run from an out-of-workspace notes directory
  - requirements: OPS-06
  - scope: .github/workflows/daily-notes.yml, scripts/agent-run.sh, scripts/tests/test_agent_run.py, scripts/tests/test_swarm_monitor.py
  - agent: orca
  - details: Daily notes #12 failed in 48s. The reconcile job downloads `notes-in` into the checkout, so `cmd_reconcile`'s `require_clean_worktree` refuses to start before any agent runs; no `.agent/reconcile` output means publish then reports a confusing missing-artifact error. Move the reconcile download to `${{ runner.temp }}/notes-in` and pass it as `NOTES_IN` (default `notes-in` for local use). Keep the clean-worktree guard intact. Make publish fail with a clear `::error::` when reconcile did not succeed. Neutralise `##[`/`::` prefixes in untrusted tracker text printed by `scripts/taskstore.py view` in `agent-run.sh`. Add red/green tests for each case. Owner evidence: supplied run diagnosis and workflow log (authorized human); `gh`/Actions live rerun not available locally.
  - evidence:
    Classification: cross-boundary (workflow + shell + Python tests). Skills loaded by matched clause: aurevia-delivery (delivery loop), aurevia-github-workflow (.github/workflows change), aurevia-senior-shell (scripts/agent-run.sh), aurevia-senior-python (scripts/tests), aurevia-test-engineer (test-first regression design). No other skill matched at claim.
    Planning (reviewer evidence, read-only subagents, concurrent): senior-shell GO on the complete agent-run patch; github-workflow NO-GO on the incomplete proposal (missing reconcile_prompt path wiring, which is already in the owner's fix intent); senior-python GO on test design; plan-reviewer NO-GO with Required items now folded into the plan (helper ordering, fixture taskstore.py, explicit publish guard test, path alignment, branch-to-test map, protected-file delivery route). Task 01330000's own evidence records that it did not change scripts/agent-run.sh, so the historical scope overlap is resolved without editing that task.
    Durable decision: worktree `.workspaces/daily-notes-reconcile`, branch `fix/daily-notes-reconcile`, from HEAD bfd781bc7d2e8a37a9a1aa650526c626de8f0c27 (owner's local-Mac PR-workspace directive). Publishing via PR; protected files may need owner review, no auto-merge claim.
    Test evidence: pending
    Coverage: pending
    Review verdicts: pending
    Unverified locally: live Actions execution, container mount of ${{ runner.temp }}, and annotation rendering.
  - pr: pending
