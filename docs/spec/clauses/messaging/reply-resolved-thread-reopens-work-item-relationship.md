# MSG-02 clause 6 — API test evidence

## Clause

A reply to a resolved thread reopens the work item if the relationship still permits messaging.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ReplyResolvedThreadReopensWorkItemRelationshipTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ReplyResolvedThreadReopensWorkItemRelationshipTest.java`).
All five tests are active (none disabled) and use synthetic fixtures only: a mocked
`ConversationRepository`, a synchronous `TransactionTemplate`, a mocked `AuditAppender`,
a real `InMemoryMessagingStore` and `CareUnitDirectoryService` for connection verification,
and a cleared `TenantContext` after each test.

- `patientReplyToResolvedThreadReopensWorkItemWhenLinkAndConnectionAreActive` — positive
  proof. A patient replies to a `resolved` thread while the verified link and the exact
  stored connection are both active. The work-item fact ledger receives
  `appendEvent(..., "reopened", "patient-a", "resolved", "awaiting_staff", ...)` at version
  `expectedVersion + 1`, the state write `updateState(id, 2, "awaiting_staff")` is verified,
  and the returned view is `awaiting_staff`.
- `replyWhenVerifiedLinkIsRevokedDoesNotReopen` — negative proof of the relationship
  guard. With `patientLinkActive` false a reply to a resolved thread is refused with HTTP
  404 `NOT_FOUND`; no message is inserted, no state is written and no `reopened` fact is
  appended.
- `patientReplyWhenConnectionIsRevokedDoesNotReopen` — negative proof for a patient caller.
  A resolved thread whose stored connection is `REVOKED` is refused with HTTP 403
  `FORBIDDEN` by `connections.requireActive`, with no message, state change or reopen fact.
  This uses a `RESOLVED` conversation, which the neighbouring connection tests do not.
- `replyToClosedThreadNeverReopens` — negative proof of the read-only state. A `closed`
  thread is refused with HTTP 409 `CLOSED_THREAD` and no reopen fact is appended.
- `replyWithStaleVersionDoesNotReopen` — negative proof of the version guard. A caller
  holding version 1 against a `resolved` thread at version 2 is refused with HTTP 412
  `STALE_VERSION` and no reopen fact is appended.

Entry point to test trace: `ConversationController.reply` (`POST
/api/v1/conversations/{id}/reply`, `If-Match` parsed by `IfMatch.parse`) delegates to
`ConversationService.reply` (`messaging/conversations/ConversationService.java:145-180`).
The reopen branch at lines 173-176 is proved by the positive test; the verified-link guard
at line 158 by `replyWhenVerifiedLinkIsRevokedDoesNotReopen`; the connection guard at line
166 by `patientReplyWhenConnectionIsRevokedDoesNotReopen`; and the version and state guards
at lines 153-154 (`requireVersion` 414-418, `requireWritable` 420-424) by the stale-version
and closed-thread tests.

Production classes exercised through that entry point:
`com.aurevia.smartclinic.messaging.conversations.ConversationService` (transactional
lifecycle orchestration and the reopen decision), `ConversationContract.State` and
`ConversationContract.WorkEventType` (`resolved` → `reopened` → `awaiting_staff`),
`PatientConnectionVerifier` and its `CareUnitDirectoryService` implementation (real
connection re-verification on every patient reply), `ConversationActions` (bounded
caller-role matrix) and `ConversationRepository` (mocked SQL boundary).

The clause is caller-agnostic. Equivalent staff-side proof already lives in
`com.aurevia.smartclinic.it.messagingconversations.ConversationServiceTest`:
`staffReplyReopensAResolvedThreadWhenTheVerifiedLinkIsActive` and
`staffReplyCannotReopenAResolvedThreadWhenTheVerifiedLinkIsRevoked`. HTTP-level proof of
the reopen and closed-thread behavior is in
`com.aurevia.smartclinic.it.messagingconversations.ConversationFlowTest.patientOpenStaffReplyUnreadMarkReadReopenAndClose`
(lines 305-325), and the tenant/role-scoped HTTP boundary is covered by
`com.aurevia.smartclinic.it.messaging.ConversationHttpSecurityTest`. Those suites are cited as existing evidence
only; this task did not change them. No OpenAPI contract claim is made here — the OpenAPI
entry and real-PostgreSQL checks belong to the integration suites and the integrator gate.

## Gap

None.

The rule is built and proven by active tests. This task added proof only: it did not change
production code, an existing test, the contract or a migration, and it added no dependency.
The `view` state assertion is supported by the stubbed read side; the authoritative proof
of the reopen is the verified append-only `reopened` work-item fact.
