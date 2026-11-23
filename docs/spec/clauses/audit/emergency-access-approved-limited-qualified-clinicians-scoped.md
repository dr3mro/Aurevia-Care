# IAM-06 clause 2 — emergency access is scoped, reasoned, alerted and reviewed

## Clause

Emergency access, where approved, is limited to qualified clinicians, scoped inside their own organization, time-limited, reasoned, alerted and reviewed.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.audit.clauses.EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the
`synthetic-auth` profile, three synthetic tenants (an approved home organization,
an approved foreign organization and an unapproved organization) and the same
Flyway and seeding setup as the neighbouring `authz.access` integration test. All
six tests are active; none are disabled, skipped or excluded. Only synthetic
subjects, reasons and identifiers are used, and each test carries its own
correlation id so its audit assertions cannot be satisfied by another test.

Test methods (one positive rule proof, five negative refusals):

- `approvedQualifiedClinicianOpensOwnOrganizationReasonedTimeLimitedAlertedReviewedGrant`
  — positive proof. `POST /api/v1/access/emergency` with a `clinician` role in the
  approved organization returns HTTP 201 with the expected `Location`, subject,
  facility, qualifying role, recorded reason, `active` status and a ten-minute
  expiry; `GET /api/v1/access/emergency/active` then reports `allowed=true` with
  reason `ACTIVE`. The database row is read back under forced RLS with
  `organization_id` equal to the home organization, purpose `EMERGENCY_ACCESS`,
  no revocation or review yet. The `EMERGENCY_GRANT`, `EMERGENCY_ALERT` and
  `EMERGENCY_REVIEW_OPEN` audit tuples are each asserted by full tuple plus
  correlation id, and the V88 row is asserted open with recipient `privacy_auditor`,
  no outcome, no reviewer and no closure time. Finally an independent privacy
  auditor closes the review through `POST /api/v1/access/emergency/{id}/review`
  (HTTP 200, `status=reviewed`); the V88 row becomes `closed` with outcome
  `concerns_noted`, reviewer `auditor-a` and a closure time, and the grant row
  records the same reviewer. This completes the alerted-and-reviewed chain.
- `wrongRoleIsRefused` — negative proof. `reception` and `tenant_admin` subjects
  are refused (HTTP 403 `FORBIDDEN`, "missing action permission"); neither a grant
  nor a review row is created for them, and two `EMERGENCY_GRANT/DENIED` audit rows
  are recorded. Only a qualified clinician may open emergency access.
- `anotherTenantCannotGrantOrSeeTheGrant` — negative proof. A clinician belonging
  only to Org B is refused when granting inside Org A (HTTP 403). A grant created
  inside Org A by a clinician who is an active member of *both* organizations is
  invisible from Org B: the `active` decision there is `NO_GRANT` with
  `allowed=false`, the Org A grant id is absent from Org B's review queue but
  present in Org A's, and a foreign privacy auditor's closure attempt returns
  HTTP 404 `NOT_FOUND` with a `EMERGENCY_REVIEW/NOT_FOUND` audit row. The Org A
  grant and its open review survive the refused cross-tenant commands. Forced RLS
  therefore confines the grant and its review to the organization that created it.
- `unapprovedSiteIsRefused` — negative proof. With no approved
  `access.emergency.enabled` entry in Org C (`public.effective_config` returns no
  row), a qualified clinician is refused (HTTP 403, "not approved for this site"),
  no grant is created and one `EMERGENCY_GRANT/DENIED` audit row is recorded.
- `missingReasonOrOutOfRangeDurationIsRefused` — negative proof. A missing or
  blank reason returns 422 `INVALID_REASON`, and lifetimes of 0 and 61 minutes
  return 422 `INVALID_DURATION`; input validation is pre-transactional so no grant
  and no audit row is written. An otherwise identical command with a reason and a
  ten-minute lifetime returns 201, so the refusals are attributable to the inputs.
- `expiredAndStackedGrantsAreRefused` — negative proof. A seeded expired grant
  yields `allowed=false` with reason `EXPIRED` and a denied active-check audit row,
  and it does not block a fresh grant. A seeded unexpired grant makes a second
  grant return HTTP 409 `CONFLICT` with a `CONFLICT` audit row keyed to the
  still-active grant id; no second grant row is created. Emergency access is
  time-limited and cannot be stacked.

Entry-point-to-test trace:

- `POST /api/v1/access/emergency` → `com.aurevia.smartclinic.web`-adjacent
  `com.aurevia.smartclinic.authz.access.AccessController.grant` →
  `com.aurevia.smartclinic.authz.access.EmergencyAccessService.grant` →
  `com.aurevia.smartclinic.authz.access.AccessRules.validateFacility`,
  `validateReason`, `validateDuration`, `isEnabled`, `isClinician` and
  `com.aurevia.smartclinic.authz.access.AccessRepository.insertGrant`,
  `appendAudit`, `openReview` → `db/migrations/V11__emergency_and_support_access.sql`
  (`emergency_access_grant`) and `db/migrations/V88__access_review.sql`
  (`emergency_access_review`).
- `GET /api/v1/access/emergency/active` → `AccessController.active` →
  `EmergencyAccessService.active` → `AccessRepository.latestGrant`/`appendAudit`.
- `POST /api/v1/access/emergency/{id}/review` → `AccessController.review` →
  `EmergencyAccessService.review` → `AccessRepository.reviewGrant`, `closeReview`,
  `appendAudit`.
- `GET /api/v1/access/emergency/review-queue` → `AccessController.reviewQueue` →
  `EmergencyAccessService.reviewQueue` → `AccessRepository.listGrants`.

Where the clause is implemented today (source survey):

- `EmergencyAccessService` re-reads membership, role, site approval and facility
  scope inside each guarded transaction; the organization and subject come from
  `com.aurevia.smartclinic.tenant.TenantContext`, never from the request body.
  Its `grant` method validates the reason and a 1–60 minute lifetime, refuses a
  stacked unexpired grant, and writes the grant, the `EMERGENCY_ALERT` audit fact
  and the V88 review row together.
- `AccessRules` supplies `isClinician`, `isEnabled`, `validateReason` and
  `validateDuration`; `AccessRepository` is the single RLS-bounded SQL boundary;
  `AccessController` is the thin HTTP adapter.
- `db/migrations/V11__emergency_and_support_access.sql` defines the grant table
  with `ENABLE` and `FORCE` row-level security, a tenant-composite facility
  foreign key and `expires_at > granted_at`.
- `db/migrations/V88__access_review.sql` defines the privacy-auditor review table
  with `ENABLE` and `FORCE` row-level security and a check that a closed review
  carries an outcome, an independent reviewer, a note and a closure time.
- Independent existing evidence: `Iam06AcceptanceTest` (a1–a7) and
  `EmergencyAccessReviewIntegrationTest` exercise the same domain services and
  HTTP routes from separately owned suites.

## Gap

None.

The clause's behaviour is implemented and proven by the active tests above; this
story added proof only and did not change production code, an existing test, a
migration or the contract. Publication of the emergency routes in
`contracts/openapi.yaml` and the generated client is a separate, already-tracked
contract gap (`contracts/openapi/missing-routes.txt`), not a missing rule in this
clause, and is deliberately out of scope for this test-only proof.
