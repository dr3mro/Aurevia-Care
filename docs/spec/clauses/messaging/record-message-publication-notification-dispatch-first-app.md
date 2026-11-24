# MSG-05 clause 1 — record publication, dispatch, first view and requested acknowledgment as separate facts

## Clause

Record message publication, notification dispatch, first in-app view and, when requested, explicit patient acknowledgment as separate facts.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.messaging.clauses.RecordMessagePublicationNotificationDispatchFirstAppTest`
([source](../../../../apps/api/src/test/java/com/aurevia/messaging/clauses/RecordMessagePublicationNotificationDispatchFirstAppTest.java)),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with two synthetically seeded tenants that use
identical local identifiers and with the real `JdbcInstructionFactStore` bean
behind the real controller. Every request goes through `RestClient` with synthetic
subject and organization headers, not through a standalone mock MVC setup:

- `publicationDispatchFirstViewAndAcknowledgmentAreFourSeparateOrderedFacts`
  — publishes one revision with a generic notification and a requested
  acknowledgment, records the first in-app view and then the receipt; the
  `GET /api/v1/instructions/{id}/facts` timeline has four distinct, ordered
  facts bound to revision 1: a publication by the publishing staff actor, a
  dispatch whose only content is `genericKey = NEW_INSTRUCTION`, a first view by
  the patient, and an acknowledgment with `scope = RECEIPT`. The opaque content
  reference does not appear anywhere in the fact response. Four separate
  `instruction_fact_publication`, `instruction_fact_dispatch`,
  `instruction_fact_first_view` and `instruction_fact_acknowledgment` rows are
  asserted in the database.
- `dispatchRetryRecordsAnotherSeparateGenericFactOverHttp` — with publication
  sending no notification, `POST /api/v1/instructions/dispatch/retry` appends a
  generic dispatch fact with no clinical text; a second retry with a fresh
  idempotency key appends another, so dispatch is an append-only fact distinct
  from publication and from the notification that came before it.
- `repeatedFirstViewDoesNotCreateAnAcknowledgment` — a second first view with a
  different client key returns the original fact; exactly one first-view row and
  no acknowledgment row are written.
- `receptionStaffCannotPublishAnInstruction` — an active `reception` member has
  no publish permission (403 `FORBIDDEN`) and no instruction row is written.
- `otherTenantCannotReadFactsAndSeesNotFound` — a staff actor of the other
  tenant receives 404 `NOT_FOUND` for the same instruction id and sees neither
  the patient identifier nor the content reference; the owning tenant still sees
  its own publication and dispatch.
- `acknowledgmentIsRefusedWhenNotRequested` — acknowledging a revision that did
  not request one is refused (422 `ACKNOWLEDGMENT_NOT_REQUESTED`) with no
  acknowledgment row.
- `acknowledgingAnOlderRevisionIsRefused` — with revisions 1 and 2 published,
  acknowledging revision 1 is refused (409 `STALE_REVISION`) and writes no
  acknowledgment; revision 2 can then be acknowledged as a receipt.

Production classes exercised through `POST /api/v1/instructions/publish`,
`POST /api/v1/instructions/dispatch/retry`, `POST /api/v1/instructions/first-view`,
`POST /api/v1/instructions/acknowledge` and `GET /api/v1/instructions/{id}/facts`:
[InstructionFactController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFactController.java)
(thin HTTP adapter),
[InstructionFactService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFactService.java)
(authorization, fact separation, exact-revision binding, idempotency and overdue
rules),
[InstructionFacts.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFacts.java)
(the four immutable record types and the `RECEIPT` scope),
[InstructionFactActions.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFactActions.java)
(patient actions separated from staff actions),
[InstructionFactInputs.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFactInputs.java)
(boundary validation that accepts no clinical text),
[InstructionFactStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/InstructionFactStore.java)
and
[JdbcInstructionFactStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/acknowledgment/JdbcInstructionFactStore.java)
(the tenant-scoped store and its PostgreSQL adapter), plus the nine
`instruction_fact_*` tables with RLS and FORCE RLS supplied by
[V63__instruction_facts.sql](../../../../db/migrations/V63__instruction_facts.sql).
The tenant boundary is the real `TenantContextInterceptor` and the real V4/V10/V60
authoritative folds, seeded with synthetic identities, charts and connections.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.messaging.acknowledgment.InstructionFactControllerHttpTest`
(`publicationFirstViewAndAcknowledgmentAreSeparateFactsOverHttp` proves the same
four-fact timeline through MockMvc with the in-memory store),
`com.aurevia.messaging.acknowledgment.it.JdbcInstructionFactStoreIntegrationTest`
(the append-only and tenant-scoped store behaviour on PostgreSQL) and
`com.aurevia.messaging.acknowledgment.InstructionFactServiceTest`.

Discovery also found a related but distinct publication/dispatch trail in the
MSG-04 instruction workflow:
`com.aurevia.messaging.instructions.InstructionService` appends
`SENT`, `DISPATCHED`, `REPLACED`, `SUPERSEDED` and `WITHDRAWN` facts to
`com.aurevia.messaging.instructions.InstructionStore`, exposed by
`InstructionController` on `GET /api/v1/patient-instructions/{threadId}` and typed
by `InstructionContract.FactType`. That trail has no first-view or
acknowledgment fact and its in-memory store is not durable, so it is not the
durable four-fact store this clause is about; it is exercised by its own suite.

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=RecordMessagePublicationNotificationDispatchFirstAppTest test`
— `tests="7" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that these routes are published in the committed OpenAPI contract.

## Gap

The committed OpenAPI contract has no `/api/v1/instructions/publish`,
`/dispatch/retry`, `/first-view`, `/acknowledge` or `/{id}/facts` operations
(`contracts/openapi.yaml`, lines 51–52 and 223–227). Publishing
them would change `contracts/openapi.yaml`, the generated client and the contract
tests — one separate ARC-04/WEB-05 contract task. No production code, migration,
existing test or contract file was changed by this story, so there is no other
gap in the clause behaviour itself.
