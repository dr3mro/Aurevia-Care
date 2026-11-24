# G0 isolation across API, search, files, workers and exports

- Date: 2026-11-21 · Requirements: ARC-02, IAM-01 · Gate: G0 · Class: safety-critical (docs/WORKFLOW.md)
- Evidence source: executed tool commands on synthetic PostgreSQL fixtures, simulated specialist review. No human, clinical or patient evidence.
- Scope: one test class and this gap note. No production code, migration, dependency or threshold was changed.

## Purpose

The G0 row of section 9 of `PROJECT_V1_SPEC.md` requires that two tenants with identical
local IDs remain isolated across API, search, files, workers and exports. `S-G0-04` in
tasks/ was blocked because several of those surfaces did not exist. This note records which
surfaces exist today, the test that proves each one, and the stories still needed for the rest.

## Synthetic fixtures

`IsolationAcrossSurfacesTest` creates two organizations, `ORG_A` and `ORG_B`, with active
memberships for `tenant_admin`, `reception`, `clinician`, `worker` and `privacy_auditor` subjects.
Both tenants reuse exactly the same local identifiers:

| Local identifier | Value | Surface |
|---|---|---|
| Facility code | `MAIN` (plus `SHARED-WARD` written by both) | API reads and writes |
| Issuer-scoped MRN | `MRN-SHARED` at issuer `CLINIC` | Shared registration fixture (seeded directly in SQL); reused by orders and documents. There is no MRN-search endpoint — `PatientController` supports only `?name=` — so MRN-search isolation is not claimed |
| Order code | `CBC-SHARED` under a per-tenant authorization | Order reads (write-side identifier) |
| File name | `shared-scan.pdf` | Document originals |
| Delivery idempotency key | `ORDER-CODE-SHARED` | Worker/outbox processing |

Each surface test reads or processes the peer tenant's record id and asserts concealment (404,
empty result or a response containing only the caller's organization). A missing subject must
answer 401 and a missing `X-Active-Organization` must answer 422 on every existing surface, on
writes as well as reads (`POST /api/v1/facilities` with no subject or no organization); a direct
`FacilityService.get` call with no bound `TenantContext` must throw `IllegalStateException`
before SQL. No real patient data, credential or token is used.

## Surface inventory

| Surface | Exists | Serving class | Test method | Status |
|---|---|---|---|---|
| API reads and writes | Yes | `com.aurevia.web.FacilityController`, `com.aurevia.tenancy.FacilityService` | `apiReadsAndWritesStayTenantScopedWithTheSameFacilityCode` | Proven |
| Patient search | Yes | `com.aurevia.web.PatientController`, `com.aurevia.patient.PatientRepository` | `patientSearchNeverReturnsThePeerTenantsRecordWithTheSameMrn` | Proven |
| Files and attachment retrieval | Partly | `com.aurevia.documents.DocumentController`, `com.aurevia.documents.DocumentService`; `com.aurevia.messaging.attachments.AttachmentController` for patient-unit files | `filesAndRendersWithTheSameNameAreConcealedAcrossTenants` | Partly proven |
| Worker/outbox processing | Yes | `com.aurevia.events.DeliveryController`, `com.aurevia.events.DurableEventService`, `com.aurevia.events.JdbcDeliveryStore` | `workerAndOutboxProcessingStaysTenantScopedWithTheSameEventKey` | Proven |
| Tenant export | No | None. `com.aurevia.tenant.importing.ImportController` is import-only; `com.aurevia.messaging.governance.MessagingGovernanceController` offers a scoped, expiring messaging export, not an OPS-07 tenant export | `tenantExportNeverIncludesAnotherTenantsRecords` (`@Disabled` with a GAP reason) | Not built |

The fail-closed assertions are collected in `missingTenantContextFailsClosedOnEverySurface`.
The disabled export test is written in full — it uses seeded tenant records, creates a document
in each tenant, requests an export, asserts a `schemaVersion` and `sha256` field in the manifest
plus the owner's patient and file name, and asserts cross-tenant and missing-context denial —
but it cannot run until an export surface exists. It is the only
`@Disabled` test added by this story; the skip is an explicit gap marker, not a weakened gate.

### Why files are only partly proven

Document associations and rendered PDFs are proven: the same file name is attached in both
tenants and each tenant's metadata, original bytes and rendered bytes are readable only by its
owner. The separate patient-to-unit file surface (`POST /api/v1/patient/files/upload`,
`GET /api/v1/patient/files/{id}/download`) is backed by `JdbcAttachmentStore` and the durable
`V82__attachment_store.sql` tables with forced RLS (`JdbcAttachmentStoreIntegrationTest` covers
the store). What is missing here is an HTTP-level cross-tenant download assertion built on a
verified care-unit connection grant fixture. That path is therefore not claimed as proven in
this note.

## Stories still required for the missing surfaces

1. **OPS-07 tenant export (Not built).** Add a story that creates a tenant export surface with a
   versioned manifest, schema version, checksums, authorized-document selection, provenance and
   audit history, backed by a Flyway table with `organization_id`, forced RLS and a `Jdbc*`
   store behind an interface, a thin injected controller with an HTTP-level integration test and
   an OpenAPI entry. Acceptance: the disabled
   `tenantExportNeverIncludesAnotherTenantsRecords` is enabled and passes, including
   cross-tenant 404 and missing-context fail-closed. Ownership: Architect + Product Owner for the
   export contract, then a backend story.
2. **Patient-unit attachment retrieval (Partly proven).** Add a story with a verified care-unit
   connection grant fixture so that a cross-tenant download of an identically named file is
   proven at HTTP level through `AttachmentController`, reusing the existing `JdbcAttachmentStore`
   and its V82 tables. Until then the status stays Partly proven and this note does not claim
   that path is fully isolated.

No story is required for API reads/writes, patient search or worker/outbox processing: their
isolation is proven by the tests named above.

## Reproduction

```
AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=IsolationAcrossSurfacesTest test
python3 -I scripts/check-docs.py
python3 -I -m unittest discover -s scripts/tests -q
bash scripts/quick-check.sh
```

The first command drops and recreates the disposable database `aurevia_isolation_surfaces_it`,
applies all Flyway migrations, runs the seven tests and drops the database again. The existing
surfaces are exercised through their real HTTP entry points; the worker surface additionally
reads the JDBC store with a bound tenant context. Results are recorded in
`.agent/pr-notes/GATE-ISOLATION-SURFACES-20261121-152812.md`.

In this cloud worker `scripts/quick-check.sh` stops at `check-pack.py: opencode.json is stale`:
`git ls-files -v opencode.json` shows flag `S`, the worktree file equals `.github/opencode.ci.json`,
and `git show HEAD:opencode.json` equals the generator's local render. This is the documented
OPS-CW-003-01 skip-worktree artifact, not a defect from this change; `opencode.json` was not edited.

## Related evidence

- `docs/decisions/0001-g0-tenant-isolation.md` — tenant derivation, forced RLS, fail-closed context.
- `docs/decisions/0003-persistence-of-in-memory-stores.md` — inventory of in-memory stores and the persistence programme; the attachment store has since been replaced by `JdbcAttachmentStore` and V82.
- `docs/gates/g0-evidence-pack.md` (when written by GATE-PACK-G0) — aggregate G0 verdict; this note supplies its isolation row.
- tasks/ entry `S-G0-04` — original blocked isolation story.
