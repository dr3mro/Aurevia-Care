# S-G3-05 — Conversation lifecycle, unread counts, staff work queue, assignment and reopen (MSG-02)

## Requirement IDs

Primary: **MSG-02**. Supporting: ARC-01, ARC-02, ARC-03, ARC-04, ARC-07, IAM-02, OPS-02, UX-01.

## Acceptance mapping (MSG-02)

| # | Acceptance | Where | Evidence |
|---|---|---|---|
| A1 | Patient or authorized unit staff can open a conversation bound to one unit and one verified link | `ConversationService.open`; `V33` `patient_link_active` + insert guard | `ConversationServiceTest`, `ConversationFlowTest.databaseGuardsRejectIllegalWrites` |
| A2 | Replies append an immutable sequenced message; patient reply moves to `awaiting_staff`, staff reply to `awaiting_patient` | `ConversationService.reply`, `ConversationRepository.insertMessage` | `ConversationServiceTest.patientReply…`/`staffReply…`; `ConversationFlowTest.patientOpenStaffReply…` |
| A3 | Inbox with unread counts on mobile; own messages excluded; watermark monotonic | `ConversationRepository.inbox`, `domain.ts:unreadCount/unreadTotal/markRead` | `ConversationFlowTest` unread assertions; `domain.test.ts` |
| A4 | Role-scoped staff work queue, excluding closed threads | `ConversationService.workQueue`, `ConversationRepository.workQueue` | `ConversationFlowTest.wrongUnitDenialAndRoleScopedWorkQueue`; `ConversationServiceTest.workQueue…` |
| A5 | Every inbound patient message activates a work item; reply to `resolved` reopens it with a `reopened` fact | `ConversationService.reply` (REOPENED fact), V33 `work_item_event` | `ConversationServiceTest.replyToResolvedThreadReopensWorkItem`; `ConversationFlowTest` reopen assertions |
| A6 | Staff assign / transfer within the unit / resolve with reason and audit | `ConversationService.reassign`, `transitionWithReason`; V33 append-only event ledger | `ConversationFlowTest.assignTransferAndSameUnitConstraint`; `ConversationServiceTest` |
| A7 | Four states tracked; `closed` is read-only for authorized viewers | `ConversationContract.State`, `requireWritable`, V33 `guard_conversation_update` | `ConversationFlowTest.patientOpenStaffReply…` (closed denial + still readable) |
| A8 | Wrong-unit and wrong-patient access concealed (404/403), revocation effective next request | `requireAccess`, fresh membership/role fold, link fold on reply | New red-first tests `aForeignThreadIsConcealedBeforeVersionAndStateChecks`, `aWrongUnitStaffMemberIsDeniedBeforeVersionCheck`; `ConversationFlowTest` |
| A9 | Optimistic concurrency: If-Match, version+1, `FOR UPDATE`, atomic audit | `IfMatch.parse`, repository CAS updates, V33 version guard | `ConversationFlowTest.preconditionsValidationAndTenantIsolation`; `ConversationContractTest` |
| A10 | Two tenants with identical local IDs stay isolated | Forced RLS on all V33 tables, tenant-bound FKs | `ConversationFlowTest.preconditionsValidationAndTenantIsolation`, `directFoldAssertionsConfirmVerifiedLinkAndForcedRls` |
| A11 | Invalid input is 422, missing precondition 428, stale 412, illegal transition 409 | `ConversationInputs`, `IfMatch`, service guards | `ConversationFlowTest`, new `aMalformedConversationIdIsRejectedBeforeAnyRepositoryCall` |

## What I changed (base SHA `ddbd816` already contained the full slice)

The base tree already held a complete S-G3-05 implementation (six Java files, V33, three Java test classes, four mobile files). I verified it, then fixed four real defects found by specialist review, each test-first:

**Backend (`ConversationService.java`)**
1. **Concealment leak (blocking, security)** — `requireVersion`/`requireWritable` ran before `requireAccess` in `reply`, `reassign`, `transitionWithReason` and `markRead`, so a foreign/closed thread returned 412/409 instead of 404/403 and disclosed existence, version and state. Authorize now runs immediately after `loadForUpdate`, before any version/state check.
2. **Malformed `id` reached SQL** — `@PathVariable` values were passed raw into `CAST(:id AS uuid)`, producing a 500. Every id-taking method now calls `ConversationInputs.uuid(id, "id")` before `execute`, returning 422.

