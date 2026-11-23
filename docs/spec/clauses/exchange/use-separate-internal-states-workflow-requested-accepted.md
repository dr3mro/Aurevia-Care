# XCH-02 clause 1 — separate internal state channels

## Clause

Use separate internal states for workflow (requested → accepted → processing → completed, with rejected/cancelled branches), transmission (pending → sent → delivered or failed), and clinical review (unreviewed → acknowledged → action pending → resolved).

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.UseSeparateInternalStatesWorkflowRequestedAcceptedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UseSeparateInternalStatesWorkflowRequestedAcceptedTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants that
share the same tenant-local order reference. It proves the clause through the real
REST entry point, not just the domain enums:

- `workflowChainMovesRequestedToAcceptedToProcessingToCompleted` — one task is
  accepted, processed and completed with quoted `If-Match` versions; each response
  is 200 with the expected `workflowState` while transmission stays `pending` and
  clinical review stays `unreviewed`; the read-back holds version 4 and four
  append-only history events on the `workflow` channel.
- `rejectedAndCancelledBranchesAreTerminal` — rejecting without a reason is
  refused with 422 `REASON_REQUIRED`; rejecting and cancelling with a reason reach
  `rejected`/`cancelled`, and a later transition is refused with 409.
- `transmissionChainLeavesClinicalReviewUntouched` — send/deliver reaches
  `delivered` and send/fail reaches `failed`, while `workflowState` stays
  `requested`, `clinicalReviewState` stays `unreviewed`, review evidence is null
  and no `clinical_review` history event exists. A delivery receipt can never
  close a clinical task.
- `clinicalReviewNeedsHumanAcknowledgment` — resolving from `unreviewed` is
  refused with 409; acknowledge → request-action → resolve binds `reviewedBy`,
  `reviewedAt` and `reviewedRevision` to the acting clinician and the exact
  reviewed revision, without moving the other two channels.
- `forbiddenTransitionIsRefused` — completing a `requested` task is refused with
  409 `INVALID_TRANSITION` and the state does not move.
- `wrongRoleCannotAcknowledge` — lab collector and reception subjects are refused
  with 403 `FORBIDDEN`; only a clinician may change the clinical channel.
- `peerTenantCannotSeeOrMoveTask` — an actor of the peer tenant cannot read or
  transition the task (404), even though both tenants use the same local order
  reference.
- `staleVersionCannotAdvance` — a stale quoted `If-Match` is refused with 412
  `STALE_VERSION` and the state does not move.

Production classes exercised through `com.aurevia.smartclinic.exchange.task.ExchangeTaskController`
(`POST /api/v1/exchange-tasks`, `/{id}/{command}`, `GET /api/v1/exchange-tasks/{id}`):

- `com.aurevia.smartclinic.exchange.task.ExchangeTaskService` — command dispatch,
  per-request membership and role checks, tenant-guarded transaction, optimistic
  version check, human-acknowledgment binding and audit.
- `com.aurevia.smartclinic.exchange.task.ExchangeStateMachine` — one-channel
  transition matrices and the rule that a transmission command copies clinical
  review evidence unchanged.
- `com.aurevia.smartclinic.exchange.task.WorkflowState`, `TransmissionState` and
  `ClinicalReviewState` — the three independent legal chains and terminal states.
- `com.aurevia.smartclinic.exchange.task.ExchangeCommand` and `ExchangeDimension`
  — each command writes exactly one named channel.
- `com.aurevia.smartclinic.exchange.task.ExchangeActionPolicy` — clinical review
  is clinician-only; workflow, transmission and review actions are separated.
- `com.aurevia.smartclinic.exchange.task.ExchangeTask`, `ExchangeTaskView`,
  `ExchangeTaskEvent` and `ExchangeTaskRepository` — persisted state, review
  evidence and append-only history.
- `com.aurevia.smartclinic.api.IfMatch` — quoted and weak precondition parsing
  with 428/422/412 failures.
- `db/migrations/V20__exchange_task_states.sql` — RLS/FORCE RLS, the
  `exchange_task` check constraint that review evidence exists exactly when the
  clinical channel left `unreviewed`, and the update guard that permits only one
  legal channel change per version.

The same transition matrices are asserted at unit level in
`com.aurevia.smartclinic.it.exchangetask.ExchangeStateMachineTest` and through the
HTTP boundary in `com.aurevia.smartclinic.it.exchangetask.ExchangeTaskIsolationTest`;
this story proves the whole clause through the REST entry point with committed
state and read-back history in one class. Its fixture harness
(`seedTenant`/`seedMember`/`seedTask`, the `call`/`get` helpers, the frozen clock
and the dedicated database) mirrors `ExchangeTaskIsolationTest`. What this class
adds is a single per-clause entry-point proof, including the workflow `cancel`
branch that the broader HTTP suite does not cover; it does not replace that suite
and deliberately does not repeat its storage-forgery, audit or membership tests.
The `exchange-tasks` routes remain in the shrinking OpenAPI baseline, so this
status is a statement about clause behavior, not contract completeness.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=UseSeparateInternalStatesWorkflowRequestedAcceptedTest test`
— Surefire reports 10 tests, 0 failures, 0 errors, 0 skipped. See
`.agent/pr-notes/SPEC-XCH-02-C01-20261123-055719.md` for the exact final lines.

## Gap

None.
