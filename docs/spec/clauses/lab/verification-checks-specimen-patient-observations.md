# LAB-02 clause 3 — verification checks specimen, patient and observations

## Clause

Verification checks specimen, patient and observations.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.lab.clauses.VerificationChecksSpecimenPatientObservationsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/VerificationChecksSpecimenPatientObservationsTest.java`),
a MockMvc HTTP test under the `synthetic-auth` profile that drives the real
`POST /api/v1/lab/results/{id}` capture endpoint and `POST /api/v1/lab/results/{id}/verify`
endpoint with synthetic identifiers. The accession store, critical-result workflow, audit
appender and transaction boundary are mocked; the controller, the result-entry service and
the report kernel are real, so the verified revision's flags are genuinely re-derived from
approved reference ranges.

- `verificationChecksSpecimenPatientAndObservations` (rule met) — captures GLU and NA observations
  whose preliminary flags are both `none`, then verifies them as a different subject and asserts
  the issued revision is `final` at version 2, names the verifier/actor and the matched patient,
  specimen and order, and that both observation flags were re-evaluated (`low` and `high`) against
  the approved catalog ranges while the preserved preliminary revisions still carry `none`.
- `wrongPatientAtVerificationIsRefused` (negative) — a mismatched patient id is refused with
  HTTP 422 `PATIENT_ACCESSION_MISMATCH` and a `MISMATCH` audit outcome.
- `wrongAccessionOrIssuerAtVerificationIsRefused` (negative) — a wrong accession number and,
  separately, a wrong issuer are each refused with HTTP 422 `PATIENT_ACCESSION_MISMATCH`
  and a `MISMATCH` audit outcome.
- `unknownSpecimenAtVerificationIsConcealed` (negative) — an absent specimen is concealed as
  HTTP 404 `NOT_FOUND` with a `NOT_FOUND` audit outcome.
- `collectorCannotVerifyReport` (negative) — an active collector without the verifier role is
  refused HTTP 403 `FORBIDDEN` with a `DENIED` audit outcome.
- `entererCannotVerifyOwnReport` (negative) — a subject holding both roles can reach the
  segregation check, but the enterer is refused HTTP 422 `SELF_VERIFICATION` with a `CONFLICT`
  audit outcome.
- `staleVersionCannotVerifyReport` (negative) — an outdated expected version is refused HTTP
  412 `STALE_VERSION` and writes no verification audit row.
- `alreadyFinalReportCannotBeVerifiedAgain` (negative) — re-verifying an issued report is
  refused HTTP 409 `INVALID_STATE` with a `CONFLICT` audit outcome.
- `otherTenantCannotSeeTheReport` (negative) — a second active organization cannot read the
  report and receives HTTP 404 `NOT_FOUND`.

Production classes and methods exercised by these HTTP requests:

- `com.aurevia.smartclinic.labflow.results.LabResultEntryController` — the thin HTTP adapter
  for the capture, verify and read routes under `/api/v1/lab/results`.
- `com.aurevia.smartclinic.labflow.results.ResultEntryService` — `verify`/`decide`/`matches`:
  membership and role authorization, specimen lookup, patient/accession/issuer label match,
  version and state preconditions, segregation of duties, deferred-failure audit outcomes and
  the critical-result hand-off.
- `com.aurevia.smartclinic.lab.result.LabResultService` — `verify`, `requireMatch` and
  `evaluate`: the tenant-scoped append-only revision ledger, identifier match and the
  re-evaluation of every observation against approved effective configuration.
- `com.aurevia.smartclinic.lab.result.LabPolicyCatalog.ReferenceRange` — the approved,
  effective-dated range that changes the observation flag from `none` to `low` at verification.
- `com.aurevia.smartclinic.lab.result.LabRules` — the collector/verifier role boundary.
- `com.aurevia.smartclinic.labflow.results.ReportView` — the non-sensitive HTTP projection
  whose state, version, verifier, identifiers and observation flags are asserted.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` and
  `com.aurevia.smartclinic.api.ApiErrorHandler` — authenticate the synthetic subject, bind the
  active organization and render each refusal as the asserted status and `$.code`.

## Gap

None.
