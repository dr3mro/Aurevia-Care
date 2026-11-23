- [ ] FIX-STAFF-MAIL-FACILITY · Resolve staff mail role recipients by selected facility
  - attempts: 1
  - requirements: MSG-02, ARC-07
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/mail, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/mail, apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/directory, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/directory
  - depends: SMSG-05, SMSG-06, FIX-STAFF-MAIL-PERSISTENCE
  - details:
    StaffRecipientDirectory.holders accepts facilityId but queries every active member in the tenant by role.
    Reuse the facility-aware directory from SMSG-06. Resolve role holders for the selected facility at send time inside the tenant-scoped transaction. Fail closed when facility membership cannot be established.
    Acceptance: two facilities sharing a role produce different recipient sets; inactive staff and other organizations are excluded; changing eligible holders between compose and send requires recipient reconfirmation where the send contract requires it.
