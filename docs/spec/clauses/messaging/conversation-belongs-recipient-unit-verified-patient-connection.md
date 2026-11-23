# ARC-07 clause 1 — A conversation belongs to one recipient unit and one verified patient connection

## Clause

A conversation belongs to one recipient unit and one verified patient connection.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ConversationBelongsRecipientUnitVerifiedPatientConnectionTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ConversationBelongsRecipientUnitVerifiedPatientConnectionTest.java`),
a real-PostgreSQL HTTP integration test under the `synthetic-auth` profile. It
uses the production JDBC messaging store, so care units, verified relationships
and patient-unit connections are resolved from tenant-owned tables under forced
row-level security. All fixtures are synthetic.

- `conversationIsBoundToOneRecipientUnitAndOneVerifiedConnection` — a patient
  opens a conversation through a verified laboratory connection; the persisted
  conversation's unit key, connection identifier and patient identifier equal the
  values read from the connection and its directory unit, and the state is
  `awaiting_staff`. The client cannot choose a different unit or connection.
- `conversationRefusesAWrongRecipientUnitForItsConnection` (negative) — naming
  radiology with a laboratory connection returns HTTP 403 `FORBIDDEN` and writes
  no conversation, message or work-item event.
- `conversationRefusesAnotherTenantsUnitOrConnection` (negative) — using a
  connection owned by another organization under the first organization's
  context returns HTTP 404 `NOT_FOUND`; the foreign connection is visible only
  under its own tenant and no conversation row is written.
- `revokedConnectionCannotReplyToTheConversation` (negative) — after the
  connection is revoked through the normal HTTP command, a patient reply returns
  HTTP 403 `FORBIDDEN` and writes no message or work-item event.
- `replyRefusesStaleVersion` (negative) — a reply with a mismatched version
  precondition returns HTTP 412 `STALE_VERSION` and writes no message or
  work-item event.
- `conversationRefusesAnotherPatientsConnectionForItsOwnChart` (negative) — a
  verified patient naming its own chart but another verified patient's connection
  returns HTTP 404 `NOT_FOUND` and writes no row, proving the stored patient
  identity comes from the connection rather than the request body.

Production classes and files exercised by the request path:

- `com.aurevia.smartclinic.messaging.conversations.ConversationController` —
  `POST /api/v1/conversations` and `POST /api/v1/conversations/{id}/reply`; a thin
  adapter that injects the domain service and parses the version precondition.
- `com.aurevia.smartclinic.messaging.conversations.ConversationService` —
  `open` derives the stored patient, recipient unit and connection from the
  verified connection for a patient caller, and `reply` re-resolves the stored
  connection on every patient message so revocation stops new messaging.
- `com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier` —
  the conversation-owned boundary whose `requireActive` returns the server-derived
  patient, connection and unit identities.
- `com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService` —
  implements that verifier, resolves the exact connection in the active tenant,
  requires an active verified chart and connection, and compares the directory's
  unit category with the requested one.
- `com.aurevia.smartclinic.messaging.conversations.ConversationRepository` and
  `com.aurevia.smartclinic.messaging.units.JdbcMessagingStore` — the tenant-guarded
  SQL boundaries over the conversation and care-unit tables.
- `com.aurevia.smartclinic.messaging.conversations.ConversationUnitResolver` —
  parses the directory's bounded unit category into the conversation vocabulary.
- `db/migrations/V33__conversation_lifecycle.sql` — the conversation and
  append-only trails with FORCE RLS, the verified-link insert guard and immutable
  ownership columns on update.
- `db/migrations/V60__care_units.sql` — the directory, eligibility and connection
  tables with FORCE RLS, from which the recipient unit is derived.

## Gap

The tests above prove the rule for patient-initiated conversations. For a
staff-initiated `open`, `ConversationService.open` authorizes the named unit
through the role/unit matrix but persists the client-named patient and connection
without calling `PatientConnectionVerifier.requireActive`; the database guard
only checks the verified patient link. The clause is therefore not proven for
staff-initiated conversations. Closing that gap needs production changes in
`ConversationService.open` (and any staff-side connection resolution it depends
on) plus a new HTTP test for the staff caller — one separate task, outside this
test-only scope.

Separately, the conversation routes are listed in
`contracts/openapi/missing-routes.txt` rather than published in
`contracts/openapi.yaml`; that contract-parity debt is also outside this task
and does not change the clause's runtime behavior or its evidence.
