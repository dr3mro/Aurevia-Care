# FIX-STAFF-ATTACHMENT-WIRING — attachment authorization and send end to end

## Summary

A clean, confirmed staff attachment is now published and downloadable only through the real mail and instant send paths. Both paths run a preflight before the message row is committed and bind the validated files after the store returns the real message id. Quarantined, unconfirmed, foreign-recipient, former-participant and cross-tenant attempts grant no access and leave no foreign audit. The pending-to-sent transition is a conditional compare-and-set, so a same-key race cannot emit a second SENT fact. Segment 2 resumed the committed segment 1 work after `origin/main` was merged, re-verified every gate, closed the security reviewer's Required concurrency finding, and completed the review loop.

## Affected requirements

MSG-03, CLN-07. Entry `tasks/00020000-FIX-STAFF-ATTACHMENT-WIRING.md`.

## Risk class

Safety-critical (patient-visible messaging, recipient authorization, tenant isolation) per `docs/WORKFLOW.md`.

## Review owner

Primary swarm worker `swarm-6`; read-only reviewers listed under `## Review verdicts`. No human or clinical participant reviewed this change.

## Data changes

No migration or backfill in this worker change. `contracts/openapi.yaml` and `apps/staff-web/src/api/generated/openapi.d.ts` are unchanged: no request or response shape changed, and `npm run gen:api:check` reports no drift. A pre-existing durable-data gap (an unsent attachment link cannot satisfy the immediate `message_id → staff_message(id)` foreign key on real PostgreSQL) is out of scope, recorded under `## Findings` and requested under `## task-requests` with the reserved slot V97.

## Authorization boundaries and audit events

Tenant and subject come only from `TenantContext` (active organization header validated against membership); attachment drafts, links, byte objects and audits are all tenant-scoped. `StaffAttachmentPublicationService` re-derives active roles from `StaffRepository.activeRoles` inside the guarded transaction on both the preflight and the bind phase. Mail download grants come from the frozen `staff_mail_metadata.resolved_recipients`; instant grants come from still-active `staff_participant` rows; the standalone `/send` route cannot manufacture a durable grant because `JdbcStaffAttachmentStore.grantRecipients` is a documented no-op. Audit facts: UPLOADED, SCANNED_CLEAN, SCANNED_QUARANTINED, SENT, DOWNLOADED and ACCESS_DENIED, with no payload bytes, checksums or object keys in any fact.

## Migration and rollout considerations

None in scope. Rollout is gated on the integrator's database and full integration suite; the reserved V97 data change must land before a real-PostgreSQL unsent upload bound to a draft/conversation can be exercised end to end.

## Acceptance mapping

| Story acceptance | Test evidence |
|---|---|
| Confirmed clean upload through the mail send path, download by resolved recipient | `MailAttachmentPublicationHttpTest.cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient` |
| Confirmed clean upload through the instant send path, download by active participant | `InstantAttachmentPublicationHttpTest.cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant` |
| Quarantined file cannot publish a message or grant access | `quarantinedMailAttachmentIsNotPublishedOrGranted`, `quarantinedInstantAttachmentIsNotPublishedOrGranted`, `FlaggedAttachmentTest.flaggedPdfIsQuarantinedAuditedAndCannotBeSentOrDownloaded` |
| Unconfirmed attachment cannot publish | `MailAttachmentPublicationHttpTest.unconfirmedSendCannotPublishAttachments`, `StaffAttachmentServiceTest.sendGuardsStateAndOwnership` |
| Unrelated recipient, former participant and other tenant denied | `InstantAttachmentPublicationHttpTest.formerParticipantStrangerAndCrossTenantDownloadsAreDenied`, `MailAttachmentPublicationHttpTest.crossTenantDownloadNeverGrantsAccessAndLeavesNoForeignAudit`, `StaffAttachmentAudienceIntegrationTest.onlyTheExactMessagesAudienceCanDownloadItsAttachment` |
| Audience checked against persisted membership or frozen mail recipients | `JdbcStaffAttachmentStoreTest.recipientQueryDerivesTheAudiencePerMessageAndRejectsFormerParticipants`, `StaffAttachmentAudienceIntegrationTest` |
| Access audit and retry behavior | `sentAuditFactsCarryNoPayloadOrIntegrityValue`, `sameKeyRetryPublishesOneMessageAndOneGrant` in both HTTP tests, `StaffAttachmentAtomicBindTest.onlyTheWinningPublicationRecordsSent` |
| API schemas updated and types regenerated for changed contracts | No contract shape change; `(cd apps/staff-web && npm run gen:api:check)` → `generated client matches the contract`, `git diff --exit-code -- contracts/openapi.yaml apps/staff-web/src/api/generated/openapi.d.ts` → 0 |

