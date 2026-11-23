# CLN-06 clause 3 — only explicit confirmation creates a booking

## Clause

Only explicit confirmation creates a booking.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.ExplicitConfirmationCreatesBookingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ExplicitConfirmationCreatesBookingTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database
(`smartclinic_explicit_confirmation_it`, created with `DROP DATABASE ... WITH (FORCE)` and
migrated from `db/migrations`) with two synthetic organizations sharing local facility,
clinician and visit-type codes:

- `explicitConfirmationCreatesExactlyOneBookingAtTheOverriddenDate` — a `reception` actor
  suggests a follow-up (HTTP 200, `confirmed=false`, `bookingId=null`, no appointment row),
  overrides the date with a recorded reason (HTTP 200, version 2, still unconfirmed and no
  appointment row), then confirms with `If-Match: "2"`. Confirmation returns 200 with
  `confirmed=true` and a non-blank `bookingId`; `public.appointment` gains exactly one row.
  The row is located by the returned `bookingId` and carries `state=booked`,
  `visit_type=FOLLOW`, `override_reason='follow-up confirmation'` (the booking provenance,
  distinct from the suggestion's own `clinician directed` override reason), a persisted
  `starts_at` instant of `2027-03-25T13:00:00Z` and a facility-local start of `2027-03-25 09:00`
  in `America/New_York` (after the spring-forward, UTC-4).
- `clinicianCannotConfirmBecauseBookingIsNotTheirAction` — a clinician's confirmation is
  refused (403) and the appointment count is unchanged: booking is not a clinician action.
- `peerTenantCannotConfirmAnotherTenantsSuggestion` — a peer organization's confirmation is
  refused (404 under V74 forced RLS) and neither tenant's appointment count changes.
- `staleVersionDoesNotBook` — confirmation with a stale `If-Match` is refused (412) and the
  appointment count is unchanged.
- `secondConfirmationDoesNotBookAgain` — the first confirmation creates one appointment
  (200), and replaying it at the next version is refused (409) with the count still at one.

Production classes exercised through `POST /api/v1/follow-ups/suggest`,
`POST /api/v1/follow-ups/{id}/override` and `POST /api/v1/follow-ups/{id}/confirm`:
`com.aurevia.smartclinic.scheduling.followup.FollowUpController` (thin HTTP adapter that binds
`If-Match` and injects the command boundary),
`com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` (transaction boundary),
`com.aurevia.smartclinic.scheduling.followup.FollowUpService` (`suggest`/`override` never call
the booking port; `confirm` calls it exactly once after version and state checks),
`com.aurevia.smartclinic.scheduling.followup.FollowUpSuggestion` (new confirmed version),
`com.aurevia.smartclinic.scheduling.followup.FollowUpBookingService` (delegates to shared
scheduling at 09:00 with reason `follow-up confirmation`),
`com.aurevia.smartclinic.scheduling.followup.JdbcFollowUpStore` (persists `confirmed` and
`booking_id`), `com.aurevia.smartclinic.api.IfMatch` (version precondition),
`com.aurevia.smartclinic.authz.ActionPolicy` (reception may book, clinician may not),
`com.aurevia.smartclinic.scheduling.AppointmentService` (shared booking invariants) and
`db/migrations/V74__build_05.sql` (ENABLE + FORCE RLS for suggestions and tasks); the
appointment row and its protected-slot constraint come from `db/migrations/V8__scheduling.sql`.

The mandated test package is `chart.clauses`, matching this clause document's location, while
the production implementation lives in `scheduling.followup`; no follow-up production class
exists under `chart`. Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.scheduling.followup.it.FollowUpHttpIntegrationTest` and
`com.aurevia.smartclinic.it.schedulingfollowup.FollowUpServiceTest`.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security
-Dtest=ExplicitConfirmationCreatesBookingTest test` — `tests="5" errors="0" skipped="0"
failures="0"` in the Surefire XML report. "Proven by tests" is limited to the clause behaviour
above; it does not claim that the follow-up routes are published in the committed OpenAPI
contract.

## Gap

None for this clause. Adjacent and out of scope: the five `/api/v1/follow-ups/*` routes
(`suggest`, `{id}/override`, `{id}/confirm`, `{id}/tasks`, `tasks/{id}/complete`) appear only
in `contracts/openapi/missing-routes.txt:214-218`, not in `contracts/openapi.yaml`, so no
generated client entry exists for them. Publishing that route family is a separate
contract/WEB-05 task and would change `contracts/openapi.yaml`, the generated client and the
contract tests; this test-only story does not edit those files.
