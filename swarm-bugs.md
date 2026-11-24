# Aurevia-Care — Comprehensive Workflow Audit, Debugging, and Repair

## 1. Mission

Act as a coordinated engineering team consisting of:

- Senior software architect.
- GitHub Actions and workflow specialist.
- DevSecOps engineer.
- Senior Python and Bash developers.
- Git and repository integrity specialist.
- Distributed systems and concurrency specialist.
- QA and regression testing engineer.
- AI model routing specialist.
- Scrum master and technical documentation specialist.

Perform an exhaustive, source-level audit of Aurevia-Care's workflow infrastructure. Investigate, reproduce, repair, and regression-test all 35 findings described below, together with any additional defects discovered during execution.

Repository: `dr3mro/Aurevia-Care`

The objective is to produce a reliable, secure, maintainable, autonomous development workflow that requires minimal human intervention without compromising repository integrity or product quality.

## 2. Global scope

Inspect the entire repository, with particular attention to:

- `.github/workflows/`
- `.github/actions/`
- `.github/scripts/`, if present.
- `scripts/`
- `scripts/tests/`
- Agent definitions and skills.
- OpenCode configuration and instructions.
- Swarm orchestration and model routing.
- Task planning, claiming, execution, and completion.
- Watchdog and debugger workflows.
- Repair and integration workflows.
- PR lifecycle and auto-merge automation.
- Delivery gate and merge queue.
- Git operations, hooks, worktrees, and temporary repositories.
- Repository rulesets and branch protection.
- Test infrastructure and fixtures.
- Dependency caching and artifact management.
- Persistent memory and progress tracking.

Discover actual file locations before making changes. Do not assume filenames or architecture that are not present.

Trace execution across workflow YAML, reusable workflows, composite actions, shell scripts, Python modules, agent instructions, GitHub API calls, and external command invocations.

## 3. Non-negotiable safety rules

1. Never push directly to `main`.
2. Never bypass branch protection or repository rulesets.
3. Never use administrative merge overrides.
4. Never weaken the Delivery gate or existing quality requirements.
5. Never reduce coverage thresholds or suppress legitimate failures.
6. Never execute destructive Git operations against the user's working repository.
7. Preserve all staged, unstaged, untracked, and ignored user files.
8. Preserve existing Git stashes, branches, configuration, index flags, and worktrees.
9. Run potentially destructive tests exclusively in disposable repositories.
10. Do not execute the existing full test suite until Git isolation has been audited.
11. Never expose credentials, tokens, secrets, or sensitive environment variables.
12. Use only verified zero-cost AI models.
13. Make small, atomic, independently reviewable commits.
14. Do not introduce unnecessary dependencies or architectural complexity.
15. Do not modify unrelated application functionality.
16. Do not cancel active workflows or modify repository settings without explicit authorization.
17. Do not introduce speculative fixes for unconfirmed defects.
18. Do not mark a task complete based solely on an unrelated successful workflow.

## 4. Finding classification and tracking

The findings below include previously reported defects and suspected weaknesses. Treat every finding as requiring independent verification.

Maintain `bugs.md` with these status markers:

- `[ ]` Pending.
- `[-]` Investigation or implementation in progress.
- `[x]` Fixed and independently verified.
- `[N/A]` Investigated and disproven.
- `[!]` Blocked by an external dependency or human decision.

For each finding, record:

- Finding ID and severity.
- Confirmation status.
- Affected files and line numbers.
- Affected workflows and execution paths.
- Root cause.
- Reproduction procedure.
- Proposed and implemented fix.
- Regression tests.
- Verification results.
- Commit and PR references.
- Confidence level.
- Remaining risks.

# 5. Detailed findings, investigation scopes, and required fixes

## Phase 1 — Git safety and repository integrity

Complete this phase before executing potentially destructive tests.

### [ ] WF-08 — Local repository unexpectedly resets

**Severity:** Critical

**Scope:**

- All Python and Bash tests.
- `test_check_pack.py`.
- Git test fixtures and setup/teardown methods.
- Build scripts.
- Swarm workers and integrators.
- Cleanup routines.
- Git hooks.
- Agent instructions.
- Repository synchronization scripts.

**Required work:**

1. Inspect the local Git reflog, when accessible, to identify unexpected resets, checkouts, rebases, and HEAD movements.
2. Search for destructive Git operations, including commands constructed dynamically or passed as subprocess argument arrays.
3. Investigate:
   - `git reset --hard`
   - `git reset --mixed`
   - `git restore`
   - `git checkout --`
   - `git clean`
   - `git read-tree`
   - `git update-index`
   - `git stash`
   - Forced branch updates.
   - Worktree removal.
4. Trace every operation to its effective working directory and Git environment.
5. Inspect Python fixtures, `setUp`, `tearDown`, context managers, exception handlers, and shell traps.
6. Identify whether any test accidentally modifies the actual repository.
7. Move all destructive test operations into disposable repositories created specifically for each test.
8. Introduce shared, safe Git test fixtures where appropriate.
9. Ensure cleanup can remove only resources created and owned by that test.
10. Document the exact cause of the unexpected reset if sufficient evidence exists.

**Acceptance criteria:**

- Tests cannot modify the user's repository.
- Simulated staged, unstaged, ignored, and untracked changes survive test execution.
- HEAD, branch, index flags, and stashes remain unchanged.
- Tests pass when invoked from different working directories.
- The original reset defect is reproduced and prevented, or the inability to reproduce it is documented.

### [ ] WF-09 — Git operations target incorrect directories

**Severity:** Critical

**Scope:**

- Git command wrappers.
- Python `subprocess.run` and `Popen`.
- Shell `cd` operations.
- Worktree management.
- Cleanup scripts.
- Worker and integration scripts.

**Required work:**

1. Inventory all Git mutation call sites.
2. Identify commands relying on the caller's current working directory.
3. Require explicit repository paths for mutation operations.
4. Prefer `git -C "$repo_path"` or explicit subprocess `cwd`.
5. Resolve canonical paths and validate repository identity.
6. Reject empty, invalid, unexpected, or unauthorized paths.
7. Verify the intended branch and worktree before mutation.
8. Prevent cleanup from targeting the primary checkout.
9. Protect against symlink and path traversal mistakes.
10. Consolidate duplicated safety checks into reusable helpers where practical.

