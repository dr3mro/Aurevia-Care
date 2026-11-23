# RX-01 clause 5 — keep override reason, author and matched data

## Clause

Keep override reason, author and matched data.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.prescription.clauses.KeepOverrideReasonAuthorMatchedDataTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/KeepOverrideReasonAuthorMatchedDataTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from the
production Flyway migrations (`filesystem:../../db/migrations`) and dropped in
`@AfterAll`, with two synthetic tenants sharing local identifiers and one synthetic
patient per tenant:

- `documentedOverrideKeepsReasonAuthorAndMatchedSnapshot` — a verified exact allergy
  conflict is documented with a reason, then `GET /api/v1/prescription-drafts/{id}`
  returns `/override/reason`, `/override/authorSubject` equal to the authenticated
  synthetic prescriber and `/override/matchedSnapshot` containing
  `catalog=<entry>:1;allergy=verified_list;outcome=conflict;findings=exact_match:AMOX-SUB:BETA_LACTAM`.
  A JDBC read of `public.prescription_override` confirms the same reason, author,
  snapshot and the overridden draft version. The selection request also carries an
  extra `authorSubject` field: it is not a component of `SelectMedicationRequest`, so
  Spring's default Jackson configuration silently ignores it. The observable proof
  is that both the response and the persisted row carry the authenticated synthetic
  prescriber's subject rather than the injected value; the V43
  `set_rx_override_author_from_actor` trigger derives authorship from `app.actor`.
- `verifiedConflictWithoutReasonIsRefused` — a verified conflict without a reason is
  refused (409, `ALLERGY_OVERRIDE_REQUIRED`) and no override row is written.
- `blankAndOverlongReasonsAreRefused` — a blank reason maps to `null` in
  `PrescriptionInputs.optionalText` and is refused by the conflict rule (409,
  `ALLERGY_OVERRIDE_REQUIRED`); a reason over 1000 characters is refused by the
  boundary (422, `INVALID_OVERRIDEREASON`). Both leave no override row. This records
  the observed behavior: blank reasons are refused by the domain conflict rule, not
  by `OverrideRecord`'s contract validation.
- `staleVersionCannotAttachAnOverride` — an `If-Match` version that does not match
  the locked draft is refused (412, `STALE_VERSION`) before any override is appended.
- `peerTenantCannotReadTheOverride` — a clinician of the peer tenant gets 404 for the
  draft, and a JDBC query under the peer tenant sees zero override rows: V43 forced
  RLS confines the immutable record to its organization.
- `overrideRowIsAppendOnly` — direct `UPDATE` and `DELETE` on the override row are
  rejected with `append-only` by the V43 immutability triggers; the reason survives
  unchanged in both the HTTP view and the persisted row.

Production classes exercised through `POST /api/v1/prescription-drafts/{id}/allergy-check`,
`POST /api/v1/prescription-drafts/{id}/select-medication` and
`GET /api/v1/prescription-drafts/{id}`:
`com.aurevia.smartclinic.prescription.PrescriptionController` (thin HTTP adapter,
`If-Match` boundary), `com.aurevia.smartclinic.prescription.PrescriptionService`
(`selectMedication`, the private `snapshot` and the stale-version guard),
`com.aurevia.smartclinic.prescription.PrescriptionInputs` (`optionalText`),
`com.aurevia.smartclinic.prescription.AllergyChecker` (retained, structured
findings), `com.aurevia.smartclinic.prescription.PrescriptionRepository`
(`insertOverride`, `findOverride`) and
`com.aurevia.smartclinic.prescription.PrescriptionContract` (`OverrideRecord`,
`DraftView`, `SelectMedicationRequest`). The RLS/FORCE RLS policy, the author trigger
and the append-only triggers come from `db/migrations/V43__prescription_drafts.sql`.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.it.prescription.PrescriptionFlowTest`
(`fullWorkflowKeepsValidationOverrideAndAuditSeparate` asserts the same reason, author
and snapshot through the same entry points; `immutableLedgerAndDatabaseVersionGuardHold`
asserts the same append-only behavior).

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security
-Dtest=KeepOverrideReasonAuthorMatchedDataTest test` — `tests="6" errors="0" skipped="0"
failures="0"` in the Surefire XML report. "Proven by tests" is limited to the clause
behavior above; it does not claim that these routes are published in the committed
OpenAPI contract.

## Gap

None for the clause behavior. Out of scope and pre-existing: the committed OpenAPI
contract has no `/api/v1/prescription-drafts` paths or override fields
(`contracts/openapi.yaml` lists the create, allergy-check,
select-medication and read routes). Publishing them would change
`contracts/openapi.yaml`, the generated client and the contract tests; that debt is
tracked by owner task `FIX-OPENAPI-02` (WEB-05), one separate contract-publication
task, and no contract file is changed here.
