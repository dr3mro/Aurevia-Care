# ARC-01 clause 3 — MRN uniqueness is issuer scoped

## Clause

MRN uniqueness is issuer scoped.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.tenant.clauses.MrnUniquenessIssuerScopedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/tenant/clauses/MrnUniquenessIssuerScopedTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants, following the fixtures and database setup of
`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/RecordPatientrecordIssuerScopedMrnOrganizationTest.java`
(the IAM-03 clause 1 proof, which exercises the same rule). A dedicated database
(`smartclinic_arc01_clause3_it`) is migrated from `db/migrations` so the durable
rows are read back under forced row-level security.

Test methods (one positive rule proof and two negative refusals):

- `mrnUniquenessIsScopedToItsIssuer` — an authenticated `reception` user registers
  an MRN with one issuer through `POST /api/v1/patients` (201), the same MRN with
  the same issuer is refused with 409 and the `CONFLICT` / `MRN already exists for
  this issuer` error body, and the same MRN with a different issuer is accepted
  (201) and has its own durable row. This is the assertion that fails if the
  issuer is dropped from the uniqueness rule or from the service's existence check.
- `peerTenantMayReuseMrnButCannotReadTheFirstRecord` — a peer organization reuses
  the same issuer-scoped MRN (201), both organizations hold one durable row, and
  the first organization's patient is concealed from the peer (404), after a
  positive-control read (200) proves the identifier is real. This fails if forced
  row-level security is removed.
- `roleWithoutRegistrationPermissionCannotUseIssuerScopedMrn` — a `privacy_auditor`
  is refused (403) when attempting to register an issuer-scoped MRN. This
  is a generic authorization refusal, not issuer-scope evidence.

Production classes the test exercises:

- HTTP entry point: `com.aurevia.smartclinic.web.PatientController`
  (`POST /api/v1/patients`, `GET /api/v1/patients/{id}`), which injects the service.
- Domain service and boundary: `com.aurevia.smartclinic.patient.PatientService`
  (`register` returns 409 "MRN already exists for this issuer" through its
  `mrnExists` pre-check and the `DuplicateKeyException` fallback),
  `com.aurevia.smartclinic.patient.PatientRepository`,
  `com.aurevia.smartclinic.patient.Patient`,
  `com.aurevia.smartclinic.patient.PatientView`,
  `com.aurevia.smartclinic.authz.ActionPolicy`.
- Tenant scope: `com.aurevia.smartclinic.tenant.TenantContext`,
  `com.aurevia.smartclinic.tenant.TenantGuardTransactionManager`,
  `com.aurevia.smartclinic.web.TenantContextInterceptor`.
- Schema: `db/migrations/V7__patient_registration.sql` defines `patient_record`
  with `UNIQUE (organization_id, mrn_issuer, mrn, version)` plus enabled and
  forced row-level security and the `tenant_isolation` policy.
- Contract: `contracts/openapi/generated/openapi.yaml` already declares the
  `/api/v1/patients` `registerPatient` operation and the `X-Active-Organization`
  header; this story adds no route and does not regenerate the client.

## Gap

None