**Acceptance criteria:**

- Incorrect paths fail safely before mutation.
- Execution from an unexpected directory cannot modify another repository.
- Cleanup cannot delete the primary checkout.
- Path validation has positive and negative regression tests.

### [-] WF-25 — Unsafe inherited Git environment variables

**Severity:** High

**Scope:**

- Test fixtures.
- Child processes.
- Temporary repositories.
- Git hooks.
- Worker subprocess environments.

**Required work:**

1. Inspect inherited environment handling.
2. Identify repository-specific Git variables, including:
   - `GIT_DIR`
   - `GIT_WORK_TREE`
   - `GIT_INDEX_FILE`
   - `GIT_COMMON_DIR`
   - `GIT_OBJECT_DIRECTORY`
   - `GIT_ALTERNATE_OBJECT_DIRECTORIES`
   - `GIT_NAMESPACE`
3. Use Git's local-environment variable inventory where appropriate.
4. Remove repository-specific variables from isolated test subprocesses.
5. Preserve necessary authentication and legitimate runtime configuration.
6. Avoid modifying the parent process environment unnecessarily.
7. Add tests using deliberately contaminated environments.

**Acceptance criteria:**

- Temporary repository operations cannot be redirected to the primary repository through inherited environment variables.
- Parent environment remains unchanged.
- Legitimate Git operations continue to function.

### [ ] WF-05 — Python tests fail or modify Git index state

**Severity:** High

**Scope:**

- `scripts/tests/`
- `test_check_pack.py`
- Test discovery and execution scripts.
- Git index fixtures.
- `skip-worktree` and `assume-unchanged` behavior.

**Required work:**

1. Reproduce the six previously reported failures in an isolated checkout.
2. Identify whether failures depend on repository state, execution order, operating system, or inherited environment.
3. Inspect tests that manipulate Git index flags.
4. Remove dependencies on the developer's actual repository state.
5. Ensure tests create their own commits, files, branches, and index conditions.
6. Verify deterministic setup and cleanup.
7. Test individual execution, full-suite execution, repeated execution, and randomized ordering where supported.
8. Fix production logic if the tests reveal a legitimate application defect.

**Acceptance criteria:**

- All affected tests pass independently and as part of the complete suite.
- Tests do not alter the primary repository.
- Repeated execution produces consistent results.
- No legitimate failing test is disabled.

### [ ] WF-10 — Branch protection bypass

**Severity:** Critical

**Scope:**

- Merge scripts.
- Integration workflows.
- Worker Git commands.
- GitHub CLI and API calls.
- Repository rulesets.
- Agent instructions.
- Token permissions.

**Required work:**

1. Search for direct pushes to `main`, force pushes, administrative merges, and bypass options.
2. Inspect previous merge and integration failure paths.
3. Identify any token or actor capable of bypassing required checks.
4. Remove automated bypass behavior.
5. Require changes to enter through protected pull requests.
6. Ensure merge automation uses GitHub-supported auto-merge or merge queue mechanisms.
7. Verify repository rulesets through read-only API queries when authorized.
8. Report any repository configuration changes requiring owner approval.

**Acceptance criteria:**

- Automated code cannot bypass the Delivery gate.
- No direct pushes to `main` occur.
- Failed or incomplete PRs remain unmerged.
- Unauthorized merge attempts fail safely.

## Phase 2 — Delivery gate and pull request lifecycle

### [ ] WF-11 — Delivery gate incorrectly reports success

**Severity:** Critical

**Scope:**

- Delivery workflow.
- Required status checks.
- Job dependency graph.
- Conditional execution.
- Reusable workflows.
- Branch protection.
- Merge queue events.

**Required work:**

1. Inspect the current Delivery gate and its complete dependency graph.
2. Verify existing required jobs, including `policy`, `script-tests`, `frontend`, `java-verify`, `docs`, `verify`, and `release`, where applicable.
3. Inspect `needs`, `if`, `always()`, `continue-on-error`, and skipped-job behavior.
4. Ensure the final gate evaluates every required dependency explicitly.
5. Distinguish legitimately inapplicable jobs from unexpectedly skipped jobs.
6. Fail closed when a required result is missing, failed, or cancelled.
7. Preserve the canonical required check name `Delivery gate`.
8. Verify the gate evaluates the correct commit SHA.
9. Test push, pull request, and merge queue execution paths.

**Acceptance criteria:**

- All required checks pass: gate passes.
- Any required check fails: gate fails.
- Any required check is cancelled: gate does not pass.
- Unexpectedly skipped or missing checks: gate fails.
- Legitimately inapplicable checks follow documented policy.
- The merge queue receives the required status check.

### [ ] WF-01 — Fix PR gate handoff failure

**Severity:** High

**Scope:**

- Fix PR gate workflow.
- Handoff job.
- Debugger workflow.
- Repair PR creation.
- Integration workflow.
- GitHub token permissions.

**Required work:**

1. Retrieve the failed workflow logs, including the previously reported handoff failure if still available.
2. Identify the exact failing command and exit status.
3. Trace the handoff from repair completion to PR readiness and merge eligibility.
4. Inspect event payloads, PR numbers, branch references, commit SHAs, and job outputs.
5. Verify authentication and permissions.
6. Check for missing or malformed outputs between jobs.
7. Make handoff operations idempotent.
8. Prevent duplicate handoffs and unnecessary repair cycles.
9. Provide actionable diagnostics when handoff fails.

**Acceptance criteria:**

- Successful repair PRs transition correctly into normal validation.
- Repeated handoff events do not create duplicate actions.
- Failed validation cannot trigger an unauthorized merge.
- Handoff failures identify the exact blocking condition.

### [ ] WF-15 — Passing PRs remain drafts

**Severity:** High

**Scope:**

- Worker PR creation.
- Swarm PR flow.
- Fix PR gate.
- PR readiness automation.
- Draft labels and task state.

