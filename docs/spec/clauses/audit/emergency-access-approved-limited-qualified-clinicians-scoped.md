# IAM-06 clause 2 — emergency access is approved, clinician-only, own-organization, time-limited, reasoned, alerted and reviewed

## Clause

Emergency access, where approved, is limited to qualified clinicians, scoped inside their own organization, time-limited, reasoned, alerted and reviewed.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.audit.clauses.EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest.java`),
a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, the same fresh-database/flyway/seed fixture style as the neighbouring
`authz.clauses` tests, and synthetic subjects, facilities and justifications only.
All six tests are active (none are disabled). Local command:
`mvn -q -B -f apps/api/pom.xml -Dtest=EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest test`;
Surefire report: `tests="6" errors="0" skipped="0" failures="0"`.

Positive rule proof (one test) and negative refusals (five tests):

- `approvedSiteLetsClinicianGrantReasonedTimeLimitedAlertedAndReviewedAccess` —
  at a site with an approved `access.emergency.enabled` configuration a `clinician`
  with active membership and an in-scope facility can `POST /api/v1/access/emergency`.
  The 201 response and the authoritative row carry the clinician subject, role
  `clinician`, the facility, the recorded reason and a future expiry exactly equal to
  the requested minutes (time-limited); `GET /api/v1/access/emergency/active` then returns
  `allowed=true, reason=ACTIVE`. An `EMERGENCY_ALERT` audit event and an open
  privacy-auditor review row are created with the grant (alerted), and an independent
  `privacy_auditor` closes that review with an explicit `approved` outcome through
  `POST /api/v1/access/emergency/{id}/review`, leaving the grant `reviewed`.
- `grantIsRefusedWhenTheSiteHasNotApprovedEmergencyAccess` — a clinician at a tenant
  with no approved configuration is refused (403) and no grant row is written ("where
  approved").
- `grantByAReceptionRoleIsRefused` — an active `reception` member is refused (403) and
  no grant row is written (limited to qualified clinicians).
- `grantCannotUnlockAnotherOrganizationsData` — the grant is visible only in its own
  organization; forced RLS hides it from the other tenant, whose auditor receives 404
  when trying to review it, and the owner's review row is unchanged (own-organization
  scope).
- `expiredGrantIsNotActiveOnTheNextRequest` — a grant whose expiry has passed returns
  `allowed=false, reason=EXPIRED` on the next `active` check (time-limited).
- `grantWithoutAReasonIsRefused` — a blank justification is refused (422) and no grant
  row is written (reasoned).

Entry-point-to-test trace: `POST /api/v1/access/emergency` →
`com.aurevia.smartclinic.authz.access.AccessController.grant` →
`com.aurevia.smartclinic.authz.access.EmergencyAccessService.grant` →
`com.aurevia.smartclinic.authz.access.AccessRepository` (forced-RLS tables);
`GET /api/v1/access/emergency/active` → `AccessController.active` →
`EmergencyAccessService.active`; `POST /api/v1/access/emergency/{id}/review` →
`AccessController.review` → `EmergencyAccessService.review`. Production classes the
test exercises: `AccessController`, `EmergencyAccessService`, `AccessRepository`,
`AccessRules`, `AccessContracts`, plus `com.aurevia.smartclinic.tenant.TenantContext`.

Source survey result (where the clause is implemented today):

- `apps/api/src/main/java/com/aurevia/smartclinic/authz/access/EmergencyAccessService.java`
  enforces approval, clinician role, facility scope, expiry, reason validation, the
  alert audit event and the independent review inside tenant-guarded transactions.
- `apps/api/src/main/java/com/aurevia/smartclinic/authz/access/AccessRules.java` and
  `AccessContracts.java` hold the bounded validation and the immutable command and
  projection records; `AccessRepository.java` is the single forced-RLS SQL boundary.
- `apps/api/src/main/java/com/aurevia/smartclinic/authz/access/AccessController.java`
  is the thin HTTP adapter under `/api/v1/access`.
- `db/migrations/V11__emergency_and_support_access.sql` creates `emergency_access_grant`
  with a composite tenant+facility foreign key, checks, `ENABLE` and `FORCE ROW LEVEL
  SECURITY`, a tenant policy and grants; `db/migrations/V88__access_review.sql` adds
  `emergency_access_review`, also with FORCE RLS and a tenant policy, so a grant opens
  exactly one review that only an independent reviewer can close.
- Neighbouring corroborating suites already on the branch (not re-run here):
  `com.aurevia.smartclinic.authz.access.EmergencyAccessReviewIntegrationTest`,
  `EmergencyAccessReviewTest`, `AccessReviewRulesTest` and `AccessReviewRepositoryTest`.
- The `audit` package (`AuditEvent`, `AuditAppender`, `AuditService`, `AuditRepository`,
  `AuditPage`) supplies the append-only OPS-02 chain to which `AccessRepository.appendAudit`
  writes the emergency-access events; the authorization itself lives in `authz.access`.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the five access routes
(`POST /api/v1/access/emergency`, `GET .../active`, `POST .../{id}/revoke`,
`POST .../{id}/review`, `GET .../review-queue`) are baselined in
`contracts/openapi.yaml` and are not declared in `contracts/openapi.yaml`.
Publishing them would change the contract, the generated staff-web client and the
contract tests — one separate ARC-04/WEB-05 contract task. This task added proof only:
it did not change production code, an existing test, the contract or a migration.
