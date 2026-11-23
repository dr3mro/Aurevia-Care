# IAM-04 clause 1 — API test evidence

## Clause

Provide a same-tenant duplicate review worklist.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.ProvideSameTenantDuplicateReviewWorklistTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/ProvideSameTenantDuplicateReviewWorklistTest.java`).
All four tests are active (none disabled) and use synthetic fixtures only.

- `worklistListsSameTenantPairsForAnAuthorizedReviewer` — positive proof. A reviewer with
  active membership and the `privacy_auditor` role receives exactly the candidate pairs
  returned by the tenant-bounded repository, and the list attempt is audited `SUCCESS`.
- `worklistIsEmptyWhenTheTenantHasNoCandidatePairs` — boundary proof. An empty tenant-bounded
  result yields an empty worklist with a `SUCCESS` audit, so the endpoint never fabricates
  candidates.
- `worklistRefusesASubjectWithoutActiveMembership` — negative proof. A subject with no active
  membership gets HTTP 403 `FORBIDDEN`, the repository is never queried and the denial is
  audited `DENIED`.
- `worklistRefusesAnActiveRoleWithoutReviewPermission` — negative proof. An active `reception`
  subject lacks the review permission, gets HTTP 403 `FORBIDDEN`, never queries the repository
  and is audited `DENIED`.

The tenant boundary itself is enforced in the repository SQL, not in the service: the
`DuplicateRepository.likelyPairs()` query joins candidates only inside one `organization_id`
and reads both sides from the row-level-security fold `public.current_patients()`. That
cross-tenant behaviour is proven end to end (HTTP + real PostgreSQL, forced RLS) by the
neighbouring integration test
`com.aurevia.smartclinic.it.patientidentity.ConsolidationIsolationTest.worklistIsSameTenantOnly`,
which shows a peer tenant cannot see another tenant's pair. This task did not modify that test.

Production classes exercised through the normal entry point
(`GET /api/v1/duplicate-reviews` → service → repository):

- `com.aurevia.smartclinic.patient.consolidation.ConsolidationController` — thin HTTP adapter;
  `worklist()` binds the list request and delegates to the service.
- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService` — `worklist()` opens the
  tenant-guarded transaction, authorizes active membership plus the review action, returns the
  bounded pairs and appends the audit event.
- `com.aurevia.smartclinic.patient.consolidation.DuplicatePolicy` — bounded review role set
  (`tenant_admin`, `privacy_auditor`); `reception` is refused.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateRepository` — `likelyPairs()` is the
  single SQL boundary; `DuplicatePair` is the worklist row type.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateReview` — the review ticket type used
  by the wider worklist flow.
- `db/migrations/V9__duplicate_review_consolidation.sql` — creates the review, association,
  dependency and consolidation tables with forced RLS and append-only folds that back the
  worklist.

## Gap

The clause behaviour is built and proven; no implementation gap for the worklist rule itself.

One documentation debt remains outside this task's scope: the five
`/api/v1/duplicate-reviews` routes are listed in `contracts/openapi.yaml` rather
than declared in `contracts/openapi.yaml`, so the worklist is not yet part of the published
OpenAPI contract or its generated staff-web client. Closing that needs one separate,
cross-boundary task: add the operations and schemas to `contracts/openapi.yaml` and regenerate
`apps/staff-web/src/api/generated/openapi.d.ts`, then remove the five baseline entries. That
task is contract work (WEB-05/ARC-04) and is explicitly outside this clause task's scope; no
contract or migration was changed here.

Also observed but not changed (pre-existing on the base branch, unrelated to this clause):
committed conflict-marker lines remain inside Javadoc in
`apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
`Hl7Parser.java` and `Hl7Segment.java` (`exchange.hl7`). They are comment text and do not
break compilation, but a separate owner-owned change should remove them.
