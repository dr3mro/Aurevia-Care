- [-] FIX-STAFF-ATTACHMENT-WIRING · Verify attachment authorization and send end to end for both staff messaging modes
  - agent: swarm-6
  - attempts: 1
  - requirements: MSG-03, CLN-07
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/staffmsg, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg, contracts/openapi.yaml, apps/staff-web/src/api/generated/openapi.d.ts
  - depends: SMSG-05, SMSG-07, FIX-STAFF-MAIL-PERSISTENCE
  - details:
    Agent brief. Trace the existing standalone attachment routes through `StaffAttachmentController` and `StaffAttachmentService.send`, then compare them with `MailService.send` and `InstantMessagingService.send`. Use `JdbcStaffAttachmentStore` to check how publication grants and attachment links persist. Read the `staffmsg/security/FlaggedAttachmentTest` and `StaffAttachmentControllerHttpTest` cases before adding coverage.
    Add a red HTTP test for each actual send path, from clean confirmed upload to download by the resolved recipient. Assert that a rejected attachment cannot publish a message or grant download access, and that retry does not issue duplicate grants or audit records. Verify the audience against persisted membership or frozen mail recipients.
    Record the controller-to-store trace and `python3 -I scripts/check-wiring.py` output. If durable linkage requires a migration, flag the out-of-scope data change before editing and request its reserved migration slot.

    SMSG-07 is scoped to the attachment package and depends only on the schema task. Verify the instant and mail send paths call its policy using durable draft/conversation linkage. Implement missing cross-package wiring.
    Acceptance: HTTP tests exercise confirmed clean uploads through both send paths and authorized download. Quarantined files, unconfirmed attachments, unrelated recipients and other tenants remain denied. Verify access audit and retry behavior. Update API schemas and regenerate types for changed contracts.
