# IAM-03 clause 5 — confirm two identifiers at high-risk transitions

## Clause

Confirm two identifiers at high-risk transitions when available, including collection and chart consolidation.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.patient.clauses.ConfirmIdentifiersHighRiskTransitionsAvailableIncludingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/ConfirmIdentifiersHighRiskTransitionsAvailableIncludingTest.java`),
an HTTP-level integration test on a fresh PostgreSQL database with synthetic tenant
data, using the same fixtures, headers and Flyway setup as the neighbouring clause
tests:

- `consolidationConfirmsTwoIssuerScopedIdentifiersAndRecordsTheBasis` — a second
  authorized reviewer confirms the two issuer-scoped MRNs of the pair; the
  consolidation succeeds (200), the duplicate chart resolves to the survivor and
  an audit row records `CONFIRMED:<mrn>@<issuer>;<mrn>@<issuer>`.
- `consolidationRefusesMissingOrMismatchedIdentifierConfirmation` — a request with
  neither two identifiers nor an approved process and a request with identifiers
  that do not match the two records are both refused (422); no association or
  consolidation row is appended and both refusals are audited as `DENIED`.
- `collectionConfirmsTwoIdentifiersWhenAvailable` is present but `@Disabled` with a
  `GAP:` reason: it would prove the collection half of this clause and would fail
  today because registration accepts a single MRN/issuer without confirmation.

Production classes exercised by the consolidation entry point:

- `com.aurevia.smartclinic.patient.consolidation.ConsolidationController`
  (`POST /api/v1/duplicate-reviews/{reviewId}/consolidate`, binding
  `ConsolidationApprovalRequest.confirmedIdentifiers` and `alternateProcess`);
- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService`
  (`consolidate`, `confirmationFailure`, `confirmationOutcome`), which checks the
  two distinct issuer-scoped identifiers against both locked patient records,
  accepts one bounded coded alternate process only when identifiers are absent,
  and audits the decision basis;
- `com.aurevia.smartclinic.patient.consolidation.DuplicatePolicy` (second-reviewer
  and role gate), `DuplicateRepository` (pair lock, append-only association and
  consolidation, RLS-bounded reads), `com.aurevia.smartclinic.patient.Patient`,
  `com.aurevia.smartclinic.tenant.TenantContext` and the Flyway migrations under
  `db/migrations` (forced row level security).

Supporting evidence already on the branch, not duplicated here:
`com.aurevia.smartclinic.it.patientidentity.ConsolidationServiceTest` covers every
branch of `confirmationFailure` and `confirmationOutcome` as unit tests, and
`com.aurevia.smartclinic.it.patientidentity.ConsolidationIsolationTest.identifierConfirmationIsEnforcedAgainstTheTwoRecords`
covers the same HTTP route including the documented `ALTERNATE:site_witness`
outcome. The route is currently baselined as missing from the OpenAPI contract in
`contracts/openapi.yaml`; no public contract entry proves it yet.

Survey result for the collection half: `com.aurevia.smartclinic.web.PatientController`
(`POST /api/v1/patients`) and `com.aurevia.smartclinic.patient.PatientService.register`
collect a name, one issuer-scoped MRN, an optional unknown-DOB reason, contacts,
identifiers and consents, and warn on likely duplicates, but never require two
confirmed identifiers and never record an alternate-process code. No other class
under `apps/api/src/main/java/com/aurevia/smartclinic/patient` or its neighbours
implements collection-time confirmation.

## Gap

The chart-consolidation transition is implemented and proven. The collection
transition is not built: patient registration does not confirm two identifiers
when available and does not record the approved alternate process when they are
unavailable. Closing that gap needs **one** bounded task: add a bounded
confirmation contract to `PatientController.RegisterRequest`, enforce it in
`PatientService.register` inside the tenant-guarded transaction, audit the basis,
add the failing-then-green tests (including the disabled test above) and document
the route in the OpenAPI contract. Files that task would change (none changed
here): `apps/api/src/main/java/com/aurevia/smartclinic/web/PatientController.java`,
`apps/api/src/main/java/com/aurevia/smartclinic/patient/PatientService.java`,
their tests under `apps/api/src/test`, `contracts/openapi.yaml` and the generated
client; no migration is needed because the existing patient tables already store
issuer-scoped identifiers and the audit event carries the outcome.