## Clinical and architecture review

Simulated/static specialist review only (plan, code, coverage, devsecops). No physician, unit or real-patient sign-off was used; live enablement remains a G5 gate.

## Entry-point-to-store trace (tool evidence: file reads, no edits)

| Surface | Trace |
|---|---|
| Standalone upload | `POST /api/v1/staff/attachments/upload` → `StaffAttachmentController.upload` → `StaffAttachmentService.upload` → `StaffAttachmentStore.putBytes/putAttachment` → `JdbcStaffAttachmentStore.putBytes` / `putAttachment` |
| Standalone scan | `POST /{id}/scan` → `StaffAttachmentController.scan` → `StaffAttachmentService.scan` → `store.putAttachment` + `appendAudit` |
| Standalone send | `POST /api/v1/staff/attachments/send` → `StaffAttachmentController.send` → `StaffAttachmentService.send`: confirm, ids, recipients, per-file quarantine/clean/uploader/draft/sent checks, mark sent + SENT audit |
| Mail send | `POST /api/v1/staff/mail/drafts/{id}/send` → `MailController.send` → `MailService.send`: replay short-circuit, `attachments.requirePublishable` preflight, `MailStore.publish` with real message id, `attachments.publish` with frozen `expanded` recipients → `StaffAttachmentPublicationService` → `StaffAttachmentService.publishPrepared` → `StaffAttachmentStore.bindToMessage` |
| Instant send | `POST /api/v1/staff/instant/{conversationId}/messages` → `InstantMessagingController.send` → `InstantMessagingService.send`: replay short-circuit, preflight, `InstantConversationStore.sendMessage` with real message id, publish with active `staff_participant` subjects through the same publication seam |
| Download | `GET /{id}/download` → `StaffAttachmentController.download` → `StaffAttachmentService.download`: not-sent, `StaffAttachmentStore.isRecipient`, verified bytes, DOWNLOADED audit; `JdbcStaffAttachmentStore.isRecipient` joins `staff_message` to frozen mail recipients and active participants on `organization_id` |

`StaffAttachmentPublicationWiringTest` proves Spring constructs exactly one `StaffAttachmentPublication` bean from `StaffAttachmentConfiguration` and injects that same instance into both send services. No test seeds a grant manually in the two HTTP send tests.

## Plan and review stage

`aurevia-plan-reviewer` reviewed the plan before coding: first NO-GO (preflight before commit, per-thread audience, tests-first, context-wiring proof, named coverage commands), revised plan **GO** with no Required gaps. `aurevia-test-engineer` designed the red matrix. Segment 2's atomic-bind fix was re-reviewed by all three post-code reviewers; all final verdicts are copied under `## Review verdicts`.

## Test evidence

Evidence in this section came from commands executed in this checkout (tool evidence). Reviewer verdicts are simulated/static review evidence. No simulated-user or human participant was involved.

**Merge state (tool evidence)** — `git merge --no-edit origin/main` → `Already up to date.` at merge HEAD `13d69d58`; `git diff --name-only 37321f5c..13d69d58 -- apps/api/src/main/java/com/aurevia/staffmsg apps/api/src/test/java/com/aurevia/staffmsg contracts/openapi.yaml apps/staff-web/src/api/generated/openapi.d.ts` → empty; `git grep -nE '^(<<<<<<<|>>>>>>>) '` → none.

