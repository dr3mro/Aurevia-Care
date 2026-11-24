# ARC-07 clause 8 — expose the six communication commands over HTTP

## Clause

Expose commands to connect, send, assign, resolve, acknowledge and withdraw or replace instructions.

## Status

Partly proven

## Evidence

Test class `com.aurevia.messaging.clauses.ExposeCommandsConnectSendAssignResolveAcknowledgeTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/ExposeCommandsConnectSendAssignResolveAcknowledgeTest.java`),
a real-PostgreSQL HTTP integration test under the `synthetic-auth` profile with a
fresh Flyway database, `RestClient` and the `X-Synthetic-Subject` /
`X-Active-Organization` headers. Every request goes through the real Spring MVC
pipeline and the production controllers and services; only synthetic tenants,
charts and subjects are used.

- `connectSendAssignResolveAcknowledgeAndWithdrawOrReplaceInstructionsAreExposedOverHttp` —
  `POST /api/v1/patient-unit-connections` creates an active connection (201) for
  a verified chart; `POST /api/v1/conversations` opens a thread (201) and
  `POST /api/v1/conversations/{id}/reply` sends a message (200); `assign` and
  `resolve` on that thread return 200, advancing the version and setting the
  assignee, with `resolve` moving the state to resolved;
  `POST /api/v1/patient-instructions` sends a revision (201) which `replace`
  and `withdraw` then advance to revision 2 and to the withdrawn state;
  `POST /api/v1/instructions/publish` plus `POST /api/v1/instructions/acknowledge`
  record a receipt-only acknowledgment (200) that is read back from the fact
  timeline. Each step asserts the resulting state, not only the status code.
- `wrongRoleCannotAssignOrResolveAConversation` — a patient subject is refused
  403 on both work-item commands and the conversation remains at version 1
  with no assignee.
- `anotherTenantCannotMutateTheConnectedConversation` — a subject with an active
  membership in a second tenant is refused 404 by both `assign` and `resolve`
  under forced row-level security; the conversation in the owning tenant is
  unchanged.
- `staleVersionCannotAssignResolveWithdrawOrReplace` — a stale `If-Match`
  version is refused 412 on conversation assign/resolve and on instruction
  replace/withdraw; both aggregates keep their original version and state.
- `missingInputIsRefused` — a null patient id, an empty resolve reason and a
  malformed instruction id are refused 422; the conversation is unchanged.
- `withdrawnInstructionCannotBeReplaced` — replacing a withdrawn revision is
  refused 409 `INVALID_TRANSITION` and the withdrawn revision stays current.

Production classes and routes exercised by this entry point:

- connect — `com.aurevia.messaging.units.CareUnitController`
  (`POST /api/v1/patient-unit-connections`) and
  `CareUnitDirectoryService.connect`, backed by `JdbcMessagingStore` and the
  `care_unit` / `eligible_relationship` tables of `db/migrations/V60__care_units.sql`.
- send (message) — `com.aurevia.messaging.conversations.ConversationController`
  (`POST /api/v1/conversations`, `POST /api/v1/conversations/{id}/reply`) and
  `ConversationService`, backed by `ConversationRepository` and
  `db/migrations/V33__conversation_lifecycle.sql`.
- send (instruction) — `com.aurevia.messaging.instructions.InstructionController`
  (`POST /api/v1/patient-instructions`) and `InstructionService.send`.
- assign / resolve — `ConversationController` (`POST /api/v1/conversations/{id}/assign`,
  `POST /api/v1/conversations/{id}/resolve`) and `ConversationService.assign` /
  `ConversationService.resolve`, guarded by `ConversationActions` and
  `IfMatch` version preconditions.
- acknowledge — `com.aurevia.messaging.acknowledgment.InstructionFactController`
  (`POST /api/v1/instructions/acknowledge`) and `InstructionFactService.acknowledge`,
  backed by `JdbcInstructionFactStore` and `db/migrations/V63__instruction_facts.sql`;
  retries are deduplicated by a durable client id.
- withdraw / replace — `InstructionController` (`POST /api/v1/patient-instructions/{threadId}/withdraw`,
  `POST /api/v1/patient-instructions/{threadId}/replace`) and
  `InstructionService.withdraw` / `InstructionService.replace`, whose legal
  transitions are defined by `InstructionContract.State.canTransitionTo`.

`com.aurevia.web.TenantContextInterceptor` binds the tenant from the
synthetic headers and `com.aurevia.api.IfMatch` parses the optimistic
version precondition used by the irreversible commands.

## Gap

All six commands have live HTTP routes; none needs a disabled placeholder. Two
adjacent gaps prevent the clause from being called fully proven:

1. Instruction send, withdraw and replace are served by
   `apps/api/src/main/java/com/aurevia/messaging/instructions/InstructionStore.java:83`
   (`InstructionStore.InMemory`), the only instruction store in `src/main`.
   There is no `JdbcInstructionStore` and no instruction-thread/revision
   migration (`V63` creates only `instruction_fact_*` tables), so instruction
   state is process-local and does not survive a restart. One bounded task:
   add `JdbcInstructionStore`, a Flyway migration with RLS and FORCE RLS for
   threads, revisions and facts, replace the bean, and move the HTTP assertions
   above onto the durable store.
2. The connection and instruction route families are baselined as undocumented in
   `contracts/openapi.yaml` (lines 64-66, 202, 206, 236 and
   238-240)
   and are absent from `contracts/openapi.yaml`, so no generated client exposes
   connect, instruction send/withdraw/replace or publish/acknowledge. The
   conversation open/reply/assign/resolve routes are already published
   (`contracts/openapi.yaml:738, 818, 847, 905`). One bounded task: publish the
   connection and instruction routes with their tags and command headers, add
   their route-parity entries and regenerate the clients.

Total: two bounded tasks. Neither touches this story's two-file scope.
