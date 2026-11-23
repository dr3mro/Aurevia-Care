# Engineering delivery workflow

## Route reviews by change risk

| Class | Typical change | Review and verification |
|---|---|---|
| Routine | Contained UI fix, documentation, local refactor | One implementation specialist and one targeted reviewer. Reuse existing acceptance evidence where unaffected. |
| Cross-boundary | API, database, integration, shared client or deployment change | Named domain reviewer for each affected boundary. Include contract and integration checks and record migration or rollback impact. |
| Safety-critical | Patient identity, permission, release, prescribing, tenant policy or critical results | Clinical and defensive security reviews plus independent QA. Require negative and failure tests and the specification's sign-offs for live enablement. |

Every swarm story starts with a briefing: `scripts/route-task.py` picks the specialists, skills and starting paths from the task scope, and the read-only `smartclinic-task-advisor` (the senior developer) turns that into one consolidated brief for the worker (goal, plan, specialists per stage, skills to load, files to read first, tests first, risks, done criteria). The worker loads only what the brief names instead of every file; if the advisor fails the plain routing block is used, and `BRIEF=off` skips the advisor run. Every swarm story also passes a quality loop: the plan reviewer (`smartclinic-plan-reviewer`) before coding, then the code reviewer, coverage auditor and security reviewer after it. Each ends its answer with `VERDICT: GO` or `VERDICT: NO-GO`, findings are marked Critical, Required or Optional, and a reviewer that cannot answer GO blocks the task. `scripts/quality-gate.sh` enforces this with `scripts/check-verdicts.py` and rejects added suppressions and weakened checks with `scripts/check-constraints.py`; set `SWARM_REVIEW_GATE=off` to skip only the verdict part.

Branching, refresh-before-check, conflict resolution and merge bookkeeping follow [the git workflow and conflict policy](reference/git-workflow.md). Removing a route, flag, field or module follows [the deprecation and removal checklist](reference/deprecation-checklist.md).

The orchestrator assigns the class once per story and sends reviewers only IDs, a small diff or paths, and relevant evidence. Run independent reviews concurrently when the environment supports it. Make reviewers focus on distinct risks. Escalate only findings with impact, evidence and a suggested fix. Reduce review churn by resolving repeated findings in a reusable test or check.

## Keep the loop recoverable

1. Frame one user outcome and one acceptance matrix. Include expected failures and state after retry where relevant.
2. Identify the smallest release-ready slice. Record risks and dependencies and verify tools first. Implement test-first and use targeted checks in the inner loop.
3. Refactor while green. Trace a request, event or user action from entry point to assertion. Document contracts and update the developer reference as part of the same change.
4. Run the full required checks before the scoped commit. Reuse the SHA-linked CI and documentation artifacts as evidence. Record the next action, link, unresolved risks and actual results. Do not paste logs into memory.
5. Fix blockers or move them to a separate story with an owner and resume trigger. Feed observed clinician and unit feedback into the backlog. Distinguish simulated review from real user feedback.

After five stories and after meaningful pipeline changes, review time to green CI, review time, flaky test reruns, and defects caught. Use the evidence to remove duplicate checks or redundant handoffs while retaining boundary verification and release gates.
