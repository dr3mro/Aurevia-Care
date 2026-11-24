# CLN-01 clause 2 — API test evidence

## Clause

Prevent overlap for protected resources transactionally.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.scheduling.clauses.PreventOverlapProtectedResourcesTransactionallyTest`
(path: `apps/api/src/test/java/com/aurevia/scheduling/clauses/PreventOverlapProtectedResourcesTransactionallyTest.java`).
All three tests are active (none disabled) and pass against real PostgreSQL with synthetic fixtures only.

- `twoConcurrentBookingsOfOneProtectedSlotLeaveExactlyOneCommittedAppointment` — positive proof of the
  transactional rule. Two simultaneous HTTP bookings claim the same clinician and room slot; exactly one
  returns 201 and the other 409, and only one committed row exists for that slot. This shows the advisory
  lock taken inside the tenant-guarded transaction and the database exclusion constraint serialising the
  two writers.
- `anOverlappingBookingOfTheSameProtectedClinicianIsRefusedAndWritesNothing` — negative proof. A slot is
  already committed on clinician `DR-A`; a new single booking overlapping it is refused with 409
  `SLOT_CONFLICT` and the appointment count is unchanged. A single booking has no application-level
  pre-check, so this 409 is raised by the exclusion constraint inside the transaction.
- `anOverlappingBookingOfTheSameProtectedRoomIsRefusedEvenForADifferentClinician` — negative proof for the
  second protected resource. The committed slot uses `DR-B`/`ROOM-1` and the attempt uses `DR-A`/`ROOM-1`:
  only the room overlaps, yet the rule still refuses it with 409 `SLOT_CONFLICT` and writes nothing.

Production classes exercised through the normal HTTP entry point `POST /api/v1/appointments`
(`com.aurevia.web.AppointmentController`):

- `com.aurevia.scheduling.AppointmentService` — opens one tenant-guarded `TransactionTemplate`
  per booking, re-checks membership and permission, validates availability, takes deterministic advisory
  locks on the protected resources before the insert, and translates a constraint failure to a 409.
- `com.aurevia.scheduling.AppointmentRepository` — `lockResources` locks clinician then room in
  sorted order with `pg_advisory_xact_lock`; `expireStaleHolds` releases lapsed overlapping holds; `insert`
  lets the database reject a protected-slot collision.
- `com.aurevia.api.SqlFailureTranslator` — maps the exclusion-constraint SQL state `23P01` to
  the stable `409 SLOT_CONFLICT` contract without revealing which protected resource collided.
- `db/migrations/V8__scheduling.sql` — `appointment_clinician_slot_excl` and `appointment_room_slot_excl`
  partial GiST exclusion constraints, with row level security enabled and forced, are the transactional
  authority for slot exclusivity.

Neighbouring implementations observed in the source survey (not changed by this task):
`AppointmentService.bookSeries` pre-checks every occurrence with `AppointmentRepository.findConflicts` and
rejects the whole series with `SeriesConflictException` (409 `SERIES_CONFLICT`) before any row is written;
`reschedule` locks the row with `FOR UPDATE` and rechecks the version; `AppointmentState` folds lapsed holds
to `expired`. The route is served by `AppointmentController`, documented in `contracts/openapi.yaml`, and the
scheduling schema lives under `db/migrations`.

## Gap

None.
