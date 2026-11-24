# IAM-01 clause 4 — facility scope may further narrow access

## Clause

Facility scope may further narrow access.

## Status

Not built

## Evidence

The would-prove API test is `apps/api/src/test/java/com/aurevia/authz/clauses/FacilityScopeFurtherNarrowAccessTest.java`. It is disabled (`@Disabled("GAP: ...")`) because the behaviour is absent, so the branch stays green. When enabled it contains:

- `facilityScopeAllowsAccessToTheAssignedFacility` — a reception membership issued with a facility assignment reads that facility's worklist and gets HTTP 200 (rule met).
- `facilityScopeDeniesAccessToAnotherFacilityOfTheSameOrganization` — the same role and membership are refused HTTP 403 for a second facility of the same organization (negative test). Today this answers HTTP 200, which is the gap.

The test uses the same synthetic PostgreSQL setup as `it/StaffLifecycleIsolationTest`: `X-Synthetic-Subject` and `X-Active-Organization` headers, Flyway migrations from `db/migrations`, and two seeded facilities in one tenant. The invitation endpoint (`/api/v1/staff-invitations` with `facilityId`, then `/accept`) expresses the facility assignment; the worklist endpoint (`/api/v1/clinic/worklist?facilityCode=...`) is the access under test.

Production classes surveyed (none of them narrow access by facility today):

- `com.aurevia.authz.ActionPolicy` and `scheduling.checkin.WorklistPolicy` — role/action matrices with no facility input.
- `com.aurevia.scheduling.checkin.CheckInService.authorize` — checks active membership and role permission only; `worklist` passes the caller-supplied facility code straight to the repository.
- `com.aurevia.authz.access.EmergencyAccessService` — records a `facilityCode` on the grant for the audit scope, but `active()` checks membership, clinician role, revocation and expiry only.
- `com.aurevia.authz.access.AccessRepository` — `facilityExists(code)` only checks that a facility belongs to the active organization.
- `com.aurevia.staff.StaffInvitationService` and `V70__build_01.sql` `accept_staff_invitation` — the invitation carries `facility_id`, but acceptance inserts a facility-free `membership` row.
- `V1__tenant_schema.sql` defines the tenant-owned `facility` table; there is no `staff_facility`, `user_facility`, `membership_facility` or `facility_assignment` table, and `membership`/`role_assignment` have no facility column.
- `com.aurevia.web.FacilityController`, `FacilityService` — facility CRUD, not an authorization scope.
- No contract under `contracts/` describes a facility-scope claim or input.

## Gap

Facility scope is a recorded attribute, not an enforced authorization boundary. A facility is stored on a staff invitation and on an emergency grant, but acceptance does not propagate it to the membership, and no `authorize`/policy decision consults a user's assigned facilities before granting access to a resource of another facility in the same organization.

To build it:

1. Persist facility scope on the identity model — add a tenant-owned, forced-RLS association between membership (or role assignment) and `facility` in a new Flyway migration; propagate the invitation's `facility_id` in `V70__build_01.sql`'s `accept_staff_invitation`; expose it through the staff store and service.
2. Enforce it at every trust boundary that is already facility-aware — `CheckInService.worklist` and the other check-in commands, `AppointmentService`, and the emergency/support decisions in `authz/access`, by narrowing the resource set to the caller's assigned facilities (and deciding the admin/privacy-auditor exception). Update the action-policy contract and OpenAPI with the scope claim.
3. Enable these two tests (remove `@Disabled`) and add the equivalent negative cases for the other facility-aware endpoints.

Estimate: two implementation tasks (persistence, then enforcement) plus one test-enablement task. This task is test-only by scope and does not write production code, migrations, the contract or an existing test.
