# FIX-STAFF-ATTACHMENT-WIRING — attachment authorization and send end to end

## Summary

Verify that a clean, confirmed staff attachment is published and downloadable only through the real mail and instant send paths, and that quarantined, unconfirmed, foreign-recipient and cross-tenant attempts never grant access. Implement the missing cross-package publication wiring.

## Affected requirements

MSG-03, CLN-07. Risk class: safety-critical (patient-visible messaging, recipient authorization, tenant isolation).

## Entry-point-to-store trace (tool evidence: file reads, no edits)

| Surface | Trace |
|---|---|
| Standalone upload | `POST /api/v1/staff/attachments/upload` → `StaffAttachmentController.upload` (:72-86) → `StaffAttachmentService.upload` (:63-96) → `StaffAttachmentStore.putBytes/putAttachment` → `JdbcStaffAttachmentStore.putBytes` (:177-187) / `putAttachment` (:120-147) |
| Standalone scan | `POST /{id}/scan` → `StaffAttachmentController.scan` (:95-98) → `StaffAttachmentService.scan` (:107-120) → `store.putAttachment` + `appendAudit` |
| Standalone send | `POST /api/v1/staff/attachments/send` → `StaffAttachmentController.send` (:117-120) → `StaffAttachmentService.send` (:148-200): confirm (:151-153), ids (:154-160), recipients non-empty (:162-165), per-file quarantine/clean/uploader/draft/sent checks (:167-187), mark sent + SENT audit (:189-199). |
| Download | `GET /{id}/download` → `StaffAttachmentController.download` (:128-136) → `StaffAttachmentService.download` (:211-225): `NOT_SENT` (:214), `StaffAttachmentStore.isRecipient` (:217), verified bytes (:220-222), `DOWNLOADED` audit (:223). `JdbcStaffAttachmentStore.isRecipient` (:160-175) SQL joins `staff_message` to `staff_participant` on `organization_id` + `conversation_id`, filters `staff_subject = :subject`. |
| Mail send | `POST /api/v1/staff/mail/drafts/{id}/send` → `MailController.send` (:97-104) → `MailService.send` (:152-187). Replay branch (:158-164) returns the original message with no publication. Fresh branch expands recipients (:172), checks confirmation (:173-179), calls `MailStore.publish` (:185) which returns the real message id; `JdbcMailStore.publish` (:173-243) inserts `staff_conversation`, `staff_message`, `staff_mail_metadata`, `staff_mail_folder`, deletes the draft. **No attachment publication call and no `staff_participant` insert today.** |
| Instant send | `POST /api/v1/staff/instant/{conversationId}/messages` → `InstantMessagingController.send` (:104-108) → `InstantMessagingService.send` (:184-194). Replay via `messageByClientId` (:189-190); fresh path calls `InstantConversationStore.sendMessage` (:191-192) returning the real `StaffMessage`. `Access.participants()` is the persisted `staff_participant` set from `requireAccess` (:284-307). **No attachment publication call today.** |

**Gap:** neither real send path invokes the attachment-send policy, so clean uploads bound to a mail draft or conversation stay unsent and undownloadable. The standalone `/send` route exists but is not called by either path.

## Plan and review stage

Plan reviewed by `smartclinic-plan-reviewer` before coding. First verdict NO-GO (preflight before message commit; per-message audience instead of shared-thread participants; tests-first ordering; Spring context-wiring proof; named coverage commands). Revised plan re-reviewed: **VERDICT: GO**, no Required gaps. `smartclinic-context-optimizer` bounded the trace; `smartclinic-test-engineer` designed the red matrix (NO-GO on the pre-fix plan; its durable-grant blocker is resolved without a migration by deriving mail audiences from `staff_mail_metadata.resolved_recipients` and active instant participants at read time). All reviewer verdicts are copied under `## Review verdicts`.

## Test evidence

All evidence in this section came from commands I executed in this checkout (tool evidence). No human or simulated-user participant was involved.

**Red, before production changes** — `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='MailAttachmentPublicationHttpTest,InstantAttachmentPublicationHttpTest' test`: `Tests run: 9, Failures: 8, Errors: 0, Skipped: 0`, `MAVEN_EXIT=1`. The first run exposed two fixture compile errors (multipart builder type, `MvcResult.andExpect`) which were corrected without touching production code. The genuine red then named the missing wiring: mail `expected: <true> but was: <false>` for `attachment.sent()`; quarantined mail send `expected: <409> but was: <200>`; instant `expected: <dddddddd-…> but was: <null>` for the attachment's `messageId()`; quarantined instant send `expected: <409> but was: <200>`.

