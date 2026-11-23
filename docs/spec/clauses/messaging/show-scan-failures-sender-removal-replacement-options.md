# MSG-03 clause 6 — show scan failures to the sender with removal or replacement options

## Clause

Show scan failures to the sender with removal or replacement options.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.ShowScanFailuresSenderRemovalReplacementOptionsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ShowScanFailuresSenderRemovalReplacementOptionsTest.java`),
a synthetic, database-free clause test in the same `clauses` style as
`docs/spec/clauses/scheduling/transfer-handoff-requires-active-assignee.md`:
`InMemoryAttachmentStore`, `AttachmentService` with `Clock.fixed(2026-11-19T12:00:00Z, UTC)`,
synthetic ORG_A/ORG_B, one unit and one patient, and the same `assertDenied`
status-plus-code helper as `AttachmentAcceptanceTest`. Every case enters through
`AttachmentService`, the domain service injected into `AttachmentController`.

- `failedVerdictIsRecordedWithBoundedDetailAndFailedState` — a scanner verdict of
  `failed` with a detail at the 200-character bound moves a quarantined file to
  `FAILED`, increments its version and appends one `scan_failed` fact carrying the
  bounded detail and the scanner subject (rule met).
- `uploaderSeesFailedVerdictAndBoundedDetailThroughViewAndEvents` — the uploading
  sender reads the same `FAILED` state and `scan_failed` detail through both `view`
  and `events`, and the two projections agree (rule met: failure shown to sender).
- `senderCanReplaceFailedFileByRemovingItAndUploadingAFreshCopy` — sender removal
  with reason `replace` moves the failed file to `REMOVED`, records the reason as a
  `removed` fact and leaves the original verdict intact; a fresh upload then yields
  a distinct quarantined file (rule met: removal and replacement path).
- `nonUploaderCannotRemoveFailedFile` — a same-tenant staff actor is refused with
  403 `NOT_UPLOADER` (negative: wrong actor).
- `otherTenantCannotViewFailedFileOrItsFacts` — another tenant with the same local
  connection identifier is refused with 404 `NOT_FOUND` on both `view` and `events`
  (negative: tenant concealment).
- `staleVersionCannotRemoveFailedFile` — a remove using an older version is refused
  with 409 `STALE_VERSION` (negative: stale update).
- `publishedFileCannotBeRemovedAsFailed` — a clean, published file cannot be removed
  and is refused with 409 `INVALID_REMOVE_STATE` (negative: forbidden state).
- `nonScannerCannotRecordFailedVerdict` — the uploader issuing a verdict is refused
  with 403 `FORBIDDEN` (negative: wrong role).
- `oversizedScanDetailIsRefused` and `oversizedRemovalReasonIsRefused` — details
  longer than 200 characters are refused with 422 `INVALID_DETAIL` (negative:
  missing or unbounded input).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — `scan`
  records the failed verdict, caps the detail and appends `scan_failed`; `view` and
  `events` return the file and its fact trail to the uploader through `visible`;
  `remove` is uploader-only, accepts a bounded reason and refuses published or
  already removed files; `requireVersion` guards stale updates.
- `AttachmentContract` — `AttachmentState.FAILED` and `REMOVED`, `ScanVerdict.FAILED`,
  `EventType.SCAN_FAILED` and `REMOVED`, `ScanRequest`, `RemoveRequest`, `View`, and
  `canTransitionTo` allowing `FAILED -> REMOVED`.
- `AttachmentInputs.detail` and `UploadValidation.detail` — bound both the verdict
  detail and the removal reason to 200 characters and return 422 on overflow.
- `AttachmentController` — surveyed only: `POST /api/v1/patient/files/upload`,
  `POST /api/v1/patient/files/{id}/scan` and `GET /api/v1/patient/files/{id}/download`
  are the routes exposed today; no route calls `view`, `events` or `remove`.
- `db/migrations/V82__attachment_store.sql` — `public.attachment`, `attachment_event`
  and the connection-grant table carry tenant keys, ENABLE and FORCE ROW LEVEL
  SECURITY, an append-only fact ledger with update/delete/truncate guards, and
  `failed`/`removed` in the state and verdict checks. No migration was changed here.
- Contract survey: `contracts/openapi.yaml` documents the staff scan route and a
  `ScanRequest` note about a bounded failure detail, but no sender-facing view,
  events, remove or replace operation. The three patient file routes are baselined
  in `contracts/openapi.yaml`. No contract file was changed here.

Supporting evidence already on the branch, not repeated here: the focused service
branches live in `AttachmentAcceptanceTest` — `recordsCleanAndFailedVerdictsAsSeparateFacts`,
`refusesScanFromNonScannerOnUnknownStaleOrTerminalState`,
`senderCanRemoveFailedDraftButNotPublishedFile`,
`quarantineIsPrivateToUploaderAndViewsAreTenantConcealed` and
`eventsAreScopedToTheRequestedAttachmentWithinOneTenant`.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ShowScanFailuresSenderRemovalReplacementOptionsTest test`
— `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

The domain rule is implemented and proven, but the sender cannot reach it through
the API or the patient app today. Missing:

1. **Sender-facing HTTP projection.** `AttachmentController` has no route that returns
   a failed file's view or fact trail, and no route that removes or replaces it. A
   later task adds `GET /api/v1/patient/files/{id}` (view),
   `GET /api/v1/patient/files/{id}/events` and a remove command
   (`DELETE /api/v1/patient/files/{id}` or `POST /api/v1/patient/files/{id}/remove`)
   that injects `AttachmentService`, re-derives the uploader from the live tenant and
   membership context, and keeps the existing 403/404/409/422 outcomes. The complete
   tests such a task should enable are MockMvc cases performing those requests with a
   synthetic failed file and asserting the `failed` state, the `scan_failed` detail and
   the `removed` state with reason `replace`, plus the wrong-uploader, other-tenant,
   stale-version and published-state denials.
2. **Replacement command.** No `replace` action, request type or linked-replacement
   operation exists; the only supported path today is remove then a fresh upload,
   which the enabled test proves. A dedicated linked replacement is a separate
   design decision.
3. **Contract.** The new sender routes need OpenAPI operations with tags, an
   `Idempotency-Key` header on the remove POST or DELETE and route-parity entries,
   plus regenerated clients; the corresponding `missing-routes.txt` lines can then
   shrink.
4. **Patient app wiring.** `apps/patient-mobile/src/app/RootNavigator.tsx` passes
   `placeholderAction` to `onRemove` and `onReplace`, so the buttons in
   `AttachmentReviewScreen.tsx` do nothing. A transport-backed attachment client and
   real callbacks are needed.

Closing the gap needs **two** bounded tasks: (a) the backend routes, OpenAPI entries
and HTTP tests above; (b) the patient-app transport and callback wiring with its
tests. No file beyond this note and the clause test was changed by this task.
