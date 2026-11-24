# MSG-01 clause 5 — A new recipient requires a separate authorized conversation and explicit disclosure

## Clause

A new recipient requires a separate authorized conversation and explicit disclosure.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.messaging.clauses.NewRecipientRequiresSeparateAuthorizedConversationExplicitTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/NewRecipientRequiresSeparateAuthorizedConversationExplicitTest.java`).
It is a mocked-dependency domain test, not a PostgreSQL or HTTP integration test: it enters
through the real `ConversationService` command methods that the thin `ConversationController`
calls, runs the real bounded action matrix and a synchronous tenant-guarded transaction, and
uses synthetic subjects and identifiers only. Every refusal is asserted to leave the write
methods and the success audit untouched.

- `openingThroughAVerifiedConnectionBindsOneRecipientUnit` (rule met) — a patient `open` passes the
  client-named patient, unit and connection to `PatientConnectionVerifier.requireActive` and
  persists the server-resolved patient and connection identities it returns, binding exactly one
  recipient unit and writing no second recipient. The mocked verifier returns different patient
  and connection identities from the request on purpose, which characterizes the three assignment
  lines that prefer the verified values; the real `CareUnitDirectoryService` additionally enforces
  patient and unit-key equality, so a client-named value cannot silently redirect the conversation
  in a reachable path.
- `aConnectionVerifiedForAnotherUnitCannotOpenAConversationForANewRecipient` (negative, wrong unit) —
  a connection verified for one care unit is refused with 403 `FORBIDDEN` when used to open a
  conversation for another unit; no insert or success audit is written. This is the direct proof
  that a new recipient needs its own authorized conversation.
- `transferToAStaffMemberEligibleOnlyForAnotherUnitIsRefused` (negative, wrong role and unit) — a
  target whose active role is eligible only in another care unit is refused with 422
  `INVALID_ASSIGNEE`; no assignment, work event or success audit is written.
- `aPatientCannotUseAnotherPatientsConnectionForANewRecipient` (negative, wrong principal) — a
  patient naming another patient's subject is concealed as 404 `NOT_FOUND` before any existence
  probe, connection verification or write, so one patient's connection cannot bootstrap a new
  recipient for another.

Other refusal branches of the same production methods are already exercised by
`com.aurevia.it.messagingconversations.ConversationServiceTest`: staff unit denial at
open (`openRejectsStaffOfWrongUnit`), missing, revoked, foreign-patient and other-unit connections
(`patientOpenWithoutAnyVerifiedConnectionIsDenied`, `patientOpenWithARevokedConnectionIsDenied`,
`patientOpenWithAConnectionBoundToAnotherPatientIsDenied`,
`patientOpenWithAConnectionToAnotherUnitIsDenied`), inactive and non-staff assignees
(`assignRejectsInactiveTarget`, `assignRejectsNonStaffTargetRole`), and the forced-RLS immutability
guard in `db/migrations/V33__conversation_lifecycle.sql` is proven by
`com.aurevia.it.messagingconversations.ConversationFlowTest.databaseGuardsRejectIllegalWrites`.
Those tests are cited, not duplicated, because they are outside this story's single-test-file scope.

Production classes and files exercised by the request path:

- `com.aurevia.messaging.conversations.ConversationService` — domain commands
  `open`, `transfer` and their `requireAccess`, `requireEligibleStaff` and `success` guards.
- `com.aurevia.messaging.conversations.ConversationActions` — the real bounded
  action and unit matrix; no disclosure or add-recipient action is declared.
- `com.aurevia.messaging.conversations.PatientConnectionVerifier` — the server-side
  connection boundary; its production implementation is
  `com.aurevia.messaging.units.CareUnitDirectoryService` (`requireActive`), which
  derives the patient, connection and unit from the persistent connection, never the request body.
- `com.aurevia.messaging.conversations.ConversationContract`,
  `com.aurevia.messaging.conversations.ConversationRepository` and
  `com.aurevia.audit.AuditAppender` — the single recipient-unit projection, the SQL
  boundary and the transactional audit writer.
- `com.aurevia.messaging.conversations.ConversationController` — the thin HTTP entry
  point at `/api/v1/conversations`; it exposes open, reply, inbox, work queue, read, assign,
  transfer, resolve and close only.

Discovery note (not exercised by this mocked test): `db/migrations/V33__conversation_lifecycle.sql`
declares `conversation` with one `unit_key` and one `connection_id`, and its
`guard_conversation_update()` makes `unit_key`, `connection_id`, `patient_id` and `patient_subject`
immutable (42501). That is the database-level proof that a new recipient needs a *new*
conversation. The `/api/v1/conversations/**` routes are currently baselined in
`contracts/openapi.yaml` rather than published in `contracts/openapi.yaml`;
contract work is outside this test-only story's scope. `V91__staff_messaging.sql` and
`V62__messaging_governance.sql` belong to separate staff-mail and retention features and carry
no disclosure path.

Verified locally with the command and final line from the Surefire report:

```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest=NewRecipientRequiresSeparateAuthorizedConversationExplicitTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## Gap

The "separate authorized conversation" half is built: a conversation is bound to exactly one
verified connection and one recipient unit, a second unit needs its own verified connection and
its own conversation, and cross-unit or cross-patient grants are refused. The "explicit
disclosure" half is absent. There is no `DISCLOSE` action in `ConversationActions`, no disclosure
request or event in `ConversationContract`, no add-recipient command in `ConversationService` or
`ConversationController`, no disclosure table or record in any Flyway migration and no disclosure
audit event. A search for `DISCLOSE`/`disclos` across the `messaging` package, `contracts/` and
`db/migrations/` returns no patient-messaging matches; the only hits describe other domains'
disclosure concepts (the `exchange_disclosure` ledger in `V19__exchange_orders.sql` belongs to
XCH-01, and `contracts/openapi.yaml` and `V24__patient_release.sql`/`V84__report_release_store.sql`
use "disclosure" for release workflows). None is patient-messaging disclosure evidence.

Closing the gap needs three separate tasks:

1. Domain and command: add a bounded disclosure action and an explicit-disclosure request carrying
   the original conversation, the new recipient unit and the disclosing actor to
   `ConversationActions` and `ConversationContract`; add a `disclose` command to
   `ConversationService` that verifies the new unit has its own authorized connection and commits
   the disclosure with the audit event, and a thin route in `ConversationController`.
2. Persistence and migration: add the append-only disclosure record under `db/migrations` with
   forced row-level security, grants, tenant context and an immutable audit binding, a store
   behind an explicit interface under `messaging/conversations`, and a repository method; add the
   HTTP-level integration test through the new route.
3. Contract and evidence: publish the conversation routes (currently baselined as missing) plus
   the new disclosure route in `contracts/openapi.yaml` with the required tag, `Idempotency-Key`
   and route-parity entries, regenerate the client, and add the enabled assertion that an
   undisclosed second recipient cannot access the original thread.

The complete proof to enable in task 3 is a test named
`aNewRecipientRequiresASeparateAuthorizedConversationAndAuditedDisclosure`. Its setup opens the
original clinic conversation through a verified connection and seeds a second verified connection
for the lab unit. It then calls the disclosure command with the original conversation, the new
recipient unit and the disclosing actor, and asserts three outcomes: the command refuses to add a
recipient to the original conversation; the lab unit is reached only through its own verified
connection and its own conversation; and a durable disclosure record plus a `CONVERSATION_DISCLOSE`
audit event with outcome `SUCCESS` exist for the original thread. It cannot be written as a
compiling test today because no disclosure command, record or action exists, and a skipped or
disabled placeholder is forbidden by the repository's added-line constraint check, so this `## Gap`
section is the exact hand-off instead. The four enabled tests above prove the built half only.

No production code, migration, contract, existing test or skipped placeholder was changed by this
test-only story.
