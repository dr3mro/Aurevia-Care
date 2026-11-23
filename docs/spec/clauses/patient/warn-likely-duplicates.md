# IAM-03 clause 4 — API test evidence

## Clause

Warn on likely duplicates.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.WarnLikelyDuplicatesTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/WarnLikelyDuplicatesTest.java`).
All five tests are active (none disabled) and pass against a real PostgreSQL database with
synthetic fixtures only. The suite recreates its own database from the authoritative Flyway
migrations, seeds two tenants with bounded roles, and drives the warning through the normal
HTTP entry point `GET /api/v1/patients/{id}` using the `synthetic-auth` profile.

- `warnsWhenAnotherPatientSharesNormalizedNameAndDob` — positive proof. Two patients whose
  names differ only by diacritics and repeated spaces but share a civil date of birth are
  registered in one tenant. Reading the second returns the first as the single likely-duplicate
  candidate (its MRN and original spelling are present), while the returned patient keeps its
  own MRN and no link is created.
- `doesNotWarnAboutAPeerTenantsDuplicate` — negative proof of tenant isolation. A matching
  name and date of birth in the peer tenant is not returned as a candidate and its MRN does not
  appear in the warning, so forced row-level security bounds the rule to the active organization.
- `doesNotWarnWhenDateOfBirthIsUnknown` — negative proof of the missing-input path. Two patients
  with a coded unknown date of birth and the same name produce no candidates; the rule is not
  applied when the date needed for the match is absent.
- `doesNotWarnWhenNameOrDateOfBirthDiffers` — negative proof of the match condition. A different
  name with the same date of birth, and the same name with a different date of birth, each
  produce no candidates.
- `deniesDuplicateWarningToRoleWithoutPatientRead` — negative proof of authorization. A
  `privacy_auditor` with active membership but no `PATIENT_READ` permission is refused with
  HTTP 403 and receives no candidate data.

Production classes exercised through the entry point and its transaction:

- `com.aurevia.smartclinic.web.PatientController` — thin adapter binding `GET /api/v1/patients/{id}`
  to the service and returning the view.
- `com.aurevia.smartclinic.patient.PatientService` — `get` authorizes the read, loads the patient
  and computes candidates from normalized name and civil date of birth; it never merges or links
  records, and it audits both success and denial.
- `com.aurevia.smartclinic.patient.PatientView` — immutable view carrying the patient and its
  frozen duplicate list.
- `com.aurevia.smartclinic.patient.PatientRepository` — `likelyDuplicates` reads effective patients
  through `public.current_patients()` under enforced tenant isolation; the underlying table and
  effective-version fold are defined in `db/migrations/V7__patient_registration.sql`.
- `com.aurevia.smartclinic.authz.ActionPolicy` — bounds `PATIENT_READ` and `PATIENT_REGISTER` to
  reception, which is why the auditor role is denied.

The existing unit suite `com.aurevia.smartclinic.patient.PatientServiceTest` and HTTP acceptance
suite `com.aurevia.smartclinic.it.PatientRegistrationTest` cover the same behaviour; this task
adds a focused, clause-only proof and changes neither.

## Gap

None for the warning rule: it is built and proven by active tests through the HTTP entry point.
This task added proof only; it did not change production code, an existing test, the OpenAPI
contract or a migration.

One adjacent, non-blocking debt is recorded for completeness: `GET /api/v1/patients/{id}` is
currently listed in `contracts/openapi.yaml` as a baselined route, so the view's
`duplicates` field is not yet published in the authoritative OpenAPI contract. Publishing that
route (and regenerating the staff client) is a separate cross-cutting contract task under WEB-05,
outside this clause's test-only scope and not required to prove the warning rule.
