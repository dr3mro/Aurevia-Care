# MSG-02 clause 5 — conversation work-item state tracking

## Clause

Track awaiting staff, awaiting patient, resolved and closed states.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.messaging.clauses.TrackAwaitingStaffAwaitingPatientResolvedClosedTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/TrackAwaitingStaffAwaitingPatientResolvedClosedTest.java`),
a database-free, service-level proof. Its entry point is `ConversationService`,
not the state enum: every state change is asserted through the persisted wire key
passed to `ConversationRepository.updateState` and the work-item fact passed to
`ConversationRepository.appendEvent`, so a stubbed read-back cannot hide a wrong
write. Fixtures use synthetic UUIDs, a fixed clock, synthetic subject keys and a
real in-memory care-unit directory; the repository, transaction template and
audit appender are mocked.

- `workItemMovesFromAwaitingStaffToAwaitingPatientThenResolvedAndClosed` — one
  work item is answered by staff (`awaiting_staff` → `awaiting_patient`, wire key
  `awaiting_patient`), resolved with a reason (`resolved` event from
  `awaiting_patient` to `resolved`) and closed with a reason (`closed` event from
  `resolved` to `closed`). Each returned projection is asserted and each
  `updateState`/`appendEvent` call is verified with its exact wire arguments.
- `aReplyReopensAResolvedWorkItemToAwaitingStaff` — a patient reply on a resolved
  thread moves it back to `awaiting_staff` and appends a `reopened` fact from
  `resolved` to `awaiting_staff`.
- `aReplyToAResolvedWorkItemWithARevokedLinkDoesNotReopen` — the clause's
  "if the relationship still permits messaging" condition is enforced: an
  inactive verified link is refused with 404 `NOT_FOUND` and no message, state
  change or reopen fact is written.
- `replyToAClosedThreadIsRefusedAndStateIsUnchanged` — a closed thread is
  read-only for an authorized viewer: a reply is refused with 409 `CLOSED_THREAD`,
  no write occurs and a read-back still reports `closed`.
- `aStaleVersionCannotMoveTheWorkItem` — a stale precondition is refused with
  412 `STALE_VERSION` and no state change.
- `wrongUnitStaffCannotTransitionTheWorkItem` — staff outside the conversation's
  unit are refused with 403 `FORBIDDEN`.
- `resolvingWithoutAReasonIsRefused` — blank input is refused with 422
  `INVALID_REASON` before any repository call.
- `aPatientCannotTransitionAnotherPatientsWorkItem` — a patient cannot act on
  another patient's thread; the request is concealed with 404 `NOT_FOUND` and the
  work item is unchanged. This proves subject-scoped concealment, not cross-tenant
  RLS (which is enforced in the database and proven by `ConversationFlowTest`).

Production classes exercised: `com.aurevia.messaging.conversations.ConversationService`
(`reply` reopen, `resolve`, `close`, `get` and the `requireWritable`, `requireVersion`
and `requireAccess` guards), `com.aurevia.messaging.conversations.ConversationContract`
(`State` with the four wire keys, `WorkEventType.REOPENED`/`RESOLVED`/`CLOSED`),
`com.aurevia.messaging.conversations.ConversationInputs` (reason gate),
`com.aurevia.messaging.conversations.ConversationActions`,
`com.aurevia.messaging.units.CareUnitDirectoryService` with
`InMemoryMessagingStore`, and the mocked `ConversationRepository` boundary.
`ConversationService` enforces terminality through `requireWritable`; the pure
`State.canTransitionTo` matrix is covered separately by
`ConversationContractTest.stateAllowsEveryForwardTransitionButLeavingClosed`.
`db/migrations/V33__conversation_lifecycle.sql` provides the database second line
of defence: the `state`/`event_type` checks, forced RLS and the
`conversation_update_guard` trigger that makes a closed thread read-only.
The HTTP-level, real-PostgreSQL proof of the same open → reply → resolve →
reopen → close chain is `ConversationFlowTest`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TrackAwaitingStaffAwaitingPatientResolvedClosedTest test`
— 8 tests, 0 failures, 0 skipped; see
`docs/pr/notes/SPEC-MSG-02-C05-20261122-231913.md` for the exact final lines.

## Gap

None for the state-tracking clause. The public contract entry for conversation
routes is tracked separately and is out of scope for this test-only story.
