- [x] FIX-STAFF-MAIL-FACILITY · Resolve staff mail role recipients by selected facility
  - evidence: Git history confirms PR #1378 merged into main at c7db37a7.
  - pr: #1378
  - attempts: 1
  - requirements: MSG-02, ARC-07
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/mail, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/mail, apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/directory, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/directory
  - depends: SMSG-05, SMSG-06, FIX-STAFF-MAIL-PERSISTENCE
  - details:
    Agent brief. Resume from the current claim and latest handoff evidence.
    Current starting points to inspect are `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/mail/MailRules.java`, `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/mail/MailStore.java`.
    Start with the existing assertions in `apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/mail/MailRulesTest.java`, `apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/mail/MailInputsTest.java` and add a failing case for the acceptance gap.
    Record the production entry point and integration test, and run `python3 -I scripts/check-wiring.py`.

    StaffRecipientDirectory.holders accepts facilityId but queries every active member in the tenant by role.
    Reuse the facility-aware directory from SMSG-06. Resolve role holders for the selected facility at send time inside the tenant-scoped transaction. Fail closed when facility membership cannot be established.
    Acceptance: two facilities sharing a role produce different recipient sets; inactive staff and other organizations are excluded; changing eligible holders between compose and send requires recipient reconfirmation where the send contract requires it.
