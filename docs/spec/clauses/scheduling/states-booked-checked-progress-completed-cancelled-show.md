# CLN-01 clause 4 — appointment state chain and branches

## Clause

States are booked → checked in → in progress → completed, with cancelled or no-show branches.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.scheduling.clauses.StatesBookedCheckedProgressCompletedCancelledShowTest`
(`apps/api/src/test/java/com/aurevia/scheduling/clauses/StatesBookedCheckedProgressCompletedCancelledShowTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic clinician, reception and patient fixtures. It proves the clause through
the real REST entry point, not just the domain enum:

- `bookedMovesThroughCheckedInAndInProgressToCompleted` — one appointment is
  booked (reception), checked in (reception), started (clinician) and completed
  (clinician); each response is 200 with the expected `state` and the read-back
  history records `booked`, `checked_in`, `started`, `completed` in order.
- `cancellingRecordsAReasonAndEndsTheLifecycle` — the cancelled branch records
  the reason, ends the lifecycle and a later check-in is refused with 409.
- `aNoShowRecordsAReasonAndEndsTheLifecycle` — the no-show branch records the
  reason and ends the lifecycle with a `no_show` history action.
- `aBookedAppointmentCannotSkipStraightToCompleted` — a clinician may complete
  only after check-in; a booked appointment is refused with 409
  `INVALID_TRANSITION` and stays booked.
- `aStaleVersionCannotAdvanceTheState` — a stale `If-Match` is refused with 412
  `STALE_VERSION` and the state does not move.
- `receptionCannotStartAnEncounter` — the wrong role is refused with 403
  `FORBIDDEN`; clinical transitions are separated from clerical ones.
- `cancellingWithoutAReasonIsRejected` — a missing required reason is refused
  with 422 `REASON_REQUIRED`.
- `aPeerTenantCannotTransitionAnotherTenantsAppointment` — another tenant's
  actor cannot transition the appointment (404) and the state is unchanged.
- `aReadBackAppointmentKeepsTheFinalStateAfterTheChain` — the final `completed`
  state and full history survive a fresh read after the committing requests.

Production classes exercised through `com.aurevia.web.AppointmentController`
(`POST /api/v1/appointments`, `/{id}/check-in`, `/{id}/start`, `/{id}/complete`,
`/{id}/cancel`, `/{id}/no-show`, `GET /{id}`):

- `com.aurevia.scheduling.AppointmentService` — `book`, `checkIn`,
  `start`, `complete`, `cancel`, `noShow` and the guarded `transition` command
  (reason gate, legal-transition gate, optimistic version check, audit).
- `com.aurevia.scheduling.AppointmentState` — the transition matrix
  and `requiresReason`, `eventAction`, `isTerminal` rules.
- `com.aurevia.authz.ActionPolicy` — separates clerical transitions
  from the clinical `start` and `complete` actions.
- `com.aurevia.api.IfMatch` and `com.aurevia.api.ApiException`
  — precondition parsing and structured 409/412/422/403 failures.
- `com.aurevia.scheduling.AppointmentRepository` and the `appointment`
  / `appointment_event` tables plus the `appointment_update_guard` trigger from
  `db/migrations/V8__scheduling.sql` (RLS/FORCE RLS and append-only history).

Discovery also found the same transition matrix asserted at unit level in
`com.aurevia.scheduling.AppointmentStateTest` and command-level
service tests in `AppointmentServiceTest`; this story proves the clause through
the HTTP boundary with committed state, which those suites do not cover.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StatesBookedCheckedProgressCompletedCancelledShowTest test`
— see `docs/pr/notes/SPEC-CLN-01-C04-20261122-123030.md` for the exact final lines.

## Gap

None.
