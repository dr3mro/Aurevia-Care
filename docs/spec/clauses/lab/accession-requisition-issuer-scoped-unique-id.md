# LAB-01 clause 1 — Accession a requisition with an issuer-scoped unique ID

## Clause

Accession a requisition with an issuer-scoped unique ID.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.lab.clauses.AccessionRequisitionIssuerScopedUniqueIdTest`
(`apps/api/src/test/java/com/aurevia/lab/clauses/AccessionRequisitionIssuerScopedUniqueIdTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants that each register their own patient, following
the fixtures and database setup of the neighbouring `it/lab/SpecimenAccessionIsolationTest`
and the other `*/clauses` acceptance tests. A dedicated database
(`aurevia_lab01_clause1_it`) is dropped, recreated and migrated from
`filesystem:../../db/migrations` so the durable rows are read back under forced
row-level security with `app.current_org` set per tenant.

Test methods (one positive rule proof and four negative refusals):

- `labCollectorAccessionsRequisitionWithIssuerScopedNumber` — an authenticated
  `lab_collector` accessions a requisition through `POST /api/v1/lab/accessions` with
  an accession number and issuer; the response and the durable `lab_accession` row
  carry that issuer-scoped pair in the active organization, the row is invisible
  from the peer organization, and `GET /api/v1/lab/accessions/{id}` reads it back
  with the same number and issuer.
- `duplicateNumberForSameIssuerIsRefusedButAnotherIssuerMayReuseIt` — the same
  number under the same issuer is refused (409 `CONFLICT`) while the same number
  under a different issuer is accessioned (201), and both durable rows coexist.
  This proves the number is scoped to its issuer, not global.
- `peerTenantMayReuseNumberAndCannotReadFirstAccession` — a peer organization
  reuses the same issuer-scoped number for its own patient (201), both organizations
  hold one durable row, and the first organization's accession is concealed from
  the peer (404) while remaining readable in its own tenant (200). This proves the
  number is scoped per organization.
- `roleWithoutAccessionPermissionIsRefused` — a `reception` user and a `lab_verifier`
  are both refused (403 `FORBIDDEN`) when attempting to accession a requisition,
  proving the create action is bound to the collector duty.
- `malformedIssuerAndNumberAreRefused` — a malformed issuer (422 `INVALID_ISSUER`)
  and a malformed accession number (422 `INVALID_ACCESSION`) fail closed at the
  boundary before any durable row is created.

Production classes the test exercises:

- HTTP entry point: `com.aurevia.lab.accession.LabAccessionController`
  (`POST /api/v1/lab/accessions`, `GET /api/v1/lab/accessions/{id}`).
- Domain service: `com.aurevia.lab.accession.LabAccessionService`
  (`accession`, including the pre-check, duplicate-key race guard, role authorization
  and audit on each outcome).
- SQL boundary and policy: `com.aurevia.lab.accession.LabAccessionRepository`
  (`accessionExists`, `insert`, `findById`), `com.aurevia.lab.accession.LabActionPolicy`
  (`ACCESSION_CREATE` allows `lab_collector` only), `com.aurevia.lab.accession.LabInputs`
  (bounded issuer and number), `com.aurevia.lab.accession.AccessionCommands.AccessionRequest`.
- Tenant scope: `com.aurevia.tenant.TenantContext`,
  `com.aurevia.tenant.TenantGuardTransactionManager`,
  `com.aurevia.web.TenantContextInterceptor`.

Where the clause is implemented today (survey result):

- Schema: `db/migrations/V26__laboratory_accession.sql` creates `public.lab_accession`
  with `UNIQUE (organization_id, accession_issuer, accession_no)`, enabled and forced
  row-level security on both `lab_accession` and `lab_accession_event` via a
  `tenant_isolation` policy on `organization_id = public.app_org()`, an append-only
  history ledger, database-owned author and version columns, and immutability triggers.
- Domain: `apps/api/src/main/java/com/aurevia/lab/accession` implements the
  issuer-scoped accession check and its concurrent duplicate-key backstop, the bounded
  laboratory action policy and input validation, and the tenant-guarded repository.
- Controller: `apps/api/src/main/java/com/aurevia/lab/accession/LabAccessionController.java`.
- Contract: `POST /api/v1/lab/accessions` is currently baselined as a missing route in
  `contracts/openapi.yaml`; adding the OpenAPI entry is a separate
  contract story and is intentionally out of scope for this clause-evidence change.

## Gap

None
