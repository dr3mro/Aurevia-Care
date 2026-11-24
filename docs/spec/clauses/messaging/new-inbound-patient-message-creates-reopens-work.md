# MSG-02 clause 3 — every new inbound patient message creates or reopens a work item

## Clause

Every new inbound patient message creates or reopens a work item.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.messaging.clauses.NewInboundPatientMessageCreatesReopensWorkTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/NewInboundPatientMessageCreatesReopensWorkTest.java`).
It is a synthetic, database-free HTTP-level clause test in the same `clauses`
style as `scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: a real
`ConversationService` and `ConversationController` under standalone MockMvc,
mocked `ConversationRepository`, `PatientConnectionVerifier` and `AuditAppender`,
a synchronous transaction template that runs the real guarded callback,
`ApiErrorHandler` advice and a synthetic `TenantContext`. Every case enters
through `POST /api/v1/conversations` or `POST /api/v1/conversations/{id}/reply`
with an `If-Match` precondition. No patient data, credentials or real domain
names are present.

- `patientOpenCreatesAWorkItemAndQueuesItForStaff` — a patient opens a thread
  through a verified connection; the append-only work ledger receives one
  `CREATED` fact from `null` to `awaiting_staff`, the conversation is projected
  as `awaiting_staff`, and a staff caller reading `GET /api/v1/conversations/work-queue`
  sees the same conversation in that state (rule met: create).
- `patientReplyToResolvedThreadReopensTheWorkItem` — a patient replies to a
  `resolved` thread at the current version; the connection is re-resolved, the
  message is appended, the state moves to `awaiting_staff` and the work ledger
  receives one `REOPENED` fact from `resolved` to `awaiting_staff` (rule met:
  reopen).
- `patientReplyWhileAwaitingPatientKeepsTheWorkItemActiveAwaitingStaff` — a
  reply on an already-open thread returns the single work item to
  `awaiting_staff` with its message and state written, and appends no second
  work fact (see the interpretation below).
- `patientReplyIsRefusedWhenThePatientLinkIsInactive` — a resolved thread whose
  verified patient link is no longer active is refused with 404 `NOT_FOUND`, so
  the "while the relationship still permits messaging" proviso holds and no
  work item is reopened; no message, state or work write occurs.
- `patientReplyIsRefusedWhenTheVerifiedConnectionIsRevoked` — an otherwise
  current thread whose exact connection has been revoked is refused with 403
  `FORBIDDEN` at the re-resolution boundary, with no writes (negative: the
  relationship no longer permits messaging, MSG-01 and MSG-07).
- `patientReplyIsRefusedOnAClosedThread` — a closed thread is refused with 409
  `CLOSED_THREAD` and no write occurs (negative: forbidden state).
- `patientReplyIsRefusedWithAStaleVersion` — an out-of-date `If-Match` is
  refused with 412 `STALE_VERSION` before any write occurs (negative: stale
  compare-and-set).
- `replyByAnUnauthorisedRoleIsRefused` — a `tenant_admin` has no reply
  permission; the request is refused with 403 `FORBIDDEN`, the denial is
  audited and no write occurs (negative: wrong role).
- `otherPatientsThreadIsConcealed` — a patient replying to another patient's
  thread receives 404 `NOT_FOUND`, not 403, so a foreign conversation cannot be
  distinguished by its failure (negative: wrong patient, ARC-04 concealment).

Production classes and files surveyed and exercised by these entry points:

- `com.aurevia.messaging.conversations.ConversationController` —
  `POST /api/v1/conversations`, `POST /api/v1/conversations/{id}/reply` and
  `GET /api/v1/conversations/work-queue`; it injects the service and parses
  `If-Match`.
- `com.aurevia.messaging.conversations.ConversationService` —
  `open` (derives the patient, unit and connection identities from the verified
  connection, then writes the conversation and the `CREATED` work fact),
  `reply` (re-resolves the connection, writes the message and state transition,
  and appends `REOPENED` only from `RESOLVED`), `workQueue`, `authorize`,
  `requireAccess`, `requireVersion` and `requireWritable`.
- `com.aurevia.messaging.conversations.ConversationContract`
  (`State`, `WorkEventType`, `OpenRequest`, `ReplyRequest`, `View`, `WorkQueue`),
  `ConversationActions` (patient work-item role matrix), `ConversationInputs`,
  `ConversationRepository` (`insertConversation`, `appendEvent`,
  `insertMessage`, `updateState`, `find`, `messages`, `events`, `workQueue`),
  `PatientConnectionVerifier`, `PatientConnectionVerifier.VerifiedConnection`,
  `com.aurevia.api.ApiErrorHandler`, `com.aurevia.api.IfMatch`
  and `com.aurevia.tenant.TenantContext`.
- `db/migrations/V33__conversation_lifecycle.sql` — creates
  `public.work_item_event` as an append-only transition ledger with tenant keys,
  FORCE ROW LEVEL SECURITY and immutability triggers; `public.conversation_message`
  is the separate per-message ledger and `conversation.state <> 'closed'` is
  the staff work queue predicate.
- Contract survey: the three conversation routes are baselined in
  `contracts/openapi.yaml` (lines 26-28 and 163-169) and have no
  operation in `contracts/openapi.yaml`. That is separate contract-documentation
  debt, outside this test-only scope, and does not affect whether the behaviour
  is implemented and proven.

Interpretation: the clause governs the **work item**, which is one per
conversation, not a ledger row per message. `work_item_event` records the
transitions that make that item active for staff — `CREATED` at open and
`REOPENED` from `RESOLVED` — while `conversation_message` is the per-message
ledger. The requirement's own next sentence defines the narrow case: "A reply
to a resolved thread reopens the work item if the relationship still permits
messaging." A reply on an already-open thread has nothing to create or reopen:
the existing item is already active and simply returns to `awaiting_staff`.
Emitting `REOPENED` there would falsely record a resolution that never happened.
A survey observation recorded for later tasks: `ConversationService.open`
writes the conversation and its `CREATED` work fact but no `conversation_message`
row (the first inbound content is the conversation's `topic`), so the create
half is proven at the work-item-state level rather than by a first message row.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=NewInboundPatientMessageCreatesReopensWorkTest test`
— `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (Surefire report for
`com.aurevia.messaging.clauses.NewInboundPatientMessageCreatesReopensWorkTest`).

## Gap

None.