**Required work:**

1. Trace all code paths creating draft PRs.
2. Identify the conditions required to mark a PR ready.
3. Determine whether readiness depends on checks that cannot run while a PR remains a draft.
4. Eliminate circular readiness dependencies.
5. Promote eligible PRs after required preflight validation.
6. Preserve draft status for incomplete, conflicting, or explicitly blocked work.
7. Reconcile existing eligible draft PRs through the normal workflow.
8. Record reasons when a PR remains a draft.

**Acceptance criteria:**

- Eligible PRs become ready automatically.
- Incomplete or invalid PRs remain blocked.
- Readiness transitions are idempotent.
- No circular dependency exists between draft status and validation.

### [ ] WF-16 — Auto-merge fails silently

**Severity:** High

**Scope:**

- Swarm PR flow.
- Integration scripts.
- Auto-merge labels.
- GitHub CLI merge commands.
- Repository merge settings.

**Required work:**

1. Inspect every auto-merge invocation.
2. Validate PR state, draft status, conflicts, merge eligibility, and repository configuration.
3. Distinguish enabling auto-merge from an actual completed merge.
4. Handle pending checks without incorrectly reporting failure.
5. Report permission failures, conflicts, and disabled repository settings.
6. Retry transient API errors with bounded backoff.
7. Reconcile PR state after requesting auto-merge.
8. Preserve GitHub's required-check enforcement.

**Acceptance criteria:**

- Eligible PRs enter auto-merge or the merge queue.
- Ineligible PRs receive explicit blocker diagnostics.
- Auto-merge requests are idempotent.
- No PR is reported merged before GitHub confirms the merge.

### [ ] WF-17 — Merge queue incompatibility

**Severity:** High

**Scope:**

- Delivery workflow triggers.
- `merge_group` events.
- Required checks.
- Checkout references.
- Integration and merge scripts.
- Repository merge queue configuration.

**Required work:**

1. Verify whether merge queue is enabled or required.
2. Inspect workflow support for `merge_group`.
3. Ensure required checks run against the merge-group commit rather than a stale PR head.
4. Remove assumptions that every event contains a pull request object.
5. Validate event-specific branch and SHA resolution.
6. Ensure merge automation uses the repository's configured merge mechanism.
7. Test failed and successful merge-group validation.
8. Document any settings requiring repository-owner approval.

**Acceptance criteria:**

- Merge-group commits receive the Delivery gate.
- Required checks use the correct SHA.
- Failed merge-group checks prevent merging.
- Normal PR validation remains functional.

### [ ] WF-18 — Tasks marked complete before merge

**Severity:** High

**Scope:**

- Task status files.
- Worker completion logic.
- Integrator.
- PR flow.
- Progress tracking.
- Merge event handlers.

**Required work:**

1. Inventory every task status transition.
2. Identify code marking tasks complete after local tests, commits, or PR creation.
3. Define an explicit lifecycle separating implementation, validation, review, merge, and completion.
4. Preserve existing task marker conventions.
5. Require authoritative GitHub merge confirmation before final completion.
6. Handle closed-unmerged PRs, failed merges, reopened PRs, and abandoned branches.
7. Make completion reconciliation idempotent.

**Acceptance criteria:**

- Open or unmerged PRs cannot produce completed tasks.
- Confirmed merged tasks become complete.
- Closed-unmerged work is recoverable.
- Repeated events do not corrupt task state.

### [ ] WF-19 — Worker branches become outdated

**Severity:** High

**Scope:**

- Branch creation.
- Worker checkout.
- Rebase and synchronization scripts.
- Integration workflows.
- Merge queue.

**Required work:**

1. Identify when workers fetch and synchronize with `main`.
2. Detect stale base commits before starting or integrating work.
3. Preserve worker changes during synchronization.
4. Use merge queue capabilities where appropriate instead of unnecessary rebases.
5. Restrict rebasing and any force-with-lease operations to authorized worker-owned branches.
6. Never rewrite shared branches or the user's checkout.
7. Classify merge conflicts as explicit blockers.
8. Prevent infinite synchronization loops.

**Acceptance criteria:**

- Worker branches use an appropriate base commit.
- Stale branches are safely updated or queued.
- Conflicts are reported without losing changes.
- Shared branches are never rewritten.

## Phase 3 — Failure detection and automated recovery

### [ ] WF-02 — False recovery detection

**Severity:** High

**Scope:**

- Swarm watchdog.
- Debugger.
- Failure issue management.
- Integration workflow.
- Recovery verification.

**Required work:**

1. Identify how failures are associated with subsequent successful runs.
2. Determine whether empty queues, skipped jobs, or unrelated successful executions close failure issues.
3. Introduce stable failure identifiers containing the affected workflow, operation, failure category, and relevant context.
4. Require evidence that the originally failing operation executed successfully.
5. Distinguish `RECOVERED`, `NOT_RETESTED`, `STILL_FAILING`, and `BLOCKED`.
6. Prevent unrelated workflow success from resolving failures.
7. Record the verification run and operation.

**Acceptance criteria:**

- Empty-queue runs do not resolve failures.
- Skipped operations remain unverified.
- Successful reproduction of the affected operation resolves the appropriate failure.
- Recovery evidence is traceable.

### [ ] WF-03 — Incorrect failure classification

**Severity:** High

**Scope:**

- Watchdog.
- Debugger.
- Failure classifier.
- Repair task generation.
- Issue creation.
- Retry logic.

**Required work:**

1. Inventory current failure categories and classification rules.
2. Introduce or improve classification for:
   - Application defects.
   - Test failures.
   - Workflow configuration defects.
   - Authentication failures.
   - Authorization failures.
   - Network failures.
   - Rate limits.
   - Provider outages.
   - Dependency failures.
   - Resource exhaustion.
   - Unknown failures.
3. Associate each category with an appropriate recovery policy.
4. Route transient infrastructure failures to bounded retries.
5. Route permanent credential failures to human escalation.
6. Route confirmed repository defects to repair workers.
7. Treat ambiguous failures conservatively.

**Acceptance criteria:**

