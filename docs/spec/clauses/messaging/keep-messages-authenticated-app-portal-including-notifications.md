# ARC-07 clause 7 — API test evidence

## Clause

Keep messages in the authenticated app and portal, including when notifications fail.

## Status

Partly proven

## Evidence

Test class:
`com.aurevia.messaging.clauses.KeepMessagesAuthenticatedAppPortalIncludingNotificationsTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/KeepMessagesAuthenticatedAppPortalIncludingNotificationsTest.java`).
All seven tests are active (none disabled) and pass against a dedicated, freshly migrated PostgreSQL database with two synthetic tenants. Synthetic fixtures and identifiers only; no patient data or credentials.

- `aReplyRemainsReadableInInboxAndThread` — positive proof of the storage/read half. A verified patient opens a thread through `POST /api/v1/conversations`; a lab staff member posts a reply through `POST /api/v1/conversations/{id}/reply` with `If-Match: 1`; a later `GET /api/v1/conversations/{id}` returns the immutable message body and its staff sender kind, and `GET /api/v1/conversations/inbox` lists the thread with an unread count of one. A second `GET /api/v1/conversations/{id}` returns the same body, showing the message survives beyond the command that wrote it.
- `aStaffMemberOutsideTheUnitCannotReadTheMessage` — negative proof of the authenticated boundary. A `lab_verifier` reading a `clinic`-unit thread is refused with 403.
- `aPeerTenantCannotReadTheMessage` — negative proof of tenant isolation. A second organization sharing only the local patient MRN and care-unit key, but with its own tenant-scoped subjects and connection identifiers, receives 404 for the first tenant's thread under forced row level security.
- `aStaleIfMatchCannotPostAReply` — negative proof of the version precondition. A reply carrying `If-Match: 99` against version 1 is refused with 412 `STALE_VERSION`.
- `aBlankReplyBodyIsRefused` — negative proof of input validation. Blank reply text is refused with 422 `INVALID_BODY` before any message is stored.
- `aReplyOnAClosedThreadIsRefused` — negative proof of the forbidden state. A closed thread remains readable (200) but refuses a new reply with 409 `CLOSED_THREAD`.
- `thePushNoticeCarriesNoMessageContent` — cross-check of the boundary between the app and notifications. The only available push payload has a fixed title, body and deep link and reveals neither a synthetic message body nor a patient subject; the positive test above proves the body is retrievable through the authenticated API instead.

Production classes exercised through the normal HTTP entry point `POST/GET /api/v1/conversations/**`:

- `com.aurevia.messaging.conversations.ConversationController` — thin adapter binding JSON for every route and `If-Match` for each mutating route that requires a version; the service is injected, never constructed in the controller.
- `com.aurevia.messaging.conversations.ConversationService` — `reply`, `get`, `inbox`, `authorize` and `requireAccess` run inside one tenant-guarded transaction, enforce role/unit/tenant and version/state invariants, and commit the message, audit event and state together.
- `com.aurevia.messaging.conversations.ConversationRepository` — tenant-scoped SQL boundary storing messages in `conversation_message` and serving the thread and inbox projections.
- `com.aurevia.messaging.conversations.PatientConnectionVerifier` — re-resolves the verified patient connection when a thread is opened and on every patient reply.
- `com.aurevia.messaging.appointments.GenericPushNotice` — the generic, content-free notification value checked by the cross-check.
- `db/migrations/V33__conversation_lifecycle.sql` — the `conversation_message` table with row level security enabled and forced, and the guards that keep the message ledger append-only.

The test overrides the messaging directory with a `@Primary` `InMemoryMessagingStore` test bean, as the neighbouring MSG-02 integration tests do; conversation and message persistence still runs through the real repository and migration on PostgreSQL.

## Gap

The clause is only half built. The storage and authenticated-read half above is proven. Two pieces are missing:

1. **No message notification port (the "when notifications fail" half).** `ConversationService.reply()` stores the message and audit atomically but makes no notification or outbox call; its constructor has no push/outbox dependency. The only push boundary, `PushGateway`, belongs to `messaging.appointments`, is constructed inside `PatientAppointmentController`, and has a synthetic always-delivered implementation. `db/migrations/V33__conversation_lifecycle.sql` states that delivery and notification are deliberately outside that slice, and `ConversationContract.SenderKind` documents delivery and notification as separate facts. A message-scoped notification failure therefore cannot be injected without a production change, so no active test can prove this half yet.

   Files that would change: add a tenant-owned, forced-RLS message notification outbox migration and a `Jdbc*` outbox store behind a store interface (with an `InMemory*` fake only under `src/test`); couple publication, audit and outbox insert atomically in `ConversationService.reply()`; add a push adapter that emits a generic, content-free notice and treats a provider failure as non-destructive; then add the test below and enable it. This is one cross-boundary backend/database task. The complete intended test, written against the seam such a task must provide (a failing `PushGateway` consumed by the conversation publication path), is:

   ```java
   @Test
   void aReplyRemainsReadableInInboxAndThreadWhenPushFails() {
       // Requires the message notification port from the gap task: today
       // ConversationService.reply() makes no push/outbox call.
       when(failingGateway.send(anyString(), any(GenericPushNotice.class)))
               .thenReturn(PushGateway.Outcome.INVALID_TOKEN);
       String id = open(ORG_A, PATIENT_A, "lab");
       ResponseEntity<String> reply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
               "{\"body\":\"" + MESSAGE_BODY + "\"}", LAB_STAFF_A, ORG_A, Map.of("If-Match", "1"));
       assertEquals(HttpStatus.OK, reply.getStatusCode(), reply.getBody());
       verify(failingGateway, atLeastOnce()).send(anyString(), any(GenericPushNotice.class));
       JsonNode thread = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id,
               null, PATIENT_A, ORG_A));
       assertEquals(MESSAGE_BODY, thread.get("messages").get(0).get("body").asString());
       JsonNode inbox = tree(send(HttpMethod.GET, "/api/v1/conversations/inbox",
               null, PATIENT_A, ORG_A));
       assertTrue(inbox.get("entries").toString().contains(id));
   }
   ```

2. **"App and portal" is proven at the API boundary, not in the clients.** `apps/patient-mobile/src/app/patientPages.tsx:301` still passes `onOpen={placeholderAction}` for the conversation entry, and `contracts/openapi.yaml` has no `/api/v1/conversations` entry. A patient cannot yet read the message through a generated client in the app or portal. Files that would change: wire the patient app conversation screen to a typed API client and add a rendered thread test; add the conversation route family to the OpenAPI contract with a tag, `Idempotency-Key` on every POST, `cursor`/`limit`/`nextCursor` on list GETs and route parity, then regenerate both clients. One patient-app task and one contract/client task: **three separate tasks** in total with item 1.

This evidence task added only one test class and this document. It changed no production code, no existing test, no migration and no contract. It contains no skipped test; the intended failing-notification test above is recorded here rather than added as a `@Disabled` method because `scripts/check-constraints.py` rejects any newly added disabled Java test.

## How to verify

Commands run from the repository root with synthetic fixtures only:

```text
OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=KeepMessagesAuthenticatedAppPortalIncludingNotificationsTest test
python3 -I scripts/check-docs.py
python3 -I -m unittest discover -s scripts/tests
bash scripts/worker-check.sh
```