**Red (recorded in segment 1, before production changes)** — `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='MailAttachmentPublicationHttpTest,InstantAttachmentPublicationHttpTest' test`: `Tests run: 9, Failures: 8`, `MAVEN_EXIT=1`. Genuine red named the missing wiring (`attachment.sent()` false, quarantined sends 200 instead of 409, null attachment message id).

**Green, final post-merge run** — `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='MailAttachmentPublicationHttpTest,InstantAttachmentPublicationHttpTest,StaffAttachmentPublicationServiceTest,StaffAttachmentPublicationWiringTest,StaffAttachmentAtomicBindTest,StaffAttachmentServiceTest,JdbcStaffAttachmentStoreTest,StaffAttachmentControllerHttpTest,FlaggedAttachmentTest,MailServiceTest,MailControllerHttpTest,InstantMessagingServiceTest,InstantMessagingControllerTest,BeforeSendingShowRecipientPendingFilesRequireTest,ConversationObjectAccessTest,TenantFacilityIsolationTest' -Djacoco.skip=false test jacoco:report` → `COVERAGE_CMD_EXIT=0`; fresh Surefire XML totals **144 tests, 0 failures, 0 errors, 0 skipped** (MailAttachmentPublicationHttpTest 6, InstantAttachmentPublicationHttpTest 4, StaffAttachmentPublicationServiceTest 8, StaffAttachmentPublicationWiringTest 1, StaffAttachmentAtomicBindTest 1, StaffAttachmentServiceTest 16, JdbcStaffAttachmentStoreTest 12, StaffAttachmentControllerHttpTest 4, FlaggedAttachmentTest 7, MailServiceTest 17, MailControllerHttpTest 7, InstantMessagingServiceTest 29, InstantMessagingControllerTest 10, BeforeSendingShowRecipientPendingFilesRequireTest 6, ConversationObjectAccessTest 6, TenantFacilityIsolationTest 10).

**Real-PostgreSQL audience check** — `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=StaffAttachmentAudienceIntegrationTest test` → `IT_EXIT=0`. Flyway applied 59 migrations to v96; the test proves two disjoint frozen mail audiences, departed-participant denial and cross-tenant denial with forced RLS. A stale failure XML from an earlier run at 19:15 (RLS `organization` insert) was superseded by this passing run; it was not caused by the committed change.

**Frontend definition of done** — `bash apps/staff-web/check.sh` → `STAFFWEB_EXIT=0`. Typecheck final lines: `> aurevia-staff-web@0.1.0 typecheck`, `> tsc --noEmit -p tsconfig.json`, `> tsc --noEmit -p tsconfig.json && vite build`; no `error TS` in the log. Vitest: `Test Files 252 passed (252)`, `Tests 2977 passed (2977)`; coverage `No files with missing coverage.`, `267 files fully covered.`, `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`.

**Other gates (tool evidence)** — `python3 -I scripts/check-wiring.py` → 0; `python3 -I scripts/check-names.py` → 0; `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` → `Ran 2 tests … OK`; `python3 -I scripts/check-constraints.py --base origin/main --require-base` → 0; `python3 -I scripts/check-docs.py` → 0; `(cd apps/staff-web && npm run gen:api:check)` → `generated client matches the contract`; `git diff --exit-code -- contracts/openapi.yaml apps/staff-web/src/api/generated/openapi.d.ts` → 0. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `SPOTBUGS_EXIT=0` (compile before check; no new finding). `bash scripts/quality-gate.sh origin/main docs/pr/notes/FIX-STAFF-ATTACHMENT-WIRING-20261123-182802.md` → `QUALITY_GATE_EXIT=0`. The first quality-gate run failed because the new JDBC test added a `@SuppressWarnings("unchecked")`; that annotation was removed and the mapper cast changed to the reifiable `RowMapper<?>` form, after which the gate passed with no suppression added.