- Invalid credentials do not automatically generate speculative code repairs.
- Confirmed software defects reach repair workers.
- Transient failures receive bounded retries.
- Unknown failures retain evidence for investigation.

### [ ] WF-04 — Git authentication interrupts integration

**Severity:** High

**Scope:**

- GitHub CLI authentication.
- Git remote authentication.
- Integration workflow.
- Token provisioning.
- API calls.

**Required work:**

1. Identify authentication requirements for every integration operation.
2. Verify token presence without printing its value.
3. Add appropriate authentication and authorization preflight checks.
4. Distinguish expired tokens, missing permissions, network errors, and rate limits.
5. Ensure authentication failures stop affected operations safely.
6. Retry only transient failures.
7. Preserve task and PR state during authentication outages.
8. Provide actionable remediation instructions.

**Acceptance criteria:**

- Missing or invalid credentials fail before destructive or partial integration.
- Permanent failures do not trigger infinite retries.
- Secrets never appear in logs.
- Integration resumes safely after credentials are restored.

### [ ] WF-06 — Unnecessary repair PRs

**Severity:** Medium

**Scope:**

- Debugger.
- Repair workers.
- Repair PR generation.
- Failure issue updates.
- Integration handoff.

**Required work:**

1. Identify why repair workers create PRs when no code change is necessary.
2. Detect meaningful source changes before creating a repair PR.
3. Introduce an explicit `NO_CODE_CHANGE_REQUIRED` outcome.
4. Record investigation evidence on the originating issue or execution report.
5. Distinguish infrastructure recovery from repository repair.
6. Prevent notes-only PRs unless documentation changes are independently required.
7. Prevent no-change outcomes from entering the normal repair PR lifecycle.

**Acceptance criteria:**

- No unnecessary PR is created for a no-change outcome.
- Investigation evidence remains available.
- Genuine code repairs continue through normal validation.
- No-change outcomes do not trigger recursive repair workflows.

### [ ] WF-07 — Poor failure-log extraction

**Severity:** Medium

**Scope:**

- Workflow log retrieval.
- Failure summarization.
- Debugger input.
- Issue generation.
- Agent task briefs.

**Required work:**

1. Inspect current log extraction and truncation logic.
2. Identify why unrelated startup messages and successful commands dominate failure reports.
3. Extract failed job and step names, exit codes, exceptions, assertions, and relevant surrounding context.
4. Preserve useful stack traces.
5. Apply deterministic size limits.
6. Redact credentials and sensitive information.
7. Preserve full logs as restricted artifacts where appropriate.
8. Include source run, job, and step references.

**Acceptance criteria:**

- Failure summaries identify the actual failing operation.
- Relevant stack traces are retained.
- Unrelated logs do not overwhelm the report.
- Sensitive values are redacted.

### [ ] WF-24 — Unlimited repair cycles

**Severity:** High

**Scope:**

- Watchdog.
- Debugger.
- Repair workers.
- Fix PR gate.
- Failure issue tracking.
- Retry counters.

**Required work:**

1. Trace the complete failure-to-repair-to-validation cycle.
2. Identify paths allowing failed repairs to generate unlimited subsequent repairs.
3. Introduce configurable retry and repair-attempt limits.
4. Track attempts using persistent failure fingerprints.
5. Distinguish transient retries from new repair attempts.
6. Detect repeated failures with unchanged root causes.
7. Escalate exhausted attempts instead of creating additional PRs.
8. Reset counters only after verified recovery or a materially new failure.

**Acceptance criteria:**

- Repeated identical failures cannot create unlimited repair PRs.
- Retry limits survive workflow restarts.
- Exhausted failures produce actionable escalation.
- Genuine new failures remain independently recoverable.

## Phase 4 — Concurrency, scheduling, and task orchestration

### [ ] WF-12 — Recursive watchdog triggering

**Severity:** High

**Scope:**

- All workflow triggers.
- `workflow_run` events.
- `workflow_dispatch` events.
- Watchdog.
- Debugger.
- PR flow.
- Repair workflows.

**Required work:**

1. Construct the complete workflow event dependency graph.
2. Identify direct and indirect trigger cycles.
3. Distinguish legitimate event chains from redundant execution.
4. Restrict watchdog triggers to relevant workflows and conclusions.
5. Exclude self-generated or irrelevant events where appropriate.
6. Introduce idempotency and correlation identifiers.
7. Prevent duplicate processing of the same event.
8. Verify that necessary failure recovery still occurs.

**Acceptance criteria:**

- No unbounded workflow trigger cycle exists.
- Duplicate events do not create duplicate recovery work.
- Successful unrelated workflows do not trigger unnecessary repairs.
- Legitimate failures still reach the watchdog.

### [ ] WF-13 — Duplicate task assignments

**Severity:** High

**Scope:**

- Task scheduler.
- Swarm dispatcher.
- Worker matrix generation.
- Task claiming.
- Task files and persistent state.

**Required work:**

1. Inspect how pending tasks are selected and claimed.
2. Identify read-modify-write races between concurrent dispatchers.
3. Establish an authoritative task-claiming mechanism.
4. Make claims atomic across runners, not merely within one local process.
5. Associate each claim with a worker identity and lease.
6. Reject duplicate active claims.
7. Handle repeated dispatch events idempotently.
8. Add concurrent claim regression tests.

**Acceptance criteria:**

- Two concurrent workers cannot successfully claim the same exclusive task.
- Duplicate dispatch events are harmless.
- Claims remain traceable.
- Worker failures do not permanently lose tasks.

### [ ] WF-14 — Abandoned task locks

**Severity:** High

**Scope:**

- Task locks.
- Worker leases.
- Heartbeats.
- Cancellation handling.
- Watchdog recovery.

**Required work:**

1. Identify how locks are created, renewed, and released.
2. Inspect crash, timeout, and cancellation paths.
3. Introduce configurable lease expiration where missing.
4. Associate leases with unique worker ownership.
5. Add heartbeat renewal.
6. Prevent expired workers from committing stale task-state updates.
7. Recover abandoned tasks safely.
8. Make lock release ownership-aware.

**Acceptance criteria:**

