# ARC-07 clause 6 — API test evidence

## Clause

Use durable client message IDs to deduplicate retries.

## Status

Not built

## Evidence

The complete proof is written but disabled, because the patient-communication path does not
implement the rule today. No production code, migration, contract or existing test was changed by
this evidence task.

- Test class: `com.aurevia.smartclinic.messaging.clauses.UseDurableClientMessageIdsDeduplicateRetriesTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/UseDurableClientMessageIdsDeduplicateRetriesTest.java`).
  It drives `POST /api/v1/conversations/{id}/reply` over HTTP with `RestClient` against real
  PostgreSQL migrated from `db/migrations`, with the `synthetic-auth` profile, two synthetic tenants
  that share the local patient subject, staff subject, MRN and connection id, and a test-only
  `InMemoryMessagingStore` directory fixture. The fixture supplies connections only; the dedup
  behaviour is asserted against the PostgreSQL `conversation_message` ledger, and the eventual
  implementation must persist replay state in an RLS-bounded table or column rather than in the
  fixture. Every test is marked `@Disabled("GAP: …")`; enabling any of them against the current code
  would leave a red suite, so the suite stays green and this document records the gap.

  - `aRetryWithTheSameDurableClientMessageIdPublishesOneMessageAndReturnsTheFirstOutcome` — positive
    proof of the rule. Two identical replies carry the same `Idempotency-Key` and the same `If-Match`
    version the client last read. The retry must return the first outcome (same message sequence and
    body, unchanged conversation version), and exactly one `conversation_message` row must exist.
    Today the retry is a stale update because a second message is appended unconditionally.
  - `aDifferentBodyWithTheSameDurableClientMessageIdIsRefusedWithAConflict` — negative proof. A second
    reply with the same key but different content must be refused with HTTP 409 `IDEMPOTENCY_CONFLICT`
    and must not append or replace a message; the first body stays the only stored row. The retry
    carries the fresh version returned by the first reply, so the conflict can only come from the
    key/payload check, not from a stale-version guard. The asserted contract is first-message-wins
    with a 409 for a changed payload; the neighbour staff implementation replays without comparing
    bodies, so this decision must be made explicitly by the follow-up task.
  - `theSameDurableClientMessageIdInAnotherTenantDoesNotReuseTheFirstTenantsMessage` — negative proof
    for tenant scoping. Both tenants share every local identifier. Tenant `ORG_B` reuses the key in
    its own thread with its own body, then retries with B's current version; it must get its own
    message and one row after the retry, and `ORG_A`'s first message must be unchanged. This rules
    out a global or local-identifier-only replay lookup that crosses the tenant boundary.
  - `aReplyWithoutADurableClientMessageIdIsRejected` — negative proof for missing input. With no
    `Idempotency-Key` header the reply must be refused before any message is written. The assertion
    follows the repository's authoritative contract: `com.aurevia.smartclinic.api.contract.IdempotencyKey.parse`
    raises HTTP 428 `IDEMPOTENCY_KEY_REQUIRED` for an absent header, the same convention used by the
    neighbouring staff-messaging endpoints.
  - `aMalformedDurableClientMessageIdIsRejectedBeforeAnyMessageIsWritten` — negative proof for a
    malformed key. A key containing a space reaches the HTTP boundary raw and must be refused with
    422 `INVALID_IDEMPOTENCY_KEY` before a message is written.

Production classes the enabled proof would exercise through the real HTTP entry point:

- `com.aurevia.smartclinic.messaging.conversations.ConversationController` — thin adapter for
  `POST /api/v1/conversations/{id}/reply`; it currently binds `If-Match` only (`:99-103`).
- `com.aurevia.smartclinic.messaging.conversations.ConversationService` — `reply` (`:145-180`) calls
  `ConversationRepository.insertMessage` unconditionally at `:170-171`; there is no replay lookup
  and no payload-conflict decision.
- `com.aurevia.smartclinic.messaging.conversations.ConversationRepository` — `insertMessage`
  (`:198-210`) writes `conversation_message` with no client message id parameter.
- `db/migrations/V33__conversation_lifecycle.sql` — `public.conversation_message` (`:100-114`) has
  a tenant-and-conversation-and-sequence unique key but no client message id column or unique key.
