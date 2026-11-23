# ARC-07 clause 5 — commit publication, audit, queue update and notification outbox together

## Clause

Commit publication, audit, queue update and notification outbox together.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.CommitPublicationAuditQueueUpdateNotificationOutboxTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/CommitPublicationAuditQueueUpdateNotificationOutboxTest.java`).
Nine active tests, none skipped, on a dedicated PostgreSQL database migrated from
`db/migrations` with forced row-level security. They drive the real HTTP entry point
with the synthetic-auth profile and the same directory fixtures, headers and database
seeding style as `com.aurevia.smartclinic.it.messagingconversations.ConversationFlowTest`.
Every durable fact is then read back with a tenant-bound JDBC session, in the style of
`com.aurevia.smartclinic.scheduling.clauses.RescheduleAtomicallyRetainHistoryTest`.

- `replyCommitsPublicationStateAndAuditInOneTransaction` — positive proof of the
  ordinary staff reply through `POST /api/v1/conversations/{id}/reply` under an
  `If-Match` version. The response is 200 with state `awaiting_patient` and version 2.
  Direct reads confirm exactly one `public.conversation_message` row with the expected
  sender kind, sender subject and body, one `public.audit_event` row with action
  `CONVERSATION_REPLY` and outcome `SUCCESS`, the durable `public.conversation` state
  and version, and no spurious `public.work_item_event`.
- `publicationAuditAndQueueUpdateCommitTogether` — positive proof of the conditional
  queue-fact write. A staff reply to a resolved thread reopens it: the response is 200
  with state `awaiting_patient` and version 3, and the committed database holds the
  message, the `reopened` `work_item_event` bound to version 3, the `CONVERSATION_REPLY`
  audit fact and the new state together. A peer-org read sees none of the message rows.
- `failureAfterPublicationRollsBackStateAuditAndWorkItemFact` — atomicity proof by
  fault injection. A temporary trigger rejects the reopen fact of this test's own
  conversation after the message and state writes have run. The command fails with
  HTTP 500 and direct reads show no message, no reopen fact, no success audit and the
  conversation still at `resolved` version 2. Co-presence on success alone would not
  distinguish one commit from several commit-at-the-end transactions; this test does.
- `staleVersionRefusesReplyAndWritesNothing` — negative proof (stale version). An
  `If-Match` of 9 is refused with HTTP 412 `STALE_VERSION`; no message, reopen fact,
  audit fact or state change survives.
- `peerTenantCannotPublishIntoAnotherTenantsConversation` — negative proof (another
  tenant). The peer uses the same local patient and unit codes but cannot see the
  thread under forced RLS; the reply is refused with HTTP 404 and nothing is written.
- `otherPatientCannotPublishIntoThisPatientsConversation` — negative proof (patient
  ownership). A different verified patient in the same tenant is refused with HTTP 404
  and nothing is written.
- `wrongUnitRoleCannotPublish` — negative proof (wrong role and unit). A clerk who may
  reply in general but not in the recipient unit is refused with HTTP 403 `FORBIDDEN`;
  no message, work-item fact or audit row is committed (the unit guard throws inside
  the guarded transaction, so even the denial audit rolls back).
- `closedThreadRefusesPublicationAndWritesNothing` — negative proof (forbidden state).
  A reply to a closed thread is refused with HTTP 409 `CLOSED_THREAD`; the history keeps
  only its `created` and `closed` facts.
- `replyCurrentlyWritesNoNotificationOutboxRow` — active gap marker, not proof of the
  fourth write. It records that today's reply commits no `public.delivery_outbox` row
  in either tenant. It must be inverted when the outbox is wired.

Production classes and schema exercised through that entry point:

- `com.aurevia.smartclinic.messaging.conversations.ConversationController` — the thin
  adapter for `POST /api/v1/conversations/{id}/reply`, parsing `If-Match` and delegating.
- `com.aurevia.smartclinic.messaging.conversations.ConversationService` — `reply`
  performs authorization, row lock, version and state checks, `insertMessage`,
  `updateState`, the conditional reopen `appendEvent` and `success` inside the single
  `TransactionTemplate.execute` in `execute`; `requireAccess`, `requireVersion` and
  `requireWritable` hold the negative paths.
- `com.aurevia.smartclinic.messaging.conversations.ConversationRepository` —
  `insertMessage` (`public.conversation_message`), `updateState` (`public.conversation`),
  `appendEvent` (`public.work_item_event`) and `workQueue`. The staff work queue is a
  **derived** projection (`state <> 'closed'`), not a stored table; a reply changes the
  state and version that drive it but does not change queue membership, so the clause
  proof asserts the durable state/version and the work-item fact directly instead.
- `com.aurevia.smartclinic.audit.AuditAppender` — `append` writes `public.audit_event`
  inside the caller's transaction, so the success fact commits or rolls back with the
  publication and state change.
- `db/migrations/V33__conversation_lifecycle.sql` — the conversation, message and
  work-item ledgers with forced RLS, immutable append-only triggers and the version
  guard. `db/migrations/V1__tenant_schema.sql` — `public.audit_event` with its hash chain.
- `db/migrations/V55__durable_event_outbox.sql` — `public.delivery_outbox` with forced
  RLS; cited only to show the outbox table the reply path does **not** write today.

The reply route is pre-existing and currently baselined in
`contracts/openapi/missing-routes.txt`; this proof-only change adds no OpenAPI entry
and does not claim contract coverage for it.

All fixtures are synthetic (two tenants with identical local codes, no patient data or
credentials). This task added proof only: no production code, existing test, contract
or migration was changed.

## Gap

The outbox leg of the clause is **not built**. `ConversationService.reply` commits
publication, audit and the queue/state update in one transaction, but never inserts a
`public.delivery_outbox` row. The table exists (V55) and is written only by
`com.aurevia.smartclinic.events.JdbcDeliveryStore.publishAtomically`, reached through
`com.aurevia.smartclinic.events.DurableEventService`; nothing under `messaging` imports
or calls that path. Publication, audit and state are therefore committed together, and
the notification-outbox row is not.

A complete would-prove test is specified here rather than left as a skipped JUnit test:
the shipping constraint gate (`scripts/check-constraints.py`, run by `worker-check.sh`,
`quality-gate.sh` and `integrator-gate.sh`) rejects any newly added `@Disabled`, so a
literal skipped test would block the merge. A later implementation task should enable
this method in the test class above, invert `replyCurrentlyWritesNoNotificationOutboxRow`
and complete the token values with the chosen notification contract:

```java
@Test
void notificationOutboxRowIsCommittedWithTheReply() throws Exception {
    String id = open(ORG_A, PATIENT, "lab");
    ResponseEntity<String> reply = send(HttpMethod.POST,
            "/api/v1/conversations/" + id + "/reply", "{\"body\":\"notify me\"}",
            LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
    assertEquals(200, reply.getStatusCode().value(), reply.getBody());

    // The outbox row must be durable in the same commit as the message,
    // work-item fact, audit fact and conversation state.
    assertEquals(1L, matchingMessageCount(ORG_A, LAB_STAFF, id,
            "staff", LAB_STAFF, "notify me"));
    assertEquals(1L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
    Projection committed = projection(ORG_A, LAB_STAFF, id);
    assertEquals("awaiting_patient", committed.state());
    assertEquals(2, committed.version());

    Connection connection = scoped(ORG_A, LAB_STAFF);
    try (PreparedStatement query = connection.prepareStatement(
            "SELECT count(*) FROM public.delivery_outbox "
                    + "WHERE aggregate_id = CAST(? AS uuid) AND aggregate_version = 2 "
                    + "AND state = 'pending' AND actor_subject = 'lab-a-1111'")) {
        query.setString(1, id);
        try (ResultSet rows = query.executeQuery()) {
            rows.next();
            assertEquals(1L, rows.getLong(1),
                    "the reply must commit one durable notification outbox row");
        }
    } finally {
        connection.rollback();
        connection.close();
    }
    assertEquals(0L, outboxCount(ORG_B, PEER_PATIENT, id),
            "the outbox row must stay inside the owning tenant");
}
```

Closing the gap needs **at least two separate tasks** and touches production code, which
is outside this proof-only story:

1. Wire the notification outbox into the reply transaction: insert one `delivery_outbox`
   row inside `ConversationService.reply`'s `TransactionTemplate` (or call the events
   store from it), with a tenant-stable idempotency key, aggregate version, non-PHI
   payload digest and the `delivery_audit` fact. Files: `ConversationService.java`,
   `ConversationRepository.java` (or `events/JdbcDeliveryStore.java` and its interface),
   their tests, and any notification-command contract in `contracts/openapi.yaml` plus
   the regenerated client. No new migration is needed for the table itself (V55 already
   creates it with forced RLS), but a new event-type vocabulary may need documenting.
2. Enable the test above, invert `replyCurrentlyWritesNoNotificationOutboxRow`, change
   this document's Status to `Proven by tests`, and run the full JaCoCo and Delivery
   gates for the touched production classes.

A bounded third task may be needed if the notification itself (device-token or push
delivery, retry and dead-letter handling) is not already reachable from the outbox row.
