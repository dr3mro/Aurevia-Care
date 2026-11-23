# XCH-02 clause 2 — acknowledgment by an authorized human with timestamp and revision

## Clause

Acknowledgment is made by an authorized human with timestamp and revision.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with a frozen clock and synthetic members and
tasks. Every case enters through
`POST /api/v1/exchange-tasks/{id}/acknowledge` with an `If-Match` version and the
`X-Synthetic-Subject` / `X-Active-Organization` headers.

- `acknowledgeByClinicianBindsHumanClockAndReviewedRevision` — a clinician's
  acknowledgment returns 200 and the response, the persisted task row and the
  append-only transition all carry the same evidence: `reviewedBy` is the
  authenticated clinician's `user_identity.id` (the subject string is never
  stored as the actor), `reviewedAt` is the injected fixed clock instant,
  `reviewedRevision` is the exact version carried by `If-Match` (1), and
  `clinicalReviewState` becomes `acknowledged`. The transition is recorded on the
  `clinical_review` channel as `EXCHANGE_ACKNOWLEDGE` with `reviewed_revision = 1`
  and the same derived actor; the workflow and transmission channels stay
  unchanged. A single `EXCHANGE_ACKNOWLEDGE` / `SUCCESS` audit row is recorded
  for the task.
- `receptionCannotAcknowledge` — a `reception` actor is refused (403) and the row
  stays `unreviewed` with no actor, timestamp or revision.
- `labCollectorCannotAcknowledge` — a `lab_collector` actor, who may record
  delivery receipts but not clinical review, is refused (403) and the row is
  unchanged.
- `otherTenantClinicianCannotAcknowledge` — a clinician active in another tenant
  cannot acknowledge a task owned by the first tenant (404, row concealed by
  forced RLS); the row is unchanged.
- `staleRevisionCannotAcknowledge` — a stale `If-Match` version is refused (412)
  and the row is unchanged.
- `missingIfMatchCannotAcknowledge` — an absent `If-Match` precondition is
  refused (428) and the row is unchanged.
- `requestBodyCannotSpoofReviewEvidence` — extra body fields claiming an actor,
  timestamp, revision and review state are ignored; the persisted evidence stays
  the authenticated clinician, the clock instant and the preconditioned version.

Production classes exercised through that route:
`com.aurevia.smartclinic.exchange.task.ExchangeTaskController` (thin HTTP
adapter, no actor or time in the wire contract),
`com.aurevia.smartclinic.exchange.task.ExchangeTaskService` (re-reads membership
and roles per request, binds the reviewed revision to the current version,
writes audit, refuses the negative cases),
`com.aurevia.smartclinic.exchange.task.ExchangeCommand`,
`com.aurevia.smartclinic.exchange.task.ExchangeStateMachine` (actor intentionally
absent from the clinical snapshot),
`com.aurevia.smartclinic.exchange.task.ExchangeActionPolicy` (clinical review is
clinician-only), `com.aurevia.smartclinic.exchange.task.ExchangeTask`,
`com.aurevia.smartclinic.exchange.task.ExchangeTaskRepository` (compare-and-set
that omits the actor column), `com.aurevia.smartclinic.api.IfMatch`, and
`db/migrations/V20__exchange_task_states.sql` (RLS and FORCE RLS on
`exchange_task` and its transition ledger, the guard trigger that requires a
revision and timestamp for a clinical change and derives `clinical_review_actor_id`
from the authenticated subject, and the transition actor trigger).

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest test`
— `tests="7" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the acknowledge route is published in the committed OpenAPI contract.

## Gap

Clause behaviour is fully covered. Out of scope and pre-existing: the committed
OpenAPI contract does not list `POST /api/v1/exchange-tasks/{}/{}` (the
acknowledge command route); it is recorded in
`contracts/openapi/missing-routes.txt:205`. Publishing that route, its tag,
`Idempotency-Key` header and the regenerated client would change
`contracts/openapi.yaml`, the generated client and the contract tests — one
separate contract task. No production, migration or contract file was changed by
this evidence story.
