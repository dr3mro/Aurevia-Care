# IAM-05 clause 1 — patient identity proofing

## Clause

Patient identity proofing uses site-verified enrollment plus expiring single-use invitations and re-verification for lost access or disputed identity.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.patient.clauses.PatientIdentityProofingUsesSiteVerifiedEnrollmentTest`
(path: `apps/api/src/test/java/com/aurevia/patient/clauses/PatientIdentityProofingUsesSiteVerifiedEnrollmentTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the
`synthetic-auth` profile and two synthetic tenants, applying Flyway migrations
from `db/migrations`. It follows the fixtures and database setup of the
neighbouring clause tests and of
`com.aurevia.it.patient.identity.StaleInvitationIntegrationTest`.
All four tests are active (none disabled) and pass with synthetic data only.

Test methods (one positive rule proof, three negative refusals):

- `siteVerifiedEnrollmentSingleUseInvitationAndReverificationProveTheRule` —
  positive proof. A `reception` staff member enrolls a site-verified account and
  issues a bounded, 600-second `LINK` invitation; the patient redeems it and
  gains access to the synthetic chart. Replaying the same token is refused
  (409), unlinking ends access immediately (the current link event becomes
  `unlinked`), a `tenant_admin` re-verification retires the stale token and
  issues a fresh one, and only the fresh token restores access. The durable
  rows are read back under forced row-level security: one `patient_account`,
  two append-only `linked` and two `unlinked` `account_chart_link` events, and
  no account visible from the other tenant.
- `receptionAndUnverifiedCallersAreRefusedProtectedIdentityCommands` — wrong role
  and missing membership refusals: a `reception` member may enrol and invite but
  is refused re-verification (403) and reading another account's access (403);
  a subject without active membership cannot enroll an account (403).
- `anExpiredInvitationCannotRestoreAccess` — an invitation whose stored
  `expires_at` is moved into the past by the database clock is refused (409) and
  never grants access, proving the expiring property.
- `anInvitationIssuedInAnotherTenantCannotBeClaimed` — a token issued in one
  tenant is invisible to an identically named account in the other tenant
  (404) and grants no access there, proving tenant isolation of invitations.

Production classes exercised through the normal HTTP entry points:

- Controller: `com.aurevia.patient.identity.IdentityProofingController`
  (`POST /api/v1/patient-identity/accounts`, `/invitations`, `/links/claim`,
  `/links/unlink`, `/links/reverify` and `GET /accounts/{subject}/access`).
- Domain and boundary: `com.aurevia.patient.identity.IdentityProofingService`,
  `IdentityProofingRepository`, `IdentityActionPolicy` and the value types
  `PatientAccount`, `InvitationIssue`, `InvitationRecord` and `ChartLink`.
- Platform: `com.aurevia.tenant.TenantContext` and the
  `com.aurevia.web.TenantContextInterceptor`.

Where the clause is implemented today (source survey result):

- Package `apps/api/src/main/java/com/aurevia/patient/identity` holds
  the controller, service, SQL repository, bounded action policy and immutable
  value records listed above. `IdentityProofingService.enroll` performs
  site-verified enrollment; `issueInvitation` bounds purpose and TTL and stores
  only the token hash; `linkWithInvitation` consumes the token atomically;
  `unlink` and `reverify` retire outstanding invitations and append link events;
  `hasAccess` is a live fold over the latest link event.
- Schema: `db/migrations/V10__patient_identity_proofing.sql` creates
  `patient_account`, `account_invitation` and `account_chart_link` with forced
  row-level security, tenant policies, append-only triggers on the account and
  link tables, and the `consume_invitation`, `has_chart_access` and
  `current_chart_links` folds. Later migrations in `db/migrations` do not change
  these identity tables.
- Contract survey result: `contracts/openapi.yaml` currently declares no
  `/api/v1/patient-identity/**` paths. The routes are live in the controller and
  exercised over HTTP by this test; publishing them in the contract is a
  separate cross-boundary documentation change and was not made here.
- Neighbouring evidence: `com.aurevia.it.patientidentity.IdentityProofingCoverageTest`,
  `IdentityProofingIsolationTest`, `IdentityProofingUnitTest`, and
  `com.aurevia.it.patient.identity.StaleInvitationIntegrationTest`,
  `OutstandingInvitationRevocationTest`, `IdentityLinkProjectionContractTest`.

## Gap

None.
