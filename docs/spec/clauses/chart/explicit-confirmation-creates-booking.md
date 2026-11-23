# CLN-06 clause 3 — only explicit confirmation creates a booking

## Clause

Only explicit confirmation creates a booking.

## Status

Proven by tests

## Evidence

HTTP-level proof against a fresh PostgreSQL database migrated from `db/migrations`
under the `synthetic-auth` profile, with synthetic fixtures only. The clause is owned by
the `scheduling.followup` domain, so the test reaches it through its real HTTP entry point
rather than through `chart.record`. Baseline run on the unmodified tree:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=ExplicitConfirmationCreatesBookingTest test`
-> `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (full final line in the worker notes).

- Test class: `com.aurevia.smartclinic.chart.clauses.ExplicitConfirmationCreatesBookingTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ExplicitConfirmationCreatesBookingTest.java`).
- `explicitConfirmationCreatesExactlyOneBookingWithProvenance` — positive proof. `POST
  /api/v1/follow-ups/suggest` returns an unconfirmed suggestion with no `bookingId` and
  leaves the appointment count unchanged. The explicit `POST /api/v1/follow-ups/{id}/confirm`
  with the version read by the caller returns a confirmed suggestion with a `bookingId`,
  increments the tenant's appointment count by exactly one, and the new appointment carries
  `override_reason = "follow-up confirmation"`.
- `suggestOverrideAndTaskCommandsCreateNoBooking` — negative proof. Suggesting, applying an
  authorized override, opening a care-team task and completing that task all leave the
  appointment count unchanged, and the override response carries no `bookingId`. (The
  suggestion's null `bookingId` is asserted in the positive test above.)
- `confirmationWithoutVersionPreconditionCreatesNoBooking` — negative proof. A confirmation
  with no `If-Match` version is refused (HTTP 428) and books nothing.
- `staleVersionConfirmationCreatesNoBooking` — negative proof. A confirmation carrying a
  version other than the one read by the caller is refused (HTTP 412) and books nothing;
  the correct version then books exactly once.
- `repeatedConfirmationCreatesNoSecondBooking` — negative proof. Re-confirming a confirmed
  suggestion is refused (HTTP 409) and the appointment count stays at one.
- `roleWithoutBookingPermissionCreatesNoBooking` — negative proof. A clinician, whose role
  does not grant `APPOINTMENT_BOOK`, cannot confirm (HTTP 403) and books nothing.
- `peerTenantCannotConfirmAnotherTenantsSuggestion` — negative proof. A user of another
  tenant cannot see or confirm the suggestion (HTTP 404) and books nothing in either tenant.

Entry-point-to-test trace: `POST /api/v1/follow-ups/suggest` and `POST
/api/v1/follow-ups/{id}/confirm` -> `FollowUpController` -> `IfMatch.parse` (missing
precondition 428) -> `FollowUpCommandService.confirm` (transaction boundary) ->
`FollowUpService.confirm` (re-checks the booking action and tenant-owned suggestion,
refuses already-confirmed as 409 and a stale version as 412, then makes the single
`FollowUpBooking.book` call) -> `FollowUpBookingService` (fixed 09:00 facility-local slot,
`follow-up confirmation` reason) -> `AppointmentService.book` (shared slot invariants and the
`public.appointment` insert). `JdbcFollowUpStore` then persists the confirmed suggestion and
its `booking_id`. The test drives every step through the HTTP request and asserts the
resulting rows with tenant-scoped JDBC queries.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.scheduling.followup.FollowUpController` — thin adapter publishing
  `POST /api/v1/follow-ups/suggest`, `/{id}/override`, `/{id}/confirm`, `/{id}/tasks` and
  `/tasks/{taskId}/complete`.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` — transactional command
  boundary; makes the confirmation, its booking and its audit facts atomic.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpService` — the invariant owner:
  `confirm` is the only command that calls the booking port, after version, re-confirmation
  and authorization checks; the other commands never call it.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpBookingService` and `FollowUpBooking` —
  the explicit-confirmation booking boundary, delegating to the shared scheduling service with
  the `follow-up confirmation` reason.
- `com.aurevia.smartclinic.api.IfMatch` and `com.aurevia.smartclinic.authz.ActionPolicy` — the
  version precondition (428/412) and the role matrix that refuses a clinician confirmation.
- `com.aurevia.smartclinic.scheduling.AppointmentService` and `JdbcFollowUpStore` — the shared
  appointment insert and the tenant-scoped persistence of the confirmed suggestion and its
  `booking_id`.

The durable suggestion and task tables are created in `db/migrations/V74__build_05.sql` with
`ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY` and tenant isolation policies;
`db/migrations/V8__scheduling.sql` owns the shared appointment table. All five follow-up
routes are published in `contracts/openapi.yaml` (paths at lines 3492, 7146, 7171, 7200 and
7229). The `/confirm` entry does not yet declare the `If-Match` parameter or the 428 and 412
responses; that is an adjacent contract-documentation issue recorded as a separate task in
the worker notes and does not change the runtime rule proved here.

## Gap

None. All seven tests pass against current production and the clause's booking rule is proven
end to end. This note corrects two earlier inaccuracies: the stale claim that the follow-up
routes were absent from `contracts/openapi.yaml`, and a description of task responses that
asserted more than the test checks. It also names the migration that actually owns the
follow-up tables. No production code, existing test, migration or contract was changed by
this evidence task.
