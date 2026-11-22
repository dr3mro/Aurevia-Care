# MSG-03 clause 2 — show recipient and pending files before send

## Clause

Before sending, show the recipient and pending files and require explicit send.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.BeforeSendingShowRecipientPendingFilesRequireTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/BeforeSendingShowRecipientPendingFilesRequireTest.java`).
It is a synthetic, database-free clause test in the `clauses` style used by
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: a public in-memory
store from `src/test`, a fixed clock and synthetic organization, subject, draft and
file identifiers. Each case enters through the service command the controller calls;
no production code, existing test, contract or migration was changed.

- `pendingListsUnsentDraftFilesBeforeSend` — one clean and one quarantined unsent
  file are both returned by `StaffAttachmentService.pending`; the quarantine reason
  is visible through `StaffAttachmentService.view().audit()` as a
  `SCANNED_QUARANTINED` fact (the pending projection itself has no reason field).
- `sendPublishesNamedCleanFilesOnlyWithExplicitConfirmation` — an explicit
  `SendRequest(confirm=true)` publishes only the two named clean files, marks them
  sent, records a `SENT` audit fact and leaves the quarantined file pending.
- `sendWithoutConfirmationRefusesAndSendsNothing` — `confirm=false` is refused with
  422 `CONFIRM_REQUIRED` and leaves no `sent()` write or `SENT` fact.
- `sendRefusesQuarantinedFileAndPublishesNoBatchMember` — a quarantined member is
  refused with 409 `QUARANTINED_FILE` before any member is published, so the clean
  members of the batch stay unsent (all-or-nothing).
- `sendRefusesWrongRoleAndForeignDraft` — `security_scanner` and `privacy_auditor`
  are refused with 403 `FORBIDDEN`, a file bound to another draft with 403
  `WRONG_DRAFT`, and a file resolved under another organization with 404
  `NOT_FOUND`; none is published.
- `recipientConfirmationMustMatchResolvedRecipientsBeforeSend` —
  `MailService.send` refuses a null confirmed list with 428 `CONFIRMATION_REQUIRED`
  and a partial confirmed list with 409 `RECIPIENT_CONFIRMATION_MISMATCH`, publishes
  nothing on either refusal, and on a matching confirmation freezes the role-holder
  subjects resolved by `RecipientDirectory` on the sent message. This proves that
  the recipient set must be explicitly confirmed, not that it is displayed.

Production classes and files surveyed and exercised by these entry points:

- `com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService` —
  `pending` (the `!sent()` filter and the quarantine audit reason) and `send`
  (the `confirm` gate, named-file validation, all-or-nothing publication and
  `SENT` audit) own the pending-files and explicit-send half of the clause.
- `com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract` —
  `SendRequest(draftId, messageId, attachmentIds, recipients, confirm)` is the
  command that carries the explicit confirmation; `StaffActor`, `StaffAttachment`,
  `ScanRequest`, `UploadRequest` and `View` are the synthetic value types used.
- `com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentActions` — the
  action matrix that denies `SEND` to scanner and auditor roles.
- `com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentController` —
  `GET .../pending` and `POST .../send` are the HTTP entry points; `send` injects
  the service and documents the `confirm` flag in the `SendRequest` schema.
- `com.aurevia.smartclinic.staffmsg.mail.MailService.send`,
  `MailRules.confirmationMatches`, `RecipientDirectory`, `MailContract.DraftView`
  and `MailCommands.SendCommand` — the recipient-confirmation half: `DraftView`
  carries declared `to()`/`cc()` recipients and `send` requires an exact match
  against the subjects resolved at send time.
- `contracts/openapi.yaml` (routes and the `SendRequest` schema, where `confirm` is
  required) was surveyed as evidence only and was not changed.
- A second MSG-03 path exists in `com.aurevia.smartclinic.messaging.attachments`
  (`AttachmentService.publish`, `AttachmentContract.PublishRequest` bound to
  `recipientUnitId`): it implements the "both sides attach" half but has no
  `confirm` flag and no recipient confirmation, so it is not the target of this
  clause test. The scoped package `messaging.clauses` holds the test while the
  production types it exercises stay in `staffmsg`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=BeforeSendingShowRecipientPendingFilesRequireTest test`
— Surefire report `tests="6" errors="0" skipped="0" failures="0"`.

## Gap

The recipient half of the clause is only partly observable through the API. There
is no pre-send surface that returns the *resolved* recipient list the sender must
confirm: `DraftView.to()`/`cc()` carry the declared recipients (which may be a
role), while `MailService.send` expands a role through `RecipientDirectory` at send
time and blind-matches `SendCommand.confirmedRecipients()` against that expanded
list. For a role recipient the concrete subjects are therefore not shown to the
sender before the confirm step, and the attachment `SendRequest.recipients` is only
checked for non-emptiness and never persisted or displayed. A staff web view that
renders resolved recipients before send is also outside this API test's scope.

Closing the gap needs a separate story (no production code was written here):
add a pre-send recipient-review response that resolves the draft's recipients in
the staff-mail domain (`MailContract`/`MailService`/`MailController`), document it
in `contracts/openapi.yaml` (route, tag and response), regenerate the client, and
have the staff-web compose view present that list before the send button. That is
one cross-boundary API story plus one staff-web story; the resolved-list endpoint
must precede the view.
