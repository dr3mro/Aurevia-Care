# IAM-06 clause 4 — platform support access disabled by default

## Clause

Platform support access to clinical data is disabled by default, with a separate approved and audited incident procedure.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.audit.clauses.PlatformSupportAccessClinicalDataDisabledDefaultTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/PlatformSupportAccessClinicalDataDisabledDefaultTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic-only incident and staff fixtures:

- `supportAccessIsDeniedWhenNoApprovedIncidentExists` — a check with no incident and
  a check against a created-but-pending incident both return `allowed:false` and
  `NO_APPROVED_INCIDENT`; both denials are written to the audit chain.
- `approvedIncidentBySeparatePrivacyAuditorAllowsOnlyBoundSubject` — a `tenant_admin`
  opens the incident, a `privacy_auditor` approves it, and only the bound subject's
  check returns `allowed:true` with `APPROVED_INCIDENT`; a second active member in
  the same tenant is denied, and the create, approve, allow and deny audit rows are
  asserted with purpose `SUPPORT_ACCESS`.
- `wrongRoleCannotApproveSupportIncident` — a `reception` member's approval command
  is refused with 403 `FORBIDDEN`, the incident stays `pending`, and a `DENIED` audit
  row is recorded.
- `otherTenantCannotUseApprovedIncident` — the same subject is an active member of
  both tenants, so the denial of the other tenant's approved incident is caused by
  forced RLS rather than subject binding; the row is invisible to SQL in that tenant
  and the check returns `NO_APPROVED_INCIDENT` with a `DENIED` audit row there.
- `invalidReferenceIsRejected` — malformed and missing references on `/support/check`
  return 422 `INVALID_REFERENCE`.
- `expiredApprovedIncidentStillDeniesAccess` — a deterministically expired approved
  row (explicit past timestamps, no sleeps) is refused with `NO_APPROVED_INCIDENT`.
- `supportAccessIsDeniedWithoutActiveMembership` — a subject without active
  membership is refused with `NO_ACTIVE_MEMBERSHIP`, so the default deny does not
  depend on a valid session header alone.

Production classes exercised through `POST /api/v1/access/support/incidents`,
`/support/incidents/{reference}/approve` and `/support/check`:

- `com.aurevia.smartclinic.authz.access.AccessController` — thin HTTP adapter that
  injects the domain services and takes no organization or actor from the body.
- `com.aurevia.smartclinic.authz.access.SupportAccessService` — default-deny decision,
  separate create/approve commands, role separation and audit calls.
- `com.aurevia.smartclinic.authz.access.AccessRules` and
  `com.aurevia.smartclinic.authz.access.AccessContracts` — bounded reference,
  reason and window validation plus the decision and incident records.
- `com.aurevia.smartclinic.authz.access.AccessRepository` — RLS-bounded queries for
  the active incident and the append-only audit row.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` and
  `com.aurevia.smartclinic.tenant.TenantContext` — bind the active organization,
  subject and correlation id used by the audit trail.
- `db/migrations/V11__emergency_and_support_access.sql` — `support_access_incident`
  table, separation-of-duties CHECK, forced RLS and the tenant isolation policy;
  `db/migrations/V1__tenant_schema.sql` and `db/migrations/V3__audit_chain.sql` —
  the append-only, hash-chained `audit_event` table.

Verified locally with tool evidence:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=PlatformSupportAccessClinicalDataDisabledDefaultTest test`
— Surefire `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None in the clause behavior: the control is built and the tests above prove it over
HTTP. Two pre-existing, out-of-scope items remain recorded here rather than fixed:

1. The three support routes are listed in `contracts/openapi/missing-routes.txt`,
   so they have no OpenAPI entry and no regenerated client. Closing that needs one
   separate task touching `contracts/openapi.yaml`, the generated client and route
   parity.
2. No clinical-data route consumes the `AccessDecision` yet, so the decision is the
   authoritative gate for callers that use it but is not wired into a data endpoint
   in this slice. That is a separate integration task, not a failure of this clause.

The defensive `SELF_APPROVAL` branch in `SupportAccessService` cannot be reached
through HTTP because both the repository query and the V11 CHECK forbid a
self-approved incident; it is exercised by the existing unit tests and is not
fabricated here.