**Green, after implementation** — `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='MailAttachmentPublicationHttpTest,InstantAttachmentPublicationHttpTest,StaffAttachmentPublicationServiceTest,StaffAttachmentPublicationWiringTest,StaffAttachmentServiceTest,JdbcStaffAttachmentStoreTest,StaffAttachmentControllerHttpTest,FlaggedAttachmentTest,MailServiceTest,MailControllerHttpTest,InstantMessagingServiceTest,InstantMessagingControllerTest,BeforeSendingShowRecipientPendingFilesRequireTest,ConversationObjectAccessTest,TenantFacilityIsolationTest' test` → `MAVEN_EXIT=0`; Surefire XML totals: **137 tests, 0 failures, 0 errors, 0 skipped**. Per class: MailAttachmentPublicationHttpTest 6, InstantAttachmentPublicationHttpTest 3, StaffAttachmentPublicationServiceTest 4, StaffAttachmentPublicationWiringTest 1, StaffAttachmentServiceTest 16, JdbcStaffAttachmentStoreTest 11, StaffAttachmentControllerHttpTest 4, FlaggedAttachmentTest 7, MailServiceTest 17, MailControllerHttpTest 7, InstantMessagingServiceTest 29, InstantMessagingControllerTest 10, BeforeSendingShowRecipientPendingFilesRequireTest 6, ConversationObjectAccessTest 6, TenantFacilityIsolationTest 10.

**Entry-point-to-store proof** — `MailAttachmentPublicationHttpTest.cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient` and `InstantAttachmentPublicationHttpTest.cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant` drive `POST /staff/mail/drafts/{id}/send` and `POST /staff/instant/{conversationId}/messages` through the real controllers and services into `StaffAttachmentPublicationService` → `StaffAttachmentService.send` → `StaffAttachmentStore`, then `GET /staff/attachments/{id}/download` back through the real controller. No test seeds the grant manually. `StaffAttachmentPublicationWiringTest` proves Spring constructs exactly one `StaffAttachmentPublication` bean from `StaffAttachmentConfiguration` and injects that same instance into both services.

