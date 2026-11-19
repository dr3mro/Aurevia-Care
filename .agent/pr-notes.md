# S-G3-05 — Conversation lifecycle, unread counts, staff work queue, assignment and reopen (MSG-02)

Worker: `swarm-5` (resumed attempt). Base SHA `4f9bbeb`, branch `swarm/S-G3-05-114`. Synthetic fixtures only.

## Requirement IDs

Primary: **MSG-02**. Supporting: ARC-01, ARC-02, ARC-03, ARC-04, ARC-07, IAM-02, ADM-02, OPS-02, UX-01.

## Scope and paths (discrepancy recorded, not silently changed)

- Java production: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations/` — a business-domain package, as CODING_STYLE requires. The recorded repository refactor (`REFACTOR-PACKAGES-20261119-201815.md`) renamed `messaging/s_g3_05` to this package.
- Mobile production: `apps/patient-mobile/src/features/s_g3_05/` — retained from the base tree; every sibling G3 feature uses the same `s_g3_XX` directory.
- Tests: `apps/api/src/test/java/com/aurevia/smartclinic/it/messagingconversations/` (the story's pre-existing, unclaimed test package) and co-located mobile tests.
- Migration: **V33 only**, unchanged in this pass (`Change: expand`).

The untrusted story text named `s_g3_05` paths for both modules; the tracked TODO.md scope names `features/conversations` and `messaging/conversation` (singular). The base tree already had the two paths above. I created no new module path and named no Java type after a story or task ID; I did not rename the mobile directory because it matches all sibling stories and is already referenced by the existing tests. This ambiguity is recorded here for the integrator/Product Owner rather than resolved unilaterally.

## State at resume

The base commit already contained a complete S-G3-05 slice (6 Java production files, 3 Java test classes, V33, 6 mobile files) with green fast gates. I verified it, ran three bounded read-only specialist reviews, then fixed five concrete defects test-first. No shared tracker was edited.

## Fixed defects (red → green)

1. **Tenant admin had clinical work-item write authority (IAM-02, HIGH).** `ADMIN_ACTIONS` granted `WORK_ASSIGN/TRANSFER/RESOLVE/CLOSE` and `CONVERSATION_OPEN/REPLY/READ`, and `allowsUnit` returned true for every unit, contradicting the class Javadoc and IAM-02 ("clinical access follows separate clinical permissions"). Reduced to `WORK_QUEUE_READ` only, preserving ADM-02 operational oversight. Red: `ConversationContractTest.actionMatrix…` and `tenantAdminHasOnlyWorkQueueOversightPermission` (admin expected false, got true), `ConversationServiceTest.tenantAdminCannotWriteAWorkItem` (expected 403, got 404).
2. **Staff reply reopened a resolved thread without checking the relationship (MSG-02/MSG-07, MEDIUM-HIGH).** The `patientLinkActive` fold was gated on `caller.patient()`, so unit staff could reopen and send against a revoked link, while the mobile client already blocks this. Every reply now re-reads the fold before any write. Red: `staffReplyCannotReopenAResolvedThreadWhenTheVerifiedLinkIsRevoked` — "Expected ApiException to be thrown, but nothing was thrown"; added positive `staffReplyReopensAResolvedThreadWhenTheVerifiedLinkIsActive`.
3. **Existence oracle in `open` (ARC-04, MEDIUM).** A patient binding another subject got 403 after existence/link lookups, while a nonexistent patient got 404. The domain-authorization check now runs first and returns 404 without any existence lookup. Red: `openConcealsAnotherSubjectWithoutProbingPatientExistence` — verification of `never().patientExists(any())` failed.
4. **Mobile unread count used the wrong wire value (MSG-02, HIGH).** `SenderRole` was `'patient' | 'unit'` and `unreadCount` compared `'unit'`, but the server serializes unit messages as `staff` (`ConversationContract.SenderKind.STAFF`) and the V33 inbox query counts `sender_kind = 'staff'`. Real inbox data would show zero unread. Changed the type and comparison to `'staff'`, kept the patient-facing label `unit` via a new `senderLabel` helper, and updated all fixtures. Red: `domain.test.ts` "uses the server senderKind wire value" — Expected 1, Received 0.
5. **Inbox row hid the unread count from screen readers (UX-01, MEDIUM).** Child `Text` labels were grouped into the `Pressable`, so the count was not announced and the row had no button role. The unread count is now in the row's accessibility label and the row has `accessibilityRole="button"`. Red: `ConversationInbox.test.tsx` — "Unable to find an element with accessibility label: Synthetic Lab: awaiting_staff, 2 unread".

## Acceptance mapping (MSG-02)

| # | Acceptance | Where | Evidence |
|---|---|---|---|
| A1 | Patient or authorized unit staff opens a conversation bound to one verified link | `ConversationService.open`; V33 `patient_link_active` + insert guard | `ConversationServiceTest` open cases; `ConversationFlowTest.databaseGuardsRejectIllegalWrites` (integrator) |
| A2 | Replies append an immutable sequenced message; patient → `awaiting_staff`, staff → `awaiting_patient` | `ConversationService.reply`, `ConversationRepository.insertMessage` | Service tests; `ConversationFlowTest` main flow (integrator) |
| A3 | Inbox with unread counts on mobile, own messages excluded, server wire value | `ConversationRepository.inbox`, `domain.ts:unreadCount` | `domain.test.ts` (including `'staff'` wire case); `ConversationFlowTest` unread assertions (integrator) |
| A4 | Role-scoped staff work queue, excluding closed threads | `ConversationService.workQueue`, `ConversationRepository.workQueue` | `workQueueIsScopedToTheCallersUnits`, `workQueueForAdminCoversEveryUnit`; FlowTest queue case (integrator) |
| A5 | Every inbound patient message activates a work item; reply to `resolved` reopens it with a `reopened` fact | `ConversationService.reply` (REOPENED fact), V33 `work_item_event` | `replyToResolvedThreadReopensWorkItem`, `staffReplyReopens…`; FlowTest reopen (integrator) |
| A6 | Staff assign / transfer within unit / resolve with reason and audit | `reassign`, `transitionWithReason`; V33 append-only ledger | `assignAndTransfer…`, `resolveAndClose…`, FlowTest (integrator) |
| A7 | Four states; `closed` read-only for viewers | `ConversationContract.State`, `requireWritable`, V33 update guard | `stateAllowsEveryForwardTransitionButLeavingClosed`, `replyRejectsClosedThread`, FlowTest (integrator) |
| A8 | Wrong-unit / wrong-patient concealed before version/state checks; no existence oracle | `requireAccess`, `open` authorization-first, fresh membership/role fold | `aForeignThread…`, `aWrongUnitStaff…`, `openConcealsAnotherSubject…`, FlowTest (integrator) |
| A9 | Optimistic concurrency and tenant isolation with identical local IDs | `IfMatch`, CAS updates, forced RLS | FlowTest `preconditionsValidationAndTenantIsolation`, `directFoldAssertions…` (integrator) |
| A10 | Invalid input 422, missing precondition 428, stale 412, illegal transition 409 | `ConversationInputs`, `IfMatch`, service guards | `ConversationContractTest`, FlowTest (integrator) |
| A11 | Accessible unread badge and message text | `ConversationInbox`, `ConversationThread`, `senderLabel` | `ConversationInbox.test.tsx`, `ConversationThread.test.tsx` |

## Entry-point-to-test trace

`POST /api/v1/conversations` → `ConversationController.open` → `ConversationService.open` (authorization before existence lookup, then membership/role, verified link, unit eligibility) → guarded `TransactionTemplate` → `ConversationRepository.insertConversation` (V33 forced RLS + `WITH CHECK`, actor-derived author) → `appendEvent(CREATED)` + audit → 201. Covered by `ConversationServiceTest` open cases and `ConversationFlowTest` (integrator).

`POST /api/v1/conversations/{id}/reply` → `IfMatch.parse` → `ConversationService.reply` → uuid/body validation → `requireAccess` → `requireVersion` → `requireWritable` → **verified-link fold for every sender** → sequenced insert → optional `REOPENED` fact → atomic audit. Covered by the new revoked/active staff-reply tests, `replyRejectsRevokedPatientLink`, and `ConversationFlowTest` (integrator).

Patient UI: `ConversationInbox` / `ConversationThread` → `domain.ts` (`unreadCount`, `senderLabel`, `canReply`, `markRead`, `applyReply`) → `layout.ts` → co-located Jest/RTNL tests.

## Commands run and results (executed tool evidence)

Baseline before changes:
- `mvn -B -f apps/api/pom.xml -Pno-security,fast test` → `Tests run: 1642, Failures: 0, Errors: 0`, exit 0.
- `npx jest --ci --coverage=false src/features/s_g3_05` → 4 suites, 18 tests, exit 0.

Red (all failures caused by the missing behavior, not fixture errors):
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ConversationServiceTest#staffReplyCannotReopenAResolvedThreadWhenTheVerifiedLinkIsRevoked' test` → exit 1, "Expected com.aurevia.smartclinic.api.ApiException to be thrown, but nothing was thrown."
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ConversationContractTest,ConversationServiceTest' test` → exit 1, 57 tests / 6 failures + 1 error (admin matrix 2, admin service 1, open oracle 1, staff reopen 2 incl. the unused new stub).
- `npx jest --ci --coverage=false src/features/s_g3_05/domain.test.ts` → exit 1, "Expected: 1, Received: 0".
- `npx jest --ci --coverage=false src/features/s_g3_05` after the accessibility assertion change → exit 1, "Unable to find an element with accessibility label: Synthetic Lab: awaiting_staff, 2 unread".