- Crashed workers cannot permanently lock tasks.
- Active workers retain valid leases.
- Expired workers cannot overwrite newer ownership.
- Recovery does not create simultaneous task owners.

### [ ] WF-20 — Concurrent workers interfere with each other

**Severity:** High

**Scope:**

- Worker worktrees.
- Shared filesystem paths.
- Temporary files.
- Task state.
- Integration.
- Artifact names.

**Required work:**

1. Identify resources shared across concurrent workers.
2. Verify unique worktree, branch, temporary directory, and artifact identities.
3. Remove unsafe shared mutable files.
4. Protect conflicting integration operations using appropriate serialization.
5. Use atomic writes for shared state.
6. Detect conflicting task updates.
7. Ensure cleanup is limited to worker-owned resources.
8. Stress-test concurrent execution.

**Acceptance criteria:**

- Workers cannot overwrite each other's files or state.
- Cleanup cannot remove another worker's worktree.
- Conflicting integration operations are safely coordinated.
- Independent workers can still execute concurrently.

### [-] WF-27 — Unnecessary workflow cancellation

**Severity:** Medium

**Scope:**

- Workflow and job `concurrency` configuration.
- Swarm dispatcher.
- Worker workflows.
- Watchdog.
- Integration.
- Delivery workflow.

**Required work:**

1. Inventory all concurrency group expressions.
2. Identify unrelated runs sharing the same concurrency key.
3. Inspect `cancel-in-progress` behavior.
4. Account for GitHub Actions pending-run replacement behavior.
5. Separate concurrency groups by appropriate workflow, task, branch, or PR identity.
6. Preserve cancellation for genuinely obsolete validation runs.
7. Protect active workers and integration operations from unrelated cancellation.
8. Document intentional cancellation policies.

**Acceptance criteria:**

- Independent tasks do not cancel each other.
- Obsolete validation runs may be cancelled safely.
- Cancelled workers release or expire their claims.
- Integration state remains consistent after cancellation.

### [ ] WF-28 — Workers stall indefinitely

**Severity:** Medium

**Scope:**

- Worker execution.
- OpenCode subprocesses.
- External commands.
- Provider calls.
- Heartbeats.
- Workflow timeouts.

**Required work:**

1. Identify operations capable of blocking indefinitely.
2. Add appropriate command, job, and task-level timeouts.
3. Monitor meaningful progress rather than process existence alone.
4. Detect stalled subprocesses and missing heartbeats.
5. Implement graceful termination followed by bounded forced termination when necessary.
6. Preserve diagnostic logs.
7. Recover the task through its normal lease and retry policy.
8. Avoid terminating legitimately long-running operations prematurely.

**Acceptance criteria:**

- Stalled workers eventually terminate or recover.
- Timeouts produce explicit diagnostics.
- Task ownership is recovered safely.
- Legitimate long-running tasks remain supported.

### [ ] WF-29 — Duplicate failure issues

**Severity:** Medium

**Scope:**

- Watchdog issue creation.
- Debugger reports.
- Failure fingerprints.
- Issue labels.
- Recovery tracking.

**Required work:**

1. Inspect issue creation and existing-issue lookup logic.
2. Define stable failure fingerprints that exclude irrelevant timestamps and run IDs.
3. Search for existing matching open issues before creating new ones.
4. Update existing issues with new occurrences.
5. Handle concurrent issue creation safely.
6. Preserve distinct issues for genuinely different root causes.
7. Close issues only after verified recovery.

**Acceptance criteria:**

- Repeated occurrences update one appropriate issue.
- Distinct failures remain distinguishable.
- Concurrent processing does not create uncontrolled duplicates.
- Issue history remains traceable.

### [ ] WF-31 — Stale task and progress records

**Severity:** Medium

**Scope:**

- `tasks/`
- `bugs.md`
- `progress.md`
- `memory.md`
- `workspace.md`
- Task reconciliation.
- PR and branch tracking.

**Required work:**

1. Identify authoritative and derived state.
2. Detect disagreements between task files, worker leases, PR states, and GitHub workflow results.
3. Implement deterministic reconciliation.
4. Handle deleted branches, abandoned PRs, expired claims, and completed merges.
5. Preserve user-authored notes and unrelated content.
6. Make state updates atomic and idempotent.
7. Record reconciliation decisions.

**Acceptance criteria:**

- Task records reflect authoritative execution and merge state.
- Stale claims are recoverable.
- Completed work is not repeatedly scheduled.
- Reconciliation preserves unrelated documentation.

### [!] WF-36 — Global worker capacity exceeded and unnecessary swarm dispatch

**Severity:** Critical

**Scope:**

- Agent swarm workflow.
- Swarm dispatcher.
- Worker matrix generation.
- GitHub Actions concurrency.
- Worker capacity calculation.
- Task scheduler.
- Watchdog.
- Debugger.
- Repair workers.
- Runner allocation.
- Worker leases.
- Concurrent workflow invocations.

**Required work:**

1. Trace every workflow and script capable of launching swarm workers.
2. Investigate whether multiple swarm invocations independently allocate up to 20 workers.
3. Establish a single authoritative global concurrency budget.
4. Set the maximum permitted capacity to 20, configurable through one canonical configuration source.
5. Count running workers, queued workers, pending dispatches, and outstanding capacity reservations without double-counting.
6. Account for other GitHub Actions jobs consuming the same runner capacity.
7. Calculate available capacity before generating the worker matrix.
8. Reserve capacity atomically across concurrent dispatchers.
9. Prevent race conditions between capacity calculation and worker dispatch.
10. Do not dispatch workers when available capacity is zero.
11. Do not generate empty worker matrices.
12. Do not repeatedly trigger swarm workflows when no capacity or actionable tasks are available.
13. Resume scheduling when capacity becomes available through bounded, event-driven reconciliation.
14. Ensure watchdog, debugger, repair workers, and manual dispatches obey the same capacity policy.
15. Do not rely exclusively on strategy.max-parallel, because it limits an individual matrix rather than global execution.
16. Fail closed when worker capacity cannot be reliably determined.
17. Release reservations only after authoritative termination or safe reconciliation.
18. Prevent expired leases from releasing capacity while their corresponding workers are still executing.
19. Handle cancellation, timeout, dispatch failure, duplicate events, and API failures.
20. Record capacity calculations and dispatch decisions in structured logs.
21. Preserve sufficient runner capacity for required Delivery checks and integration operations.
22. Prevent busy polling, recursive dispatch, and uncontrolled queue growth.

