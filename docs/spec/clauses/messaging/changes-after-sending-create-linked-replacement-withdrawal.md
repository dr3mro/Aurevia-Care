# MSG-04 clause 6 — API test evidence

## Clause

Changes after sending create a linked replacement or withdrawal, mark the previous version as superseded, and notify the patient.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ChangesAfterSendingCreateLinkedReplacementWithdrawalTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ChangesAfterSendingCreateLinkedReplacementWithdrawalTest.java`).
All seven tests are active (none are disabled) and use synthetic fixtures only. They drive the real
HTTP entry point with a standalone MockMvc over the real `InstructionController`, `ApiErrorHandler` and
`TenantContextInterceptor` under the `synthetic-auth` profile. The store is the Spring
`InstructionStore.InMemory` adapter: a peer tenant is seeded with identical local identifiers while
its own context is bound, so isolation is observed at the tenant boundary, not by renaming fixtures.
This is the same style as `messaging/appointments/PatientAppointmentControllerHttpTest` and the
clause tests under `scheduling/clauses`. There is no `@Disabled` test: every behaviour the clause
describes and the code implements is proven through a normal HTTP entry point, and the absent parts
below are infrastructure, not a behaviour that can be asserted over an existing port.

- `replacementLinksNewRevisionSupersedesPriorAndDispatchesNotification` — positive proof of the
  linked replacement and the supersede half. `POST /api/v1/patient-instructions` publishes revision 1;
  `POST /api/v1/patient-instructions/{threadId}/replace` with `If-Match: "1"` returns revision 2 with
  `replacesRevision = 1`. `GET /api/v1/patient-instructions/{threadId}` returns two history revisions:
  revision 1 is `SUPERSEDED` with `supersededBy = 2`, and revision 2 is current. The fact trail holds
  `REPLACED` on revision 2, `SUPERSEDED` on revision 1 and `DISPATCHED` addressed to the patient on
  revision 2 (a second dispatch fact; the send recorded the first). The patient list still returns one
  current revision.
- `withdrawalMarksRevisionWithdrawnAndRecordsWithdrawalFact` — positive proof of the withdrawal half.
  `POST /{threadId}/withdraw` with `If-Match: "1"` marks revision 1 `WITHDRAWN` with its reason and adds
  no new revision. The fact trail holds a `WITHDRAWN` fact addressed to the patient. Withdrawal
  notification is represented **solely** by that `WITHDRAWN` fact with a patient recipient: the service
  does **not** append a new `DISPATCHED` fact on withdrawal (the single `DISPATCHED` fact at revision 1
  was written by the original send), so the test asserts the dispatch count is unchanged. No claim of a
  separate withdrawal dispatch fact is made.
- `staleVersionRefusesReplacementAndWritesNoRevision` — negative proof (stale version). `If-Match: "9"`
  is refused with HTTP 412 `STALE_VERSION`; history stays at one `SENT` revision, no `REPLACED` fact is
  written and the current revision is unchanged.
- `clinicalAssistantCannotReplaceFreeTextButCanWithdraw` — negative proof (wrong clinical role). A
  `clinical_assistant` passes the `WITHDRAW` action but lacks `CLINICAL_SEND`, so a free-text
  replacement is refused with HTTP 403 `FORBIDDEN` and writes nothing; withdrawal by the same actor
  still succeeds.
- `withdrawnRevisionCannotBeReplaced` — negative proof (forbidden state). After withdrawal the
  `WITHDRAWN` state cannot transition to `SUPERSEDED`, so a replacement is refused with HTTP 409
  `INVALID_TRANSITION` and history stays at one revision.
- `peerTenantCannotSeeOrMutateThread` — negative proof (another tenant). An actor in the peer
  organization with the same local connection, patient and unit identifiers, and with a `clinician`
  role seeded inside that tenant (a superset carrying both `READ` and `WITHDRAW`), gets HTTP 404 on
  read, replace and withdraw; the owning tenant's thread is unchanged.
- `missingIfMatchRefusesWithdrawalAndWritesNoChange` — negative proof (missing precondition). No
  `If-Match` header is refused with HTTP 428 `PRECONDITION_REQUIRED` and leaves the revision `SENT`.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.messaging.instructions.InstructionController` — thin adapter publishing
  `POST /api/v1/patient-instructions` and `POST /api/v1/patient-instructions/{threadId}/replace|withdraw`
  with `If-Match` parsing at the boundary, plus the read routes used to assert the stored result.