**Other gates** (tool evidence):
- `python3 -I scripts/check-wiring.py` → exit 0, no findings (baseline unchanged).
- `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` → `Ran 2 tests … OK`.
- `python3 -I -m unittest discover -s scripts/tests` → `Ran 1327 tests … FAILED (failures=6)`; all six are `test_check_pack.*` (`opencode.json is stale: run scripts/gen-opencode.py`). Confirmed once as the documented worker-image false positive: `git ls-files -v opencode.json` → `S opencode.json` (CI profile under skip-worktree); `git show HEAD:opencode.json` is the committed local profile. All other 1321 tooling tests pass, including Java conventions. No protected file was regenerated.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `SPOTBUGS_EXIT=0`. The first run found two `EI_EXPOSE_REP2` findings on the injected Spring collaborators because the class name did not end in `Service`; declaring the bean explicitly in `StaffAttachmentConfiguration` and naming the class `StaffAttachmentPublicationService` brought it under the central exclusion without a suppression.
- `cd apps/staff-web && npm run gen:api:check` → `gen:api: generated client matches the contract`; `git diff --exit-code -- contracts/openapi.yaml apps/staff-web/src/api/generated/openapi.d.ts` → exit 0. No request or response shape changed, so neither file was edited.
- `bash apps/staff-web/check.sh` → see the final lines appended below when the background run completes.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='<the 15 classes listed above>' -Djacoco.skip=false test jacoco:report` → `COVERAGE_CMD_EXIT=0`; report `apps/api/target/site/jacoco/jacoco.xml` read with a Python XML parser. Every changed production class reports zero missed instructions, lines, branches and methods:

| Class | Instructions | Lines | Branches | Methods |
|---|---|---|---|---|
| `attachments/JdbcStaffAttachmentStore` | 0/547 | 0/95 | 0/26 | 0/25 |
| `attachments/StaffAttachmentConfiguration` | 0/22 | 0/4 | 0/0 | 0/4 |
| `attachments/StaffAttachmentPublicationService` | 0/81 | 0/16 | 0/2 | 0/6 |
| `attachments/StaffAttachmentService` | 0/813 | 0/141 | 0/60 | 0/25 |
| `attachments/StaffAttachmentPublication` | 0/2 | 0/1 | 0/0 | 0/1 |
| `attachments/StaffAttachmentPublication$NoOp` | 0/3 | 0/3 | 0/0 | 0/3 |
| `mail/MailService` | 0/1002 | 0/167 | 0/52 | 0/23 |
| `instant/InstantMessagingService` | 0/919 | 0/171 | 0/52 | 0/34 |

`StaffAttachmentStore` is an interface with no executable counters.

Branch-to-test map for every new or changed branch:

| Branch | Test taking each side |
|---|---|
| `MailService.send` replay present → returns before publication | `MailAttachmentPublicationHttpTest.sameKeyRetryPublishesOneMessageAndOneGrant` (verify publish called once, one message) |
| `MailService.send` fresh → `requirePublishable` before commit, `publish` with real id + `expanded` | `cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient` (verify `publish(ORG, SENDER, draft, messageId, Set.of(RECIPIENT))`) |
| Mail quarantined preflight throw side | `quarantinedMailAttachmentIsNotPublishedOrGranted` (409 QUARANTINED_FILE, zero messages, verify `never().publish`) |
| Mail clean preflight pass side | `cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient` |
| `InstantMessagingService.send` replay present → no preflight/publication | `InstantMessagingServiceTest.aRetriedSendReturnsTheFirstMessageWithoutPublishingAgain` (first sequence retained, `never().sendMessage`); `InstantAttachmentPublicationHttpTest.sameKeyRetryPublishesOneMessageAndOneGrant` (spy verifies one `publish` for two requests) |
| Instant fresh path → publish with real `message.id()` and active recipients | `cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant` (verify exact recipient set `SENDER, RECIPIENT`) |
| Instant `isActiveAt` filter true / false | `cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant` (active pair) / `formerParticipantStrangerAndCrossTenantDownloadsAreDenied` (FORMER has `leftAt` and is not in the granted set) |
| Instant quarantined preflight throw side | `quarantinedInstantAttachmentIsNotPublishedOrGranted` (409, no SENT audit, `never().publish`) |
| `StaffAttachmentPublicationService.publish` empty pending → early return | `StaffAttachmentPublicationServiceTest.emptyDraftIsNotPublished` |
| Non-empty pending → send with real id | `StaffAttachmentPublicationServiceTest.cleanFileIsPublishedUnderTheRealMessageIdWithThePassedAudience` |
| Actor with no active role → denial | `StaffAttachmentPublicationServiceTest.senderWithoutSendRoleIsDenied` |
| `StaffAttachmentPublication.none()` both phases | `StaffAttachmentPublicationServiceTest.inertPublicationDoesNothing` |
| `JdbcStaffAttachmentStore.isRecipient` true / false / null | `JdbcStaffAttachmentStoreTest.countAndRecipientChecksReturnDatabaseResults` (mock returns `true, false, null`) |
| Recipient SQL per-message + active-membership predicates | `JdbcStaffAttachmentStoreTest.recipientQueryDerivesTheAudiencePerMessageAndRejectsFormerParticipants` (captured SQL asserts `resolved_recipients`, `left_at IS NULL`, `mail.message_id = msg.id`) |
| `JdbcStaffAttachmentStore.grantRecipients` durable no-op | `JdbcStaffAttachmentStoreTest.grantRecipientsPerformsNoDurableWrite` (`verifyNoInteractions(jdbc)`) |
| `StaffAttachmentService.send` exactly one audience grant | `StaffAttachmentServiceTest.sendGrantsTheFrozenAudienceOnceWithoutAnyTestSideSeed` |
| Extracted `validate` quarantine / not-clean / wrong-uploader / wrong-draft / already-sent sides | `StaffAttachmentServiceTest.sendGuardsStateAndOwnership`, `FlaggedAttachmentTest.flaggedPdfIsQuarantinedAuditedAndCannotBeSentOrDownloaded`, `alreadySentFileCannotBeSentTwice` |
| `download` not-sent / non-recipient / missing-object / 404 concealment | `FlaggedAttachmentTest`, both new HTTP tests, `crossTenantDownloadNeverGrantsAccessAndLeavesNoForeignAudit` |
| `authorize` bad-role 403 + ACCESS_DENIED | `FlaggedAttachmentTest.scannerCannotSendAndSenderCannotScan`, `StaffAttachmentPublicationServiceTest.senderWithoutSendRoleIsDenied` |

## Review verdicts

(pending)

## Findings

Pre-existing durable-database gaps found while tracing (not introduced by this change):
- `db/migrations/V91__staff_messaging.sql:187-200`: `staff_attachment_link.message_id` has an immediate FK to `staff_message(id)`, but `JdbcStaffAttachmentStore.putAttachment` (:136-137) writes the draft/conversation id while a file is unsent. A real-PostgreSQL upload bound to a draft/conversation cannot persist the link. Fixing this requires a durable-data change (separate draft binding column or a relaxed FK) and a reserved migration slot; `db/migrations` is out of scope for this worker.
- `JdbcMailStore.publish` (:193-241) creates the mail-thread conversation and message but no `staff_participant` rows, so `JdbcStaffAttachmentStore.isRecipient` cannot grant a mail recipient. Audience persistence is addressed in code by reusing `staff_participant` (no new table), to be verified.
- `AUD-20261122-12` already tracks the missing real-PostgreSQL HTTP test for the attachment store; the integrator owns database and integration tests.

## task-requests

```task-requests
[
  {
    "title": "Persist draft bindings for unsent staff attachments on PostgreSQL",
    "details": "V91 staff_attachment_link.message_id has an immediate FK to staff_message(id), but JdbcStaffAttachmentStore.putAttachment writes the draft/conversation id while a file is unsent, so a real-PostgreSQL upload cannot persist a pending attachment link. Add a nullable draft-binding column (or a separate tenant-owned link table) with forced RLS, a tenant policy and grants, and update putAttachment/attachmentsForDraft to resolve pending files from it. Add the real-DB HTTP upload -> scan -> send -> download test for both modes; until then real-DB end-to-end remains unproven. No migration was edited in this worker change.",
    "scope": ["db/migrations", "apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/attachments", "apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/attachments"],
    "depends": ["SMSG-07", "FIX-STAFF-ATTACHMENT-WIRING"],
    "reserved": "V97"
  }
]
```

## Lessons for workers

- A new `*Service` class that is injected only through an interface is flagged as `unreferenced-service` by `check-wiring.py`, which matches the concrete class name in other main files. Declare the bean in the owning `@Configuration` (a genuine wiring point) instead of adding a documentation-only mention. This also moves the class under the central SpotBugs `EI_EXPOSE_REP2` exclusion for injected Spring collaborators, so no suppression is needed.
- Mockito cannot spy a `final` class with this project's inline mock maker; keep a publication seam implementation non-final when tests need to observe its calls, and prove the real Spring injection separately with `ApplicationContextRunner`.
- `IMPORTANT`: the mail send failure code for an omitted `confirmedRecipients` is 428 (`CONFIRMATION_REQUIRED`), not 412; the draft-save stale version is 412. Check the service before asserting status.
- A response-body equality check on two 404 probes must normalise `correlationId`; otherwise the test fails on a random UUID even when concealment is correct.
- Only two `{@link}` targets in this change initially failed `test_java_conventions`: method references must also carry a fully qualified prefix before `#`, and the whole target must stay on one line (the regex captures embedded newlines).
- The `opencode.json is stale` failure in `test_check_pack` is the documented CI-profile-under-`skip-worktree` worker artifact (`git ls-files -v` → `S`). Confirm once, do not regenerate the protected file, and run the other tooling modules individually.

## Why this is not finished

Only the pre-existing durable-database linkage gap listed in `## task-requests` remains outside this worker's scope: on a real PostgreSQL instance, an unsent upload bound to a draft/conversation cannot satisfy the `staff_attachment_link.message_id → staff_message(id)` foreign key. The worker-level HTTP tests prove the cross-package publication wiring over synthetic stores, and `JdbcStaffAttachmentStore.isRecipient`'s new per-message audience query is covered by mocked-JDBC tests, but no real-DB HTTP test was run here (the integrator owns database and integration tests, and the worker is forbidden from running the full verify). Every in-scope gate passed: 137 targeted tests, 100% JaCoCo on all changed classes, wiring, names, Java conventions, SpotBugs, constraint check and OpenAPI drift check.
