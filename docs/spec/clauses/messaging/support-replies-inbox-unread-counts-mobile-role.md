# MSG-02 clause 2 — replies, inbox unread counts and role-scoped work queue

## Clause

Support replies and an inbox with unread counts on mobile, and a role-scoped work queue on the staff portal.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.SupportRepliesInboxUnreadCountsMobileRoleTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/SupportRepliesInboxUnreadCountsMobileRoleTest.java`).
All six tests are active (none disabled), use synthetic data only and drive existing
HTTP routes against a real PostgreSQL database seeded with the production Flyway
migrations. Two synthetic organizations share the local patient MRN and account
subject so tenant isolation is observable.

- `replyPostsThenInboxShowsUnreadCountAndWorkQueueIsRoleScoped` — positive proof.
  A verified patient opens a laboratory conversation, a `lab_verifier` posts
  `POST /api/v1/conversations/{id}/reply` with the current `If-Match`, and the
  assertions trace the whole rule: the reply is a staff message at sequence 1
  and moves the thread to `awaiting_patient` at version 2; `GET /api/v1/conversations/inbox`
  shows the entry's derived `unreadCount` rising from 0 to 1 (staff messages
  past `patient_read_sequence`); and `GET /api/v1/conversations/work-queue`
  for the lab role contains the open work item.
- `workQueueIsDeniedToPatientRole` — negative proof (HTTP 403 `FORBIDDEN`). A
  patient holds `INBOX_READ` but not `WORK_QUEUE_READ`, so the queue permission
  check in the domain service refuses the request.
- `replyWithStaleIfMatchIsRejected` — negative proof (HTTP 412 `STALE_VERSION`).
  An eligible lab staff member replies with a version newer than the thread's
  current one; the compare-and-set refuses it and the message trail stays empty.
- `anotherTenantsConversationIsNotVisible` — negative proof of forced RLS. An
  organization B conversation is inserted for the *same* local patient subject
  as organization A's account. With B's organization header the row is readable
  (control), but with A's header it is absent from the inbox and
  `GET /api/v1/conversations/{id}` returns HTTP 404. Because the subject string
  is identical, the concealment can only come from the tenant row-level security
  policy, not from the patient-subject access check.
- `closedThreadRefusesReply` — negative proof (HTTP 409 `CLOSED_THREAD`). The
  thread is closed at version 1 with a current precondition; a reply carrying
  the now-current version 2 passes the version check and is refused solely by
  the read-only closed-thread guard.
- `wrongUnitRoleCannotSeeUnitQueueRow` — negative proof of role scoping. A
  `reception` member (eligible for the CLINIC unit) receives HTTP 200 for the
  work queue but the LAB conversation is absent, while the lab role sees the
  same row. This exercises the unit-capability matrix rather than a permission
  denial.

Production classes exercised through the normal HTTP entry points:

- `com.aurevia.messaging.conversations.ConversationController` —
  thin adapter binding `POST /api/v1/conversations/{id}/reply`,
  `GET /api/v1/conversations/inbox` and `GET /api/v1/conversations/work-queue`;
  the service is injected, not constructed in the controller.
- `com.aurevia.messaging.conversations.ConversationService` —
  `reply`, `inbox` and `workQueue` re-derive membership, roles and unit
  capability inside the tenant transaction, enforce the version and closed-state
  guards, and reopen a resolved work item with a `REOPENED` work event.
- `com.aurevia.messaging.conversations.ConversationRepository` —
  `inbox` derives the unread count in SQL from staff messages with sequence
  greater than `patient_read_sequence`; `workQueue` filters `unit_key` to the
  caller's eligible units and excludes `closed` threads.
- `com.aurevia.messaging.conversations.ConversationContract` —
  `Inbox`/`InboxEntry.unreadCount`, `WorkQueue`, the four lifecycle states and
  the `WorkEventType.REOPENED` fact.
- `com.aurevia.messaging.conversations.ConversationActions` —
  patient versus staff permission sets and the per-unit role matrix
  (`lab_verifier` is LAB-only; patient has `INBOX_READ` but no `WORK_QUEUE_READ`).
- `db/migrations/V33__conversation_lifecycle.sql` — the existing conversation,
  message and work-event tables with tenant-keyed keys, indexes for the inbox
  and queue, and ENABLE/FORCE row-level security with a tenant policy.

The reachability chain was already present and is unchanged by this task: V33
installs the tables with FORCE RLS; the JDBC-backed `ConversationRepository`
runs every statement under the transaction-local tenant; `ConversationService`
owns the invariants; and `ConversationController` exposes them over HTTP. The
neighbouring `it/messagingconversations/ConversationFlowTest` already covers the
reopen, assignment and notification-adjacent branches of the same chain.

## Gap

None (within this API/storage proof scope).

This class proves the API contract, not the client surfaces named in the clause.
The mobile inbox is consumed by `apps/patient-mobile/src/features/conversations/ConversationInbox.tsx`
and its tests; no staff-portal work-queue view consumes these routes in
`apps/staff-web/src`. The conversation routes are also absent from
`contracts/openapi.yaml` and are listed in `contracts/openapi.yaml`,
which is pre-existing tracked debt. Client rendering and OpenAPI publication
are separate stories and were out of scope here: no production code, existing
test, contract or migration was changed.
