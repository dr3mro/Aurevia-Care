# CLN-06 clause 3 — only explicit confirmation creates a booking

## Clause

Only explicit confirmation creates a booking.

## Status

Proven by tests

## Evidence

HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated from
`db/migrations`, synthetic fixtures only). The owning production code is the `scheduling.followup`
domain, so the test reaches it through its real HTTP entry point rather than through `chart.record`.

- Test class: `com.aurevia.smartclinic.chart.clauses.ExplicitConfirmationCreatesBookingTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ExplicitConfirmationCreatesBookingTest.java`).
- `explicitConfirmationCreatesExactlyOneBookingWithProvenance` — positive proof. `POST /api/v1/follow-ups/suggest`
  returns an unconfirmed suggestion with no `bookingId` and leaves the appointment count unchanged.
  The explicit `POST /api/v1/follow-ups/{id}/confirm` with the version read by the caller returns a
  confirmed suggestion with a `bookingId`, increments the tenant's appointment count by exactly one,
  and the new appointment carries `override_reason = "follow-up confirmation"`.
- `suggestOverrideAndTaskCommandsCreateNoBooking` — negative proof. Suggesting, applying an
  authorized override, opening a care-team task and completing that task all leave the appointment
  count unchanged; the suggestion responses carry no `bookingId` and the task responses carry no
  appointment reference, so none of these commands books.
- `confirmationWithoutVersionPreconditionCreatesNoBooking` — negative proof. A confirmation with no
  `If-Match` version is refused (HTTP 428) and books nothing.
- `staleVersionConfirmationCreatesNoBooking` — negative proof. A confirmation carrying a version
  that is not the version read by the caller is refused (HTTP 412) and books nothing; the correct
  version then books exactly once.
- `repeatedConfirmationCreatesNoSecondBooking` — negative proof. Re-confirming a confirmed
  suggestion is refused (HTTP 409) and the appointment count stays at one.
- `roleWithoutBookingPermissionCreatesNoBooking` — negative proof. A clinician, whose role does not
  grant `APPOINTMENT_BOOK`, cannot confirm (HTTP 403) and books nothing.
- `peerTenantCannotConfirmAnotherTenantsSuggestion` — negative proof. A user of another tenant
  cannot see or confirm the suggestion (HTTP 404) and books nothing against it.
- `invalidVersionConfirmationCreatesNoBooking` — negative proof. A confirmation carrying a
  non-positive `If-Match` version is refused at the HTTP precondition boundary (HTTP 422,
  `INVALID_IF_MATCH`) before the command runs, and books nothing.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.scheduling.followup.FollowUpController` — thin adapter publishing
  `POST /api/v1/follow-ups/suggest`, `/{id}/override`, `/{id}/confirm`, `/{id}/tasks` and
  `/tasks/{taskId}/complete`.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` — transactional command
  boundary; makes the confirmation, its booking and its audit facts atomic.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpService` — the invariant owner: `confirm`
  is the only command that calls the booking port, after version, re-confirmation and
  authorization checks; the other commands never call it.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpBookingService` and `FollowUpBooking` —
  the explicit-confirmation booking boundary, delegating to the shared scheduling service with the
  `follow-up confirmation` reason.
- `com.aurevia.smartclinic.api.IfMatch` and `com.aurevia.smartclinic.authz.ActionPolicy` — the
  version precondition and the role matrix that refuses a clinician confirmation.
- `com.aurevia.smartclinic.scheduling.AppointmentService` and `JdbcFollowUpStore` — the shared
  appointment insert and the tenant-scoped persistence of the confirmed suggestion and its
  `booking_id`.

Adjacent production found in the survey but not part of this clause's proof: `chart.record` holds
the CLN-03 longitudinal chart and has no booking behaviour. The follow-up schedule, override,
booking and task model lives in `scheduling.followup`; its durable `follow_up_suggestion` and
`care_team_task` tables, each protected by row-level security with FORCE ROW LEVEL SECURITY, are
created by `db/migrations/V74__build_05.sql`. All five follow-up routes are published in
`contracts/openapi.yaml` (tasks 3492, suggest 7146, complete 7171, confirm 7200, override 7229),
so this HTTP proof reaches the same routes the published contract describes.

## Gap

None. The clause is implemented and proven by the active tests above. This evidence task added only
the test class and this note: it changed no production code, no existing test, no migration and no
contract.
