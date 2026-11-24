# ARC-02 clause 5 — jobs, exports, search, attachments and integrations apply the same policy

## Clause

Jobs, exports, search, attachments and integrations apply the same policy.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest`
(path: `apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, the same fixtures and database setup as the neighbouring `tenancy.clauses`
tests, and a dedicated disposable database `aurevia_arc02_c05_it` so parallel
workers do not collide. All five tests are enabled and pass with synthetic data only.

Test methods (one positive rule proof and four negative refusals):

- `allFiveSurfacesApplyTheSameTenantPolicy` — positive proof. Each tenant owns a
  record under an identifier identical to the peer's (shared MRN, order code, file
  name, object alias and delivery idempotency key) and can read or advance its own
  record on every surface.
- `peerTenantCannotUseJobsExportsSearchAttachmentsOrIntegrations` — negative proof.
  A peer worker cannot see or advance the outbox event; a peer admin cannot read the
  export job row through `JdbcExportStore` or through forced RLS SQL, and the
  identical object alias resolves to empty for the peer checksum; a peer search
  omits the shared-MRN patient and a direct read is 404; a peer document metadata
  and byte read is 404; a peer order read and list omit the owner's record. A
  valid subject asserting the other tenant's organization is refused with 403.
- `missingSubjectOrOrganizationIsRefusedOnEverySurface` — negative proof. No
  `X-Synthetic-Subject` yields 401 and no `X-Active-Organization` yields 422 on
  jobs, exports (read and start), search, documents, the patient-file attachment
  route and orders.
- `wrongRoleCannotStartAnExport` — negative proof. `ExportPermissionGuard`
  refuses `reception`, `clinician`, `worker` and `privacy_auditor` with 403. Its
  code admits only an active `tenant_admin`; a positive start assertion belongs
  to the backend task that fixes the HTTP transaction defect in the Gap.
- `staleAndExpiredStatesAreRefused` — negative proof. A repeated `If-Match: "1"`
  on `POST /api/v1/delivery/events/{id}/sent` is refused with 412, and an export
  whose durable row has been transitioned to `EXPIRED` refuses download with 410.

Production classes exercised through their real entry points:

- JOBS: `com.aurevia.events.DeliveryController`,
  `com.aurevia.events.DurableEventService`,
  `com.aurevia.events.JdbcDeliveryStore` and
  `com.aurevia.tenant.TenantGuardTransactionManager`.
  `com.aurevia.tenant.retention.RetentionJob` is a pure domain kernel
  with no HTTP entry point or caller in `src/main`, so it cannot be exercised over
  HTTP; the durable outbox is the observable jobs surface.
- EXPORTS: `com.aurevia.tenant.export.JdbcExportStore`,
  `com.aurevia.tenant.export.ExportPermissionGuard`,
  `com.aurevia.tenant.export.TenantExportService` and the V89
  `export_job` table with forced RLS. Isolation is proved at the store and object
  boundaries and role/expiry refusal through the guarded service transaction.
- SEARCH: `com.aurevia.web.PatientController` (`GET /api/v1/patients?name=`).
- ATTACHMENTS: `com.aurevia.documents.DocumentController` and
  `com.aurevia.messaging.attachments.AttachmentController` (the latter
  only for missing-context refusal).
- INTEGRATIONS: `com.aurevia.exchange.order.OrderController`.
- Shared boundary: `com.aurevia.web.TenantContextInterceptor` and
  `com.aurevia.tenant.TenantContext`.

Source survey at HEAD: every one of the five surfaces has a production
implementation. In particular `docs/gates/g0-isolation-surfaces.md:43` is stale;
it records "Tenant export | No", but `ExportController`, `TenantExportService`,
`JdbcExportStore`, `ExportPermissionGuard` and migration `V89__tenant_export.sql`
all exist. The disabled test described in that document targets a different,
unimplemented `/api/v1/tenant-exports` contract and was not reused; the real
route is `/api/v1/tenant/exports`.

## Gap

The policy itself is enforced on all five surfaces, but three delivery defects
prevent an unqualified "Proven by tests" status:

1. The export HTTP adapter `apps/api/src/main/java/com/aurevia/tenant/export/TenantExportService.java`
   does not open a `TenantGuardTransactionManager` transaction. Its DB reads
   therefore run without `app.current_org`/`app.actor`, so `ExportPermissionGuard`
   sees no membership and the `EXPORT_DENIED` audit append violates the forced-RLS
   policy. Every HTTP export command that reaches the service returns 500 instead
   of 200/403/404/410; requests without a subject or organization are still
   refused earlier by the interceptor with 401/422.
   Fixing this needs one backend task touching `TenantExportService` (or its
   controller) to run each command in a `TransactionTemplate` bound to the guard;
   the store and object layers already pass their own gate.
2. `JdbcExportStore.mapJob` reads `expires_at` with `getString`, which PostgreSQL
   renders as `2026-11-24 02:06:52+00`; `ExportJob.expired` calls `Instant.parse`
   and cannot parse that form. A `COMPLETE` job loaded from the database cannot be
   downloaded even after defect 1 is fixed. The same backend task should return
   an ISO-8601 instant (for example `getObject(..., OffsetDateTime.class)` rendered
   with `Instant.toString()`).
3. The three export routes are absent from `contracts/openapi.yaml` and are listed
   in `contracts/openapi.yaml` (lines 116, 333, 334). No OpenAPI
   parity is claimed here. Publishing them with a tag, `Idempotency-Key` on the
   POST operations and a regenerated client needs one separate contract task.

The separate patient-unit download surface `AttachmentController` returns 404 for
a peer attachment id, but its HTTP-level cross-tenant download backed by a verified
care-unit connection grant is not proved by this suite; the document association
surface is. One additional task with a connection-grant fixture would close that
sub-gap. No production code, migration, contract, threshold or existing test was
changed by this story. Clause 5 should not be accepted as "Proven by tests" until
the follow-up tasks above are merged and green.
