# MSG-03 clause 3 — API test evidence

## Clause

Bind uploads to the uploader, selected conversation and unit.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.BindUploadsUploaderSelectedConversationUnitTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/BindUploadsUploaderSelectedConversationUnitTest.java`).
All seven tests are active (none disabled) and pass against real PostgreSQL with
synthetic fixtures only. The suite creates its own database, applies the
production Flyway migrations from `db/migrations`, seeds two tenants that share
the same local connection, unit and patient identifiers, and sends multipart
uploads through the real HTTP entry point `POST /api/v1/patient/files/upload`.

- `uploadBindsUploaderConversationAndUnitFromServerGrant` — positive proof. A
  patient-role uploader sends a byte-valid PDF for an active grant. The 201 body
  and the committed row in `public.attachment` both carry the synthetic subject
  as uploader, the chosen connection id, and the grant's unit and patient ids.
- `uploadIgnoresClientSuppliedUnitAndPatientBinding` — direct binding proof. The
  upload repeats the valid payload with spoofed `unitId` and `patientId` form
  fields. Both the 201 body and the stored row still carry the grant's values.
  A red run that asserted the spoofed unit failed with
  `expected: <unit-spoofed> but was: <unit-shared>`, showing the server, not the
  test, refuses the rebind.
- `uploadRefusesRoleWithoutUploadPermission` — a `privacy_auditor` subject gets
  403 `FORBIDDEN` and no row is written.
- `uploadRefusesInactiveConnection` — a verified but inactive grant gets 403
  `INACTIVE_CONNECTION` and no row is written.
- `uploadRefusesUnknownConnection` — an unknown connection id gets 404
  `NOT_FOUND` and no row is written.
- `uploadRefusesGrantOwnedByAnotherTenant` — a connection that exists only in the
  peer tenant gets 404 `NOT_FOUND` from the active tenant, and neither tenant's
  attachment table gains a row. Forced RLS and the tenant-scoped grant lookup
  keep the foreign grant invisible.
- `boundAttachmentCannotBeReboundByDirectSql` — a direct SQL `UPDATE` of a bound
  row's `unit_id`, run as the migration owner with the tenant bound, is rejected
  by the `attachment_binding_guard` trigger (function
  `attachment_binding_immutable`, SQLSTATE `44000`); the original binding is
  unchanged. This is the durable, role-independent proof of the clause.

Production classes and contracts exercised through the HTTP entry point:

- `com.aurevia.smartclinic.web.TenantContextInterceptor` — binds organization
  and subject from validated request headers under the `synthetic-auth` profile.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentController` — reads
  the grant for the active tenant and builds the uploader actor from the server
  grant, never from the request, then injects the domain service.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — re-derives
  roles on every command, checks grant state and binding, and writes the
  immutable attachment and its append-only fact.
- `com.aurevia.smartclinic.messaging.attachments.JdbcAttachmentStore` —
  tenant-guarded read and write of `public.attachment` and
  `public.attachment_connection_grant`.
- `com.aurevia.smartclinic.staff.StaffRepository` — fresh active roles for the
  caller inside the active tenant.
- `db/migrations/V82__attachment_store.sql` — forced RLS on all three attachment
  tables, the tenant isolation policy and the immutable-binding trigger.

The service's explicit wrong-unit / organization-mismatch branch is **not**
taken by this HTTP class: `AttachmentController.upload` passes
`grant.patientId()` as the actor's patient id and `store.grant(...)` is already
scoped to the active tenant, so that defensive branch is unreachable through
the public entry point. It remains proven by the unchanged service-level
`com.aurevia.smartclinic.messaging.attachments.AttachmentAcceptanceTest`
(`deniesUnknownInactiveAndForeignGrants`, `deniesMismatchedPatientAndStaffUnitBindings`).
This class proves the clause as observable and enforceable through HTTP and the
database. The route is currently listed in `contracts/openapi/missing-routes.txt`,
a pre-existing contract-publication gap owned elsewhere and outside this
test-only story.

## Gap

None.