**Mandatory scheduling formula:**

Available capacity = max(0, global capacity − occupied capacity − outstanding reservations − applicable reserved headroom).

Workers to launch = min(available capacity, actionable pending tasks, per-dispatch limit).

Capacity calculation and reservation must be performed as one atomic operation.

**Mandatory regression tests:**

| Scenario | Expected result |
|---|---|
| 20 occupied slots | Launch 0 workers |
| 19 occupied slots | Launch at most 1 worker |
| 15 occupied slots | Launch at most 5 workers |
| 15 running and 5 reserved | Launch 0 workers |
| No actionable tasks | Launch 0 workers |
| Two simultaneous dispatchers | Combined allocation never exceeds available capacity |
| Capacity API failure | Launch 0 workers |
| Duplicate dispatch event | No duplicate allocation |
| Worker cancellation | Reconcile and safely reclaim capacity |
| Worker lease expires while still running | Do not prematurely reclaim capacity |
| Watchdog triggers during full capacity | No additional workers |
| Delivery jobs consume shared capacity | Reduce available worker allocation accordingly |

**Acceptance criteria:**

- Global worker allocations never exceed the configured limit.
- No additional workers are dispatched when capacity is exhausted.
- Concurrent dispatchers cannot overallocate capacity.
- Queued and reserved workers are accounted for.
- Required Delivery jobs are not starved.
- Capacity is safely reclaimed after worker termination.
- No uncontrolled dispatch or polling loops exist.
- All scheduling paths obey the same capacity policy.
- Independent QA verifies concurrency and race-condition regression tests.

## Phase 5 — AI model routing and cost enforcement

### [ ] WF-21 — Paid models accidentally selected

**Severity:** High

**Scope:**

- OpenCode configuration.
- Model discovery.
- Provider metadata.
- Agent-specific model settings.
- Routing scripts.
- Environment variables.
- Fallback logic.

**Required work:**

1. Inventory every model selection entry point.
2. Identify provider pricing metadata and its reliability.
3. Enforce zero-cost input and output token pricing.
4. Check other applicable charges, including reasoning, cache, request, and tool fees.
5. Reject unknown, missing, stale, or ambiguous pricing when zero cost cannot be verified.
6. Validate the model immediately before execution.
7. Prevent implicit provider defaults from selecting paid models.
8. Record the selected model and pricing-validation result.
9. Fail safely when no verified free model exists.

**Acceptance criteria:**

- No paid or unverified model can execute.
- Agent-specific configuration cannot bypass cost enforcement.
- Fallback models follow the same pricing policy.
- Missing free models produce a clear blocked outcome.

### [ ] WF-22 — Model fallback failure

**Severity:** High

**Scope:**

- Model router.
- Provider availability.
- `SWARM_MODEL`.
- Worker and planner model selection.
- Retry handling.

**Required work:**

1. Trace the complete preferred-model selection and fallback sequence.
2. Validate `SWARM_MODEL` as the configured fallback.
3. Detect unavailable models, unsupported capabilities, provider failures, and exhausted quotas.
4. Distinguish retryable failures from permanent configuration errors.
5. Select another eligible free model according to documented policy.
6. Prevent fallback loops.
7. Preserve task context when retrying.
8. Record attempted models and failure reasons.

**Acceptance criteria:**

- Preferred-model failure triggers a valid fallback.
- `SWARM_MODEL` is used according to documented policy.
- Every attempted model satisfies the zero-cost requirement.
- Exhausted candidates produce a clear blocked state.

### [ ] WF-23 — Forced model override ignored

**Severity:** High

**Scope:**

- `SWARM_FORCE_MODEL`.
- `SWARM_MODEL`.
- Planner.
- Briefing agent.
- Workers.
- Debugger.
- QA agents.
- Nested agent execution.

**Required work:**

1. Identify all code paths reading model configuration.
2. Centralize or consistently enforce model selection precedence.
3. Define:
   - `SWARM_FORCE_MODEL=1`: use the configured `SWARM_MODEL` exclusively, subject to zero-cost validation.
   - `SWARM_FORCE_MODEL=0`: permit task-specific routing with validated fallback.
4. Reject invalid flag values.
5. Prevent nested agents from silently overriding forced configuration.
6. Verify planning and briefing model selection separately from execution.
7. Add end-to-end routing tests using mocked provider responses.

**Acceptance criteria:**

- Forced mode consistently uses the configured model.
- Forced mode fails safely if that model is invalid or unavailable.
- Normal mode supports task-specific routing and fallback.
- All agents obey the same zero-cost policy.

## Phase 6 — Security, performance, and maintainability

### [-] WF-26 — Excessive workflow token permissions

**Severity:** High

**Scope:**

- Workflow-level permissions.
- Job-level permissions.
- Personal access tokens.
- GitHub App tokens.
- Secret handling.
- PR-triggered workflows.

**Required work:**

1. Inventory permissions required by each job.
2. Default to read-only or no permissions where practical.
3. Grant write permissions only to jobs performing authorized mutations.
4. Separate untrusted PR validation from privileged integration.
5. Audit `pull_request_target` and privileged `workflow_run` usage.
6. Prevent execution of untrusted PR code with privileged secrets.
7. Inspect third-party action pinning and shell input interpolation.
8. Remove unnecessary credential persistence.

**Acceptance criteria:**

- Jobs receive only necessary permissions.
- Untrusted code cannot access privileged credentials.
- Required PR and issue automation remains functional.
- No secrets appear in logs or artifacts.

### [ ] WF-30 — Redundant CI executions

**Severity:** Medium

**Scope:**

- Workflow triggers.
- Push and PR events.
- Watchdog.
- PR flow.
- Reusable workflows.
- Merge queue.
- Validation jobs.

