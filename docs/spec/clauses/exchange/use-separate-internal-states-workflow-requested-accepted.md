# XCH-02 clause 1 — separate workflow, transmission and clinical review states

## Clause

Use separate internal states for workflow (requested → accepted → processing → completed, with rejected/cancelled branches), transmission (pending → sent → delivered or failed), and clinical review (unreviewed → acknowledged → action pending → resolved).

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.UseSeparateInternalStatesWorkflowRequestedAcceptedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UseSeparateInternalStatesWorkflowRequestedAcceptedTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic clinician, lab-collector and tenant-admin fixtures. Each case owns a
distinct synthetic `orderRef` (the V20 unique key), quoted `If-Match` ETags are
advanced per step, and the review clock is frozen so the bound timestamp is
exact. The class proves the three chained state machines and their refusal gates
through the real REST entry point, not just the domain enums:

- `workflowChainRunsRequestedToAcceptedToProcessingToCompleted` — one task is
  created as requested, accepted, processed and completed; transmission stays
  pending and clinical review stays unreviewed at every step, versions advance
  1→4, and the read-back history records `requested → accepted → processing →
  completed` with four workflow-channel events.
- `rejectAndCancelBranchesRecordReasonAndEndTheWorkflow` — both terminal branches
  persist their reason on the task and in the event ledger; a later accept is
  refused with 409 `INVALID_TRANSITION` and the terminal state does not move.
- `aFailedTransmissionLeavesTheClinicalChannelUntouched` — the transmission
  `pending → failed` branch writes one transmission event, leaves workflow and
  clinical review unchanged, and a later deliver is refused with 409.
- `clinicalReviewChainRunsAcknowledgedActionPendingToResolved` — the clinical
  `unreviewed → acknowledged → action pending → resolved` chain is clinician-only,
  binds revision and timestamp, writes only clinical-review events, leaves the
  other two channels unchanged, and both the premature and post-terminal
  transitions are refused with 409.
- `aDeliveryReceiptCannotCloseTheClinicalTask` — send plus deliver reaches
  delivered while clinical review stays unreviewed with no review evidence and
  no clinical event; a clinician acknowledge then binds actor, timestamp and
  revision 3 without disturbing the delivered transmission.
- `aRequestedTaskCannotSkipToCompleted` — a workflow `complete` by the lab
  collector on a requested task is refused with 409 `INVALID_TRANSITION` and the
  state is unchanged.
- `aStaleVersionCannotAdvanceTheWorkflow` — `If-Match: "99"` is refused with 412
  `STALE_VERSION` and the workflow stays requested.
- `aTenantAdminCannotAdvanceTheWorkflow` — a tenant_admin (allowed to create,
  read and transmit) is refused a workflow command with 403 `FORBIDDEN`.
- `aLabCollectorCannotAcknowledgeClinicalReview` — a lab_collector (allowed to
  workflow and transmit) is refused a clinical command with 403 `FORBIDDEN`.
- `aPeerTenantCannotTransitionAnotherTenantsTask` — a clinician of the peer
  tenant receives 404 `NOT_FOUND` and the owner's state is unchanged, proving
  the forced-RLS tenant filter conceals the row.
- `rejectingWithoutAReasonIsRejected` — an empty reason is refused with 422
  `REASON_REQUIRED` and no event is appended.

Entry-point-to-test trace: HTTP `POST /api/v1/exchange-tasks`,
`POST /api/v1/exchange-tasks/{id}/{command}` and `GET /api/v1/exchange-tasks/{id}`
→ `com.aurevia.smartclinic.exchange.task.ExchangeTaskController` (thin adapter,
injected service, parses `If-Match` and the allowlisted command) →
`com.aurevia.smartclinic.exchange.task.ExchangeTaskService` (tenant-guarded
transaction, re-read membership/roles, optimistic version check, audit) →
`com.aurevia.smartclinic.exchange.task.ExchangeStateMachine` (one channel per
command) → `com.aurevia.smartclinic.exchange.task.ExchangeTaskRepository` (RLS-bounded
SQL, append-only event ledger) → `db/migrations/V20__exchange_task_states.sql`
(three CHECK-constrained channels, review-evidence invariant, forced RLS and the
single-channel transition guard trigger). Wire types are
`WorkflowState`, `TransmissionState`, `ClinicalReviewState`, `ExchangeDimension`,
`ExchangeCommand`, `ExchangeTask`, `ExchangeTaskView` and `ExchangeTaskEvent`;
`ExchangeActionPolicy` separates the role matrix, and
`com.aurevia.smartclinic.api.IfMatch` / `ApiException` /
`ApiErrorHandler` render the 403/404/409/412/422 contract.

Discovery: this is the only XCH-02 three-channel implementation. The package
`apps/api/src/main/java/com/aurevia/smartclinic/exchange/task` and migration
`db/migrations/V20__exchange_task_states.sql` own the states; no other package,
migration, controller or generated client implements the same three channels or
routes. The unit suites (`ExchangeStateMachineTest`, `WorkflowStateTest`,
`TransmissionStateTest`, `ClinicalReviewStateTest`) and the existing HTTP suites
`it/exchangetask/ExchangeTaskIsolationTest` and `ExchangeTaskControllerTest`
cover the same model; this class adds the clause-level, end-to-end evidence.

The delivery and acknowledgment cases here assert channel independence, which
is this clause's rule; the deeper human-binding and delivery-versus-clinical
rules are owned by the separate clause stories `AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest`
and `TransmissionAcknowledgmentCannotCloseClinicalTaskTest` and are cited there
as corroborating evidence rather than re-proven.

Verified locally:
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=UseSeparateInternalStatesWorkflowRequestedAcceptedTest test`
— 11 tests run, 0 failures, 0 errors, 0 skipped; see
`.agent/pr-notes/SPEC-XCH-02-C01-20261122-153909.md` for the exact final lines.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the routes
`POST /api/v1/exchange-tasks`, `POST /api/v1/exchange-tasks/{}/{}` and
`GET /api/v1/exchange-tasks/{}` are listed only in
`contracts/openapi/missing-routes.txt:44,204-205` and are absent from
`contracts/openapi.yaml`; publishing them and regenerating the client is one
separate contract task.