- `com.aurevia.smartclinic.messaging.instructions.InstructionService` — `send`, `replace` and `withdraw`
  re-derive roles and unit assignment, check the version precondition and drive the revision state
  machine; `replace` links a new revision, appends the notification facts and calls `supersede`.
- `com.aurevia.smartclinic.messaging.instructions.InstructionContract` — `State.canTransitionTo`
  (`SENT` to `SUPERSEDED` or `WITHDRAWN`), `FactType` (`REPLACED`, `SUPERSEDED`, `WITHDRAWN`,
  `DISPATCHED`) and the immutable `Revision`, `Fact` and `View` records.
- `com.aurevia.smartclinic.messaging.instructions.InstructionActions` — the bounded `WITHDRAW` and
  `CLINICAL_SEND` matrix that admits the withdrawal but refuses a non-clinician replacement.
- `com.aurevia.smartclinic.messaging.instructions.InstructionStore.InMemory` — tenant-keyed synthetic
  adapter for `supersede`, `withdraw`, `save` and `append` (see Gap for its durability status).
- `com.aurevia.smartclinic.api.IfMatch` and `ApiErrorHandler` — precondition parsing and the uniform
  error codes asserted by the negative tests; `web.TenantContextInterceptor` — synthetic identity and
  active-organization binding.

Adjacent production found in the survey but not part of this clause's proof: `V63__instruction_facts.sql`
creates the MSG-05 fact tables (dispatch, acknowledgment and follow-up) but no instruction
revision/template table, and the only `PushGateway` in `src/main` belongs to `messaging/appointments`
and is not wired to instructions. The unit-level `messaging/instructions/InstructionServiceTest` and
`InstructionControllerTest` already cover the service and adapter branches; this task adds the
clause-focused HTTP proof of the change-after-send guarantee.

## Gap

The rule (linked replacement/withdrawal, prior revision superseded, patient-notification fact) is
built and proven over HTTP. The durable, published and delivered parts are missing:

1. **Durable instruction store and migration.** `InstructionStore` has only the in-memory adapter in
   `src/main`; there is no `JdbcInstructionStore`, and no Flyway migration creates an instruction
   revision/template table with `ENABLE` and `FORCE ROW LEVEL SECURITY` and tenant policies
   (`V63__instruction_facts.sql` is the separate MSG-05 fact store). Files that would change: add a
   `JdbcInstructionStore` behind `InstructionStore`, move `InMemory` to `src/test`, add a Flyway
   migration for revisions/templates with RLS/FORCE RLS, and an HTTP integration test against real
   PostgreSQL proving the supersede link survives a restart. One cross-boundary backend/database task.
2. **Real patient notification.** Notification is only a `FactType.DISPATCHED` row; `FactType.DISPATCHED`
   itself says delivery is a separate fact and no delivery fact or push port exists. `withdraw` records
   no dispatch fact at all. Files that would change: an instruction push port and generic-dispatch
   adapter, wiring in `InstructionService.replace` and `withdraw`, and a delivery fact in the MSG-05
   store; the disabled-free proof above would then be extended. One backend/integration task.
3. **Published API contract.** The `/api/v1/patient-instructions`, `/templates`, `/{}/replace` and
   `/{}/withdraw` routes are absent from `contracts/openapi.yaml` and listed in
   `contracts/openapi/missing-routes.txt` (lines 70–71 and 257–260). Files that would change: add the
   operations with tags, `Idempotency-Key` on POST and route-parity entries to `contracts/openapi.yaml`,
   then regenerate the staff-web generated client with `gen:api`. One contract/client task.

A further pre-existing observation (not a new ask of this evidence task): `InstructionStore.InMemory`
currently lives in `src/main`, whereas AGENTS.md expects `InMemory*` fakes in `src/test`.

This evidence task added only the test class and this note. It changed no production code, no existing
test, no migration and no contract.