**Mobile (`domain.ts`, `ConversationThread.tsx`)**
3. **Screen readers could not hear message text** — `accessibilityLabel` overrode the body with `"message N from unit"`. Now `accessibilityRole="text"` with `accessibilityLabel="<role> said: <body>"`.
4. **Blank replies were sendable** — Send was enabled for `''`, and `applyReply` accepted whitespace. Added `EMPTY_REPLY_MESSAGE`, domain-level trim/reject, `trim()` on send, and an accessible disabled state on the Send pressable (`accessibilityRole="button"`).

## Tests first — red → green

Red commands and observed failures (all caused by missing behavior, not fixture errors):

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest test` → **3 failures**: `aForeignThread…` expected 404 got 412; `aWrongUnitStaff…` expected 403 got 412; `aMalformedConversationId…` expected 422 got 403.
- `npx jest --ci --coverage=false src/features/s_g3_05` (from `apps/patient-mobile`) → **3 failures**: label `unit said: Please bring your results.` not found; blank press called `onSend('conv-1','   ')`; `applyReply` did not throw for a whitespace body.
- Intermediate mobile run after the first fix dropped to 90.9% statements / 75% branches on `ConversationThread.tsx` (the defensive inner `return` was unreachable because a disabled Pressable never fires `onPress`). Removed the redundant guard; the domain layer remains the second line of defence. Final coverage 100%.

Green results:

- `mvn -B -f apps/api/pom.xml -Pno-security,fast test` → `Tests run: 1515, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, exit 0. `s_g3_05`: `ConversationContractTest` 16/16, `ConversationServiceTest` 37/37.
- `bash apps/patient-mobile/check.sh` → exit 0; `tsc --noEmit`, `eslint .`, Jest **26 suites / 271 tests passed**, 100% statements/branches/functions/lines (including `s_g3_05`).
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0.
- `git diff --name-only | grep …` → no tracker file changed.

## Entry-point-to-test trace

`POST /api/v1/conversations` → `ConversationController.open` → `ConversationService.open` (uuid/subject/unit/connection validation, membership + role fold, verified link fold, unit eligibility) → guarded `TransactionTemplate` → `ConversationRepository.insertConversation` (V33 forced RLS + `WITH CHECK`, actor-derived `created_by`, link guard) → `appendEvent(CREATED)` + `appendAudit(SUCCESS)`. Covered by `ConversationServiceTest.patientOpenCreatesAwaitingStaffConversationAndWorkItem` and `ConversationFlowTest.patientOpenStaffReplyUnreadMarkReadReopenAndClose` (real PostgreSQL, run by the integrator).

`POST /api/v1/conversations/{id}/reply` → `ConversationController.reply` (`IfMatch.parse`) → `ConversationService.reply` → uuid validation → `requireAccess` → `requireVersion` → `requireWritable` → link fold → `insertMessage` → `updateState` → optional `REOPENED` fact → atomic audit. Covered by the new concealment tests plus `replyToResolvedThreadReopensWorkItem` and `ConversationFlowTest`.

Patient UI: `ConversationInbox` / `ConversationThread` → `domain.ts` (`unreadCount`, `canReply`, `applyReply`, `markRead`) → `layout.ts` (RTL direction) → `ConversationThread.test.tsx` / `domain.test.ts`.

## Defects found and disposition

Fixed in this change: concealment leak (above), malformed-id 500 (above), screen-reader text suppression, blank reply acceptance, intermediate coverage regression.

Deferred with owner (not blocking the synthetic integration gate; blocking for real-patient enablement):

