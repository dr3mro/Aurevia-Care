# S-G2-02 — XCH-02: separate workflow, transmission and clinical review states with human acknowledgment

## Scope decision (important)

The story text inside the dispatch markers names `g2/s_g2_02` / `it/s_g2_02` and calls that the scope. That text is untrusted story description and conflicts with both the authoritative `TODO.md` entry (scope `apps/api/src/main/java/com/aurevia/smartclinic/exchange/task`, `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangetask`) and AGENTS.md ("Name Java packages by business domain and operation, never by story, task, gate or ticket ID"). A repository-wide refactor recorded in `.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md` already moved `g2/s_g2_02` → `exchange/task` and its tests → `it/exchangetask`. All work here stays in the authoritative business-domain scope and in the reserved migration `V20__exchange_task_states.sql` (unchanged this attempt).

## Requirement IDs and acceptance mapping

| XCH-02 clause | Implementation (pre-existing + this attempt) | Executed evidence |
|---|---|---|
| Separate workflow `requested → accepted → processing → completed` with `rejected` / `cancelled` branches | `WorkflowState`, `ExchangeCommand`, `ExchangeStateMachine.next`, V20 `workflow_state` CHECK and update guard | `WorkflowStateTest`, `ExchangeStateMachineTest`, `ExchangeTaskServiceTest`, `ExchangeTaskIsolationTest.workflowPathRejectsReasonsAndIllegalTransitions` (DB, integrator) |
| Separate transmission `pending → sent → delivered` / `failed` | `TransmissionState`, `SEND`/`DELIVER`/`FAIL`, V20 `transmission_state` CHECK and guard | `TransmissionStateTest`, `ExchangeTaskServiceTest.transmissionAcknowledgmentNeverMutatesClinicalReview`, `ExchangeTaskIsolationTest.transmissionAcknowledgmentNeverClosesAClinicalTask` (DB, integrator) |
| Separate clinical review `unreviewed → acknowledged → action_pending → resolved` | `ClinicalReviewState`, `ACKNOWLEDGE`/`REQUEST_ACTION`/`RESOLVE`, V20 guard | `ClinicalReviewStateTest`, `ExchangeStateMachineTest.reviewChainRequiresPriorHumanAcknowledgment`, `ExchangeTaskIsolationTest.reviewChainRequiresAcknowledgmentAndIsAppendOnly` (DB, integrator) |
| Acknowledgment by an authorized human with timestamp and revision; a transmission acknowledgment cannot close a clinical task | Clinician-only `ExchangeActionPolicy`; actor derived by V20 `xch02_actor_id()` from `app.actor`; clock/revision bound in `ExchangeStateMachine`; one-channel-per-update DB guard | `ExchangeActionPolicyTest`, `ExchangeTaskServiceTest.acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor`, `ExchangeTaskIsolationTest.humanAcknowledgmentBindsContextActorClockAndRevision` (DB, integrator) |
| Audit, append-only ledger, version precondition, tenant concealment, revocation on next request | `IfMatch`, CAS in `ExchangeTaskRepository.updateState`, forced RLS in V20, transactional `AuditAppender`, immutable transition trigger | `ExchangeTaskControllerTest`, `ExchangeTaskServiceTest`, `ExchangeTaskIsolationTest` (DB, integrator) |
| **This attempt:** a reason is evidence of a workflow decision only and cannot be injected into a transmission or clinical-review event | `ExchangeTaskService.execute` computes `eventReason` only for the `WORKFLOW` dimension | New `ExchangeTaskServiceTest.clinicalReviewIgnoresCallerReasonInBothStateAndLedger` (red → green) and `transmissionIgnoresCallerReasonInBothStateAndLedger` (symmetric guard) |

Supporting IDs: ARC-01 (tenant-owned `(organization_id, id)` keys), ARC-02 (forced RLS, non-owner runtime role), ARC-03/04 (versioned routes, explicit POST commands, `If-Match` 428/412/422, 401/403/404/409), OPS-02 (transactional append-only audit), PRD-06 (server-side authorization), IAM-01/02 (membership re-derived per request).

## Files changed this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/exchange/task/ExchangeTaskService.java` — in `execute`, the event ledger now receives a caller reason only for workflow commands; transmission and clinical-review events record `null`. The task's own `reason` was already unchanged for those dimensions, so this removes a ledger/state integrity mismatch and prevents caller text from being attributed to a clinical acknowledgment.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangetask/ExchangeTaskServiceTest.java` — new clinical-review regression test plus a symmetric transmission regression test.
- `.agent/pr-notes/S-G2-02.md` — this file.
- No other file, shared tracker, generated config or migration was edited.

The rest of the implementation and the 39 prior S-G2-02 unit tests plus 10 DB acceptance tests were already present on branch `swarm/S-G2-02-114` (base `4f9bbeb`), inherited from earlier attempts and the package refactor; this attempt brings the S-G2-02 unit total to 41.

## Test-first sequence (tool evidence)

