# Engineering delivery workflow

## Route reviews by change risk

| Class | Typical change | Review and verification |
|---|---|---|
| Routine | Contained UI fix, documentation, local refactor | One implementation specialist and one targeted reviewer. Reuse existing acceptance evidence where unaffected. |
| Cross-boundary | API, database, integration, shared client or deployment change | Named domain reviewer for each affected boundary. Include contract and integration checks and record migration or rollback impact. |
| Safety-critical | Patient identity, permission, release, prescribing, tenant policy or critical results | Clinical and defensive security reviews plus independent QA. Require negative and failure tests and the specification's sign-offs for live enablement. |

The orchestrator assigns the class once per story and sends reviewers only IDs, a small diff or paths, and relevant evidence. Run independent reviews concurrently when the environment supports it. Make reviewers focus on distinct risks. Escalate only findings with impact, evidence and a suggested fix. Reduce review churn by resolving repeated findings in a reusable test or check.

## Keep the loop recoverable

1. Frame one user outcome and one acceptance matrix. Include expected failures and state after retry where relevant.
2. Identify the smallest release-ready slice. Record risks and dependencies and verify tools first. Implement test-first and use targeted checks in the inner loop.
3. Refactor while green. Trace a request, event or user action from entry point to assertion. Document contracts and update the developer reference as part of the same change.
4. Run the full required checks before the scoped commit. Reuse the SHA-linked CI and documentation artifacts as evidence. Record the next action, link, unresolved risks and actual results. Do not paste logs into memory.
5. Fix blockers or move them to a separate story with an owner and resume trigger. Feed observed clinician and unit feedback into the backlog. Distinguish simulated review from real user feedback.

After five stories and after meaningful pipeline changes, review time to green CI, review time, flaky test reruns, and defects caught. Use the evidence to remove duplicate checks or redundant handoffs while retaining boundary verification and release gates.


## Swarm frontend handoff

Check each story against the repository gates before coding. If its acceptance
criteria conflict with a gate, record the conflict and wait for a corrected task.
For a frontend change, write a failing behavior test, make it pass, and record
both results. Check the result with `bash scripts/frontend-check.sh` and the
affected app's check before declaring the work ready.

The worker runs the same frontend command as Delivery. A failed worker result
opens a draft. The integrator skips drafts and waits for successful Delivery on
the current head of ordinary PRs. Documentation-only PRs use the merge-result
gate, and main-repair PRs use their dedicated validation tier.


## Senior engineering standard

- Confirm acceptance criteria, user-facing entry points, and allowed source and test paths before editing. Trace the current behavior and reuse domain rules already owned elsewhere.
- Demonstrate the defect with a failing behavior test, then verify the smallest fix. Test the relevant failure and retry paths. Review the final diff for unrelated changes, unhandled failures, and compatibility.
- Run the repository's canonical checks on the final code. Record the failing test and why it failed, the passing test, coverage results, and any pending checks. For dependency changes, record the scan result and review of the locked dependency.
- Review changes against the agreed risk class. Name the file, observable impact, and regression test for each finding. Record who reviewed the work and what evidence they verified. Keep raw logs in run artifacts.