Green:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ConversationContractTest,ConversationServiceTest' test` → exit 0.
- `mvn -B -f apps/api/pom.xml -Pno-security,fast test` → `Tests run: 1646, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, exit 0.
- `npm run typecheck` → exit 0; `npm run lint` → exit 0; `npm run test:coverage -- --ci` → 26 suites / 276 tests passed, `All files 100%` statements/branches/functions/lines, `s_g3_05 100%`, exit 0.
- `bash scripts/frontend-check.sh` → exit 0 ("frontend standards: policy and tool commands passed").
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0; `python3 -I scripts/check-docs.py` → exit 0; `python3 -I scripts/check-secrets.py` → exit 0.
- `git diff --check` → exit 0; `git diff --name-only | grep -E 'TODO.md|…'` → no tracker file changed.

## Deferred findings (with owners; not blocking synthetic integration, blocking real-patient enablement)

1. **`patient_link` is not the IAM-05 source of truth** and has no production writer; only the test fixture seeds it (V39). Owner: S-G0-07 and S-G3-10. Need a regression test that the IAM-05 unlink and this fold cannot disagree.
2. **`connectionId` is shape-checked and stored, not validated against a connection record.** Owner: S-G3-04 durable connection table + S-G3-10 revocation.
3. **Ordinary patient replies append no dedicated work-item activation fact** (only CREATED and REOPENED); the state change reactivates the queue. Owner: operational-metrics follow-up (OPS-05) if the trail is required.
4. **Mid-operation denials (403/404/409/412/422) roll back without a DENIED audit row**; only `authorize()`-level denials are committed as audits. Owner: MSG-07 / S-G3-10 hardening.
5. **`markRead` still writes a watermark on a `closed` thread** (V33 guard blocks state changes only). Owner: DB defence-in-depth follow-up; record-receipt exemption is a policy choice.
6. **ARC-07 client message ID / retry dedup is not wired**: mobile `ReplyInput.messageId` never crosses the API and V33 has no client key. Owner: X-01 / S-G3-10 (existing cross-cutting idempotency infrastructure).
7. **Localization of state labels and real-device RTL/screen-reader verification** are not in this slice. Owner: S-G5-10 (UX-01).
8. **Clinical and DevSecOps sign-off** for real-patient enablement remains a G5 gate; the reviews here are simulated and read-only.

No `BUGS.md` entry was added because the worker is instructed not to edit shared trackers; the items above are the handoff for the orchestrator.

## Evidence sources

- **Tool (executed by me):** all commands and results quoted above, plus `git diff`/`git status` inspection.
- **Reviewer (simulated, read-only):** `smartclinic-senior-backend`, `smartclinic-senior-mobile`, `smartclinic-qa-reviewer` bounded reviews; findings triaged above. The reviewers did not execute tests.
- **Simulated user / human participant:** none. No clinical or real-patient evidence; G5 sign-off is still required.

## Integrator-owned gates (not run by this worker, per instruction)

`bash db/check.sh`, `@Tag("integration") ConversationFlowTest`, Flyway V1–V33 on real PostgreSQL, JaCoCo 100% build-failing thresholds, SpotBugs, OWASP/NVD, `scripts/check.sh`, `scripts/integrator-gate.sh`, `gen-opencode.py --check` and `check-pack.py` under the CI `opencode.json` profile. **No observed database/integration evidence exists for V33 in this note** — those gates must be executed by the integrator before the story is marked `[x]`. `bash scripts/docs.sh` was attempted but this worker has no Doxygen ("Install Doxygen to build the developer reference"), so the developer-reference build is also integrator/CI evidence; the new Javadoc deliberately uses `{@code}` rather than a new `{@link}` to avoid the recorded Doxygen link failures.
