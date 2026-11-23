# MSG-02 clause 7 — closed threads are read-only for authorized viewers

## Clause

Closed threads are read-only for authorized viewers.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.ClosedThreadsReadAuthorizedViewersTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ClosedThreadsReadAuthorizedViewersTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: mocked
`ConversationRepository`, `PatientConnectionVerifier` and `AuditAppender`, the
real `ConversationActions` matrix and `ConversationService`, a transaction
template that runs the real guarded callback, and a bound and cleared synthetic
tenant context. Every case enters through `ConversationService`, the boundary
the HTTP controller delegates to; all fixtures use synthetic subjects and text.

- `closedThreadRemainsReadableToAuthorizedViewer` — an eligible lab viewer reads
  a `CLOSED` thread and receives its `View` with the message and work-item
  trails intact; no conversation state or append-only trail is written (rule
  met, staff viewer).
- `closedThreadRemainsReadableToAuthorizedPatient` — the verified patient who
  owns the same closed thread also reads its `View` and both trails, with no
  write (rule met, patient viewer).
- `replyToClosedThreadIsRefusedWithCLOSED_THREAD` — a lab staff reply at the
  current version is refused with 409 `CLOSED_THREAD`; no message, state or
  event write occurs (negative: forbidden state).
- `patientReplyToClosedThreadIsRefusedBeforeConnectionVerification` — the
  verified patient's reply at the current version is refused with 409
  `CLOSED_THREAD` even with an active patient link; connection re-resolution is
  never called and no write occurs, proving the closed guard takes precedence
  over the MSG-01/MSG-07 connection checks (negative: forbidden state).
- `assignTransferResolveAndCloseOnClosedThreadAreRefused` — `assign`,
  `transfer`, `resolve` and `close` are each refused with 409 `CLOSED_THREAD`
  and no write, covering both private mutator guards (negative: forbidden
  state for every work-item command).
- `closedThreadIsNotVisibleToUnauthorizedViewer` — a reception viewer on a lab
  thread is refused with 403 `FORBIDDEN`, and a patient bound to another
  patient's thread with 404 `NOT_FOUND`; both are refused on reads and on a
  mutating reply with a matching version, proving authorization precedes the
  409 closed-state check (negative: wrong role and wrong patient).
- `staleVersionOnClosedThreadIsRejectedBeforeTheStateCheck` — a reply with a
  stale version is refused with 412 `STALE_VERSION` before the closed-state
  check is reached (negative: stale update precedence).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.messaging.conversations.ConversationService`
  owns the rule: `requireWritable` raises `ApiException(409, "CLOSED_THREAD")`
  for a `CLOSED` row. It is called from `reply`, `reassign` (`assign` and
  `transfer`) and `transitionWithReason` (`resolve` and `close`), after
  `requireAccess` and `requireVersion`, so authorization and version take
  precedence. Read paths `get`, `inbox` and `workQueue` never call it.
- `ConversationContract.State` (`CLOSED`, `canTransitionTo`),
  `ConversationActions`, `ConversationRepository`, `ConversationInputs` and
  `ApiException` — the state machine, bounded role/unit matrix, SQL boundary,
  input guards and structured failure used by those commands.
- `com.aurevia.smartclinic.messaging.conversations.ConversationController` —
  `POST /api/v1/conversations/{id}/reply|assign|transfer|resolve|close|read`
  and `GET /api/v1/conversations/{id}|/inbox|/work-queue` are the HTTP entry
  points, with version passed in `If-Match` on every mutating command.
- `db/migrations/V33__conversation_lifecycle.sql` — enables and forces row
  level security with a tenant isolation policy and adds the
  `conversation_update_guard` trigger that rejects any state change out of
  `closed` (`a closed conversation is read-only`), so the invariant is also
  enforced in the database and not only in the service.
- Contract survey: the conversation routes are listed in
  `contracts/openapi.yaml` as
  `GET /api/v1/conversations/{}`, `GET /api/v1/conversations/inbox`,
  `GET /api/v1/conversations/work-queue`, `POST /api/v1/conversations` and
  `POST /api/v1/conversations/{}/assign|close|read|reply|resolve|transfer`.
  They are baselined there and are not described as operations in
  `contracts/openapi.yaml`. That is separate pre-existing contract-documentation
  debt and does not affect whether the behaviour is implemented and proven.

Broader coverage already present (not repeated here): the service-level branches
live in `it/messagingconversations/ConversationServiceTest`, and the
HTTP-plus-PostgreSQL path, including the 409 `CLOSED_THREAD` refusal followed by
a still-successful read after close, lives in
`it/messagingconversations/ConversationFlowTest`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ClosedThreadsReadAuthorizedViewersTest test`
— 7 tests, 0 failures, 0 errors, 0 skipped. With clean Surefire report
directories, the full unit suite reported 3695/0/0/0 and the integration suite
800/0/0/4 (four pre-existing skips), with `ConversationFlowTest` 8/8.

## Gap

None.