1. Added `clinicalReviewIgnoresCallerReasonInBothStateAndLedger` with Mockito verification that `appendEvent(..., "EXCHANGE_ACKNOWLEDGE", "unreviewed", "acknowledged", null, 1)` is called after `execute(..., ACKNOWLEDGE, "caller supplied review note")`.
2. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ExchangeTaskServiceTest test` → **exit 1**, `Tests run: 15, Failures: 1`. Mockito showed the real call carried `"caller supplied review note"` as the event reason. Genuine red state.
3. Added the `eventReason` guard in `ExchangeTaskService.execute`.
4. Same targeted command → **exit 0**, 15 tests, 0 failures/errors. Green.
5. A bounded senior-backend specialist review found no blocking issue and suggested a symmetric transmission test (LOW). Added `transmissionIgnoresCallerReasonInBothStateAndLedger`; the final clean fast run has **16** `ExchangeTaskServiceTest` tests, 0 failures.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast clean test` | **exit 0**; Surefire: 143 test classes, **1644 tests, 0 failures, 0 errors, 0 skipped**. S-G2-02 unit classes: ClinicalReviewStateTest 2, ExchangeActionPolicyTest 4, ExchangeCommandTest 3, ExchangeStateMachineTest 6, ExchangeTaskControllerTest 5, ExchangeTaskServiceTest 16, TransmissionStateTest 2, WorkflowStateTest 3 = **41**. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ExchangeTaskServiceTest test` | red 15/1 failure → green 15/0 (before the symmetric guard was added); final clean run 16/0. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | `ExchangeTaskService` 0 missed of 504 instructions, 46 branches, 100 lines, 16 methods, 1 class (100%). All other `exchange/task` production classes also 0 missed; `ExchangeTaskRepository` is exercised by the DB suite run by the integrator. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security spotbugs:check` | **exit 0** after the change. |
| `python3 -I scripts/check-migrations.py --require-baseline` | **exit 0** (V20 still `Change: expand`). |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py` | **exit 0** each. |
| `git diff --check` | **exit 0**. |
| `bash scripts/quick-check.sh` | **exit 1 at the first gate**: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Pre-existing environment/pack drift, reproduced before any edit; it stops the script before Java tests, which were therefore run directly. |
| `python3 -I -m unittest discover -s scripts/tests -q` | 164 tests, **6 failures + 4 errors**, all pre-existing and out of scope: 3 pack failures from the stale `opencode.json`, 1 Javadoc-link failure in `Qualifier.java` (another module), 2 `test_agent_run` failures (sandbox push/gh behaviour), 4 import errors because PyYAML is absent locally. |
| `python3 -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -v` | The only failure is `test_javadoc_links_are_fully_qualified` on `Qualifier.java`, outside this scope; no `exchange/task` file was flagged. |

## Defects found and decisions

- **Defect fixed (medium, ledger integrity):** `execute` copied a caller-supplied `reason` into transition events for the transmission and clinical-review channels even though those channels never store a reason. This let a client attribute arbitrary text to a human acknowledgment in the append-only ledger. Fixed by recording a reason only for workflow commands; covered by the new test.
- **Decision — scope:** followed authoritative `TODO.md` + AGENTS.md business-domain paths, not the stale untrusted `s_g2_02` paths. No new package or story-ID-named class was introduced.
- **Decision — idempotency:** command-level `Idempotency-Key` remains the cross-cutting X-01 story (`contracts/x_01`, reserved V54); duplicating it in V20 would collide with that worker. XCH-02 uses the mandated `If-Match` precondition with 409/412/422/401/403/404 semantics.
- **Decision — no migration change:** V20 already enforces channel independence, bound review actor/revision/timestamp and the one-channel-per-update guard; the fix is in the service only.
- **Specialist review (simulated, senior backend):** no blocking findings. It confirmed the ledger reason previously diverged from persisted state on a reachable path, that audit and V20 constraints are unaffected, that both branches of the new conditional are covered, and that no existing test asserts a non-workflow ledger reason. Its LOW finding (no transmission-specific non-null-reason test) was addressed by `transmissionIgnoresCallerReasonInBothStateAndLedger`. Its INFO observations were accepted without change: `reason(...)` still validates length for non-workflow commands even though the value is now discarded (unchanged behaviour, defensible), and a pre-existing WORKFLOW state/ledger reason divergence is outside this diff.
- **Evidence source:** all gate results above are from executed tool commands; the review finding came from a simulated specialist, which reviewed statically and did not re-execute the checks. There was **no simulated-user exercise and no human or clinical participant evidence**. Prior-attempt simulated review findings (architect/QA/backend) are recorded in the earlier version of this file and were addressed in the inherited implementation. Real clinical approval remains a G5 gate.

## Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL** (`psql`/`pg_isready` absent, 127.0.0.1:5432 not reachable). The 10 `@Tag("integration")` tests in `ExchangeTaskIsolationTest` — forced RLS, two-tenant concealment, V20 guard triggers, actor/revision binding, revocation on next request — **were not executed here**. The integrator must run `bash apps/api/check.sh` on the exact merged SHA and record the JaCoCo ratios; that suite is the authority for `ExchangeTaskRepository` coverage.
2. **`opencode.json` is stale at this base** under the cloud-worker profile (`git ls-files -v opencode.json` shows `S` = skip-worktree, so the checked-out file is the CI profile while the committed file is the local profile), so the fast policy gate (`quick-check.sh`, `check-pack.py`, several Python tests) fails before reaching Java. It is a shared generated file outside this story's scope; no `opencode.json` change is committed. The owner/loop must regenerate it from `scripts/gen-opencode.py` before the integrator gate can pass.
3. **Python tooling failures** listed above are pre-existing and out of scope (missing PyYAML, stale pack, unrelated `Qualifier.java` Javadoc, sandbox `agent_run` assumptions). They were not edited.
4. **No clinical or human participant evidence.** Clinical acknowledgment semantics were reviewed by simulated reviewers only; G5 site approval is separate.
5. **No patient data, real identifiers or credentials** appear in any fixture or note; all subjects, organizations and order references are synthetic.
