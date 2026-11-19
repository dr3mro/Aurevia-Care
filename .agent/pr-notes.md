# S-G2-02 — Separate workflow, transmission and clinical review states with human acknowledgment

## Requirement IDs and acceptance mapping

Primary: **XCH-02**. Supporting: **ARC-01, ARC-02, ARC-03, ARC-04, IAM-01, PRD-06, OPS-02**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-02, §2 IAM-01, §7 ARC-01..04, §8 OPS-02. Gate: G2.

| XCH-02 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Three independent state channels: workflow `requested→accepted→processing→completed` (+ `rejected`/`cancelled`), transmission `pending→sent→delivered\|failed`, clinical review `unreviewed→acknowledged→action_pending→resolved` | `WorkflowState`, `TransmissionState`, `ClinicalReviewState` matrices; `ExchangeDimension`; `ExchangeStateMachine.next` writes exactly one channel; V20 `CHECK` constraints + `xch02_guard_task_update` mirror the matrices in SQL | `WorkflowStateTest` (3), `TransmissionStateTest` (2), `ClinicalReviewStateTest` (2), `ExchangeStateMachineTest` (6); `ExchangeTaskIsolationTest.workflowPathRejectsReasonsAndIllegalTransitions`, `transmissionAcknowledgmentNeverClosesAClinicalTask`, `reviewChainRequiresAcknowledgmentAndIsAppendOnly` (integrator) |
| Acknowledgment by an authorized human with timestamp and revision | Clinician-only `EXCHANGE_REVIEW` in `ExchangeActionPolicy`; `Clock`-derived timestamp; `reviewedRevision = current.version()` after `If-Match` CAS; actor derived by V20 `xch02_actor_id()` from transaction-local `app.actor`, never from a request DTO | `ExchangeTaskServiceTest.acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor`, `acknowledgeDeniesNonClinicianAndInactiveMembership`; `ExchangeTaskIsolationTest.humanAcknowledgmentBindsContextActorClockAndRevision` (integrator) |
| A transmission acknowledgment cannot close a clinical task | `DELIVER`/`FAIL`/`SEND` map to `TRANSMISSION`; the state machine copies the clinical channel and its evidence unchanged; V20 restores clinical evidence columns on non-clinical writes and enforces the one-channel rule | `ExchangeStateMachineTest.aTransmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskServiceTest.transmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskIsolationTest.transmissionAcknowledgmentNeverClosesAClinicalTask` (integrator, incl. direct SQL assertion) |
| Explicit POST commands with version precondition and machine-readable errors (ARC-03/04) | `POST /api/v1/exchange-tasks` and `/{id}/{command}`, `IfMatch.parse` (428 missing / 422 malformed / 412 stale through CAS), allowlisted `ExchangeCommand.from`, uniform `ApiException` mapping | `ExchangeTaskControllerTest` (5); `ExchangeTaskIsolationTest.authorizationPreconditionsAndTenantConcealment` (401/403/404/409/412/422/428) (integrator) |
| Tenant isolation, fail-closed authorization, append-only ledger and audit (ARC-01/02, IAM-01, OPS-02) | `(organization_id,id)` composite keys; forced RLS on both V20 tables with `WITH CHECK`; `FOR UPDATE` + version CAS; immutable `exchange_task_transition`; `appendAudit` SUCCESS/DENIED inside the guarded transaction; membership and roles re-derived per request | `ExchangeTaskIsolationTest` two-tenant `LOCAL-1` concealment, ledger immutability attempt, audit-row assertions (integrator); `ExchangeTaskServiceTest` 403 denial audits |

### Entry-point-to-test trace

`POST /api/v1/exchange-tasks` → `ExchangeTaskController.create` → `ExchangeTaskService.create` → guarded `TransactionTemplate` (`TenantGuardTransactionManager` binds `app.current_org`/`app.actor`) → `ExchangeActionPolicy.allowsCreate` → `ExchangeTaskRepository.insert` (forced RLS) → `appendEvent(CREATE)` + `appendAudit` → 201 with three initial states. Verified by `ExchangeTaskControllerTest.createReturns201WithLocationAndInitialStates` and `ExchangeTaskIsolationTest.createOpensTheThreeInitialChannelsWithOneHistoryEvent`.

`POST /api/v1/exchange-tasks/{id}/{command}` + `If-Match` → `ExchangeTaskController.execute` → `IfMatch.parse` → `ExchangeCommand.from` → `ExchangeTaskService.execute` → authorize → `repository.loadForUpdate` (`FOR UPDATE`) → `requireVersion` → `ExchangeStateMachine.next` (one channel) → `repository.updateState` (version CAS, V20 guard) → `appendEvent` + `appendAudit` → next state. Verified by `ExchangeTaskControllerTest`, `ExchangeTaskServiceTest`, `ExchangeStateMachineTest` and the integration scenarios.

`GET /api/v1/exchange-tasks/{id}` → `get` → read policy → `repository.find` + `history` → read audit → state + append-only history.

## Commands run and results

Evidence source for this section: **executed tool commands** (not reviewer claims).

