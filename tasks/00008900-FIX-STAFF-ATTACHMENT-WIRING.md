- [ ] FIX-STAFF-ATTACHMENT-WIRING · Verify attachment authorization and send end to end for both staff messaging modes
  - attempts: 0
  - requirements: MSG-03, CLN-07
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg, contracts/openapi.yaml, apps/staff-web/src/api/generated/openapi.d.ts
  - depends: SMSG-05, SMSG-07, FIX-STAFF-MAIL-PERSISTENCE
  - details:
    SMSG-07 is scoped to the attachment package and depends only on the schema task. Verify the instant and mail send paths call its policy using durable draft/conversation linkage. Implement missing cross-package wiring.
    Acceptance: HTTP tests exercise confirmed clean uploads through both send paths and authorized download. Quarantined files, unconfirmed attachments, unrelated recipients and other tenants remain denied. Verify access audit and retry behavior. Update API schemas and regenerate types for changed contracts.