`bash scripts/check.sh` is not run by this worker: it delegates to `apps/api/check.sh` → full `mvn verify` (over ten minutes), which the worker instructions forbid; the integrator and Delivery run that gate and the database suite on the exact merge SHA. The constituent fast policy checks above were all run here. The full `python3 -I -m unittest discover -s scripts/tests` run from segment 1 had six `test_check_pack` failures from the documented CI-profile-under-`skip-worktree` artifact (`git ls-files -v opencode.json` → `S`); the committed profile at `git show HEAD:opencode.json` is correct, and no protected file was regenerated.

## Coverage

Command: the final 16-class command above with `-Djacoco.skip=false test jacoco:report`; exit 0. Report `apps/api/target/site/jacoco/jacoco.xml` parsed with an XML parser. Every changed production class reports zero missed instructions, lines, branches and methods:

| Class | Instructions | Lines | Branches | Methods |
|---|---|---|---|---|
| `attachments/JdbcStaffAttachmentStore` | 0/663 | 0/114 | 0/30 | 0/29 |
| `attachments/StaffAttachmentConfiguration` | 0/22 | 0/4 | 0/0 | 0/4 |
| `attachments/StaffAttachmentPublicationService` | 0/68 | 0/16 | 0/2 | 0/6 |
| `attachments/StaffAttachmentService` | 0/918 | 0/160 | 0/72 | 0/28 |
| `attachments/StaffAttachmentPublication` | 0/10 | 0/4 | 0/0 | 0/4 |
| `mail/MailService` | 0/1004 | 0/167 | 0/52 | 0/23 |
| `instant/InstantMessagingService` | 0/933 | 0/172 | 0/52 | 0/35 |

`StaffAttachmentStore` is an interface with no executable counters. `InMemoryStaffAttachmentStore` lives under `src/test`. One earlier intermediate run showed a single missed mapper lambda at `JdbcStaffAttachmentStore.java:182`; the JDBC test now invokes the real `RowMapper` through a mocked `ResultSet`, and the final run reports zero misses.

Branch-to-test map for every new or changed branch:

| Branch | Test taking each side |
|---|---|
| `JdbcStaffAttachmentStore.bindToMessage` `changed == 1` true → metadata write + true | `JdbcStaffAttachmentStoreTest.bindToMessageReportsOnlyTheWinningConditionalWrite` (`thenReturn(1)`, `assertTrue`, `verify(documents).putArtifact`) |
| `bindToMessage` `changed == 0` → replay/conflict probe | same test (`thenReturn(0)`) |
| `bindToMessage` probe `anyMatch` true → false (replay, no audit) | same test (bound id `message-1` equals target, `assertFalse`) |
| `bindToMessage` probe `anyMatch` false → 409 `ALREADY_SENT` | same test (bound id `other-message`, `assertThrows` code) |
| `StaffAttachmentService.publishPrepared` `if (store.bindToMessage(updated))` true → SENT audit + bound result | `StaffAttachmentAtomicBindTest.onlyTheWinningPublicationRecordsSent` first call, `assertEquals(AuditType.SENT, …)` |
| `publishPrepared` false → no SENT audit (deterministic `true,false` stub, no thread race) | same test second call, `verify(store, times(1)).appendAudit(...)` |
| `MailService.send` replay present → returns before publication | `MailAttachmentPublicationHttpTest.sameKeyRetryPublishesOneMessageAndOneGrant` |
| `MailService.send` fresh → preflight then publish with real id and frozen recipients | `cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient` |
| Mail quarantined preflight throw / clean pass | `quarantinedMailAttachmentIsNotPublishedOrGranted` (409, `never().publish`) / clean test |
| `InstantMessagingService.send` replay → no preflight or publication | `InstantMessagingServiceTest.aRetriedSendReturnsTheFirstMessageWithoutPublishingAgain`, `InstantAttachmentPublicationHttpTest.sameKeyRetryPublishesOneMessageAndOneGrant` |
| Instant fresh publish with real `message.id()` and active recipients | `cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant` (`Set.of(SENDER, RECIPIENT)`) |
| Instant `isActiveAt` true / false | clean instant test / `formerParticipantStrangerAndCrossTenantDownloadsAreDenied` |
| Instant quarantined preflight throw / clean pass | `quarantinedInstantAttachmentIsNotPublishedOrGranted` / clean instant test |
| `StaffAttachmentPublicationService.publish` empty pending → early return | `StaffAttachmentPublicationServiceTest.emptyDraftIsNotPublished` |
| Non-empty pending → send with real id | `cleanFileIsPublishedUnderTheRealMessageIdWithThePassedAudience` |
| Actor with no active role → denial | `senderWithoutSendRoleIsDenied` |
| `StaffAttachmentPublication.none()` both phases | `inertPublicationDoesNothing` |
| `JdbcStaffAttachmentStore.isRecipient` true / false / null | `countAndRecipientChecksReturnDatabaseResults` (mock `true, false, null`) |
| Recipient SQL per-message + active-membership predicates | `recipientQueryDerivesTheAudiencePerMessageAndRejectsFormerParticipants`, `StaffAttachmentAudienceIntegrationTest` |
| `JdbcStaffAttachmentStore.grantRecipients` durable no-op | `grantRecipientsPerformsNoDurableWrite` (`verifyNoInteractions(jdbc)`) |
| `StaffAttachmentService.send` exactly one audience grant | `sendGrantsTheFrozenAudienceOnceWithoutAnyTestSideSeed` |
| Extracted `validate` quarantine / not-clean / wrong-uploader / wrong-draft / already-sent sides | `sendGuardsStateAndOwnership`, `FlaggedAttachmentTest.flaggedPdf…`, `alreadySentFileCannotBeSentTwice` |
| `download` not-sent / non-recipient / missing-object / 404 concealment | `FlaggedAttachmentTest`, both HTTP tests, `crossTenantDownloadNeverGrantsAccessAndLeavesNoForeignAudit` |
| `authorize` bad-role 403 + ACCESS_DENIED | `scannerCannotSendAndSenderCannotScan`, `senderWithoutSendRoleIsDenied` |

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Resolved (Required, devsecops):** concurrent same-key sends could both observe a pending file and emit two SENT audits. Fixed with the conditional `bindToMessage` compare-and-set, winner-only SENT audit and deterministic race test; the reviewer re-ran and answered GO.
- **Resolved (Required, coverage auditor):** the notes had not yet recorded the new fix pass. This file now lists the 16-class command, refreshed JaCoCo counts and all six new branch-to-test rows.
- **Accepted (Optional):** mail senders are not in the frozen `resolved_recipients`, so a mail sender cannot download their own attachment while an instant sender can; fail-closed and consistent with the recipient-based grant model. Instant audience is evaluated at read time, so a later-added active participant can read an earlier message; revoked roles are still denied by the fresh DOWNLOAD role check. The unused `draftId` argument on `StaffAttachmentPublication.publish` is kept for contract symmetry; the bind derives the pending link from the stored draft id. Standalone `/send` uses unconditional `putAttachment`; it has no idempotency-key contract and is not on either real message-send path. The `InMemoryStaffAttachmentStore.bindToMessage` sent guards are defensive and mirror the durable tier.
- **Pre-existing durable-database gap (out of scope):** `db/migrations/V91__staff_messaging.sql:187-200` gives `staff_attachment_link.message_id` an immediate FK to `staff_message(id)`, while `JdbcStaffAttachmentStore.putAttachment` writes the draft/conversation id for an unsent file, so a real-PostgreSQL upload bound to a draft cannot persist the link. Fixing this needs the reserved data change below. `JdbcMailStore.publish` also creates no `staff_participant` rows; the new audience query avoids that by deriving mail grants from `staff_mail_metadata.resolved_recipients`. `AUD-20261122-12` tracks the missing real-PostgreSQL HTTP test for the store; the integrator owns database and full integration tests.
- No PHI, credentials or raw bytes appear in code, fixtures, logs or notes. No new route, contract shape, suppression, exclusion, skipped test or lowered threshold was introduced.