- `java -version` → `openjdk version "21.0.13" 2026-10-13 LTS` (Temurin). `mvn -version` → `Apache Maven 3.10.0`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. This is the exact DB-less fast command from `scripts/quick-check.sh`. Surefire XML aggregation: **1463 tests, 0 failures, 0 errors, 0 skipped across 134 suites**. The 8 in-scope S-G2-02 suites contribute **38 tests, all green**: `ClinicalReviewStateTest` 2, `ExchangeActionPolicyTest` 4, `ExchangeCommandTest` 3, `ExchangeStateMachineTest` 6, `ExchangeTaskControllerTest` 5, `ExchangeTaskServiceTest` 13, `TransmissionStateTest` 2, `WorkflowStateTest` 3. (The exact command was re-run a second time with `>/tmp/sg2_02_fasttest.log` to capture the exit code: `FAST_TEST_EXIT=0`.)
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0**. Reserved V20 is present as `db/migrations/V20__exchange_task_states.sql` with a valid `V<n>__name.sql` name and `Change: expand`; no other migration version was touched.
- `git check-ignore -v .agent/pr-notes.md` → not ignored (tracked file); `git ls-files --error-unmatch .agent/pr-notes.md` → tracked. `git status --short` before commit → clean.
- Cross-checks: `IfMatch.parse` (428/422/412 semantics), JaCoCo `check` execution bound at 1.00 for INSTRUCTION/LINE/BRANCH/METHOD/CLASS and the `fast` profile's `excludedGroups=integration` were read from `apps/api/pom.xml` to confirm the gate boundary.

I did **not** run the database/integration/coverage/static-security gates. Per the worker contract these belong to the integrator: `bash db/check.sh`, `mvn -q -B verify` (runs the `@Tag("integration")` `ExchangeTaskIsolationTest` on real PostgreSQL plus JaCoCo 100%, OWASP and SpotBugs), and `scripts/check.sh`. No result from those gates is claimed here.

## Reviewer findings and dispositions

Three independent simulated specialists reviewed the in-scope files (paths, XCH-02 acceptance and the fast-gate evidence only). All three returned **no blocking defect and no required in-scope source change**:

- **Senior Java & Spring Boot** — transition correctness, transmission/clinical independence, server-side acknowledgment binding, CAS, authorization/error mapping all traceable to tests. One low-severity hardening idea: `ExchangeTaskService.reason()` bounds only presence, not length/characters, though `reason` is persisted to two `text` columns. Disposition: **non-blocking follow-up**; not changed here because it is outside the XCH-02 acceptance and the reviewer explicitly said it should not block acceptance.
- **Software Architect** — Java↔V20 transition matrices match one-for-one; channels independent; revision/time/actor bound to the reviewed version; concurrency/RLS/ledger/audit sound. Flagged (a) no direct SQL negative test of the V20 guard rejection paths, (b) no `Idempotency-Key` (documented project deferral; XCH-02 requires only `If-Match`), (c) "revision" interpretation question, (d) DB guard cannot check role for a hypothetical direct SQL writer. Disposition: **(a) recommended to integrator/QA** as added evidence, not a source change; **(b)** out of scope by documented decision; **(c)** recorded as a decision below; **(d)** defence-in-depth only — authorization is enforced before every write.
- **Senior Database** — V20 evidence `CHECK`, guard trigger, fail-closed `xch02_actor_id`, CAS, immutability triggers, grants and forced RLS all correct; change-safety checker not tripped. One out-of-scope observation: `db/check.sh` static table lists do not include the two new tables, so the shell gate does not assert V20 policy; the Java IT is the runtime proof. Disposition: **separate change**, not touched here.

Reviewer evidence is **simulated review**, not human or clinical acceptance. No human participant was involved.

## Defects found

No reproducible in-scope defect was found. The hardening ideas and verification-gap suggestions above are recorded as follow-ups, not as accepted defects.

## Decisions / notes for the integrator

- The complete S-G2-02 implementation (16 main files), 9 test files and the V20 migration were **already committed at this branch's base** `da4fbb4` (a prior attempt's work, reflected in the recycled `.agent/pr-notes.md`). This worker changed **no production or test source** after verification; its contribution is this PR description and the recorded executable evidence. The prior notes described S-G1-07 and were replaced.
- **"Revision" interpretation**: XCH-02's "timestamp and revision" is implemented as the work-item optimistic version the clinician reviewed (the `If-Match` version at acknowledgment), not the immutable order snapshot revision from XCH-01. This slice is deliberately self-contained (no V19 dependency) and the choice is documented in `ExchangeStateMachine`/V20 comments. Confirm with Product/Architecture before G2 sign-off if a different meaning was intended; it does not block this story.
- **Idempotency keys** are absent on transition routes by documented project deferral; a retry after a lost response gets 412 from the version CAS rather than the original outcome. This diverges from the literal ARC-04 sample contract but is outside XCH-02.
- Verify on the merge result: the `@Tag("integration")` `ExchangeTaskIsolationTest` (real PostgreSQL: V20 guard, forced RLS, actor derivation, two-tenant concealment, ledger immutability), JaCoCo 100% at `verify`, OWASP/SpotBugs and `scripts/check.sh`. These are the integrator's gates, not claims of this worker.
- Scope respected: only `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_02`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_02` and reserved migration V20 were in scope; no shared tracker, doc, workflow, other migration or source file was edited. No push, tag or `gh` call was made.
- Synthetic fixtures only; no patient data, credentials or raw logs were recorded. All gate results above are tool-generated; review findings are simulated; there is no human or clinical evidence.
