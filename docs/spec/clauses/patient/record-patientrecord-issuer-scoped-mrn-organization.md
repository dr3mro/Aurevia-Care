# IAM-03 clause 1 — PatientRecord and issuer-scoped MRN per organization

## Clause

Record PatientRecord and issuer-scoped MRN per organization.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.patient.clauses.RecordPatientrecordIssuerScopedMrnOrganizationTest`
(`apps/api/src/test/java/com/aurevia/patient/clauses/RecordPatientrecordIssuerScopedMrnOrganizationTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants with identical local identifiers, following the
fixtures and database setup of the neighbouring `PatientRegistrationTest` and the
`authz/clauses` acceptance tests. A dedicated database (`aurevia_iam03_clause1_it`)
is migrated from `db/migrations` so the durable rows are read back under forced
row-level security.

Test methods (one positive rule proof, three negative refusals):

- `receptionRecordsIssuerScopedPatientRecordInActiveOrganization` — an authenticated
  `reception` user records a PatientRecord through `POST /api/v1/patients` with an
  MRN and its issuer; the HTTP response and the durable `patient_record` row carry
  the issuer-scoped MRN in the active organization, the original spelling is
  preserved, and the row is invisible from the peer organization. The record is
  read back through `GET /api/v1/patients/{id}` and again carries the MRN and issuer.
- `mrnIsScopedToItsIssuerSoAnotherIssuerMayReuseItButTheSameIssuerCannot` — the same
  MRN under a different issuer creates a second patient (201), while the same MRN
  under the same issuer is refused (409). This proves the MRN is issuer-scoped.
- `peerOrganizationReusesTheIssuerScopedMrnAndCannotReadTheFirstRecord` — a peer
  organization reuses the same issuer-scoped MRN (201), both organizations hold one
  durable row, and the first organization's patient is concealed from the peer (404).
  This proves the record is scoped per organization.
- `roleWithoutPatientPermissionCannotRecordPatientRecord` — a `privacy_auditor` is
  refused (403) when attempting to record a PatientRecord.

Production classes the test exercises:

- HTTP entry point: `com.aurevia.web.PatientController` (`POST /api/v1/patients`,
  `GET /api/v1/patients/{id}`).
- Domain service and boundary: `com.aurevia.patient.PatientService`,
  `com.aurevia.patient.PatientRepository`,
  `com.aurevia.patient.Patient`,
  `com.aurevia.patient.PatientView`,
  `com.aurevia.authz.ActionPolicy`.
- Tenant scope: `com.aurevia.tenant.TenantContext`,
  `com.aurevia.tenant.TenantGuardTransactionManager`,
  `com.aurevia.web.TenantContextInterceptor`.

Where the clause is implemented today (survey result):

- Schema: `db/migrations/V7__patient_registration.sql` defines `patient_record` with
  `UNIQUE (organization_id, mrn_issuer, mrn, version)`, an enabled and forced
  row-level security isolation policy, an append-only trigger, the database-owned
  `created_by` and version functions, and `current_patients()` for the effective fold.
- Domain: `apps/api/src/main/java/com/aurevia/patient` (`Patient`,
  `PatientRepository`, `PatientService`, `PatientView`) records the patient with an
  issuer-scoped MRN inside the active tenant and enforces registration authorization.
- Controller: `apps/api/src/main/java/com/aurevia/web/PatientController.java`.
- Contract: `contracts/openapi/generated/openapi.yaml` declares the `/api/v1/patients`
  `registerPatient` operation (action `PATIENT_REGISTER`) and the `X-Active-Organization`
  header.

## Gap

None
