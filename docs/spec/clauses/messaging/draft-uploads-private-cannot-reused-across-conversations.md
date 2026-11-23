# ARC-07 clause 3 — API test evidence

## Clause

Draft uploads are private and cannot be reused across conversations.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.DraftUploadsPrivateCannotReusedAcrossConversationsTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/DraftUploadsPrivateCannotReusedAcrossConversationsTest.java`).
All three tests are active (none disabled) and run against real PostgreSQL with synthetic fixtures only.
They use a fresh database `smartclinic_draft_uploads_clause_it`, apply the production Flyway migrations under
`db/migrations`, seed two tenants with identical local connection, unit and patient identifiers and bind
`TenantContext` exactly as the HTTP interceptor does.

- `draftIsPrivateToItsUploaderAndBoundToItsConversation` — positive proof. A patient uploads through
  `AttachmentService.upload`, the row is `QUARANTINED` with a `PENDING` verdict, the uploader alone can `view`
  it and read its `UPLOADED` fact, `download` returns 409 `NOT_PUBLISHED`, and the stored row keeps its
  uploader, connection, unit and patient binding.
- `anotherActorCannotSeeOrDownloadTheDraft` — negative privacy proof. A different subject in the same tenant
  gets 404 from `view` and `events`; a different-unit staff actor with a DOWNLOAD-permitting role gets 404
  from `view`/`events` and 409 `NOT_PUBLISHED` from `download`, proving the state guard precedes the participant
  check; a different tenant gets 404 from `view`, `events` and `download`, proving tenant concealment precedes
  the state guard. Neither unit's inbox exposes the draft.
- `draftCannotBeReboundToAnotherConversationOrUploader` — negative reuse proof. Copies of the stored draft with
  a different `connectionId` or `uploaderSubject` are refused by `JdbcAttachmentStore.putAttachment` with
  409 `WRONG_CONVERSATION` and every binding column stays unchanged. A direct `UPDATE ... SET connection_id`
  by the migration owner raises the immutable-binding error, and the row still carries its original connection.

Production classes and artefacts exercised through their public boundaries:

- `com.aurevia.smartclinic.messaging.attachments.AttachmentService` — `visible()` confines a non-downloadable
  draft to its uploader for `view()` and `events()`; `download()` refuses an unpublished draft with 409
  `NOT_PUBLISHED` before checking conversation participation; `load()` reads only inside the active tenant.
- `com.aurevia.smartclinic.messaging.attachments.JdbcAttachmentStore` — the guarded `INSERT ... ON CONFLICT ...
  WHERE` upsert returns no row and raises 409 `WRONG_CONVERSATION` when an existing draft would be rebound to a
  different uploader, connection, unit or patient.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentContract` — the `Attachment`, `Actor`,
  `ConnectionGrant` and `View` records carry the immutable binding and fact trail used by the assertions.
- `db/migrations/V82__attachment_store.sql` — `attachment_binding_immutable()` trigger plus `ENABLE`/`FORCE ROW
  LEVEL SECURITY` and the tenant policy are the database authority for both non-reuse and tenant isolation.
- `com.aurevia.smartclinic.messaging.attachments.AttachmentController` — exposes only `POST /api/v1/patient/files/upload`,
  `POST /api/v1/patient/files/{id}/scan` and `GET /api/v1/patient/files/{id}/download`. No publish, view or events
  HTTP route exists, so the draft privacy and non-reuse rules cannot be observed over HTTP; the real-DB
  service/store path above is the appropriate evidence for this test-only clause proof.

## Gap

None.
