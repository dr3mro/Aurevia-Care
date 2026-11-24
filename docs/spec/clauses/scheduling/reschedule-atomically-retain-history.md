# CLN-01 clause 3 — API test evidence

## Clause

Reschedule atomically and retain history.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.scheduling.clauses.RescheduleAtomicallyRetainHistoryTest`
(path: `apps/api/src/test/java/com/aurevia/scheduling/clauses/RescheduleAtomicallyRetainHistoryTest.java`).
All seven tests are active (none are disabled) and use synthetic data on a dedicated PostgreSQL
database migrated from `db/migrations` with forced row-level security. They drive the real HTTP
entry point with the synthetic-auth profile, the same fixtures and the same style as the
neighbouring `com.aurevia.scheduling.it.SeriesHttpIntegrationTest`.

- `rescheduleMovesTheSlotAndRetainsHistoryInOneCommit` — positive proof. A booked appointment
  is moved through `POST /api/v1/appointments/{id}/reschedule` under an `If-Match` version. The
  response carries the new start and version 2; `GET /api/v1/appointments/{id}` returns exactly
  two history entries: the original `booked` event followed by a `rescheduled` event bound to
  version 2, recording `previousStartsAt`, `previousEndsAt`, the override reason and the actor.
  A direct database check confirms both the new `starts_at` and two durable `appointment_event`
  rows, so the slot change and the history append are one commit.
- `staleVersionRefusesTheMoveAndWritesNoHistory` — negative proof (stale version). An `If-Match`
  of 9 is refused with HTTP 412 `STALE_VERSION`; the stored slot and the single history event
  are unchanged, proving the failed command left no partial write.
- `occupiedTargetSlotRefusesTheMoveAndWritesNoHistory` — negative proof (forbidden state of the
  target slot). A separately seeded appointment occupies the target protected slot. The move is
  refused with HTTP 409 `SLOT_CONFLICT` from the database exclusion constraint; the original
  slot and its single history event are unchanged.
- `failedRescheduleRollsBackTheStaleHoldReleaseToo` — negative proof of atomicity across all
  writes in the command. The target is booked on a different clinician while a lapsed
  clinician-only hold overlaps the same target on the appointment's current clinician. The
  reschedule releases that hold first and then fails the slot update with HTTP 409
  `SLOT_CONFLICT`. Direct database checks confirm the hold is still `held` at version 1 with no
  `expired` history event, so the release and its event were rolled back with the failed move.
- `terminalAppointmentRefusesTheMoveAndKeepsItsHistory` — negative proof (forbidden source
  state). After cancellation the appointment is terminal; rescheduling is refused with HTTP 409
  `INVALID_TRANSITION`, and the history holds only `booked` and `cancelled` with no `rescheduled`
  event.
- `peerTenantCannotRescheduleAnotherTenantsAppointment` — negative proof (another tenant). The
  peer organization uses the same local codes but cannot see the appointment under forced RLS;
  the move is refused with HTTP 404 and the original slot and history are unchanged.
- `roleWithoutReschedulePermissionCannotMoveAnAppointment` — negative proof (wrong role). A
  clinician has active membership but no `APPOINTMENT_RESCHEDULE` permission; the move is
  refused with HTTP 403 `FORBIDDEN` and nothing is written.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.web.AppointmentController` — thin adapter; `POST /api/v1/appointments/{id}/reschedule`
  parses `If-Match` and delegates, `GET /api/v1/appointments/{id}` returns the view with history.
- `com.aurevia.scheduling.AppointmentService` — `reschedule(String, RescheduleRequest, int)`
  runs authorization, version check, terminal-state guard, availability check, resource locking
  and the slot update plus history append inside one tenant-guarded `TransactionTemplate`;
  `get(String)` reads the append-only history.
- `com.aurevia.scheduling.AppointmentRepository` — `loadForUpdate`, `updateSlot`
  (version compare-and-set, increment and new slot), `appendEvent` (immutable revision-bound
  history row) and `history`.
- `com.aurevia.scheduling.RescheduleRequest`, `Appointment`, `AppointmentEvent`,
  `AppointmentView` and `AppointmentState` — command, stored row, history entry, read view and
  the state machine that marks cancelled appointments terminal.
- `com.aurevia.api.IfMatch` and `SqlFailureTranslator` — precondition parsing and
  the `SLOT_CONFLICT` mapping.
- `com.aurevia.authz.ActionPolicy` — `APPOINTMENT_RESCHEDULE` is allowed for
  reception and tenant admin, not for the clinician role used by the wrong-role test.
- `db/migrations/V8__scheduling.sql` — the `appointment_event` append-only ledger with forced
  RLS, one event per appointment revision, and the partial exclusion constraints that make a
  double-booked slot uncommittable.

Neighbouring implementations observed in the source survey (not changed by this task): the
unit-level `AppointmentServiceTest` already covers each service branch of `reschedule`, and the
`SeriesHttpIntegrationTest` reschedules one series occurrence as part of a broader series test.
This task adds a clause-focused HTTP proof of the atomicity and history guarantee.

## Gap

None.

The CLN-01 clause-3 rule is built and proven by active tests. This task added proof only; it did
not change production code, an existing test, the OpenAPI contract or a migration.
