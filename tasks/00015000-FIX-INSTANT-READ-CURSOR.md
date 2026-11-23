- [ ] FIX-INSTANT-READ-CURSOR · Persist monotonic instant-message read cursors
  - attempts: 1
  - requirements: MSG-02, ARC-02, ARC-07
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/instant, db/migrations/V96__staff_message_read_cursor.sql
  - depends: SMSG-03
  - reserved: V96
  - details:
    JdbcInstantConversationStore keeps read cursors in a ConcurrentHashMap even though messages are persisted. The instant controller is already present on main.
    Use an RLS-protected JDBC cursor keyed by tenant, conversation and participant. Keep updates monotonic and atomic. Reject or bound a cursor beyond the last visible sequence so later messages still become unread.
    Acceptance: a new store instance sees the cursor, stale or repeated updates do not move it backward, future messages become unread, and another tenant or participant cannot affect it. Cover HTTP mark-read and unread counts against PostgreSQL.
