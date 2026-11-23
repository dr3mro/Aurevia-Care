# IAM-05 clause 4 — staff contact change and account ownership

## Clause

A staff contact change does not change account ownership.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.StaffContactChangeDoesChangeAccountOwnershipTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/StaffContactChangeDoesChangeAccountOwnershipTest.java`).
It is a real-PostgreSQL HTTP acceptance test tagged `integration`, using the
`synthetic-auth` profile and two synthetic tenants that share one chart identifier,
following the fixtures and database setup of
`com.aurevia.smartclinic.it.messagingenrollment.PatientAccessHttpIntegrationTest`
and the neighbouring clause tests. Both tests are active (none disabled) and pass
with synthetic data only.

Test methods (one positive rule proof, one negative refusal set):

- `staffContactChangePreservesAccountOwnershipAndChartAccess` — positive proof. A
  reception staff member enrolls a verified account for a synthetic patient, then
  replaces the recovery contact through `POST /api/v1/patient/access/accounts/contact`.
  The returned account keeps the same id, organization, subject, verified chart
  links, fact digest, state, failed-attempt count and creation time; only the
  contact digest changes to a 64-character SHA-256 value that never contains the
  raw contact. Verified chart access is still allowed, the append-only audit holds
  a `contact_changed` event with detail `ownership_unchanged`, and the durable row
  read back under forced row-level security keeps the same subject and chart links.
- `staffContactChangeIsRefusedForWrongRoleOtherTenantAndInvalidInput` — negative
  refusals. A patient (403) and a privacy auditor (403) lack the clerical contact
  edit action, a reception member of the other tenant is concealed with 404, a
  blank contact digest is rejected with 422 `INVALID_CONTACTDIGEST`, and an unknown
  account is refused with 404. None of the refusals changes the stored contact
  digest or writes the raw contact.

Production classes exercised through the normal HTTP entry point:

- Controller: `com.aurevia.smartclinic.messaging.enrollment.PatientAccessController`
  (`POST /api/v1/patient/access/accounts/contact`), which derives the caller from
  `com.aurevia.smartclinic.web.TenantContextInterceptor` and fresh active roles
  from `com.aurevia.smartclinic.staff.StaffRepository`.
- Domain: `com.aurevia.smartclinic.messaging.enrollment.PatientAccessService`
  (`changeStaffContact`), `com.aurevia.smartclinic.messaging.enrollment.EnrollmentActions`
  (`CONTACT_EDIT_STAFF`, allowed for `reception` and `tenant_admin`), and
  `com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract`
  (`ChangeContactRequest`, `PatientAccount`, `EventType.CONTACT_CHANGED` with the
  `ownership_unchanged` detail).
- Persistence: `com.aurevia.smartclinic.messaging.enrollment.PatientAccessStore`
  and its `com.aurevia.smartclinic.messaging.enrollment.JdbcPatientAccessStore`,
  writing `patient_access_account` inside the tenant-guarded transaction.

Source survey — where the clause is implemented today:

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/enrollment/PatientAccessService.java`
  constructs the updated account from the existing id, organization, subject,
  linked chart ids, fact digest, state, attempt count and creation time, replacing
  only the contact digest. Its class documentation states that a staff contact edit
  never changes account ownership.
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity/IdentityProofingService.java`
  documents the companion invariant: account ownership follows the immutable chart
  identifier, never mutable contact data.
- Schema: `db/migrations/V83__patient_access_store.sql` owns `patient_access_account`
  and the other patient-access tables with forced row-level security;
  `db/migrations/V4__staff_lifecycle.sql` supplies the `active_roles` fold used for
  the request-time role check.
- Controllers under `web`: `com.aurevia.smartclinic.web.PatientController` covers
  registration and search only; the staff contact edit lives in the
  `messaging/enrollment` controller above.
- An existing service-level proof is
  `com.aurevia.smartclinic.it.messagingenrollment.PatientAccessTest.staffContactChangeNeverChangesOwnership`;
  this clause test adds the HTTP-level evidence and the cross-tenant, wrong-role
  and invalid-input refusals.

## Gap

None for the clause rule: it is implemented and proven over the HTTP route and the
durable store. Separately, `POST /api/v1/patient/access/accounts/contact` is currently
listed in `contracts/openapi.yaml` instead of being declared in
`contracts/openapi.yaml`; that OpenAPI contract-parity debt is already tracked as a
separate gate task and is not a gap in this clause's behavior.
