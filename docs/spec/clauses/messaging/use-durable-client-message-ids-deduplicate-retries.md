# ARC-07 clause 6 — evidence and gap analysis

## Clause

Use durable client message IDs to deduplicate retries.

## Status

Not built

## Evidence

This clause is not implemented on the patient-communication surface. The evidence
below is a static source survey of the current code; no test can prove the rule
today because the behaviour does not exist. No production code, migration,
contract, dependency or existing test was changed by this evidence task.

Survey of the patient conversation path (all paths relative to the repository root):

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations/ConversationController.java`
  binds only `If-Match` on `POST /api/v1/conversations/{id}/reply` (`:99-103`); it
  does not bind or parse an `Idempotency-Key` header, so a retry key never reaches
  the domain.
- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations/ConversationService.java`
  calls `ConversationRepository.insertMessage` unconditionally in `reply`
  (`:145-180`); there is no replay lookup and no payload-conflict decision, so a
  second reply with the client's last `If-Match` is a stale update or a duplicate.
- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations/ConversationRepository.java`
  writes `conversation_message` through `insertMessage` (`:198-210`) with no client
  message id parameter and offers no read-by-client-id method.
- `db/migrations/V33__conversation_lifecycle.sql` defines `public.conversation_message`
  (`:100-114`) with a tenant-and-conversation-and-sequence unique key but no client
  message id column or unique key. The table already has forced row-level security
  and a tenant policy, so an additive column is covered by that policy.
- `ConversationContract.ReplyRequest`
  (`messaging/conversations/ConversationContract.java:342`) is a body-only record
  with no client-id field; `com.aurevia.smartclinic.api.contract.IdempotencyKey` is
  the existing validated key vocabulary (428 absent / 422 malformed) that the
  eventual HTTP binding must reuse.
- `contracts/openapi.yaml` publishes no `POST /api/v1/conversations/{id}/reply`
  operation, so the header and the replay-conflict response are absent from the
  public contract.

Neighbouring top-level `com.aurevia.smartclinic.staffmsg` siblings do deduplicate,
with active green tests, but they are **not** proof of this clause: they use
different tables, stores, routes and contract records for staff channels, while
this requirement is patient communication. Status `Not built` therefore means
*not built on the patient surface*; it does not claim the pattern is absent from
the repository. The neighbours are cited only as an implementation pattern:

- `staffmsg/instant/InstantMessagingService.send` (`:174`) looks up
  `InstantConversationStore.messageByClientId` before `JdbcInstantConversationStore`
  inserts.
- `db/migrations/V91__staff_messaging.sql:55-74` stores `client_message_id` with
  `UNIQUE (organization_id, conversation_id, client_message_id)`.
- `staffmsg/mail/MailService.send` replays via `JdbcMailStore.replay` before
  publishing, with a durable idempotency claim in
  `db/migrations/V95__durable_staff_mail.sql`.

A complete HTTP-over-PostgreSQL proof was drafted during the evidence pass but
consisted entirely of tests disabled with `@Disabled("GAP: …")`: the rule cannot be
exercised against the current code, so enabling them would leave a red suite.
Project policy (`scripts/check-constraints.py`, enforced by
`scripts/quality-gate.sh`) rejects added skipped tests, and this task's scope
forbids production-code changes, so those inactive placeholders were removed
rather than kept as a gate suppression. The behavioural contract they were to
assert is recorded in the Gap section below so the implementation task can turn
it into active tests. No threshold, exclusion or check was weakened.

## Gap

The rule is absent from the patient-communication path. To implement it and prove
it with active tests:

1. **Durable client message id on the message ledger.** V33's
   `public.conversation_message` needs a `client_message_id` column bounded like
   the neighbouring `client_message_id` (`1..200`), a unique key on
   `(organization_id, conversation_id, client_message_id)` as the concurrent-retry
   backstop, and the tenant policy/RLS already installed for the table. Files: a
   new Flyway migration under `db/migrations` (never an edit to the applied V33)
   plus `ConversationRepository.insertMessage` and its row mapper in
   `messaging/conversations/ConversationRepository.java`.
2. **Replay and payload-conflict path.** `ConversationService.reply` must look up
   the first message by tenant, conversation and client id before authorizing the
   state/version mutation, return that first outcome on an identical retry,
   refuse a different body with 409 `IDEMPOTENCY_CONFLICT` (an explicit
   first-message-wins policy; the staff neighbour replays without comparing
   bodies). This conflict policy still needs Product Owner confirmation before it
   is implemented. Leave the message and work-item ledgers and the conversation
   version untouched on a replay. The lookup belongs on `ConversationRepository`
   (or a small dedicated store) behind an interface, with deterministic unit tests
   for the empty-result and conflict sides rather than a timing race.
3. **HTTP header and published contract.** `ConversationController.reply` must bind
   and parse `Idempotency-Key` with `IdempotencyKey.parse` (428 absent / 422
   malformed) and pass it to the service; `POST /api/v1/conversations/{id}/reply`
   and the header must be added to `contracts/openapi.yaml` with the 409
   replay-conflict response, and the generated client regenerated.
4. **Active proof.** Write active HTTP-over-PostgreSQL tests for the five required
   behaviours: identical retry returns the first outcome without a second message
   or version bump; a changed body under the same key is a 409; the key is tenant
   scoped; a missing key is a 428; a malformed key is a 422. Prove 100% JaCoCo
   coverage of every new production branch.

This is **one** cross-boundary implementation task (migration + repository +
service + controller + OpenAPI/client plus active tests and full coverage). The
follow-up story is requested in the `task-requests` block of the worker notes.
