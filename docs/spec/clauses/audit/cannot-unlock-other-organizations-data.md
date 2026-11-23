# IAM-06 clause 3 — it cannot unlock other organizations’ data

## Clause

It cannot unlock other organizations’ data.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.audit.clauses.CannotUnlockOtherOrganizationsDataTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/CannotUnlockOtherOrganizationsDataTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, two synthetic tenants and the same fixtures and database setup as the
neighbouring `authz.clauses` and `authz.access` tests. All four tests are active
(none are disabled) and pass with synthetic data only. The entry-point-to-test trace
is `POST /api/v1/access/emergency` → `GET /api/v1/access/emergency/active` →
`POST /api/v1/access/emergency/{id}/revoke` and `/review`, with the authoritative
organization read back from the grant and review tables after each command.

Test methods (one positive rule proof, three negative refusals):

- `emergencyGrantStaysInsideTheCliniciansOwnOrganization` — positive proof. A
  qualified clinician grants access in ORG_A; the HTTP response is 201 and the
  `emergency_access_grant` and `emergency_access_review` rows both carry ORG_A as
  their `organization_id`, with an open privacy-auditor review. The same subject's
  next `GET .../active` in ORG_A is `allowed=true, reason=ACTIVE`.
- `anotherOrganizationCannotUnlockOrReadTheGrant` — negative tenant proof. The
  same clinician subject is seeded with an active membership and the clinician role
  in *both* ORG_A and ORG_B, so subject filtering cannot fake the result. After an
  ORG_A grant, that subject's `GET .../active` under ORG_B is `allowed=false,
  reason=NO_GRANT`; direct reads under ORG_B see zero grant and zero review rows for
  the ORG_A identifier, while a positive control under ORG_A sees exactly one of
  each. Revoke (as the same ORG_B clinician) and review (as a distinct ORG_B privacy
  auditor, with a valid note and outcome) both return 404 `NOT_FOUND` — the foreign
  rows are concealed, not acknowledged. A final read under ORG_A confirms the
  grant's `revoked_at`/`reviewed_at` and the review's closure fields are still null
  and its status is still open.
- `wrongRoleCannotOpenAnEmergencyGrant` — negative role proof. A `reception` member
  of ORG_A receives 403 `FORBIDDEN` with no grant or review row for that subject,
  and an `EMERGENCY_GRANT` audit row with outcome `DENIED`, purpose
  `EMERGENCY_ACCESS`, scope `clinician:MAIN` and the request's unique correlation id
  is committed.
- `missingOrOutOfOrganizationFacilityIsRefused` — negative input proof. A blank
  reason and an out-of-range duration are refused with 422 and create no grant or
  review row; a well-formed facility code that is absent from the active organization is refused
  with 404 `NOT_FOUND`. No grant or review row is created for the subject.

Production classes and database objects exercised through the normal HTTP entry points:

- Controller: `com.aurevia.smartclinic.web.TenantContextInterceptor` binds the active
  organization from the request; `com.aurevia.smartclinic.authz.access.AccessController`
  maps the grant, active, revoke and review commands and takes no organization,
  actor or role from the body.
- Domain: `com.aurevia.smartclinic.authz.access.EmergencyAccessService` re-reads
  membership and clinician/reviewer roles inside the guarded transaction and binds
  `TenantContext.organizationId()` into the insert commands;
  `com.aurevia.smartclinic.authz.access.AccessRules` validates the bounded command
  fields; `com.aurevia.smartclinic.authz.access.AccessRepository` confines every
  read and write to the forced row-level security policy.
- Data: `db/migrations/V11__emergency_and_support_access.sql` creates
  `emergency_access_grant` with a composite `(organization_id, facility_code)`
  foreign key and forced row-level security; `db/migrations/V88__access_review.sql`
  creates `emergency_access_review` with a composite `(organization_id, grant_id)`
  foreign key and forced row-level security. Both policies are
  `organization_id = public.app_org()`. `db/migrations/V93__membership_facility_scope.sql`
  narrows facility scope inside one organization and does not widen tenant scope.
- Contract: the `/api/v1/access/emergency*` routes the test drives are listed in
  `contracts/openapi/missing-routes.txt`; they are not yet in `contracts/openapi.yaml`,
  a pre-existing documentation gap outside this test-only task's scope.

## Gap

None — for the clause proof.

The clause is a negative guarantee: there is no separate "unlock" endpoint. A grant
only produces an access decision scoped to the active organization, and no request
field can name a foreign organization. The new class proves both halves with a
shared cross-tenant subject: the authoritative rows are created in the caller's own
organization and the same subject cannot read or change them from another one. No
production code, existing test, contract or migration was changed.