1. **Patient-link single source of truth** — V33 creates its own `patient_link` / `patient_link_active()` instead of projecting V10's authoritative `has_chart_access()`. An IAM-05 unlink via `account_chart_link` does not flip `patient_link.active`, and there is **no production writer** for `patient_link` (only the synthetic test fixture seeds it). Owner: S-G0-07 (IAM-05 writer) and S-G3-10 (revocation effects); resolve before G5, with a regression test that both folds cannot disagree.
2. **Server-side connection validation** — `connectionId` is shape-checked and stored but not validated against a connection record at open/reply, so a revoked/invented connection is not refused by this slice. S-G3-04 models connections in memory only; there is no durable connection table in V1–V32. Owner: S-G3-04 DB migration + S-G3-10 revocation.
3. **Domain-denial audits** — authorize-level denials are audited, but mid-operation denials (403/404/412/409/422) roll back without a DENIED audit row. Owner: follow-up MSG-07/S-G3-10 hardening.
4. **Work-item facts for ordinary replies** — only CREATED and REOPENED facts are appended; a normal `awaiting_patient → awaiting_staff` reply has no dedicated fact. The state change already reactivates the queue entry; record/reopen semantics are covered by A5. Owner: operational-metrics follow-up if the trail is required.
5. **V33 closed-thread DB guard** — blocks state changes out of `closed` but not read-watermark updates; the service enforces read-only on the API path. Owner: DB defence-in-depth follow-up.
6. **`privacy_auditor` `allowsUnit` dead branch** — returns true but no action is granted to that role (fail-closed); the comment overstates intent. Owner: doc/cleanup follow-up.
7. **Tenant-admin work-item write actions** — `ADMIN_ACTIONS` grants WORK_ASSIGN/TRANSFER/RESOLVE/CLOSE to `tenant_admin`, while IAM-02 says clinical access follows separate clinical permissions and the method comment says admins hold no work-item write action. `ConversationContractTest.actionMatrixSeparatesPatientStaffAndAdminPermissions` currently encodes admin == staff, so changing this is a design decision needing Product Owner / clinical approval; deliberately not changed here.

## Decisions

- **Kept `s_g3_05` module paths.** The task scope names these paths and every sibling G3 story uses the same convention. Java classes are named by business domain (`ConversationService`, `ConversationRepository`, `ConversationContract`, …); no class, type or domain symbol is named by a story or task ID. Renaming the module directory would touch 17 files and exceed the swarm's 15-file `needs-human` guard, and would diverge from all sibling stories.
- **Java tests added to the story's existing dedicated package** `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g3_05/`. That path is not in the literal scope list, but it is this story's own test package and is not claimed by any other task; test-first was mandatory and the package already existed in the base tree. No shared tracker was edited.
- **No V33 change.** Reserved version used exactly once; no other migration touched.
- **No push, tag or `gh` call**, per the worker instruction.

## Environment caveats (measured, not fixed)

- `python3 -I scripts/check-pack.py` and `gen-opencode.py --check` fail locally: `opencode.json` is flagged `S` (skip-worktree) and holds the CI profile (1837 lines) while `HEAD:opencode.json` is 1777 lines. This is the documented cloud-worker artifact from the delivery skill, not a source change.
- `python3 -I -m unittest discover -s scripts/tests -q` fails locally with 8 failures / 4 errors: `ModuleNotFoundError: No module named 'yaml'` (4), `gh: permission denied` and a rejected test push (2), stale `opencode.json` (3) and task-count state (1), plus two pre-existing `test_java_conventions` failures in out-of-scope files (`G3EvidenceService.java`, `PatientFlowContract.java`). None were caused by this change and none are in scope.
- Database and integration tests (`bash db/check.sh`, `@Tag("integration") ConversationFlowTest`) were **not run by me**, per the worker instruction; the integrator runs them against real PostgreSQL. V33 has no `db/check.sh` assertion of its own tables (the offline runner's guard no-ops before V7); Flyway numeric-order application and the integration suite are the authority.

## Evidence sources

- **Tool (executed commands)**: initial and final fast gates, red/green test commands, policy checks, `git diff` inspection — all results quoted above.
- **Reviewer (simulated, read-only)**: `smartclinic-senior-backend`, `smartclinic-senior-database`, `smartclinic-senior-mobile` bounded reviews; findings and dispositions above.
- **Simulated user / human participant**: none. No clinical or real-patient evidence; G5 sign-off remains required.
