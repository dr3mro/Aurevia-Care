# MSG-03 clause 8 — file sent to a unit enters its conversation inbox as patient-supplied material

## Clause

A file sent to a unit enters its conversation inbox as patient-supplied material.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.FileSentUnitEntersConversationInboxPatientTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/FileSentUnitEntersConversationInboxPatientTest.java`),
service-level tests over the synthetic `InMemoryAttachmentStore` with the same
fixtures and fixed-clock style as `messaging/attachments/AttachmentAcceptanceTest`.
No production file was changed by this story.

Enabled tests and the production behaviour they exercise:

- `publishedPatientFileAppearsInTheReceivingUnitInboxAsPatientSuppliedMaterial` —
  a patient upload receives a clean scan, is explicitly published to a message
  and recipient unit, and then appears in the receiving unit's inbox with
  `patientId = pat-001`, `uploaderSubject = patient-a`, `messageId = msg-1`,
  `recipientUnitId = unit-1`, `state = published` and a separate `PUBLISHED`
  fact sourced from the patient actor. The same values are asserted on the
  published projection, the inbox item and the fact trail.
- `unpublishedFileStaysOutOfTheUnitInbox` — quarantined, clean-but-unpublished
  and failed files are all excluded: the `state == PUBLISHED` filter admits only
  published material.
- `staffFromAnotherUnitCannotReadTheInbox` — a genuinely published file bound to
  another unit is visible only in that other unit's inbox; a staff actor whose
  unit differs from the requested inbox, or who has no unit assignment, is
  refused (403 `WRONG_UNIT`). This covers both sides of the unit-match guard.
- `patientCannotReadTheUnitInbox` — a patient actor is refused with 403
  `PATIENT_INBOX_UNAVAILABLE`; the patient message list is a different surface.
- `otherTenantCannotSeeTheUnitInbox` — two tenants publish files to the same
  local unit id; each inbox sees only its own tenant's file. Tenant scoping comes
  from the store key and RLS in the real implementation.

Production classes exercised by these tests:
`com.aurevia.smartclinic.messaging.attachments.AttachmentService` (`upload`,
`scan`, `publish`, `inbox`, `events` and the append-only fact writer),
`AttachmentContract` (`Actor`, `ConnectionGrant`, `Attachment`,
`AttachmentEvent`, `PublishRequest`, `ScanRequest`, `UploadRequest`,
`AttachmentState`, `EventType`), `AttachmentActions` and `AttachmentStore`.
The durable schema is `db/migrations/V82__attachment_store.sql`, which stores
the uploader, unit, patient, message and recipient binding, the `published`
state and fact, RLS with FORCE RLS, immutable binding and append-only facts.

Scope of the claim: `AttachmentService.inbox(unit)` computes a unit-scoped list
of `state == PUBLISHED` attachments from the tenant store; it is not a persisted
conversation inbox and no attachment-to-conversation-message record is written
today. `patientId` is grant-derived (a staff upload on the same verified grant
also carries it), so "patient-supplied" rests on `uploaderSubject = patient-a`
together with the `PUBLISHED` fact. The route-level proof is therefore not
available from this evidence task:

- An HTTP acceptance sketch (`sentFileAppearsInUnitConversationInboxOverHttp`
  in `SentFileAppearsInUnitConversationInboxOverHttpTest`) was drafted with the
  real-PostgreSQL/Flyway fixture style of the neighbouring clause tests
  (`chart/clauses/RecordClinicalAuthorSeparatelyDataEntryActorTest`), but the
  publish/inbox routes and the attachment-to-conversation-message link it needs
  do not exist. It was deleted in repair attempt 1 as dead, unexercisable code
  rather than left `@Disabled` (the constraint gate forbids an added skip). The
  follow-up implementation task must re-create it as an enabled HTTP test when
  those routes land; see the Gap section below.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security
-Dtest=FileSentUnitEntersConversationInboxPatientTest test` — Surefire
`tests="5" errors="0" skipped="0" failures="0"` for the enabled class. No
skipped test remains in this change.

## Gap

The domain rule is implemented and service-level provenance is proven, but the
clause is not reachable end to end, so this is not "Proven by tests":

1. **No HTTP publication route.** `AttachmentController`
   (`apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentController.java:83-128`)
   exposes only `/upload`, `/{id}/scan` and `/{id}/download`. There is no route
   calling `AttachmentService.publish`, `inbox` or `fileIntoChart`; no production
   caller of those methods exists anywhere under `apps/api/src/main`.
2. **No unit-inbox route and no conversation link.** The patient-side
   `GET /api/v1/conversations/inbox` returns `Conversation + unreadCount` only;
   it carries no attachment provenance. No table or code links a published
   `messageId` to a `conversation_message`, and there is no "patient-supplied"
   source-kind marker on messages.
3. **No contract entry.** `contracts/openapi.yaml` has no patient-files
   publish/inbox path, and even `contracts/openapi.yaml` lists
   only upload, scan and download for `/api/v1/patient/files` (the missing-route
   baseline must not be edited by this task).

Files a closing task would change (none changed here):
`AttachmentController.java`, a new thin route-delegation layer if the reading
service needs a caller, `ConversationService`/`ConversationRepository`, a Flyway
migration adding the attachment-to-conversation-message link with RLS and FORCE
RLS, `contracts/openapi.yaml` (with a tag and `Idempotency-Key` on the publish
POST), the regenerated client and their tests. `JdbcAttachmentStore` and the
V82 attachment schema already exist and must not be recreated. This is
**one** bounded cross-boundary task plus **one** OpenAPI contract task (two
separate tasks); it is not a documentation-only gap.
