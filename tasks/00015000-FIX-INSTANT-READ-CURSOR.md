- [x] FIX-INSTANT-READ-CURSOR · Persist monotonic instant-message read cursors
  - evidence: Git history confirms PR #1372 merged into main at 7ca403a7.
  - pr: #1372
  - attempts: 1
  - requirements: MSG-02, ARC-02, ARC-07
  - scope: apps/api/src/main/java/com/aurevia/staffmsg/instant, apps/api/src/test/java/com/aurevia/staffmsg/instant, db/migrations/V96__staff_message_read_cursor.sql
  - depends: SMSG-03
  - reserved: V96
  - details:
    Agent brief. Resume from the current claim and latest handoff evidence.
    Current starting points to inspect are `db/migrations/V96__staff_message_read_cursor.sql`, `apps/api/src/main/java/com/aurevia/staffmsg/instant/InstantMessagingService.java`.
    Start with the existing assertions in `apps/api/src/test/java/com/aurevia/staffmsg/instant/InstantMessagingServiceTest.java`, `apps/api/src/test/java/com/aurevia/staffmsg/instant/InstantMessagingControllerTest.java` and add a failing case for the acceptance gap.
    Record the production entry point and integration test, and run `python3 -I scripts/check-wiring.py`.

    JdbcInstantConversationStore keeps read cursors in a ConcurrentHashMap even though messages are persisted. The instant controller is already present on main.
    Use an RLS-protected JDBC cursor keyed by tenant, conversation and participant. Keep updates monotonic and atomic. Reject or bound a cursor beyond the last visible sequence so later messages still become unread.
    Acceptance: a new store instance sees the cursor, stale or repeated updates do not move it backward, future messages become unread, and another tenant or participant cannot affect it. Cover HTTP mark-read and unread counts against PostgreSQL.