## task-requests

```task-requests
[
  {
    "title": "Persist draft bindings for unsent staff attachments on PostgreSQL",
    "details": "V91 staff_attachment_link.message_id has an immediate FK to staff_message(id), but JdbcStaffAttachmentStore.putAttachment writes the draft/conversation id while a file is unsent, so a real-PostgreSQL upload cannot persist a pending attachment link. Add a nullable draft-binding column (or a separate tenant-owned link table) with forced RLS, a tenant policy and grants, and update putAttachment/attachmentsForDraft to resolve pending files from it. Add the real-DB HTTP upload -> scan -> send -> download test for both modes; until then real-DB end-to-end remains unproven. No migration was edited in this worker change.",
    "scope": ["db/migrations", "apps/api/src/main/java/com/aurevia/staffmsg/attachments", "apps/api/src/test/java/com/aurevia/staffmsg/attachments"],
    "depends": ["SMSG-07", "FIX-STAFF-ATTACHMENT-WIRING"],
    "reserved": "V97"
  }
]
```

## Lessons for workers

- A conditional compare-and-set belongs in the store, not in a pre-check: `UPDATE ... WHERE message_id = :draftId` plus a replay/conflict probe makes "only the winning caller audits" deterministic. Model a race with stubbed `true,false` returns instead of threads so the assertion cannot pass by scheduler luck.
- When mocking a JDBC query, invoke the captured `RowMapper` with a mocked `ResultSet` rather than returning a plain list; otherwise the mapper lambda is a missed JaCoCo line even though every visible branch passes.
- A new `*Service` class injected only through an interface is flagged as `unreferenced-service` by `check-wiring.py`; declare the bean in the owning `@Configuration`. This also moves it under the central SpotBugs `EI_EXPOSE_REP2` exclusion without a suppression.
- Mockito cannot spy a `final` class with this project's mock maker; keep a publication seam non-final when tests must observe its calls, and prove real Spring injection separately with `ApplicationContextRunner`.
- The mail send failure code for an omitted `confirmedRecipients` is 428 (`CONFIRMATION_REQUIRED`), not 412; the draft-save stale version is 412. Check the service before asserting status.
- Normalise `correlationId` when comparing two 404 bodies; otherwise concealment tests fail on a random UUID.
- Javadoc `{@link}` method references need a fully qualified prefix before `#` and must stay on one line for `test_java_conventions.py`.
- The `opencode.json is stale` failure in `test_check_pack` is the CI-profile-under-`skip-worktree` worker artifact (`git ls-files -v` → `S`); confirm once, never regenerate the protected file, and run other tooling modules individually.
- A swarm worker must not run `scripts/check.sh`: it invokes the full `mvn verify`. Run targeted `-Dtest=...` suites, one integration class when needed, and the individual policy scripts; the integrator owns the full gate.
- `scripts/check-constraints.py` fails on *any new* `@SuppressWarnings`, including a generic-cast `"unchecked"` in a test. Cast to the reifiable wildcard form (`(RowMapper<?>)`) instead of adding an annotation; existing pre-file suppressions are grandfathered but new ones are not.

## Completion

This story is complete at the worker scope. All acceptance scenarios pass, all 16 targeted Java classes plus the real-PostgreSQL audience test are green, every changed class has 100% JaCoCo coverage, the staff-web check (including `tsc --noEmit`) is green, every policy gate passes, and all three post-code reviewers answered GO. The only remaining work is the out-of-scope V97 durable linkage change filed above and the integrator's full database/integration gate on the exact merge SHA. No further action is needed from this worker.
