- [-] FIX-INSTANT-SEND-CONTRACT · Bind instant message sends to the conversation in the URL
  - agent: swarm-9
  - attempts: 3
  - requirements: MSG-02, ARC-07, WEB-05
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/instant, contracts/openapi.yaml, apps/staff-web/src/api/generated/openapi.d.ts
  - depends: SMSG-03, FIX-OPENAPI-03
  - details:
    Agent brief. Start at `InstantMessagingController.send` on `/{conversationId}/messages`. It currently forwards the body unchanged. Follow `InstantMessagingService.send` to `requireAccess` and the JDBC write. Reuse the Spring HTTP setup in `InstantMessagingIntegrationTest#httpMarkReadUpdatesUnreadCounts` and the persistence assertions in `aRetriedSendPublishesExactlyOneMessage`.
    Write the red HTTP case with two accessible conversations so a permission denial cannot mask the mismatch. Check the documented status and error code and verify both histories are unchanged. Send twice with the same key through the matching URL and assert a single persisted message. Keep the existing client body shape while enforcing the URL binding.
    Verify generated staff types, Spring route parity, the changed Java branch assertions and `python3 -I scripts/check-wiring.py`.

    InstantMessagingController.send receives a conversation ID from the URL but delegates using only the ID in the request body. A routing mismatch can send a message to a different conversation the sender belongs to.
    Bind authorization and the write to the URL ID. Reject inconsistent IDs from existing clients, and document the request and error contract. Regenerate staff web types.
    Acceptance: a normal send and a same-key retry produce one message in the route conversation. A mismatched request writes nothing to either conversation and produces a documented error. Drive both cases through HTTP with synthetic memberships.