**Required work:**

1. Measure repeated workflow executions and their triggering events.
2. Identify equivalent validation running unnecessarily against the same inputs.
3. Consolidate duplicated workflow logic where practical.
4. Apply appropriate event and path filtering.
5. Preserve required check reporting even when work is legitimately inapplicable.
6. Avoid reusing results across incompatible commit SHAs or configurations.
7. Preserve merge-group validation.
8. Compare workflow duration and runner consumption before and after changes.

**Acceptance criteria:**

- Unnecessary executions are reduced.
- Required validation is never skipped improperly.
- Results remain associated with the correct commit.
- Performance improvements are measurable or clearly documented.

### [ ] WF-32 — Inefficient dependency caching

**Severity:** Medium

**Scope:**

- Maven dependencies.
- Node.js dependencies.
- Python dependencies.
- OWASP dependency-check data.
- Build tooling.
- GitHub Actions cache configuration.

**Required work:**

1. Inventory current cache paths and keys.
2. Include relevant lockfiles, dependency manifests, operating system, architecture, and tool versions.
3. Avoid unnecessary invalidation from unrelated source changes.
4. Separate incompatible dependency caches.
5. Prevent untrusted workflows from poisoning privileged caches.
6. Verify safe restoration and corruption handling.
7. Optimize OWASP/NVD data updates without disabling freshness or security requirements.
8. Measure cache hit rates and build duration.

**Acceptance criteria:**

- Cache reuse improves where practical.
- Dependency changes invalidate appropriate caches.
- Corrupt caches recover safely.
- Security and reproducibility requirements remain intact.

### [ ] WF-33 — GitHub API rate-limit failures

**Severity:** Medium

**Scope:**

- GitHub CLI calls.
- REST and GraphQL requests.
- Polling loops.
- Watchdog.
- Scheduler.
- Integrator.
- Issue and PR automation.

**Required work:**

1. Inventory repeated API calls and polling loops.
2. Identify redundant requests.
3. Implement pagination correctly.
4. Respect rate-limit headers and retry instructions.
5. Use bounded exponential backoff with jitter.
6. Distinguish rate limits from permanent authorization failures.
7. Cache read-only responses when safe.
8. Make retried mutations idempotent.
9. Add simulated rate-limit tests.

**Acceptance criteria:**

- Rate limits do not produce uncontrolled retry loops.
- Permanent errors fail promptly.
- Retried mutations do not create duplicate resources.
- Exhausted retries produce actionable diagnostics.

### [ ] WF-34 — Insufficient workflow diagnostics

**Severity:** Medium

**Scope:**

- Workflow logs.
- Job summaries.
- Diagnostic artifacts.
- Worker execution reports.
- Model routing.
- Task state transitions.

**Required work:**

1. Define a consistent structured diagnostic format.
2. Record workflow, run, job, task, worker, PR, and commit identifiers.
3. Record model selection and fallback decisions.
4. Record failure category, exit status, and retry attempts.
5. Include elapsed time and relevant execution stages.
6. Generate readable GitHub Actions job summaries.
7. Upload diagnostic artifacts on failure and cancellation where feasible.
8. Redact sensitive information.
9. Define appropriate artifact retention.

**Acceptance criteria:**

- Failed operations can be traced across workflows.
- Task and PR relationships are visible.
- Model routing decisions are explainable.
- Diagnostic output contains no credentials.

### [ ] WF-35 — Insufficient workflow regression testing

**Severity:** Medium

**Scope:**

- Python and Bash tests.
- Workflow validation.
- Agent swarm integration.
- GitHub API mocks.
- Model provider mocks.
- Git fixtures.
- Delivery gate tests.

**Required work:**

1. Inventory existing workflow-related test coverage.
2. Identify untested orchestration paths and failure conditions.
3. Add deterministic unit and integration tests.
4. Add an end-to-end simulated lifecycle covering:
   - Task discovery.
   - Atomic claiming.
   - Model selection.
   - Worker execution.
   - Local validation.
   - PR creation.
   - Draft promotion.
   - Delivery gate.
   - Merge queue.
   - Merge confirmation.
   - Task completion.
5. Add failure-path tests for:
   - Invalid credentials.
   - Rate limits.
   - Provider outages.
   - Unavailable free models.
   - Worker crashes.
   - Duplicate events.
   - Expired leases.
   - Merge conflicts.
   - Cancelled jobs.
   - Skipped required checks.
   - Failed repair attempts.
   - Contaminated Git environments.
6. Mock external mutations rather than creating real test PRs or issues.
7. Integrate the tests into the existing required validation pipeline.

**Acceptance criteria:**

- Critical orchestration paths have regression coverage.
- Failure scenarios are deterministic and reproducible.
- Tests do not require paid services or real GitHub mutations.
- All Git tests are isolated.
- Workflow regression tests are enforced by the existing quality gates.

# 6. Additional discovery requirements

Do not limit the audit to the listed findings.

Investigate additional defects involving:

- Shell quoting and injection.
- Incorrect exit-code propagation.
- Suppressed errors.
- Invalid YAML expressions.
- Missing job outputs.
- Unsafe handling of event payloads.
- GitHub Actions expression injection.
- Artifact collisions.
- Missing pagination.
- Incorrect branch or SHA resolution.
- Stale cache restoration.
- Cross-platform compatibility.
- Hardcoded repository names.
- Legacy Aurevia naming discrepancies.
- Unreachable code.
- Duplicate implementations.
- Excessive context consumption.
- Incorrect agent instructions.
- Missing dependency declarations.
- Inconsistent task status transitions.
- Non-idempotent cleanup.
- Race conditions and deadlocks.
- Supply-chain security.
- Unbounded resource consumption.

Assign newly confirmed findings IDs starting at `WF-36`.

Apply the same investigation, reproduction, implementation, and verification requirements.

# 7. Agent coordination strategy

Assign work according to expertise.

