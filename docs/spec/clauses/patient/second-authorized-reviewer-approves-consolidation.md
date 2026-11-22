# IAM-04 clause 3 — API test evidence

## Clause

A second authorized reviewer approves consolidation.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.SecondAuthorizedReviewerApprovesConsolidationTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/SecondAuthorizedReviewerApprovesConsolidationTest.java`).
All four tests are active (none are disabled) and use synthetic fixtures with a mocked
repository boundary.

- `secondAuthorizedReviewerApprovesAndBothReviewersAreRecorded` — positive proof. A
  different subject with the `privacy_auditor` reviewer role approves a ticket opened by
  another reviewer; the event is appended with `approverSubject` set to the approver and
  `openedBySubject` still holding the original opener, the pair is locked, and the audit
  outcome is `SUCCESS` with no `DENIED` event.
- `reviewerWhoOpenedTheTicketCannotApproveIt` — negative proof of the *second* reviewer
  rule. Even an authorized `tenant_admin` who opened the ticket cannot approve it: HTTP
  409 `SECOND_REVIEWER_REQUIRED`, a `DENIED` audit event, no pair lock and no association
  or consolidation appended.
- `unauthorizedRoleCannotApproveAnotherReviewersTicket` — negative proof of the
  *authorized* reviewer rule. A `reception` role with active membership is refused with
  HTTP 403 `FORBIDDEN` before the ticket is even read, and nothing is appended.
- `subjectWithoutActiveMembershipCannotApprove` — negative proof that authority is
  re-derived per request: a subject with no active membership is refused with HTTP 403
  `FORBIDDEN` and a `DENIED` audit event.

Production classes exercised through the normal service entry point
`ConsolidationService.consolidate`:

- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService` — re-authorizes
  the approver on every call, refuses an approver equal to `openedBySubject` with
  `SECOND_REVIEWER_REQUIRED`, locks both records, re-checks current state inside the lock
  and appends the association, event and audit in one transaction.
- `com.aurevia.smartclinic.patient.consolidation.DuplicatePolicy` — bounds the approval
  action to the `tenant_admin` and `privacy_auditor` reviewer roles; `reception` is denied.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateReview` and
  `com.aurevia.smartclinic.patient.consolidation.Consolidation` — carry the opener and
  approver as distinct provenance fields on the ticket and the appended event.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateRepository` — the SQL boundary
  for membership/role folds, the pair lock and the append-only event inserts.

Source survey (unchanged by this task): the implementation lives in the
`com.aurevia.smartclinic.patient.consolidation` package (`ConsolidationService`,
`ConsolidationController`, `DuplicateRepository`, `DuplicatePolicy`, `DuplicateReview`,
`ReviewDetail`, `Consolidation`); the controller under
`apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ConsolidationController.java`
delegates to the injected service; the tenant-owned tables and forced-RLS policies for
reviews, associations, dependencies and consolidations are in the Flyway migrations under
`db/migrations` (the `V9` duplicate-review schema and its later append-only version folds);
the approval command and its request body are documented in `contracts/openapi.yaml` and
exercised at HTTP level by `it/patientidentity/ConsolidationIsolationTest`. Neighbouring
unit coverage is in `it/patientidentity/ConsolidationServiceTest`.

## Gap

None.

The second-authorized-reviewer rule is built and proven by active tests. This task added
proof only; it did not change production code, an existing test, the contract or a
migration. The HTTP-level and real-PostgreSQL acceptance evidence belongs to the existing
consolidation integration suite and the integrator's database gate.
