# ARC-07 clause 2 — store message sender identity, sender role, immutable content, server timestamp and conversation sequence

## Clause

Store each message with sender identity, sender role, immutable content, server timestamp and conversation sequence.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.StoreMessageSenderIdentitySenderRoleImmutableTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/StoreMessageSenderIdentitySenderRoleImmutableTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
Every test seeds its own uniquely identified conversation and asserts stored
state on `public.conversation_message` under the correct forced-RLS scope and
through `GET /api/v1/conversations/{id}`:

- `replyStoresSenderIdentityRoleBodyServerTimestampAndSequence` — a staff reply
  stores `sender_kind=staff`, `sender_subject` equal to the authenticated caller,
  `sender_role` equal to the single active role, the body verbatim, `created_at`
  inside a before/after window around the request (server-generated), and
  `sequence=1`; the row matches the thread view. `sender_subject` is doubly
  enforced: the service supplies the authenticated subject and the V33
  `set_message_sender_from_actor()` trigger overwrites it from `app.actor`.
- `staleIfMatchReplyIsRefusedAndStoresNoMessage` — a mismatched `If-Match`
  version is refused (412 `STALE_VERSION`) and no message row appears.
- `otherTenantCannotReplyInThisConversation` — an ORG_B staff member first
  replies successfully in its own thread (positive control), then is refused
  (404) against an ORG_A thread because FORCE RLS conceals it, and no ORG_A
  message row is written.
- `missingBodyIsRejectedBeforeStorage` — a missing or blank body is refused
  (422 `INVALID_BODY`) and no message row appears.
- `storedMessageContentIsImmutable` — a direct `UPDATE` of the stored body is
  rejected by the V33 append-only trigger for the migration owner (the SQL error
  names the append-only rule), and the original body is retained in both the row
  and the thread view.
- `secondReplyAdvancesSequence` — two replies store sequences 1 and 2, proving
  the monotonic per-conversation sequence.

Production classes exercised by the entry-point-to-assertion trace:
`com.aurevia.smartclinic.web.TenantContextInterceptor` and
`com.aurevia.smartclinic.tenant.TenantGuardTransactionManager` (bind the
synthetic subject, active organization and transaction-local `app.actor`),
`com.aurevia.smartclinic.messaging.conversations.ConversationController`
(`POST /api/v1/conversations/{id}/reply` and `GET /api/v1/conversations/{id}`),
`com.aurevia.smartclinic.messaging.conversations.ConversationService` (authorizes
and appends the message with the caller subject and active role),
`com.aurevia.smartclinic.messaging.conversations.ConversationRepository`
(`nextSequence`, `insertMessage`, `messages`),
`com.aurevia.smartclinic.messaging.conversations.ConversationContract.Message`
(the observable stored value), and the V33 Flyway migration
`db/migrations/V33__conversation_lifecycle.sql` (table, `clock_timestamp()`
server default, RLS and FORCE RLS, no-update trigger, sender-actor trigger).
No production class was changed by this story.

Verified locally:
`mvn -f apps/api/pom.xml -Pno-security -Dtest=StoreMessageSenderIdentitySenderRoleImmutableTest -Dgroups=integration test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
