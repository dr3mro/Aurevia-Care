# IAM-03 clause 3 — Registration collects names, date of birth or a coded unknown reason, contacts, language, identifiers with issuer and verification state, and applicable consent records

## Clause

Registration collects names, date of birth or a coded unknown reason, contacts, language, identifiers with issuer and verification state, and applicable consent records.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.RegistrationCollectsNamesDateBirthCodedUnknownTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/RegistrationCollectsNamesDateBirthCodedUnknownTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry point `POST /api/v1/patients` and reads the record back with
`GET /api/v1/patients/{id}` against two synthetic organizations with forced row-level
security. All inputs are synthetic and use the reserved `.test` domain.

- `registrationCollectsDateOfBirthNamesContactsLanguageIdentifiersAndConsents` — a
  civil date of birth, original and normalized names, issuer-scoped MRN, language,
  contacts, identifiers carrying issuer and verification state, and consent records
  are accepted with HTTP 201, round-trip through the JSON text columns, and survive
  a later read through the controller.
- `registrationCollectsCodedUnknownReasonWhenDateOfBirthIsUnavailable` — when the
  date of birth is absent, a coded `not_disclosed` reason is accepted alongside the
  other collected fields and read back unchanged.
- `registrationRefusesMissingDateOfBirthAndMissingCodedReason` (negative) — a
  registration with neither a date of birth nor a coded reason is refused with 422.
- `registrationRefusesDateOfBirthTogetherWithCodedReason` (negative) — a date of
  birth and a coded reason are mutually exclusive; sending both is refused with 422.
- `registrationRefusesMalformedCodedReason` (negative) — an ill-formed coded reason
  (`BAD-REASON!`) is refused with 422.
- `registrationByRoleWithoutPatientPermissionIsDenied` (negative) — a synthetic
  subject whose only role is `privacy_auditor` is refused with 403 because it lacks
  the `PATIENT_REGISTER` action.
- `collectedFieldsAreNotVisibleFromAnotherTenant` (negative) — a patient registered
  in organization A is not visible to a reception subject of organization B (404);
  collected fields do not cross the tenant boundary.

Production classes and files exercised by the request path:

- `com.aurevia.smartclinic.web.PatientController` — thin HTTP adapter binding
  `RegisterRequest` and mapping the service result to HTTP 201.
- `com.aurevia.smartclinic.patient.PatientService` — enforces the mutually-exclusive
  date-of-birth / coded-reason rule, normalizes names for search only, and applies
  the action policy before any write.
- `com.aurevia.smartclinic.patient.PatientRepository` — the single SQL boundary,
  writing `contacts`, `identifiers` and `consents` as JSON text inside the
  tenant-guarded transaction and reading them back through the effective-version fold.
- `com.aurevia.smartclinic.patient.Patient` and `com.aurevia.smartclinic.patient.PatientView`
  — effective projection carrying every collected field.
- `db/migrations/V7__patient_registration.sql` — `patient_record` with the
  `contacts`, `identifiers` and `consents` jsonb columns, the DOB/reason exclusivity
  check, forced row-level security, append-only triggers and tenant grants.
- `contracts/openapi.yaml` — the `POST /api/v1/patients` entry is
  baselined here; the route is reachable over HTTP but not yet described in
  `contracts/openapi.yaml` (see Findings in the worker notes).

## Gap

None.
