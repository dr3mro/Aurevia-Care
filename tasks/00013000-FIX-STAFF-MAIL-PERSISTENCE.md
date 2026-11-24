- [x] FIX-STAFF-MAIL-PERSISTENCE · Store staff mail state and retry keys durably
  - pr: #682
  - evidence: PR #682; integrator ran scripts/repair-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/FIX-STAFF-MAIL-PERSISTENCE.md; simulated review only
  - attempts: 1
  - requirements: MSG-02, ARC-02, ARC-07
  - scope: apps/api/src/main/java/com/aurevia/staffmsg/mail, apps/api/src/test/java/com/aurevia/staffmsg/mail, db/migrations/V95__durable_staff_mail.sql
  - depends: SMSG-05
  - reserved: V95
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/api/src/main/java/com/aurevia/staffmsg/mail/MailStore.java`, `apps/api/src/main/java/com/aurevia/staffmsg/mail/JdbcMailStore.java`.

    MailService keeps drafts, messages, folders and send idempotency in process-local maps. Reuse the V91 mail tables where suitable and add a forward migration for gaps with RLS and FORCE RLS for new tables.
    Use JDBC behind an injected persistence port. Keep draft version checks and confirmation, persist recipients and per-person folders, and make send plus idempotency atomic.
    Acceptance: send and folder state survive a new service instance. Exercise stale draft, retry, rollback, two-tenant isolation and separate viewer state through HTTP against PostgreSQL.
