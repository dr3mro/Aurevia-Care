# CLN-01 clause 5 — record reason and actor

## Clause

Record reason and actor for overrides, cancellation and no-show.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.scheduling.clauses.RecordReasonActorOverridesCancellationShowTest`
(`apps/api/src/test/java/com/aurevia/scheduling/clauses/RecordReasonActorOverridesCancellationShowTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing the same facility, resource and patient codes:

- `overrideBookingRecordsTheReasonAndAuthenticatedActor` — a booking outside the
  approved availability window succeeds only with an `overrideReason`, which is
  persisted on `appointment.override_reason` and returned on the appointment
  projection. The creation event records the authenticated actor (the subject's
  identity id, derived by the V8 trigger); the creation event itself carries no
  reason, so the override reason is not claimed to be in the append-only history.
- `cancellationRecordsTheReasonAndAuthenticatedActor` — `POST /{id}/cancel` with a
  reason moves `booked → cancelled` and appends a `cancelled` history event with
  the reason, the from/to states and the authenticated actor.
- `noShowRecordsTheReasonAndAuthenticatedActor` — `POST /{id}/no-show` with a
  reason moves `booked → no_show` and appends a `no_show` history event with the
  reason and the authenticated actor.
- `cancellationWithoutReasonIsRefusedAndLeavesNoHistoryEvent` and
  `noShowWithoutReasonIsRefusedAndLeavesNoHistoryEvent` — an absent reason is
  refused with `422 REASON_REQUIRED` and leaves the appointment booked with no
  extra history event.
- `blankCancellationReasonIsRefusedAndLeavesNoHistoryEvent` and
  `blankNoShowReasonIsRefusedAndLeavesNoHistoryEvent` — a whitespace-only reason
  is refused at the HTTP boundary with `422 REASON_REQUIRED`, also leaving the
  appointment booked with no extra history event.
- `cancelIgnoresAClientSuppliedActor` — a cancel body carrying `actorId` and
  `createdBy` fields naming another tenant's subject still records the
  authenticated subject's identity id, so a client cannot forge the actor.
- `bookingOutsideAvailabilityWithoutOverrideReasonIsRefused` — the outside-window
  slot is refused with `422 OUTSIDE_AVAILABILITY` when no override reason is
  supplied.
- `cancellationWithStaleVersionIsRefused` — after a successful check-in advances
  the version from 1 to 2, reusing `If-Match: "1"` is refused with
  `412 STALE_VERSION`, so a reason cannot be recorded against a superseded
  revision.
- `peerTenantCannotCancelAnotherTenantsAppointment` — a peer tenant's actor is
  refused with `404` on the other tenant's appointment, so neither reason nor
  actor crosses the tenant boundary.

Production classes exercised through the HTTP entry point
`POST /api/v1/appointments` and `POST /api/v1/appointments/{id}/{cancel,no-show}`:

- `com.aurevia.web.AppointmentController` — thin adapter; parses
  `If-Match` and the recorded `ReasonRequest`.
- `com.aurevia.scheduling.AppointmentService` — `createOne` records
  the override reason, `checkAvailability` requires it outside approved windows,
  and `transition` / `cancel` / `noShow` require and forward the reason.
- `com.aurevia.scheduling.AppointmentState` — `requiresReason()` is
  true only for `CANCELLED` and `NO_SHOW`; `canTransitionTo` gates the branches.
- `com.aurevia.scheduling.SchedulingInputs` — normalizes the reason
  and rejects an over-long or blank value.
- `com.aurevia.scheduling.AppointmentRepository` and
  `com.aurevia.scheduling.AppointmentEvent` — persist the append-only
  history event with its reason and actor, exposed by `AppointmentView`.
- `db/migrations/V8__scheduling.sql` — `appointment.override_reason` and
  `appointment_event.reason` columns, the `appointment_event_actor` trigger that
  derives `actor_id` from the authenticated `app.actor`, forced tenant RLS and
  the database state-machine guard.
- `com.aurevia.authz.ActionPolicy` and `com.aurevia.tenant.TenantContext`
  — action and tenant authorization re-checked inside the guarded transaction.

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecordReasonActorOverridesCancellationShowTest test`
— `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` (see the story notes for the
captured final lines).

## Gap

None. The clause is implemented and proven end to end. The published
`contracts/openapi.yaml` does not yet list the `/api/v1/appointments/{}` action
paths (they are in `contracts/openapi.yaml`), but that contract
publication is a separate, already-tracked debt (FIX-OPENAPI-03) outside this
test-only story; the tests drive the real controller routes directly.