- `ConversationContract.ReplyRequest` (`messaging/conversations/ConversationContract.java:342`, a
  body-only record with no client-id field) and `com.aurevia.smartclinic.api.contract.IdempotencyKey`
  — the existing request and key vocabulary reused as-is by the test.

Source survey (nothing found that proves this clause for patient communication): no client message id
column or unique key in `V33__conversation_lifecycle.sql`; no replay method on `ConversationRepository`
or in `messaging/conversations`; no `Idempotency-Key` binding on `ConversationController.reply`; and no
`/api/v1/conversations/{id}/reply` operation in `contracts/openapi.yaml`.

Neighbouring top-level `com.aurevia.smartclinic.staffmsg` siblings do deduplicate, with active green
tests, but they are **not** proof of this clause: they use different tables, stores, routes and
contract records for staff channels, while this requirement is patient communication. Status
`Not built` therefore means *not built on the patient surface*; it does not claim the pattern is
absent from the repository. The neighbours are cited only as an implementation pattern:
`staffmsg/instant/InstantMessagingService.send` (`:174`) looks up
`InstantConversationStore.messageByClientId` (`:100`) before `JdbcInstantConversationStore` inserts,
and `db/migrations/V91__staff_messaging.sql:60-69` stores `client_message_id` with
`UNIQUE (organization_id, conversation_id, client_message_id)`.
`staffmsg/mail/MailService.send` (`:144-179`) replays via `JdbcMailStore.replay` before publishing,
with a durable idempotency claim in `db/migrations/V95__durable_staff_mail.sql`.

The `@Disabled` annotations are the story-mandated mechanism for a clause whose behaviour is missing:
a complete but inactive test keeps the pull request green, and no threshold, exclusion or check was
weakened by this task. Precedent:
`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/CaptureChiefConcernHistoryExamAssessmentDiagnosesTest.java`.
Caveat for the integrator: `scripts/check-constraints.py:56` currently flags *any* added `@Disabled`
in Java, including clause evidence, and it runs in `quick-check.sh` and with `--require-base` in the
integrator gate. No code-level exception exists today, so a scoped, owner-approved allowance for
clause-evidence tests is needed before this branch can pass that gate; it is requested in the worker
notes rather than implemented here because `scripts/` is out of this task's scope.

## Gap

The rule is absent from the patient-communication path. To implement it and enable the tests above:

1. **Durable client message id on the message ledger.** V33's `public.conversation_message` needs a
   `client_message_id` column bounded like the neighbouring `client_message_id` (`1..200`), a unique
   key on `(organization_id, conversation_id, client_message_id)` as the concurrent-retry backstop,
   and the tenant policy/RLS already installed for the table. Files: a new Flyway migration under
   `db/migrations` (never an edit to the applied V33) plus `ConversationRepository.insertMessage`
   and its row mapper in `messaging/conversations/ConversationRepository.java`.
2. **Replay and payload-conflict path.** `ConversationService.reply` must look up the first message by
   tenant, conversation and client id before authorizing the state/version mutation, return that
   first outcome on an identical retry, refuse a different body with 409 `IDEMPOTENCY_CONFLICT`
   (an explicit first-message-wins policy; the staff neighbour replays without comparing bodies), and
   leave the message and work-item ledgers and the conversation version untouched on a replay. The lookup
   belongs on `ConversationRepository` (or a small dedicated store) behind an interface, with a
   deterministic unit test for the empty-result and conflict sides rather than a timing race. Files:
   `ConversationService.java`, `ConversationRepository.java` and their tests under
   `apps/api/src/test/java/com/aurevia/smartclinic/messaging/conversations/`.
3. **HTTP header and published contract.** `ConversationController.reply` must bind and parse
   `Idempotency-Key` with `IdempotencyKey.parse` (428 absent / 422 malformed) and pass it to the
   service; `POST /api/v1/conversations/{id}/reply` and the header must be added to
   `contracts/openapi.yaml` with the 409 replay-conflict response, and the generated client
   regenerated. Files: `ConversationController.java`, `contracts/openapi.yaml`, the generated client
   and `scripts/docs.sh` output as relevant.

This is **one** cross-boundary implementation task (migration + repository + service + controller +
OpenAPI/client in the same change), followed by **one** enablement/review task that removes the
`@Disabled` annotations and proves 100% coverage of the new branches; **two separate tasks** in total.
The follow-up story is requested in the `task-requests` block of the worker notes.