| Agent | Responsibility |
|---|---|
| Software architect | Architecture, shared abstractions, state machine |
| Git specialist | Repository safety, worktrees, Git isolation |
| Workflow engineer | GitHub Actions, triggers, Delivery gate |
| DevSecOps | Permissions, secrets, supply chain |
| Python developer | Orchestration, state management, tests |
| Bash developer | Shell safety, portability, execution |
| Concurrency specialist | Claims, leases, races, cancellation |
| Model routing specialist | Free-model enforcement and fallback |
| Debugger | Reproduction and root-cause analysis |
| QA engineer | Independent regression verification |
| Scrum master | Dependencies, prioritization, progress |
| Context optimizer | Compact task briefs and context management |

Parallelize only independent investigations.

Do not allow multiple agents to modify the same files simultaneously without coordination.

Assign explicit file ownership and task dependencies. Use isolated worker worktrees only after their safety has been verified.

The QA agent must independently verify critical fixes rather than relying exclusively on the implementing agent's report.

# 8. Execution procedure

For every finding:

1. Read relevant repository instructions and persistent memory.
2. Inspect the actual implementation.
3. Trace the complete affected execution path.
4. Determine whether the defect exists.
5. Record evidence and confidence.
6. Reproduce the defect safely.
7. Identify the underlying root cause rather than merely suppressing symptoms.
8. Define acceptance criteria and regression tests.
9. Implement the smallest maintainable correction.
10. Run targeted tests.
11. Run relevant integration tests.
12. Perform independent review.
13. Update documentation and persistent state.
14. Create an atomic commit on an authorized repair branch.
15. Verify the resulting PR through the normal Delivery gate.

If a finding is disproven, mark it `[N/A]` and document the evidence.

If a finding requires unavailable credentials, permissions, or external configuration, mark it `[!]` and provide exact remediation instructions.

Do not repeatedly retry permanent blockers.

# 9. Mandatory validation

Discover and use the repository's existing validation entry points.

Run applicable checks, including:

- `actionlint`.
- ShellCheck.
- Python unit and integration tests.
- Git isolation tests.
- Swarm orchestration tests.
- Model routing tests.
- Workflow policy tests.
- Delivery gate tests.
- Staff-web Vitest and existing per-file coverage requirements.
- Patient-mobile checks.
- Java API Maven verification.
- JaCoCo.
- SpotBugs.
- OWASP dependency-check.
- Database migrations and policy tests.
- Documentation and Doxygen checks.
- Existing wiring and architectural checks.

Run the full validation suite only after completing the Git safety investigation.

Use isolated execution environments for tests that mutate Git state.

Do not weaken, bypass, or disable existing checks.

# 10. Required Git safety verification

Create a disposable repository containing:

- Multiple commits and branches.
- Staged changes.
- Unstaged changes.
- Untracked files.
- Ignored files.
- Existing stashes.
- Git index flags.
- Multiple worktrees.
- Custom local configuration.

Capture its initial state.

Execute the applicable test suite from different working directories and with deliberately contaminated Git environments.

Verify afterward that the simulated developer repository remains unchanged.

Test both successful and failing execution paths, including cleanup after exceptions and cancellation.

Never use the actual user's repository as the destructive-test target.

# 11. Documentation and persistent state

Maintain the existing project tracking files:

- `bugs.md` — Findings, evidence, fixes, and verification.
- `progress.md` — Completed work, remaining work, and milestones.
- `memory.md` — Architectural decisions and lessons learned.
- `workspace.md` — Active tasks, branches, ownership, and blockers.
- `tasks/` — Existing task lifecycle records.

Follow the repository's established file locations and conventions.

Document:

- Workflow event dependency graph.
- Task lifecycle and state transitions.
- Failure classification and recovery policy.
- Git safety boundaries.
- Model selection precedence.
- Zero-cost model enforcement.
- Concurrency and lease management.
- Delivery gate and merge queue integration.
- Authentication and permission requirements.
- Operational troubleshooting procedures.

Keep documentation synchronized with implementation.

# 12. Execution priority

Execute phases in this order:

1. Git safety and repository integrity.
2. Delivery gate and PR lifecycle.
3. Failure detection and automated recovery.
4. Concurrency and task orchestration.
5. Model routing and cost enforcement.
6. Security, efficiency, and regression testing.
7. Final integration and independent audit.

Immediately prioritize any newly discovered critical security or data-loss defect.

Do not postpone Git safety fixes while running unrelated tests.

# 13. Final report

Produce a concise but evidence-backed audit report.

| ID | Severity | Confirmed? | Root cause | File:line | Fix | Regression test | Status | Confidence |
|---|---|---|---|---|---|---|---|---|

Include:

- Total findings investigated.
- Confirmed defects.
- Fixed and verified defects.
- Disproven suspected defects.
- Newly discovered defects.
- Remaining blockers.
- Test results.
- Git safety verification.
- Root cause of the local repository reset, if established.
- Delivery gate verification.
- Merge queue compatibility.
- Model routing verification.
- Security improvements.
- Workflow execution and performance improvements.
- Remaining risks.
- Commits and pull requests created.

Distinguish measured improvements from estimated improvements.

Never claim a fix is verified without supporting test results.

# 14. Definition of done

The audit is complete only when:

- Every finding has an evidence-backed disposition.
- Every confirmed actionable defect is fixed.
- Critical fixes receive independent QA verification.
- Git isolation tests pass.
- No tests can modify the user's primary checkout.
- Delivery gate fails closed for required failures.
- Branch protection remains enforced.
- PR automation is compatible with the configured merge mechanism.
- Task completion requires confirmed merging.
- Automated recovery is bounded and idempotent.
- Concurrent workers cannot corrupt shared state.
- Only verified zero-cost models execute.
- Required regression tests pass.
- Existing quality gates remain intact.
- Documentation and persistent state are synchronized.
- Remaining external blockers are explicitly documented.

Begin immediately with read-only repository inspection and the local Git reset investigation.

Continue autonomously through all actionable findings. Do not stop after generating an audit report or implementation plan. Implement and verify the fixes.

Do not request confirmation for routine, reversible engineering decisions within the authorized scope. Escalate genuine blockers, destructive operations, repository settings changes, credential requirements, and decisions that could compromise existing user work.
