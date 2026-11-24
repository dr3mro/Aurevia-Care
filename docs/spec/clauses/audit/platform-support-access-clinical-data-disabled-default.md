# IAM-06 clause 4 — API test evidence

## Clause

Platform support access to clinical data is disabled by default, with a separate approved and audited incident procedure.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.audit.clauses.PlatformSupportAccessClinicalDataDisabledDefaultTest`
(path: `apps/api/src/test/java/com/aurevia/audit/clauses/PlatformSupportAccessClinicalDataDisabledDefaultTest.java`).
All six tests are active (no `@Disabled`) and pass with synthetic subjects, incident
references and fixed-clock instants only; no patient data, credentials or real
organization identifiers are used. Every request goes through the real HTTP entry
point under `/api/v1/access/support/**` in `AccessController`, backed by the real
`SupportAccessService` and `AccessRules`; only the RLS-bounded `AccessRepository` is
a synthetic Mockito boundary, matching the neighbouring clause-test style. The
standalone MockMvc harness does not install the Spring Security filter chain, so the
OIDC URL-level authentication boundary is not exercised here; it is covered by the
authz clause and integration suites. This proof covers the reported decision and the
audited incident procedure rather than enforcement at clinical-data read call sites.

- `supportAccessIsDeniedByDefaultAndAuditsTheRefusal` — positive proof of the default-deny
  rule. With no usable incident, `POST /support/check` returns HTTP 200 with
  `allowed:false` and the stable reason `NO_APPROVED_INCIDENT`, and a
  `SUPPORT_ACCESS` / `SUPPORT_ACCESS_CHECK` / `DENIED` audit is appended. No incident
  is opened or approved.
- `separatelyApprovedUnexpiredIncidentEnablesAuditedSupportAccess` — positive proof of the
  separate approved and audited incident procedure. A `tenant_admin` opens a pending
  incident (HTTP 201, `Location` header, requester bound from the tenant context), a
  `privacy_auditor` independently approves it (HTTP 200, `approvedBy` from the context),
  and only then does the bound support subject get `allowed:true` /
  `APPROVED_INCIDENT`. The three steps write `SUPPORT_INCIDENT_CREATE`,
  `SUPPORT_INCIDENT_APPROVE` and `SUPPORT_ACCESS_CHECK` audit events with purpose
  `SUPPORT_ACCESS`, bounded `role:reference` scopes and `SUCCESS` outcomes; the
  captured contexts confirm the actor for all three steps and the organization on
  creation and on the cross-subject check.
- `wrongRoleCannotOpenOrApproveAnIncidentAndRefusalIsAudited` — negative proof of role
  separation. A `privacy_auditor` cannot open an incident and a `tenant_admin` cannot
  approve one; both get HTTP 403 `FORBIDDEN`, neither command reaches the repository,
  and each refusal is audited with outcome `DENIED`.
- `selfApprovedOrInactiveMembershipIncidentNeverEnablesAccess` — negative proof of the
  forbidden state. A self-approved incident yields reason `SELF_APPROVAL` and a subject
  without active membership yields `NO_ACTIVE_MEMBERSHIP`; both stay `allowed:false`,
  neither writes a `SUCCESS` check audit. The `SELF_APPROVAL` service branch is
  defense-in-depth: the real repository filters `approved_by <> subject`, the approval
  UPDATE requires `subject <> approver`, and V11 adds a CHECK constraint, so a
  self-approved row cannot reach it. The authoritative self-approval proof is
  `AccessIntegrationTest.supportIncidentCannotBeSelfApprovedAtDatabaseLevelAndGrantsAreTenantIsolated`.
- `differentSupportSubjectCannotReuseAnApprovedIncidentReference` — negative proof that
  an approval is bound to a subject: the same reference checked by a different support
  subject stays denied with `NO_APPROVED_INCIDENT`, and the captured audit context
  carries that subject and organization. Because the repository is mocked, this test
  does not itself prove tenant isolation; the real-PostgreSQL proof is
  `AccessIntegrationTest.supportAccessIsDeniedByDefaultAndNeedsSeparateApproval`, where
  the other tenant's check of the same reference returns `allowed:false`.
- `blankReferenceReasonOrWindowAreRefusedBeforeAnyWrite` — negative proof of missing
  input. Blank reference, blank reason and a zero-minute window give HTTP 422
  `INVALID_REFERENCE`, `INVALID_REASON` and `INVALID_WINDOW` before any repository read
  or write.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.authz.access.AccessController` — thin adapter mapping
  `POST /support/incidents`, `POST /support/incidents/{reference}/approve` and
  `POST /support/check`; injects both domain services and maps failures through
  `com.aurevia.api.ApiErrorHandler`.
- `com.aurevia.authz.access.SupportAccessService` — default-deny decision
  with explicit reasons (`NO_ACTIVE_MEMBERSHIP`, `NO_APPROVED_INCIDENT`, `SELF_APPROVAL`,
  `APPROVED_INCIDENT`), role-separated incident creation and approval, and the
  `SUPPORT_ACCESS` audit trail.
- `com.aurevia.authz.access.AccessRules` — bounded reference, reason and
  window validation that fails closed before any store access.
- `com.aurevia.authz.access.AccessContracts` — immutable commands and
  projections; organization, actor and time are never accepted from the body.
- `com.aurevia.authz.access.AccessRepository` — RLS-bounded incident and
  audit operations (`insertIncident`, `approveIncident`, `findIncident`,
  `activeIncident`, `appendAudit`), mocked at the boundary in this proof.
- `com.aurevia.tenant.TenantContext`,
  `com.aurevia.web.TenantContextInterceptor` — per-request tenant binding
  from the authenticated subject and an explicit organization selection.

Source survey (found, not changed by this task): the implementation lives in
`apps/api/src/main/java/com/aurevia/authz/access/`
(`SupportAccessService`, `AccessController`, `AccessRepository`, `AccessRules`,
`AccessContracts`). The `support_access_incident` table, its row level security with
`FORCE ROW LEVEL SECURITY`, the self-approval guard and the grants are created by
`db/migrations/V11__emergency_and_support_access.sql`. Existing proof for the same
behaviour includes `apps/api/src/test/java/com/aurevia/it/authz/AccessIntegrationTest.java`
(`supportAccessIsDeniedByDefaultAndNeedsSeparateApproval`,
`supportIncidentCannotBeSelfApprovedAtDatabaseLevelAndGrantsAreTenantIsolated`,
`sensitiveActionsAreAuditedWithPurposeRoleFacilityAndActor`),
`Iam06AcceptanceTest` (`a8`, `a9`), `Iam06AuditEvidenceTest`,
`Iam06SensitiveActionAuditTest` and `AccessUnitTest` (support-access branch coverage).
None of these files was modified by this proof-only task.

## Gap

No behavioral gap in the proved surface: default denial, separate role-bound approval,
expiry, self-approval refusal and the audit trail are implemented and proven by active
tests. The clause is implemented as a default-deny decision service plus an audited
incident procedure; a search of `apps/api/src/main/java` finds `checkAccess` consumed
only by `AccessController`, with no clinical-data read consulting the decision. This
proof therefore demonstrates the decision and the procedure as the specification
describes them, not an enforced gate at clinical-data read call sites. Tenant isolation
and self-approval refusal at the database level are proven by the real-PostgreSQL
`AccessIntegrationTest`, not by the mocked-repository tests added here.

One non-behavioral gap remains and needs one separate cross-boundary task. The three
support routes are listed in `contracts/openapi.yaml` (lines 131-133)
rather than documented in `contracts/openapi.yaml`:
`POST /api/v1/access/support/check`, `POST /api/v1/access/support/incidents` and
`POST /api/v1/access/support/incidents/{reference}/approve`. Closing it means adding
those paths and their request/response schemas to `contracts/openapi.yaml`, removing
their three `missing-routes.txt` lines and regenerating the staff-web OpenAPI types.
That is outside this proof-only task's scope; the routes themselves work end to end
against a real database in `AccessIntegrationTest`.
