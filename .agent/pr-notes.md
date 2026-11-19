
# S-G4-02 — Therapy episode, evaluation, goals, interventions, outcomes, signed progress and discharge (PT-01)
=======

# S-G3-12 — Restore, downtime, load, accessibility and audit evidence (G3)

## Requirement IDs and acceptance mapping

Primary gate: **G3** (restore, downtime, load, accessibility and audit evidence).
Requirements evidenced: **OPS-04** (restore RPO/RTO/reconciliation), **OPS-05** (downtime runbooks, idempotent replay), **OPS-03** (load/capacity p95 + availability), **UX-01** (English/Arabic, RTL, native screen-reader, dynamic type), **OPS-02** (audit completeness and append-only chain), **ARC-03/ARC-05** (idempotency, durable events and replay). Specification: `SMARTCLINIC_V1_SPEC.md` §8 OPS-02/03/04/05, §7 ARC-03/05, §6 UX-01, §9 G3 gate row.

| G3 evidence dimension | Implementation (synthetic only) | Test evidence |
|---|---|---|
| Restore — snapshot/journal replay reconciles, RPO ≤ 15 min, RTO ≤ 4 h, no duplicate/unknown/incomplete events | `GateEvidenceEvaluator.evaluateRestore`; immutable `Snapshot`/`JournalEvent`/`RestoreResult` values | `GateEvidenceTest` (restore: 6 tests) |
| Downtime — four runbook elements present; idempotent replay has no conflicting digest, mismatched payload, duplicate, unknown or incomplete effect | `GateEvidenceEvaluator.evaluateDowntime`; `DowntimeRunbook`/`IdempotentCommand` values | `GateEvidenceTest` (downtime: 3 tests) + standalone red/green driver (below) |
| Load — pilot profile 20/100000/200/50/30, read p95 ≤ 500 ms, command p95 ≤ 900 ms, availability ≥ 99.9% | `GateEvidenceEvaluator.evaluateLoad`; `LoadProfile.PILOT`, `LatencySample`, `Availability` | `GateEvidenceTest` (load: 4 tests, incl. boundary values) |
| Accessibility — LABEL/ROLE/SCREEN_READER/RTL checks for `en` and `ar`, named owner, correct RTL; native roles/labels and RTL row mirroring in the app | `GateEvidenceEvaluator.evaluateAccessibility` (server); `evaluateAccessibility`/`evidenceSummaryLabel` + `AccessibilityEvidenceSummary` (patient app) | `GateEvidenceTest` (accessibility: 3 tests); mobile `index.test.tsx` (18 tests) |
| Audit — all OPS-02 mandatory fields on every event; chain validity folds into the verdict | `GateEvidenceEvaluator.evaluateAudit` + `EvidenceLedger.verifyChain` | `GateEvidenceTest` (audit: 2 tests + ledger chain/tamper tests) |
| Composite report — one immutable record per category, tamper-evident chained ledger, PASS only when all five records in the call pass; tenant ID validated, never inferred from fixtures | `G3EvidenceService.produce`, `EvidenceLedger`, `EvidenceHash` | `GateEvidenceTest` (service: 4 tests) + standalone red/green driver |

Entry-point-to-test trace: `G3EvidenceService.produce(organizationId, EvidenceInput)` → reject blank org (422 `INVALID_ORGANIZATION`) → fresh per-call `EvidenceLedger` → `evaluateRestore/Downtime/Load/Accessibility` → append 4 records → `evaluateAudit(input.audit(), current.verifyChain())` → append audit record → composite `GateEvidenceReport` (PASS iff all five records in this call PASS). Asserted by `GateEvidenceTest.serviceProducesAllFiveRecordsAndAValidChain`, `serviceReportsFailVerdictWhenAnyCategoryFails`, `serviceRejectsMissingOrganization`. Patient-app trace: `AccessibilityEvidenceSummary` ← `evaluateAccessibility(CONFORMING_ELEMENTS)` ← synthetic bilingual `UiElementDescriptor` fixtures → React Native Testing Library assertions on accessibility roles, labels and RTL `flexDirection`.
=======
# S-G3-11 — iOS and Android patient flows with accessibility and RTL operation

## Requirement IDs and acceptance mapping

Primary: **PAT-01, PAT-02, PAT-03, MSG-01–07, UX-01** (with **ARC-07** presentation boundary, **OPS-02** audit).
Specification: `SMARTCLINIC_V1_SPEC.md` §6 (PAT-01–03), §6.1 (MSG-01–07), §8 UX-01 / OPS-02, §7 ARC-07.

This story is the shared, cross-cutting presentation/accessibility layer consumed by the other G3
patient-flow stories (S-G3-01…S-G3-10 own the concrete appointment, report, enrollment, connection,
conversation, attachment, instruction, acknowledgment, service-hours and governance flows). Its
slice is a server-authoritative presentation policy plus the accessible, RTL-correct mobile shell:

| Acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Server authors locale, direction, platform wording, policy version, time zone and accessibility profile; app never infers them (UX-01, PAT-01–03) | `PatientFlowPresentationService.presentation`, `PatientFlowContract.FlowPresentation`, `FlowPolicyStore`; TS `createSyntheticFlowClient` + `PatientFlowHome` renders only the published policy | `PatientFlowPresentationTest.presentationIsTenantScopedLocalizedAndAudited`, `arabicIosPresentationMirrorsDirectionAndUsesIosWording`; `presentation.test.ts` (client) and `patient-flow-home.test.tsx` (renders published direction, honours server direction even when locale disagrees) |
| English/Arabic + RTL, mixed script, local name display (UX-01) | `AppLocale`→`rtl()/direction()`, `AppPlatform.settingsLabel/secureStorageLabel`, `NOTICES`/`FLOW_LABELS` bilingual tables; `rowFlexDirection`, `directionFor`, `isRtl`, `flowLabel` | `contractMapsDirectionAndGenericTextWithoutClinicalDetail`, `contractParsesWireKeysAndFailsOnUnknownValues`; `presentation.test.ts` "maps Arabic to RTL…", "localizes flow labels…"; `patient-flow-home.test.tsx` "mirrors the flow row for Arabic RTL…" |
| UTC instant with IANA zone context; reject invented zones (UX-01) | `isValidTimeZone` uses the platform authoritative `ZoneId` set; `issuedAt` is `clock.instant()`; TS `isIanaTimeZone` | `presentationRejectsInvalidLocalePlatformAndTimeZone` (incl. `Mars/Phobos`, empty, null); `presentation.test.ts` "validates IANA time zones…"; "returns a tenant-scoped, localized presentation…" asserts `issuedAt` and `timeZoneContext` |
| WCAG 2.2 AA accessible labels/roles and native screen-reader wording (UX-01, PAT-03) | `ACCESSIBILITY_PROFILE = "WCAG-2.2-AA"`; `accessibilityRole`/`accessibilityLabel` on every tile and the settings action; platform-correct secure-storage wording | assertion on `accessibility-profile` testID and `getByLabelText` for every flow/settings tile in `patient-flow-home.test.tsx`; `settingsLabel`/`secureStorageLabel` unit tests for both platforms and locales |
| Generic notifications reveal no clinical detail; full content only after auth (PAT-01, MSG-05) | fixed approved `NoticeKind.text` strings; `notificationText` re-checks active membership and the tenant-approved notice catalog | `everyNoticeKindHasApprovedTextInBothLocalesAndParsesFromWire` (no "patient" token), `notificationTextIsGenericValidatedAndTenantApproved`, `notificationTextFailsClosedWithoutMembershipOrContext`; `presentation.test.ts` generic-notice block; "does not preview a notice kind the tenant has not approved" |
| Fail closed: active membership, tenant scoping, 401/403/422 (IAM-01, ARC-02, OPS-02) | `requireContext`/`requireMembership` re-derived per request; every store call keyed by `organizationId`; `FlowAudit` appended for SUCCESS/DENIED/INVALID_INPUT | `presentationFailsClosedForMissingMembership`, `presentationRequiresAnAuthenticatedContext`, `missingPolicyVersionDefaultsToZeroAndNeverUsesAnotherTenantVersion`, `auditRecordsDenialsAndInvalidInput`; TS "fails closed for missing membership and invalid input", "does not leak the other tenant policy version" |

Synthetic fixtures only: `ORG`/`OTHER_ORG` UUIDs, subjects `patient-user`/`other-user`, fixed `Clock`
and in-memory stores. No patient data, credentials or raw logs were used or recorded.
=======

# S-G3-10 — Messaging audit, retention, legal hold, provenance and revocation effects

## Requirement IDs and acceptance mapping

Primary: **MSG-07**. Supporting: **ARC-07, OPS-02, OPS-07, PAT-02, UX-01, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-07, §7 ARC-07, §8 OPS-02/OPS-07.

| Acceptance behaviour (MSG-07) | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting, restricting conversations/files | `MessagingAuditContract.AuditAction` (9 workflow actions), `MessagingGovernanceService.recordAction` with server-derived outcome; denials appended via `deny` | `MessagingGovernanceServiceTest.recordActionAuditsEveryConversationAndFileAction`, `recordActionDeniesInactiveMembershipAndAuditsTheDenial`, `MessagingAuditDo







Test.everyAuditActionRoundTripsItsWireValue` |
| Apply configured retention and scoped expiring exports | `RetentionPolicy`, `MessagingResource`, `retentionDecision`, `applyRetention`, `createExport` (allowlisted scope, 60–86400s TTL) | `MessagingGovernanceServiceTest.retentionDecisionDistinguishesHoldWindowAndEligibleResources`, `applyRetentionSkipsHeldAndRecentResources`, `createExportBoundsScopeAndTtl`; mobile `audit.retentionEligible` |
| Legal holds suspend retention; release records actor/time/reason and preserves placement reason | `LegalHold` (+`active()`), `placeHold` (404/409), `releaseHold` (404/409), `isHeld` in retention decisions | `MessagingGovernanceServiceTest.placeHoldRequiresAnExistingResourceAndBlocksDuplicates`, `releaseHoldRecordsTheReleasingActorAndRejectsRepeats`, `releasedHoldPreservesPlacementReasonAndReasonIsAudited` |
| Preserve provenance when authorized staff link messages/instructions to the chart | `ProvenanceLink`, `linkToChart` (clinician-only, verified-patient binding), read-only `provenanceLinks` mobile projection | `MessagingGovernanceServiceTest.linkToChartPreservesProvenanceAndRequiresClinicalAuthorization`, `chartLinkRequiresValidSourceTypeAndVerifiedPatientBinding`; mobile `exposes staff-filed provenance read-only...` |
| Revocation stops new messaging and future attachment access | `recordRevocation`, `requireMessagingAllowed`, `requireAttachmentAccess` (fail closed on `revokedAt != null`) | `MessagingGovernanceServiceTest.newMessagingIsRefusedThroughARevokedConnection`, `attachmentAccessFailsClosedAfterRevocation`, `recordRevocationCapturesBlockingAndRecordAccessBasis`; mobile `blocks attachments only through a revoked connection` |
| Record prior-conversation readability under the verified record-access policy; preserve sender/recipient duties | `RecordAccessPolicy`, `RevocationEffect` (fail-closed `UNKNOWN` policy), `RetentionDuty` | `MessagingGovernanceServiceTest.missingRecordAccessPolicyFailsClosed`, `retentionDutiesPreserveSenderAndRecipientWindows`; mobile `priorConversationsReadable`, fails-closed test |
| Requests for copies follow the site record-access procedure; audit access itself | `CopyRequest`, `requestCopy`, `auditTrail` (audits its own read) | `MessagingGovernanceServiceTest.copyRequestIsRoutedToRecordAccessAndAudited`, `auditTrailReadsTenantEventsAndAuditsItsOwnRead` |
| No clinical payload in governance facts; tenant isolation; OPS-02 actor/purpose/scope/time/action/resource/correlation/outcome | `AuditEvent` has only non-sensitive fields; every store method carries `organizationId`; fresh membership+role check per request | `MessagingAuditDomainTest.auditEventCarriesOnlyNonSensitiveGovernanceFacts`, `syntheticStoreKeepsEveryGovernanceSurfaceTenantScoped`; `MessagingGovernanceServiceTest.crossTenantRecordsAreConcealed` |

Entry-point-to-test trace: `POST /api/v1/messaging-governance/actions` → `MessagingGovernanceController.recordAction` → `MessagingGovernanceService.recordAction` → `requireContext` + `authorize` (fresh membership/role via `MessagingGovernanceActions`) → `InMemoryMessagingGovernanceStore.appendAudit`. `POST .../legal-holds`, `POST .../exports`, `POST .../chart-links`, `POST .../connections/{id}/revocation-effects`, `POST .../copy-requests`, `GET .../audit`, `GET .../retention/{id}` follow the same guarded path; `MessagingGovernanceServiceTest.controllerRoutesDelegateWithoutChangingFields` traces every route to its delegate. Patient side: `createSyntheticGovernanceClient` + `MessagingAuditPanel`, exercised in `src/features/s_g3_10/index.test.tsx`.


## Entry-point-to-test trace

`POST`-style service entry `PatientFlowPresentationService.presentation(PresentationQuery)` →
`TenantContext.current()` → `requireMembership` (re-derived, fail-closed) → `AppLocale`/`AppPlatform`
parse + `isValidTimeZone` → `FlowPolicyStore.policyVersion/approvedNotices` (tenant-keyed, forced-RLS
adapter in production) → immutable `FlowPresentation` (`List.copyOf`) → `appendAudit(FlowAudit)` →
return. `PatientFlowPresentationTest` exercises every branch against a synthetic store. The app-side
mirror is `createSyntheticFlowClient(...).presentation(subject, query)` → `FlowPresentation` →
`PatientFlowHome` render; `presentation.test.ts` and `patient-flow-home.test.tsx` cover that path
including RTL mirroring and generic-notice gating.




## Defects found and fixed this cycle (TDD red → green)


Two independent read-only specialists (test-engineer and software-architect lenses) reviewed the slice. Both independently found **F1**; all three verdict-corrupting defects below were reproduced **red** against the original code with a standalone synthetic driver (`/tmp/opencode/G3FixCheckAll.java`, not committed) and **green** after the fix, then the full fast suite and 100% coverage were re-run.

- **F1 (HIGH, both reviewers) — shared mutable ledger on a singleton service.** `G3EvidenceService` is a `@Service` singleton that owned one `EvidenceLedger`; each `produce()` call appended to the previous chain and built the report/verdict from **all** entries. Second call returned 10 records and stayed FAIL after an earlier failing call, and records from a different call/organization leaked into the report (ARC-02/OPS-02). Fix: rebuild an `EvidenceLedger` per call, make `produce()` `synchronized`, expose only the most recent chain via `ledger()`, and scope the verdict to the current call. Red driver: `F1b 5 records (got 10)`, `F1c second PASS` failed; green: both pass.
- **F2 (HIGH, test-engineer) — replay effect digests never checked.** `evaluateDowntime` read only `effect.key()`; an effect with the right key but a different digest PASSED, violating ARC-03/ARC-05 "different payloads using the same key yield conflict." Fix: each effect must match both the original key **and** its payload digest, else `DIGEST_MISMATCH`. Red: `F2 wrong-digest replay FAIL` failed; green: passes.
- **F3 (MEDIUM, test-engineer) — RPO/RTO boundary truncation.** `Duration.toMinutes()` truncates, so 15 m 59 s RPO and 4 h 59 m RTO PASSED. Fix: compare full `Duration` values to the 15-min / 240-min limits. Red: `F3 15m59s RPO FAIL` failed; green: passes.
- **F4 (MEDIUM, both reviewers) — chain validity measured before append.** `evaluateAudit` received `ledger.verifyChain()` before the current records were appended, so the audit record and `chainHead` described a different chain. Fix: append the four non-audit records first, verify that chain, then build and append the audit record. No new branches; coverage preserved.
=======
Environment: Java 21.0.13, Maven 3.10.0, Node 22.23.5, npm 10.9.9, Python 3.11.2.
=======
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. 1463 tests, 0 failures/errors/skipped; s_g3_10 = 38 tests (`MessagingAuditDomainTest` 13, `MessagingGovernanceServiceTest` 25). This is the exact Java fast tier from `scripts/quick-check.sh`.
- `mvn -q -B -f apps/api/pom.xml jacoco:report` → **EXIT 0**. All 24 s_g3_10 production classes show **0 missed instructions, branches, lines and methods** (100% coverage) in `target/site/jacoco/jacoco.csv`.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → **EXIT 0** (1272 packages).
- `npx jest src/features/s_g3_10/index.test.tsx --runInBand` → **EXIT 0**, 16 tests pass.
- `npx jest --coverage --runInBand` → **EXIT 0**, 25 suites / 230 tests pass; s_g3_10 `audit.ts` and `MessagingAuditPanel.tsx` at 100% statements/branches/functions/lines (`types.ts` is type-only, 0/0, consistent with every sibling feature).
- `cd apps/patient-mobile && npm run typecheck` → **EXIT 0**; `npm run lint` → **EXIT 0**.
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0** (gaps allowed; reserved V38 not required by the checker).
- `git status` → clean before this note (base snapshot `da4fbb4`).

I did **not** run `db/check.sh`, `mvn verify`, or any real-PostgreSQL integration suite: per the worker contract those belong to the integrator. There are no s_g3_10 integration fixtures to add (this story, like S-G3-04/S-G3-07, uses a tenant-keyed in-memory adapter with unit-level acceptance).


Modified in scope: `apps/api/.../messaging/s_g3_12/G3EvidenceService.java`, `GateEvidenceEvaluator.java`. Corrected the service Javadoc's tenant-attribution overstatement (organizationId is validated nonblank, not bound to an authenticated subject — that binding belongs to the transport boundary, outside this slice).


### Findings recorded, not changed (disposition)

- **UX-01 under-coverage / empty-set semantics:** Java fails closed on no checks (`NO_CHECKS`), mobile passes an empty descriptor set (`index.test.tsx:19-21`), and neither represents staff-web WCAG 2.2 AA, per-platform native screen-reader, IANA/DST, local-name or export-unit checks. These dimensions are owned by S-G5-05/S-G5-06/S-G5-10 and S-G3-11; aligning the vocabulary and adding dimensions is a cross-story change outside this worker's scope. Recorded so S-G3-13 does not over-claim.
- **Evidence depth (OPS-02/03/04/05):** categories check field completeness and the four runbook elements but not object/IdP restore, monthly availability window, or monitoring/queue-age dimensions. Delegated to S-G5-05/S-G5-06 and recorded as evidence gaps for the G3 gate matrix, not silent PASS.
- **Ledger canonicalization (LOW):** `canonical()` omits sequence and concatenates with an unescaped `|`. Renumbering is already detected by the positional sequence check in `EvidenceLedger.verify`; detail strings are internally generated. Accepted risk; hardening would change hash preimages across the slice.
- **Value validation (LOW):** audit `outcome` accepts any nonblank value; blank command keys/digests and `successes > total` are not rejected. Synthetic fixtures are trusted here; fail-closed hardening is a follow-up.

## Commands run and results (evidence source: executed tool commands and standalone driver)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=GateEvidenceTest test` → EXIT 0; **27 tests, 0 failures/errors/skipped**.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (exact integrator fast command) → EXIT 0; Surefire aggregate **1463 tests, 0 failures/errors/skipped** across 134 suites (run before and after the fixes).
- JaCoCo report on the s_g3_12 slice → **INSTRUCTION 1542/1542, BRANCH 158/158, LINE 279/279, METHOD 50/50 (100% each)** after the fixes.
- Standalone red/green driver (synthetic): original code → `TOTAL_FAILURES=4`, exit 1 (`F1b`, `F1c`, `F2`, `F3` failed); fixed code → `TOTAL_FAILURES=0`, exit 0. Driver lives in `/tmp/opencode/` and is **not** part of this commit.
- `npm ci --no-audit --no-fund` (patient-mobile) → EXIT 0 (1272 packages). `npx jest src/features/s_g3_12 --ci` → EXIT 0, **18 tests**. `npm run test:coverage -- --ci` → EXIT 0, **25 suites, 230 tests**, 100% global branches/functions/lines/statements; `s_g3_12` executable files at 100%. `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0.
- `python3 -I scripts/check-docs.py` → EXIT 0; `python3 -I scripts/check-secrets.py` → EXIT 0; `python3 -I scripts/project-name.py displayName|repositorySlug` → `Aurevia Care` / `aurevia-care`.

Database/integration gates (`db/check.sh`, `mvn verify`, JaCoCo build-failing check, real-PostgreSQL isolation) were **not** run by this worker per the contract; the integrator runs them. Reserved version **V40** is intentionally unused: this is pure in-memory deterministic evidence evaluation with no schema change, and `db/migrations` is outside the declared scope.

## Decisions / notes for the integrator

- This is attempt 3; the slice was already present in the base commit. This worker's contribution is verification plus the three verdict-corrupting fixes above. No fabricated "red first" claim: red is demonstrated by the standalone driver reproduced against the unmodified base, not by an added test file.
- The Java test file (`apps/api/src/test/.../s_g3_12/GateEvidenceTest.java`) is outside this worker's editable scope, so dedicated regression tests for F1–F4 were **not** added. The fixes preserve all 27 existing tests and 100% coverage. **Recommended follow-up (integrator or next story):** add a `produce()`-twice assertion (5 records + PASS), a wrong-digest effect case, and a 15m59s RPO case to `GateEvidenceTest.java`. These are uncommitted recommendations only.
- All fixtures are synthetic; no patient data or credentials used or recorded. No human or clinical evidence; all evidence is tool-generated (executed commands, standalone driver) plus read-only specialist review.
- Scope respected: only the two declared paths were worked in. No shared tracker, `docs/swarm`, migration, workflow or root-level file was edited. No push, tag or `gh` call was made.
=======
No in-scope production or test defect was found. The complete, green implementation was already present in the base snapshot at `da4fbb4`; running the fast unit tiers and a full JaCoCo/TS coverage review confirmed it is correct and fully covered. No in-scope source change was necessary.

Non-blocking observation for the integrator (not a spec violation, no change made): `linkToChart` verifies a verified connection exists for the patient in the active tenant, but the synthetic store has no message-to-patient map, so it cannot additionally prove that `messageId`/`conversationId` belongs to that patient. Ownership of message↔patient binding belongs to the conversation/instruction services that call this governance guard (MSG-01/ARC-07); filing is a clinician-only action. Left unchanged to avoid out-of-scope store/interface churn.

## Decisions / notes for the integrator

- No production or test source was changed by this worker; in-scope code was already complete and green. This branch's new commit updates only this ignored-then-tracked `.agent/pr-notes.md` (the PR description), matching the established worker precedent for an already-complete story.
- Reserved migration version **V38** is intentionally unused: this story's scope is the in-memory governance adapter plus patient-mobile feature, and `db/migrations` is outside the declared scope. Migration contiguity passes without it.
- Scope respected: only `apps/api/.../messaging/s_g3_10` and `apps/patient-mobile/src/features/s_g3_10` were reviewed; no shared tracker, no other migration version, no credentials or patient data touched.
- Coverage (JaCoCo 100% line/branch/method/instruction/class and mobile 100% statements/branches/functions/lines) confirmed from locally executed reports; no fabricated or pending-as-covered results.
- Evidence source: executed tool commands only. Synthetic fixtures throughout. No simulated reviewer, simulated user, human or clinical evidence was used for this story; real-patient approval remains a G5 gate.


# S-G3-08 — Publication, dispatch, first view and revision-bound acknowledgment (MSG-05)

## Requirement IDs and acceptance mapping

Primary: **MSG-05**. Supporting: **MSG-04** (revision/withdrawal context), **MSG-01** (server-side patient/destination/context validation), **ARC-01, ARC-03, ARC-04, ARC-05, ARC-07, OPS-02, UX-01, PAT-01/02**. Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-05, §7 ARC-01..07, §8 OPS-02, §1 pilot metrics.

| Acceptance behaviour | Implementation | Synthetic test evidence |
|---|---|---|
| Publication, notification dispatch, first in-app view and explicit acknowledgment are four separate append-only facts | `InstructionFacts.FactType` (5 kinds incl. follow-up), `Publication`/`Dispatch`/`FirstView`/`Acknowledgment` records; `InstructionFactService.publish/retryDispatch/markFirstView/acknowledge` | `InstructionFactsTest`, `InstructionFactServiceTest.timelineShowsTheFourFactsAsSeparateEntries`, `facts.test.ts` (four independent slots) |
| Acknowledgment binds to one exact revision and is receipt-only | `AcknowledgmentScope.RECEIPT`; stale revision → 409 `STALE_REVISION`; `acknowledgeRevisionBound` returns `REVISION_MISMATCH`/`SUPERSEDED` | `InstructionFactServiceTest.acknowledgmentBindsToTheExactPublishedRevision`, `facts.test.ts` revision-bound cases |
| Generic push notification carries no clinical detail; content only after authentication | `GENERIC_NOTIFICATION_KEY=NEW_INSTRUCTION`, `Dispatch` has no contentRef; `buildGenericNotification()` fixed text; client re-reads credential for every protected command | `InstructionFactServiceTest.notificationDispatchIsAGenericSeparateFact`, `notification.test.ts`, `client.test.ts` auth tests |
| Track overdue requested acknowledgments and route to owning unit | `overdueCandidates`, `overdueFollowUps`, `FollowUp` (ROUTED/RESOLVED), `overdueInstructions`/`owningUnitForOverdue` on mobile | `InstructionFactServiceTest.overdueRequestedAcknowledgmentRoutesToTheOwningUnit`, `facts.test.ts` overdue routing |
| Immutable revision; idempotency; tenant isolation; fail-closed authorization/audit | `putInstruction` now `putIfAbsent` + `REVISION_CONFLICT`; monotonic revision; atomic key reservation; org-prefixed keys; per-request membership/role/connection re-check; audit on success and denial | `InstructionFactServiceTest` (11 new cases), `InMemoryInstructionFactStoreTest`, `InstructionFactsTest` |

Entry-point-to-test trace: `POST /api/v1/instructions/publish` → `InstructionFactController.publish` → `InstructionFactService.publish` → `recordedClientDigest` → membership/role/unit/patient-context/monotonic validation → `reserveClientDigest` → `putInstruction` (immutable) → `putPublication` (+ optional generic `Dispatch`) → `appendAudit` → 201. `POST .../acknowledge` → `acknowledge` → published-instruction + verified-chart + active-connection + receipt-requested + current-revision checks → atomic key reserve → `putAcknowledgmentIfAbsent` → audit. `GET .../facts` → `facts` → tenant scoped timeline. `GET .../overdue-follow-ups` → scan + follow-up routing. Verified by `InstructionFactControllerTest` (unit), `InstructionFactServiceTest` (domain) and the mobile `client.test.ts`/`facts.test.ts`; the real-PostgreSQL isolation suite is the integrator's gate.


# S-G3-01 — Patient appointment list, confirmation, generic push notifications and device revocation (PAT-01)

## Requirement IDs and acceptance mapping

Primary: **PAT-01**. Supporting: **ARC-01, ARC-02, ARC-05, OPS-02, IAM-05, UX-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §6 PAT-01, §7 ARC-01/02/05, §8 OPS-02, §2 IAM-05, §9 G3 gate evidence ("generic notifications reveal no clinical detail").

| PAT-01 acceptance behaviour | Implementation | Test evidence (synthetic only) |
|---|---|---|
| List appointments with facility time zone and status | `PatientAppointmentService.listAppointments` → `PatientAppointmentStore.forPatient`; mobile `appointmentView`/`sortByScheduled` | `PatientAppointmentServiceTest` (7 list cases); `appointments.test.ts`; `AppointmentListScreen.test.tsx` |
| Confirmation only where enabled; server-recorded timestamp; version precondition | `PatientAppointment.confirmable()`, `confirm()` version check + atomic `store.confirm`, `confirmed(at, v)`; mobile `deriveConfirmation`/`confirm` | `PatientAppointmentServiceTest` (9 confirm cases); `PatientAppointmentTest`; `confirmation.test.ts` (incl. new authoritative-flag regression) |
| Status-change push with generic text only | `notifyStatusChange` → `dispatch` → `GenericPushNotice.appointmentUpdate()` (fixed title/body/deep link, factory accepts no detail); mobile `GENERIC_NOTICE` | `GenericPushNoticeTest`; `notifications.test.ts`; service dispatch tests |
| Detail retrieved only after authentication | mobile `detailAfterAuth` gates fetcher invocation on `AuthState.authenticated`; service re-checks verified link on every command | `notifications.test.ts`; `unlinkedSubjectIsDeniedBeforeAnyRead` etc. |
| Log invalid device tokens (without provider token) | `recordInvalidToken` + dispatch INVALID_TOKEN branch → `appendInvalidTokenLog(tokenId, …)`; mobile `invalidateToken` returns event with no token value | `invalidTokenResponseIsLoggedAndTokenMarkedInvalid`, `confirmInvalidatesAndLogsARejectedToken`; `devices.test.ts` |
| Support device revocation that stops future pushes | `revokeDevice` idempotent + concealed foreign token; `DeviceState.deliverable()` re-checked at dispatch; **new** mobile `withRevokedDevice`/`withInvalidatedDevice` persist state so `eligibleTokens` excludes them | `revoke*` service tests, `dispatchSkipsANonDeliverableTokenReturnedByTheStore`; new `devices.test.ts` persistence cases |
| Verified-patient / tenant scoping | Every service command calls `authorize()` (`links.allows(subject, patientId)`) before any store call; mobile `tenantKey` NUL separator, **new** `eligibleTokens` patient filter | 403/404 denial tests; `tenancy.test.ts`; new two-patient `devices.test.ts` case |

Entry-point-to-test trace: `POST confirm` (service boundary) → `PatientAppointmentService.confirm` → `authorize` (verified link) → `store.find` (404 conceal / `confirmable` / `If-Match`) → `store.confirm` (atomic version precondition) → `dispatch` → `GenericPushNotice.appointmentUpdate()` → `PushGateway.send` (INVALID_TOKEN → `save(invalidate())` + `appendInvalidTokenLog`) → `AuditSink.append` → confirmed projection. Mobile: `AppointmentListScreen` → `appointmentView` (tenant key + zone) → `deriveConfirmation` (site **and** per-appointment flag) → `confirm` (authenticated, timestamp idempotency) → `detailAfterAuth`; device list → `eligibleTokens(devices, org, patientId)` → `withRevokedDevice`/`withInvalidatedDevice`.

## What this worker changed (attempt 2)

The base branch already contained a complete, green S-G3-01 implementation. Three genuine PAT-01 gaps were found by simulated review and fixed **test-first** (red → green), all inside `apps/patient-mobile/src/features/s_g3_01`:

1. **`eligibleTokens` had no patient dimension** (`devices.ts`). It selected every active device in the tenant, so a push for patient A could target patient B's device and the list could expose another patient's revoke control. Added a required `patientId` parameter and a `token.patientId === patientId` filter. Added a two-patient-in-one-org regression test.
2. **`deriveConfirmation` ignored the authoritative per-appointment `confirmationEnabled` flag** (`confirmation.ts`). A caller passing `enabled = true` for a server-marked non-confirmable visit produced a Confirm affordance. Both flags are now required (stricter wins); added the regression test.
3. **Revocation/invalidation had no persistence helper** (`devices.ts`). `revokeToken`/`invalidateToken` returned a new token but nothing wrote it back, so a wiring that used them alone left the device `active`. Added tenant-keyed `withRevokedDevice`/`withInvalidatedDevice` (no-op for a missing/foreign key) and tests proving a stored revocation/invalidation removes the device from `eligibleTokens` while the source registry is unchanged.

## Red → green evidence (tool-generated)
 main

- **RED** `npx jest src/features/s_g3_01 --silent` → **EXIT 1**, 5 failures / 25 pass: patient-scope assertion failure plus `TypeError: withRevokedDevice/withInvalidatedDevice is not a function`; and `deriveConfirmation({confirmationEnabled:false}, true)` wrongly returned `pending`.
- **RED** `npm run typecheck` → **EXIT 2**: `TS2305` missing exports, `TS2554` expected 2 arguments got 3. (Each failure is caused by the missing behaviour, not an unrelated error.)
- **GREEN** `npx jest --coverage --collectCoverageFrom='src/features/s_g3_01/*.ts' ... src/features/s_g3_01` → **EXIT 0**, 30/30 tests, **100% statements/branches/functions/lines** for every s_g3_01 source file.
- **GREEN** `npm run typecheck`, `npm run lint` → EXIT 0.

swarm/S-G3-08-98
- `cd apps/api && mvn -q -B -Pno-security,fast -Dtest='com.aurevia.smartclinic.messaging.s_g3_08.*Test' test` → **EXIT 0**; Surefire: **73 tests, 0 failures, 0 errors** (Service 43, Store 12, Facts 11, Controller 7).
- `cd apps/api && mvn -q -B -Pno-security,fast test` → **EXIT 0**; full fast tier **1476 tests, 0 failures, 0 errors, 0 skipped**.
- `cd apps/api && mvn -q -B jacoco:report` → EXIT 0; per-class report: `InstructionFactService` INSTRUCTION 1274/1274, BRANCH 76/76, LINE 198/198, METHOD 33/33, CLASS 1/1; `InMemoryInstructionFactStore` 869/869 instructions, 80/80 branches, 55/55 methods; every other class in the package 100% (interfaces have no code). No fabricated coverage; full-project JaCoCo gate belongs to the integrator's `mvn verify`.
- `cd apps/patient-mobile && npx jest src/features/s_g3_08 --runInBand --coverage --collectCoverageFrom='src/features/s_g3_08/**/*.{ts,tsx}' --coverageThreshold='{"global":{"branches":100,"functions":100,"lines":100,"statements":100}}'` → **EXIT 0**; **42 tests, 4 suites**; 100% statements/branches/functions/lines for every logic file (`types.ts` is type-only).
- `cd apps/patient-mobile && npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0 after fixing one `Array<T>` warning; `npm test -- --runInBand` → EXIT 0, **241 tests, 25 suites**.
- `bash scripts/frontend-check.sh` → **EXIT 0** (`frontend standards: policy and tool commands passed`, includes both modules + root typecheck/lint/prettier/tests).
- `npx prettier --check apps/patient-mobile/src/features/s_g3_08/*.ts *.tsx` → EXIT 0.
- RED evidence before implementation: Java `InstructionFactServiceTest` 2 failures (immutable revision, stale revision), then 7 failures + 1 error with the second batch (due date, patient unit, digest, key burn, replay 500, orphan key, two overdue cases). Mobile Jest 11 failures across 3 suites (per-revision first view, re-acknowledgment, status, instant compare, replay, screen revision/revision-mismatch/RTL-adjacent behavior, auth scoping, tenant filtering). All were caused by the missing behavior described above, then turned green by the scoped changes.

I did **not** run `db/check.sh`, `mvn verify`, the real-PostgreSQL isolation suite or any `gh`/push/tag command: those belong to the integrator/branch policy for this worker.

## Defects found and fixed (in scope)

1. **Published revision was mutable** — `putInstruction` overwrote an existing `(org,id,revision)`; now `putIfAbsent` and a `REVISION_CONFLICT` guard so an acknowledgment can never bind to silently changed content (ARC-01/04, MSG-05).
2. **Stale revision could be published after a newer one** — rejected with 409 `STALE_REVISION`.
3. **Idempotency key was burned by a later business denial** and a replay of a denied command threw `NoSuchElementException` (HTTP 500) — keys are now read-then-atomically-reserved after validation; committed-fact lookups fail closed with 409 `CONFLICT`.
4. **Non-atomic check-then-act on keys** allowed duplicate dispatch under concurrency — reservation is one atomic `putIfAbsent`; a lost race fails closed with 409 `CONFLICT` (tested with a synthetic racing store).
5. **Publish digest omitted patient/unit** — different payloads under the same key returned the original success; digest now includes both (ARC-03).
6. **Patient↔unit relationship was not checked on publish** — added active-connection validation (MSG-01 defense in depth).
7. **Acknowledgment requested with no due instant silently disabled overdue routing** — now 422 `ACKNOWLEDGMENT_DUE_DATE_REQUIRED`.
8. **Superseded revisions generated un-clearable overdue follow-ups** — excluded from detection and from the open queue.
9. **Mobile first view was per instruction, not per revision** — a corrected revision could neither record a first view nor be displayed correctly; now keyed by `(instructionId, revision)` end to end, with a new-revision screen test.
10. **Mobile receipt slot blocked a new requested revision** and status treated any old receipt as terminal — both are revision-aware now.
11. **Mobile `overdueInstructions`/`owningUnitFor` skipped auth and returned cross-tenant rows** — both now require the credential and filter by active organization and verified chart.
12. **Mobile screen offered an acknowledge button the domain always rejected** when `request.revision !== revision` — button is now withheld.
13. **Mobile deadline comparison was lexicographic** — uses `Date.parse` so an offset timestamp cannot hide an overdue request; `reduceFact` no longer lets a replay overwrite an earlier first view or receipt.

No patient data, credentials or raw logs are recorded here; all identifiers in code/tests are synthetic.

## Deferred findings (not fixed here — decision/owner needed)

These came from four read-only specialist reviews (simulated, not human clinical evidence) and are outside the declared scope or need a separate story:

- **Withdrawal state is owned by S-G3-07/MSG-04.** This slice's synthetic `Instruction` has no state field, so a direct API call cannot observe a MSG-04 withdrawal; only the mobile projection checks `withdrawn`. A cross-store/cross-module state check needs Architect + clinical-safety sign-off before G5 launch. Flagged as **launch-relevant** by the physician reviewer.
- **Durable storage / outbox / worker.** The in-memory adapter, local mobile fact client and lazy follow-up scan follow the pattern of sibling messaging slices (S-G3-01…07). V36 was reserved but **not created**: scope contains only the two feature paths, no `db/migrations`, and every sibling messaging story uses the synthetic adapter. A separate cross-boundary story must add tenant-keyed tables, RLS/uniqueness, atomic outbox, a scheduled overdue worker and real client API wiring. Architect/PO decision requested at integration; no durable evidence is claimed here.
- Follow-up state is stored as one overwritten row rather than an append-only fold; timeline reads are audited as `FOLLOW_UP_LIST` and a unit-unassigned tenant admin gets 404; ARC-03/04 headers (`Idempotency-Key`, `If-Match`, 412 for stale) are not implemented on these commands. Each needs its own bounded story.
- Arabic strings/localization remain with S-G3-11/S-G5-10 (UX-01); RTL direction is implemented and tested here.

## Evidence classification and directives

- Tool evidence: all command outputs and counts above, executed locally on this worker branch.
- Reviewer evidence: bounded findings from `smartclinic-senior-backend`, `smartclinic-senior-mobile`, `smartclinic-software-architect` and `smartclinic-physician-reviewer` (simulated reviewers, no edits by them).
- Simulated-user/human evidence: none. No clinician or patient participated; real-patient approval remains a G5 gate.
- Branch `swarm/S-G3-08-98`; no push, tag, `gh`, tracker or docs edit was performed by this worker. Reserved migration V36 was not used.
=======
## All commands run and exact results (evidence source: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**; Surefire 1463 tests, 0 failures/errors/skipped; 42 in `messaging.s_g3_01` (AppointmentStatusTest 3, DeviceTokenTest 2, GenericPushNoticeTest 4, PatientAppointmentServiceTest 28, PatientAppointmentTest 5). Java sources unchanged by this worker.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → EXIT 0; `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0; `npm test -- --silent` → EXIT 0, **25 suites / 235 tests passed**.
- `bash scripts/frontend-check.sh` → **EXIT 0** (`frontend standards: policy and tool commands passed`; includes both modules plus root typecheck/lint/prettier/vitest).
- `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0 (gaps allowed by decision 0002; reserved V29 not needed for this in-memory slice).
- `git diff --check` → clean; `git diff --name-only` → only the four in-scope mobile files.

I did **not** run `db/check.sh`, `mvn verify`, JaCoCo, OWASP/SpotBugs or any `@Tag("integration")` suite — the worker contract assigns database/integration gates to the integrator. No coverage result was fabricated.

## Defects found

**Fixed in this change (in scope, simulated-review findings, tool-verified red/green):** the three items above. Evidence source for discovery: simulated Senior React Native reviewer; red/green proof from executed commands.

**Reported, NOT changed — Java production/test findings (Java test directory is outside this worker's declared scope, so no test-first Java change was made):**
- `PatientAppointmentService.java:93-99` — `confirm()` commits, dispatches and audits SUCCESS, then re-reads for the response; if that post-commit `find` returns empty it throws 404 after a successful, notified action (test `confirmConcealsAnAppointmentThatDisappearsBeforeTheResponse` codifies this). Recommended: build the response from `current.confirmed(at, expectedVersion + 1)` and drop the re-read. **Blocker for the next attempt** — needs a Java regression test.
- `recordInvalidToken(..., String reason, ...)` writes caller-supplied text verbatim to the invalid-token log (`:177`); the dispatch path is safe (hardcoded `"INVALID_TOKEN"`). Recommend an allow-list / bounded code plus a hostile-input test.
- `Instant at` is trusted from the caller, not an injected `Clock`, though the Javadoc claims a server timestamp. Recommend injecting `java.time.Clock`.
- `PatientAppointmentServiceTest:209,258` use `any(GenericPushNotice.class)`; they do not assert the emitted notice equals the fixed generic template. A regression that puts clinical detail in the push would still be green at 100% coverage. Recommend an `ArgumentCaptor` assertion plus `reveals(...)` checks.

**Reported, NOT changed — remaining mobile hardening (lower priority / other stories):**
- Screen callbacks pass bare local ids while `appointmentView` computes a tenant key; after an active-org switch a stale id could be re-scoped elsewhere. Consider passing the tenant-scoped key to `onConfirm`/`onOpenDetails`.
- `AuthState` is a bare boolean; "authenticated but link since revoked" is not exercised client-side (server re-check remains the compensating control, IAM-05).
- `AppointmentListScreen` has no `accessibilityLabel`/`accessibilityRole` and hard-coded English strings (UX-01; owned by S-G3-11).
- Java slice has no controller/DB adapter or HTTP reachability — explicitly declared a follow-up in `PatientAppointmentService` Javadoc; reserved V29 unused, consistent with sibling messaging stories (s_g3_02/03/04 also use in-memory stores).

## Decisions / notes for the integrator

- Scope honoured: only `apps/patient-mobile/src/features/s_g3_01` was edited. Reserved migration **V29 was not used** — no persistence layer exists in this slice.
- Do **not** edit TODO.md/MEMORY.md/WORKSPACE.md/BUGS.md/PROJECT.md — this worker did not.
- Confirm on the merge result: patient-mobile jest 235 tests, typecheck/lint, `scripts/frontend-check.sh`, Java fast tier, JaCoCo 100%, and the real-PostgreSQL gates. Add a BUGS.md entry for the Java F1 blocker above if accepted.
- Synthetic fixtures only (`patient-1`, `patient-2`, `org-a`, `dev-1`, `synthetic-token`). No patient data or credentials used or recorded. Evidence is tool-generated for commands/red-green and simulated for reviewer findings; no clinician/patient/human evidence.
- The worktree arrived with a stale `.agent/pr-notes.md` from S-G1-07 (recycled worker image); it was replaced with this file. The file is tracked in this repository and was committed with the scoped change (commit `5500a70`).
=======

# S-G2-07 — Trend only comparable observations with units, ranges, time and qualifiers visible

Primary requirement: **XCH-07**. Supporting: **ADM-01, ARC-01, ARC-02, ARC-03, OPS-02, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-07, §7 ARC-01..03, §8 OPS-02.
Story scope: `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_07`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_07`. Reserved migration: **V25** only.

## Acceptance mapping (XCH-07)

| Requirement text | Implementation | Synthetic test evidence |
|---|---|---|
| Trend numeric observations only when test identity, units and clinically relevant comparability are established | `TrendAnalyzer.classify` rejects non-matching test, non-matching comparability group, non-final revision, qualified numeric, qualitative and non-canonical unit; `ComparabilityRule` + effective-dated `current_trend_comparability` fold (V25) define the rule; `TrendActionPolicy`/`TrendService` enforce role separation | `TrendAnalyzerTest` (17), `TrendServiceTest` (17), `TrendControllerTest` (3), `TrendRepositoryTest` (8), `TrendMigrationContractTest` (1); `TrendingTest` (6 integration, integrator) |
| Display source unit, reference range, time and interpretation | `TrendPoint` carries `unit`, `referenceLow/High/Unit`, `effectiveAt`, `interpretation`, `sourceSystem/Version`; `Observation` validates reference-range unit equals source unit and inverted ranges are rejected | `TrendAnalyzerTest.eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance`, `observationValidationRejectsMissingIdentityValueTimeAndBadRanges`; `TrendRepositoryTest.observationMapperReadsEveryDisplayedColumn` |
| Keep less-than / greater-than, qualitative and incompatible-unit results visible, never as comparable measurements | `ExcludedObservation` retains the whole `Observation` unmodified with a closed `ExclusionReason`; `TrendView.points() + excluded()` keeps every observation observable (nothing silently dropped) | `TrendAnalyzerTest.qualifiedNumericResultsAreVisibleButNotTrended`, `qualitativeResultsAreVisibleButNotTrended`, `incompatibleUnitsAreVisibleButNotTrended`, `exclusionsAreAlsoOrderedByEffectiveTimeThenId`, `viewCopiesItsListsDefensively`; `TrendingTest` asserts 3 points + 6 exclusions from 9 observations |
| Tenant isolation, fail-closed authorization and audit | Forced RLS on both V25 tables with `(organization_id, id)` composite keys, append-only triggers, actor-forced `created_by`/`approved_by`, tenant+test advisory lock for version allocation; `TrendService.authorize` re-derives membership and roles each request and audits denials | `TrendingTest.tenantsStayIsolatedAndRolesAreEnforced` (two tenants, identical local codes/MRN, wrong-unit/wrong-role denial, revocation on next request); `TrendServiceTest` denial/audit branches |

Entry-point-to-test trace: `POST /api/v1/trends/comparability` → `TrendController.define` → `TrendService.defineComparability` → guarded `TransactionTemplate` → `TrendRepository.nextComparabilityVersion` (V25 advisory lock) + `insertComparability` (forced actor) → `COMPARABILITY_DEFINE` audit → 201. `POST /api/v1/trends/observations` → `TrendController.record` → `TrendService.recordObservation` → guarded transaction → `TrendRepository.insertObservation` (forced RLS + actor) → `OBSERVATION_RECORD` audit → 201. `GET /api/v1/trends/{patientId}?testCode=…` → `TrendController.trend` → `TrendService.readTrend` → guarded transaction → `TrendRepository.findComparability`/`observationsForPatient` → `TrendAnalyzer.analyze` → `TREND_READ` audit → 200 with points + exclusions.

## Defect fixed in this pass (test-first, RED → GREEN)

**F1 — effective-dated fold ordered by version instead of effective date (reviewer SR-BACKEND, Medium correctness).** `defineComparability` accepts an arbitrary `effectiveFrom` on every version but V25's `current_trend_comparability` used `ORDER BY org, test_code, version DESC`. A backdated higher version would displace the rule actually in force, applying the wrong canonical unit and hiding correct-unit results as `INCOMPATIBLE_UNIT`.

- **RED:** added `TrendMigrationContractTest.effectiveComparabilityOrdersByEffectiveDateBeforeVersion`; `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=TrendMigrationContractTest test` → EXIT 1, 1 failure (`order by c.organization_id, c.test_code, c.version desc`).
- **GREEN:** changed V25 fold to `ORDER BY c.organization_id, c.test_code, c.effective_from DESC, c.version DESC` (version only breaks an effective-date tie); same command → EXIT 0, 1 pass.
- **Behavioural coverage for the integrator:** added `TrendingTest.aBackdatedHigherVersionDoesNotOverrideTheLaterEffectiveRule` — defines v1 (2026-01-01, mmol/mol) then a backdated v2 (2025-01-01, %) and asserts `ruleVersion` stays v1, only the mmol/mol observation trends, and the % result stays visible as `INCOMPATIBLE_UNIT`. This exercises the real PostgreSQL fold; I could not run it locally (no PostgreSQL server/client in this worker image — connection refused on 127.0.0.1:5432).

## Commands run and results (evidence: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **1464 tests, 0 failures, 0 errors, 0 skipped** (exact Java command in `scripts/quick-check.sh`). In scope: `TrendAnalyzerTest` 17, `TrendControllerTest` 3, `TrendMigrationContractTest` 1, `TrendRepositoryTest` 8, `TrendServiceTest` 17. `TrendingTest` is `@Tag("integration")` and correctly excluded from this DB-less tier; its class compiled successfully.
- `mvn ... -Pno-security -Dtest=TrendingTest test` (probe) → EXIT 1, `Connection refused` at 127.0.0.1:5432 — **no PostgreSQL in this worker image**. Database/integration verification is delegated to the integrator as instructed; no result was fabricated.
- `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0 (V25 `Change: expand`, version sequence and format valid after the fold fix).
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0.
- `git status --short` clean at the base commit `da4fbb4` before this change.

## Findings not changed (with rationale)

- **F2 — corrected/entered-in-error revisions have no lineage (reviewer SR-BACKEND, Medium).** `corrected` is classified `NON_FINAL_REVISION` and excluded, but V25 has no `supersedes_id`, so a superseded `final` can stay plotted while its correction is hidden. Cross-story dependency on **XCH-04 / S-G2-04** and **LAB-02 / S-G2-09** (correction versions, reconciliation). Adding lineage here would exceed the declared scope and duplicate another story's model; recorded for the Product Owner / architect rather than silently changing clinical semantics. The rest of XCH-07 (comparability gates, display fields, visible exclusions) is unaffected.
- Residual risks noted by review: a single current rule is applied to all historical observations; unit comparison is exact string equality; `tenant_admin` self-approves comparability policy (clinical approval is a G5 site decision); no pagination on `observationsForPatient` (ARC-03 pagination is X-01); the `DUPLICATE_OBSERVATION` 409 catches any `DataIntegrityViolationException` (V25 has no source-revision unique constraint; cross-event dedup is ARC-05 / X-02). None blocks XCH-07's stated acceptance.
- Pre-existing, out-of-scope: `test_java_conventions.py` reports 1 failure for `messaging/s_g3_11/PatientFlowContract.java` (S-G3-11, committed at base). Not modified.
- I did **not** run `db/check.sh`, `mvn verify`, OWASP, SpotBugs or JaCoCo — per the worker contract these belong to the integrator. No coverage result was fabricated.
- Synthetic fixtures only. No patient data, credentials or raw logs recorded. Evidence sources: executed tool commands; one independent simulated senior-backend review; no human or clinical evidence. Real-patient approval remains a G5 gate.



# S-G3-06 — PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access


## Story and requirements

- Story: S-G4-02, branch `swarm/S-G4-02-99`, base SHA `40e1668`. Resumption of a prior attempt (TODO `attempts: 2`); the domain implementation was already present in the base commit. This pass verifies it, closes two integrator-blocking defects, and records evidence.
- Primary requirement: **PT-01** (therapy episode: evaluation, problems, baseline measurements, goals, planned frequency/duration, interventions, outcomes, versioned scales with units, signed session/progress/discharge notes, approved summary to referrer, return acknowledgment).
- Gate evidence: **G4** — "PT episode produces signed discharge and referral closure".
- Supporting requirements exercised: IAM-01 (fresh actor snapshot, server-side authorization on every command), ARC-01 (tenant-owned opaque IDs, versions, provenance), ARC-02 (tenant-scoped store), ARC-03 (version precondition → 412, machine-readable errors), OPS-02 (append-only audit facts with actor, action, resource, outcome).
- Scope honored: only `apps/api/src/main/java/com/aurevia/smartclinic/g4/s_g4_02` and `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g4_02`. No shared tracker, workflow, script, migration or out-of-scope source was edited. Reserved migration **V42** was not needed: this slice is a tenant-scoped domain service over an in-memory store (no schema change); `scripts/check-migrations.py` permits version gaps.

## Acceptance mapping (requirement → code → evidence)

| PT-01 behavior | Production entry point | Test evidence |
|---|---|---|
| Accept referral into an episode | `TherapyService.acceptReferral` → tenant-scoped `Episode` | `TherapyEpisodeFlowTest.acceptsReferralIntoAnActiveOwnedEpisode` |
| Evaluation: problems, baselines with units, goals, frequency/duration | `TherapyService.recordEvaluation` → `Evaluation` | `TherapyEpisodeFlowTest.recordsEvaluationWithProblemsBaselinesGoalsAndPlan`; `TherapyCoverageTest.validationGuardsRejectNullAndBlankMembers` |
| Interventions | `TherapyService.recordIntervention` | `TherapyEpisodeFlowTest.recordsInterventionAndSignsSessionAndProgressNotes` |
| Versioned outcome scales, stored units, approved readings | `defineOutcomeScale` / `approveOutcomeScale` / `recordOutcome` | `OutcomeScaleTest.readingRequiresAnApprovedScaleAndMatchingUnitAndPermittedValue`; `OutcomeScaleTest.readingsBindTheExactScaleVersionAndUnitForTrending` |
| Progress notes (signed) | `recordNote(PROGRESS)` / `signNote` | `TherapyEpisodeFlowTest.recordsInterventionAndSignsSessionAndProgressNotes` |
| Discharge closes the episode | `signNote(DISCHARGE)` → `DISCHARGED` | `TherapyEpisodeFlowTest.signsDischargeTransitionsEpisodeAndSendsApprovedSummaryWithReturnAcknowledgment`; `TherapySafetyTest.dischargeRequiresSignedEvaluationAndASignedSession`; `TherapyCoverageTest.closedEpisodeCannotSignALeftoverDraft` |
| Signer attestation, checksum, signed-note immutability | `TherapyService.signNote` | `TherapySafetyTest.signedNotesAreImmutableAndCannotBeSignedTwice` |
| Approved summary to verified referrer | `TherapyService.sendSummary` | `TherapyCoverageTest.summaryRejectsAnUnverifiedRecipient`; `TherapyCoverageTest.replacementSummaryClearsPriorAcknowledgment` |
| Revision-bound return acknowledgment (referral closure) | `TherapyService.acknowledgeReturn` | `TherapySafetyTest.returnAcknowledgmentMustBindTheExactSentSummaryRevision`; `TherapyCoverageTest.acknowledgmentMustNameTheVerifiedReferrer` |
| Tenant isolation, role authorization, append-only audit | `InMemoryTherapyStore`, `TherapyService.authorize/audit`, `auditTrail` | `TherapySafetyTest.identicalLocalIdentifiersInTwoTenantsStayIsolated`; `TherapyEpisodeFlowTest.auditTrailRecordsActorAndOutcomePerCommandAndIsAppendOnly` |
| Command DTO defensive copies + 422 validation for null lists | `EvaluationRequest` / `ScaleRequest` compact constructors | `TherapyCoverageTest.commandRecordsCopyListsAndPreserveNull` (new, red→green) |

**Entry-point-to-test trace:** command → `TherapyService.<command>(Actor, …)` → `authorize` (fresh actor roles, 403) → guarded `InMemoryTherapyStore.find` (404) → `requireVersion` (412) / `requirePatient` (422) / `requireState` (409) → `store.save` → `appendAudit(SUCCESS)` → value record. Each arrow is exercised by the tests above, including the failure paths (`TherapyCoverageTest.guardFailuresAreAudited`).

## Defects found and fixed (this pass)

1. **DEF-1 (integrator-blocking, SpotBugs):** `EvaluationRequest` and `ScaleRequest` exposed caller-owned mutable lists — 9 `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` findings in `TherapyContract.java`. Fixed with null-preserving `List.copyOf` in the compact constructors and overridden accessors. Null is deliberately preserved so the service still returns a structured 422 for an omitted list (existing validation tests depend on this).
2. **DEF-2 (integrator-blocking, docs gate):** `TherapyContract.Actor.of()` repeated the record's component `@param` tags on a static factory. Doxygen folds a record's factory methods into the canonical constructor entity, producing "multiple @param documentation sections" (same root cause as the merged S-G3-06 fix). Removed those `@param` tags; component semantics remain on the record declaration.
3. **DEF-3 (clinical integrity, reviewer PT-H3 / F2):** signed-note `contentChecksum` was a 32-bit Java `hashCode` (8 hex chars, trivially collidable), mislabelled "content fingerprint" and short of CLN-05's content-checksum requirement. Replaced with the platform's SHA-256 helper `NoteChecksum.sha256(...)` (64 lowercase hex chars) over the NUL-joined bound fields (kind, revision, patient, content, effective time, author, template version, signer, attestation, signed time). Red test `signedNoteChecksumIsSha256` (asserted 64 hex chars) failed before, passed after. Cross-story import is established here (s_g3_09 → s_g3_04) and avoids duplicating crypto.
4. **DEF-4 (authorization, reviewer F1):** `authorize()` had no case for `ACTION_READ`, which fell through to `default -> null` and skipped role checking — any active role (e.g. finance, reception) could read the full signed clinical aggregate. Split into `ACTION_READ_EPISODE` (PT clinician/coordinator) and `ACTION_READ_SCALE` (admin, governance, PT clinician), replaced the switch with a declarative `ACTION_ROLES` policy map whose `getOrDefault(action, Set.of())` fails closed with no unreachable branch, and added red test `wrongActiveRoleCannotReadClinicalAggregateOrScales`. The service class Javadoc now accurately claims every command is authorized.

DEF-3 and DEF-4 change integrity/authorization semantics but no external contract; all prior tests still pass.

## Exact commands run and results

| Command | Result |
|---|---|
| `cd apps/api && mvn -B -Pno-security,fast test` (baseline) | 1491 tests, 0 failures/errors/skipped, BUILD SUCCESS |
| `mvn -B -Pno-security,fast -Dtest=TherapyCoverageTest test` (RED, before DEF-1 fix) | 15 tests, 1 failure: `commandRecordsCopyListsAndPreserveNull` expected `<[p1]>` but was `<[]>`; BUILD FAILURE |
| `mvn -B -Pno-security,fast -Dtest=TherapyCoverageTest test` (GREEN, after fix) | 15 tests, 0 failures/errors, BUILD SUCCESS |
| `cd apps/api && mvn -B -Pno-security,fast test` (final) | **1494 tests, 0 failures/errors/skipped, BUILD SUCCESS** (40 tests in s_g4_02: 5 + 15 + 6 + 14) |
| `cd apps/api && mvn -q -B jacoco:report` → parse `target/site/jacoco/jacoco.csv` for package `com.aurevia.smartclinic.g4.s_g4_02` | **MISSED = 0, COVERED = 3480** across instruction/branch/line/complexity/method; i.e. 100% coverage of every s_g4_02 class from the fast tier |
| `cd apps/api && mvn -B -DskipTests -Dspotbugs.skip=false com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0:check` (before) | 14 bugs; 9 in s_g4_02 (DEF-1) |
| same command (after) | 6 bugs, **0 in s_g4_02** |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests, 1 failure: `record='PatientFlowContract.java'` (s_g3_11, out of scope); `test_javadoc_links_are_fully_qualified` PASS |
| `bash scripts/quick-check.sh` | exit 1 at first gate: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — documented cloud-worker `skip-worktree` CI profile artifact (`git ls-files -v opencode.json` → `S`); not caused by this change |

## Out-of-scope, pre-existing failures (observed, not modified)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_11/PatientFlowContract.java` — Java-conventions defensive-copy guard (owned by S-G3-11).
- SpotBugs: `g0.s_g0_07` (1), `g4.s_g4_04` (2), `messaging.s_g3_05` (1), `messaging.s_g3_08` (1), `messaging.s_g3_10` (1) — other stories.
- Stale skip-worktree `opencode.json` in this cloud worker.
- The full `docs.sh` / Doxygen gate could not be run locally: `doxygen` is not installed and `apt-get install doxygen` failed ("Unable to locate package"). DEF-2 was fixed from the documented known-good convention and merged precedent (S-G3-06); a local Doxygen run remains an evidence gap.

## Decisions


- Keep request DTOs null-preserving: the service owns structured 422 validation for missing lists; the records only freeze non-null caller lists.
- No `@param` tags on record compact constructors or static factory methods; components are documented once on the record declaration (Doxygen merge rule). Instance `with*` copy methods keep their `@param` (regular methods, not constructor entities; merged G1 precedent).
- Reserved V42 migration intentionally unused — no schema/Flyway change in this domain-only slice; scope forbids `db/migrations` edits.

## Specialist reviews (simulated, read-only)
=======
- Keep component documentation on the record declaration; compact constructors and static factories must not repeat `@param` (Doxygen folds them into one canonical-constructor entity). This is the minimal fix that satisfies both `WARN_NO_PARAMDOC` and the no-duplicate rule.
- No tracker, workflow, script, migration or out-of-scope source file was edited; only the declared scope was reviewed.

## Evidence provenance

All results are from executed tools (Doxygen 1.9.4, Maven/Surefire, npm/tsc/eslint/Jest, Python unittest) on synthetic fixtures. No real patient data, credentials or raw logs are recorded. No human participant or authorized clinical feedback was used; the only review input was the integrator's automated gate log. Database and integration checks are the integrator's responsibility and were not run here.

---

# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Scope honoured

Only the declared scope was available for edits:
`apps/api/src/main/java/com/aurevia/smartclinic/g0/s_g0_08`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g0_08`, and reserved migration `V11`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) and no file outside that scope was edited. No push, tag or `gh` call
was made. Synthetic fixtures only.

## Requirement IDs and acceptance mapping

- **IAM-06** (primary): emergency access is time-limited (1–60 min), scoped to one
  tenant-local facility, reasoned, attributed to a qualified clinician, audited on the
  V3 append-only chain, independently reviewable by privacy auditor / tenant admin
  (grantee cannot review own grant), and revocable early. Platform support access is
  **off by default** and only effective inside an approved, time-bounded (1–240 min)
  incident window; checks return an explicit fail-closed `AccessDecision`.
- **ARC-02 / OPS-02** (supporting): V11 tables carry `(organization_id, id)` keys and
  forced row-level security; purpose, role, facility, actor and timestamp are recorded
  and committed with the audit event.
- Delivered surfaces already present in scope (unchanged by this fix attempt):
  `AccessController` (8 endpoints under the IAM route family), `EmergencyAccessService`,
  `SupportAccessService`, `AccessRepository`, `AccessRules`, `AccessContracts`,
  `V11__emergency_and_support_access.sql`, `AccessUnitTest` (41 tests),
  `AccessIntegrationTest` (8 DB tests, integrator-run).

## Reported failure — investigation and disposition

The integrator's "requested change" reported a `scripts/check.sh` failure whose only
evidence was this stack trace:

```
Error: useTheme must be used inside ThemeProvider
    at useTheme (.../apps/staff-web/src/theme/ThemeProvider.tsx:39:11)
    at Consumer (.../apps/staff-web/tests/theme/ThemeProvider.test.tsx:6:32)
```

Findings:

1. **Out of scope.** The error is in `apps/staff-web`, which is outside this task's
   declared scope (`apps/api/.../s_g0_08` + V11). It cannot be fixed from this branch
   without violating the task rules, and no in-scope file can cause or cure it — the
   staff-web tests use local synthetic fixtures and make no API calls.
2. **Not an actual test failure.** The identical stack trace was reproduced locally.
   It is the expected stderr output of the intentional negative-path test
   `throws when used outside its provider` (`ThemeProvider.test.tsx:31-35`), which
   asserts `expect(() => render(<Consumer />)).toThrow(/useTheme/)`. React 18 dev mode
   logs the guarded-callback rethrow through jsdom's `reportException`; the test still
   catches it and passes. `npx vitest run tests/theme/ThemeProvider.test.tsx` → 3/3
   passed; the full `npm test` suite → **103/103 passed, exit 0**. The pasted text is a
   benign log line, not a build failure.
3. **Merge not reproducible locally.** The checkout is a grafted single-commit shallow
   clone (`git log --all` shows only `bb355f1`; `.git/shallow` present; no local `main`
   ref), so "the merge of main and this branch" cannot be reconstructed here. This is
   an evidence gap, not a code defect.

No in-scope defect was found; no in-scope change was needed for a fix attempt.

## Exact commands run and results

| Command | Result |
|---|---|
| `git status --short` / `git branch --show-current` / `git log -1` | clean tree; branch `swarm/S-G0-08-67`; HEAD `bb355f1` |
| `git show --stat HEAD` / `find` scope dirs / `ls db/migrations` | full S-G0-08 implementation in scope; V11 present |
| `bash scripts/quick-check.sh` | exit 1 **only** at `check-pack: opencode.json is stale` — environment artifact (see below); all preceding policy checks passed |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree); worktree file is CI profile (`wt == generated CI == True`, `committed == generated LOCAL == True`) — documented cloud-worker CI-profile trap, not a branch defect |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; 231 tests, 0 failures, 0 errors; `AccessUnitTest` 41/41 |
| `cd apps/staff-web && npm ci && npx vitest run tests/theme/ThemeProvider.test.tsx` | **exit 0**; 3/3 passed (error text reproduced as benign stderr) |
| `cd apps/staff-web && npm test` | **exit 0**; 23 files, 103/103 passed |

Environment note: `check-pack.py` in this worker reads the skip-worktree CI-profile
`opencode.json`, so it reports it stale; the committed `opencode.json` matches the
generated local profile and is correct. Java/Database integration tests are left to
the integrator as directed.


- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **1463 tests, 0 failures, 0 errors, 0 skipped** over 134 report files; new suite `com.aurevia.smartclinic.messaging.s_g3_11.PatientFlowPresentationTest` = **13 tests, 0 failures/errors**. This is the exact Java command in `scripts/quick-check.sh`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` → **EXIT 0**. JaCoCo `jacoco.xml` shows 100% LINE/BRANCH/METHOD for every new S-G3-11 class: `PatientFlowPresentationService` 54/54 lines, 16/16 branches, 11/11 methods; `NoticeKind` 28/28 lines, 22/22 branches; `AppPlatform` 17/17 lines, 16/16 branches; `AppLocale` 13/13 lines, 8/8 branches; `InMemoryFlowPolicyStore` 18/18 lines, 11/11 methods; records and interface fully covered.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → EXIT 0; `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0; `npm test` → **EXIT 0 (25 suites, 230 tests passed)**.
- `cd apps/patient-mobile && npm run test:coverage -- --ci` → **EXIT 0**. All files **100%** statements/branches/functions/lines; the `s_g3_11` folder is **100%** on all four counters. (19 in-scope TS tests: 13 in `presentation.test.ts`, 6 in `patient-flow-home.test.tsx`.)
- `bash apps/patient-mobile/docs.sh /tmp/sg311-docs` → EXIT 0 (pending-module page; `src/index.ts` not yet present, same scaffold state as other G3 slices).
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0**. Gaps are allowed by design (decision 0002); this shard has V1–V12 plus selected V15–V46 and no V39 table is required because this slice uses a synthetic in-memory store (as do S-G3-04 and S-G3-07).
- `mvn -q -B -f apps/api/pom.xml verify -DskipTests -Dowasp.skip=true -Djacoco.skip=true` (SpotBugs on compiled classes) → EXIT 1 due to **14 pre-existing findings in other stories** (s_g0_07, s_g4_02 ×2, s_g4_04 ×2, s_g3_05, s_g3_08, s_g3_10); **zero findings in s_g3_11**. Out of scope; see below.


I did **not** run `db/check.sh`, `mvn verify` with a live PostgreSQL, or OWASP dependency analysis — those belong to the integrator/the Delivery CI per the worker contract. No coverage result was fabricated.

## Defects found

No in-scope defect was found. The S-G3-11 implementation already present at the branch HEAD from a
prior attempt is complete, internally consistent and green under every fast gate and both coverage
runners. No in-scope production or test source change was necessary; this worker's scoped contribution
is verification evidence plus this pull-request description. The prior attempt's tests are meaningful
and map one-to-one to the acceptance behaviours in the table above (each would fail without the
corresponding production behaviour).

## Out-of-scope / environment observations (for the integrator, not changed here)

- The SpotBugs findings listed above are in other stories' paths, not s_g3_11, and predate this worker's run. They will fail a security-profile `mvn verify` on any tree containing them; confirm the merged `main` has the corresponding fixes from the owning workers/repair PRs. I did not edit them (outside my declared scope).
- Do not interpret a `check-pack.py` "opencode.json is stale" result in this cloud worker as an S-G3-11 defect: the worktree `opencode.json` is the CI profile copied under `skip-worktree`, while `git show HEAD:opencode.json` is the committed local profile (the documented OPS-CW-003-01 trap). I did not regenerate or overwrite `opencode.json`.
- Job logs and test output are tool-generated evidence; no human, clinician or simulated-unit pilot feedback was available for this slice. Real-patient/RTL accessibility acceptance remains a G3/G5 gate item.

## Decisions / notes for the integrator

- No production or test source was changed; reserved migration version **V39** was not used because no database object is introduced. No other migration version was touched.
- Confirm on the merge result: the full Java `mvn verify` (SpotBugs, OWASP, JaCoCo at 100%) and the patient-mobile `check.sh` with enforced 100% coverage remain green, and the other stories' SpotBugs findings are resolved by their owners.
- Evidence source for every result above: executed tool commands (Maven/Surefire/JaCoCo, npm/Jest/coverage, Python checker). No fabricated or pending-as-covered results.
=======
- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.


- **Senior backend (bounded review):** confirmed the DEF-1 fix correct with no residual SpotBugs/Doxygen issue; found F1 (fail-open READ, fixed as DEF-4) and F2 (weak checksum, fixed as DEF-3). Noted informational concerns: audit append is not transactional with state in the in-memory store; return-acknowledgment origin is asserted by a caller-supplied string, not verified through a cross-org identity.
- **Physician / clinical safety (bounded review):** core state machine accepted as synthetic slice evidence — evaluation gates treatment, signed non-discharge note gates discharge, discharge is terminal, signed notes immutable, readings bind approved scale version + unit, replacement summary clears the old acknowledgment, two-tenant value isolation. Flagged PT-H3 (fixed as DEF-3) and the following limitations that need surfaces outside this domain-only slice.

## Routed limitations (not fixed here — need controller/DB/exchange surfaces, outside declared scope)

These are real gaps for **live enablement**, not defects in this synthetic domain slice. None is represented as verified; each needs its own story and qualified site sign-off.

- **PT-H1 / F1-secondary — return-acknowledgment provenance:** `acknowledgeReturn` checks a caller-supplied `receivedBy` against the stored referrer string; it does not authenticate the external referrer. With no REST controller or cross-org exchange channel in scope, G4 "referral closure" is **not** claimed from this slice. Owner: Product Owner + clinical safety lead + architect (XCH partner identity).
- **PT-H2 — summary disclosure:** `sendSummary` is self-approved and free-text with no content snapshot/checksum/purpose/release-policy version and no verified referrer identity (the `referrerOrg/Subject` strings are captured unverified at acceptance). Owner: clinical safety lead + privacy owner.
- **PT-H4 — atomic version precondition:** `requireVersion` and `InMemoryTherapyStore.save` are separate operations, so two concurrent commands can both pass the check and the last blind put can lose a clinical fact. Real atomicity belongs to a transactional `UPDATE ... WHERE version = ?` in the durable store; the in-memory store is synthetic scaffolding. Owner: senior backend + database specialist (integration story).
- **PT-H5 — acceptance-time verification:** `acceptReferral` does not verify the patient exists or that the referrer is an authorized party (no registry in scope). Explicit patient-binding overloads exist on the patient-bearing commands but the common overloads rely on the episode patient. Owner: clinical safety + IAM/architecture.
- **PT-H6 — discharge does not enforce closure:** summary send and acknowledgment are independent optional commands; the state model does not require them for referral closure. Owner: product + clinical safety.
- **L1/L2/L3/L4 —** clinical timestamps are non-blank strings rather than parsed instants with zone context; `templateVersion` is optional on signed notes; empty baselines/goals are allowed; in-memory audit has no hash chain and some validation failures are unaudited. Owner: relevant domain + integration stories.
- **Architectural context:** no controller, PostgreSQL/Flyway/RLS, OIDC/membership enforcement, or durable audit exists in this module. Tenant isolation was proven at the value layer only; it is not a substitute for the G0 forced-RLS guarantee. All of the above must be re-evaluated when this slice is wired to real persistence and identity.


## Evidence provenance

- Tool-generated: Maven/Surefire (fast tier, 1492 tests), JaCoCo CSV (100% scope coverage), SpotBugs 4.10.5.0 (before/after), Python unittest (Java conventions guard), `git ls-files` / `git status`.
- Simulated reviewers: bounded read-only reviews from the senior-backend and physician-clinical-safety specialists (see appended findings below, if any).
- No DB/integration tests were run locally: per worker rules the integrator runs the database and integration suites. No human or authorized clinical participant feedback; all fixtures are synthetic. No patient data, credentials or raw logs are recorded.
=======
## Requirement IDs and acceptance mapping


Primary: **MSG-06**. Supporting: **ADM-01** (named approval, version history), **ARC-01/ARC-02/ARC-03** (tenant-owned keys, isolation, version precondition), **OPS-02** (audit, no PHI), **UX-01** (en/ar, RTL, IANA time zone).

Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-06, §6 ADM-01, §7 ARC-01..03.

| MSG-06 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Each enabled unit publishes service hours, response targets, approved topics and urgent contact instructions | `ServiceProfileService.publish` + `ServiceProfileInputs` (zone/window/topic/guidance validation); `ServiceProfile` record carries `hours`, `responseTargetMinutes`, `topics`, `urgentInstructions`, `urgentChannel`, `downtimeChannel` | `ServiceProfileServiceTest`, `ServiceProfileDomainTest`, `ServiceProfileControllerTest` |
| Published guidance is named-approved, versioned and only effective at its effective date | `ServiceProfileService.approve` (distinct named approver + version precondition); `effectiveAt`; immutable `ProfileHistory` | `ServiceProfileServiceTest`, `ServiceProfileDomainTest` |
| Show guidance before sending; outside hours show when the unit next handles messages | `ServiceAvailability` from `ServiceHoursCalculator` (IANA zone, DST-safe, next handling instant); mobile `noticeLines` / `ServiceProfileNotice` render urgent guidance and channels first, then next-handling banner | `ServiceHoursCalculatorTest`, `ServiceProfileNotice` tests in `index.test.tsx` |
| Require a named queue owner, backup coverage and overdue escalation | `queueOwner`, `backupCoverage`, `escalationChannel` are required validated publish fields; `urgentChannel`/`downtimeChannel` for time-sensitive care (site-approved keys, never clinical values) | `ServiceProfileDomainTest`, `ServiceProfileServiceTest` |
| Surface backlog, unassigned work, failed notifications and overdue responses to unit supervisors | `SupervisorSnapshotCalculator.compute` + `ServiceProfileService.supervisorSnapshot` (SUPERVISOR_VIEW restricted to an assigned unit) | `SupervisorSnapshotCalculatorTest`, `ServiceProfileServiceTest` |
| Tenant isolation, fail-closed authorization, audit | Every store call carries the organization; `authorize`/`requireUnitAccess` re-derive membership, role and unit connection per request; all reads/denials appended to `MessagingAudit` | `ServiceProfileServiceTest`, `InMemoryServiceProfileStoreTest` |

Entry-point-to-test trace:
- `GET /api/v1/care-units/{unitId}/service-profile` → `ServiceProfileController.view` → `ServiceProfileService.viewProfile` → `authorize`+`requireUnitAccess` → `ServiceProfileStore.effectiveProfile` → audit → 200. Covered by `ServiceProfileControllerTest` and `ServiceProfileServiceTest`.
- `GET .../{unitId}/availability` → `availability` → `ServiceHoursCalculator.calculate` → 200. Covered by `ServiceHoursCalculatorTest`.
- `GET .../{unitId}/supervisor-snapshot` → `supervisorSnapshot` → `SupervisorSnapshotCalculator.compute` → 200. Covered by `SupervisorSnapshotCalculatorTest`.
- `POST .../{unitId}/service-profiles` + `POST .../{unitId}/service-profiles/{profileId}/approve` → `publish`/`approve` (If-Match version precondition) → guarded store → audit. Covered by `ServiceProfileControllerTest` and `ServiceProfileServiceTest`.
- Mobile: `createSyntheticServiceInfoClient.notice` → `noticeLines` → `<ServiceProfileNotice>` → testIDs asserted in `apps/patient-mobile/src/features/s_g3_09/index.test.tsx`.

## Scope and prior state

The complete in-scope implementation (9 Java main files, 6 Java test files, 4 mobile files) was already committed at the shard base `da4fbb4` from earlier attempts; this worker did **not** rewrite it. I reviewed every in-scope source and test file against MSG-06 and confirmed the behaviour above is implemented and covered. This worker's scoped source change is one documentation defect fix (below); the rest of the contribution is verification evidence.

## Commands run and results (evidence source: executed tool commands; synthetic fixtures only)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0** (run twice: before and after the source fix). The exact Java command in `scripts/quick-check.sh`. In-scope Surefire suites: `InMemoryServiceProfileStoreTest` 3, `ServiceHoursCalculatorTest` 13, `ServiceProfileControllerTest` 6, `ServiceProfileDomainTest` 13, `ServiceProfileServiceTest` 23, `SupervisorSnapshotCalculatorTest` 5 = **63 tests, 0 errors, 0 failures, 0 skipped**.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → EXIT 0 (1272 packages).
- `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0.
- `npm run test:coverage -- --ci` → **EXIT 0**. **230 tests / 25 suites passed.** Coverage for `s_g3_09`: `ServiceProfileNotice.tsx` 100% stmts/branch/funcs/lines, `serviceNotice.ts` 100% stmts/branch/funcs/lines (`types.ts` is interface-only, 0% like every sibling feature and excluded from behavioural coverage).
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0.

Java JaCoCo 100% line/branch/method/class and the real-PostgreSQL/integration gate are enforced by `mvn verify` / `db/check.sh`, which the **integrator** runs; I did not run them and did not fabricate a Java coverage number.

## Defects found and fixed (in scope)

1. **Stale Javadoc `@param escalationAfterMinutes`** in `ServiceProfileContract.PublishProfileRequest` documented a record component that does not exist (overdue escalation is represented by `escalationChannel` plus the response target used by `SupervisorSnapshotCalculator`). Removed the stale tag and clarified the three `escalationChannel` `@param` descriptions to "site-approved channel key for overdue escalation". Documentation-only; no behaviour change. All fast tests re-run green after the fix.

No functional defect was found in the pre-existing implementation. No PHI, real identifiers or credentials appear in fixtures, code or these notes.

## Decisions / notes for the integrator

- **No V37 migration.** The reserved version was not used: MSG-06 messaging stories in this slice use the tenant-scoped in-memory `ServiceProfileStore` adapter, matching siblings S-G3-02/03/04/07/08 (their reserved migrations are also absent). The store Javadoc records the G5 production gap (forced RLS, durable uniqueness, atomic outbox). Do not fabricate a migration for this story.
- The fast checks above are green on this branch. The full `scripts/quick-check.sh` is expected to fail here for two environmental reasons unrelated to this story: (a) worktree `opencode.json` is the CI profile (SHA `279aa1e7…`, skip-worktree flag `S`, equals `.github/opencode.ci.json`) while `git show HEAD:opencode.json` is the local profile (`14bba0da…`), so `gen-opencode.py --check` reports stale — the documented OPS-CW-003-01 cloud-worker trap; I did not overwrite it; (b) `check-migrations.py --require-baseline` sees only this shard's subset of migrations, so the V9+ contiguity check fails until merged with `main`. Both resolve on the integrator's merge result.
- I did not push, tag or call `gh`. Evidence sources are tool commands only; no simulated user, clinician or human-participant evidence was available for this slice.
- Commit contains the scoped Javadoc fix plus the ignored `.agent/pr-notes.md` (force-added) used as the pull-request description.
=======
Primary: **XCH-02**. Supporting: **ARC-01, ARC-02, ARC-03, ARC-04, IAM-01, PRD-06, OPS-02**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-02, §2 IAM-01, §7 ARC-01..04, §8 OPS-02. Gate: G2.

| XCH-02 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Three independent state channels: workflow `requested→accepted→processing→completed` (+ `rejected`/`cancelled`), transmission `pending→sent→delivered\|failed`, clinical review `unreviewed→acknowledged→action_pending→resolved` | `WorkflowState`, `TransmissionState`, `ClinicalReviewState` matrices; `ExchangeDimension`; `ExchangeStateMachine.next` writes exactly one channel; V20 `CHECK` constraints + `xch02_guard_task_update` mirror the matrices in SQL | `WorkflowStateTest` (3), `TransmissionStateTest` (2), `ClinicalReviewStateTest` (2), `ExchangeStateMachineTest` (6); `ExchangeTaskIsolationTest.workflowPathRejectsReasonsAndIllegalTransitions`, `transmissionAcknowledgmentNeverClosesAClinicalTask`, `reviewChainRequiresAcknowledgmentAndIsAppendOnly` (integrator) |
| Acknowledgment by an authorized human with timestamp and revision | Clinician-only `EXCHANGE_REVIEW` in `ExchangeActionPolicy`; `Clock`-derived timestamp; `reviewedRevision = current.version()` after `If-Match` CAS; actor derived by V20 `xch02_actor_id()` from transaction-local `app.actor`, never from a request DTO | `ExchangeTaskServiceTest.acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor`, `acknowledgeDeniesNonClinicianAndInactiveMembership`; `ExchangeTaskIsolationTest.humanAcknowledgmentBindsContextActorClockAndRevision` (integrator) |
| A transmission acknowledgment cannot close a clinical task | `DELIVER`/`FAIL`/`SEND` map to `TRANSMISSION`; the state machine copies the clinical channel and its evidence unchanged; V20 restores clinical evidence columns on non-clinical writes and enforces the one-channel rule | `ExchangeStateMachineTest.aTransmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskServiceTest.transmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskIsolationTest.transmissionAcknowledgmentNeverClosesAClinicalTask` (integrator, incl. direct SQL assertion) |
| Explicit POST commands with version precondition and machine-readable errors (ARC-03/04) | `POST /api/v1/exchange-tasks` and `/{id}/{command}`, `IfMatch.parse` (428 missing / 422 malformed / 412 stale through CAS), allowlisted `ExchangeCommand.from`, uniform `ApiException` mapping | `ExchangeTaskControllerTest` (5); `ExchangeTaskIsolationTest.authorizationPreconditionsAndTenantConcealment` (401/403/404/409/412/422/428) (integrator) |
| Tenant isolation, fail-closed authorization, append-only ledger and audit (ARC-01/02, IAM-01, OPS-02) | `(organization_id,id)` composite keys; forced RLS on both V20 tables with `WITH CHECK`; `FOR UPDATE` + version CAS; immutable `exchange_task_transition`; `appendAudit` SUCCESS/DENIED inside the guarded transaction; membership and roles re-derived per request | `ExchangeTaskIsolationTest` two-tenant `LOCAL-1` concealment, ledger immutability attempt, audit-row assertions (integrator); `ExchangeTaskServiceTest` 403 denial audits |

### Entry-point-to-test trace

`POST /api/v1/exchange-tasks` → `ExchangeTaskController.create` → `ExchangeTaskService.create` → guarded `TransactionTemplate` (`TenantGuardTransactionManager` binds `app.current_org`/`app.actor`) → `ExchangeActionPolicy.allowsCreate` → `ExchangeTaskRepository.insert` (forced RLS) → `appendEvent(CREATE)` + `appendAudit` → 201 with three initial states. Verified by `ExchangeTaskControllerTest.createReturns201WithLocationAndInitialStates` and `ExchangeTaskIsolationTest.createOpensTheThreeInitialChannelsWithOneHistoryEvent`.

`POST /api/v1/exchange-tasks/{id}/{command}` + `If-Match` → `ExchangeTaskController.execute` → `IfMatch.parse` → `ExchangeCommand.from` → `ExchangeTaskService.execute` → authorize → `repository.loadForUpdate` (`FOR UPDATE`) → `requireVersion` → `ExchangeStateMachine.next` (one channel) → `repository.updateState` (version CAS, V20 guard) → `appendEvent` + `appendAudit` → next state. Verified by `ExchangeTaskControllerTest`, `ExchangeTaskServiceTest`, `ExchangeStateMachineTest` and the integration scenarios.

`GET /api/v1/exchange-tasks/{id}` → `get` → read policy → `repository.find` + `history` → read audit → state + append-only history.

## Commands run and results

Evidence source for this section: **executed tool commands** (not reviewer claims).

- `java -version` → `openjdk version "21.0.13" 2026-10-13 LTS` (Temurin). `mvn -version` → `Apache Maven 3.10.0`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. This is the exact DB-less fast command from `scripts/quick-check.sh`. Surefire XML aggregation: **1463 tests, 0 failures, 0 errors, 0 skipped across 134 suites**. The 8 in-scope S-G2-02 suites contribute **38 tests, all green**: `ClinicalReviewStateTest` 2, `ExchangeActionPolicyTest` 4, `ExchangeCommandTest` 3, `ExchangeStateMachineTest` 6, `ExchangeTaskControllerTest` 5, `ExchangeTaskServiceTest` 13, `TransmissionStateTest` 2, `WorkflowStateTest` 3. (The exact command was re-run a second time with `>/tmp/sg2_02_fasttest.log` to capture the exit code: `FAST_TEST_EXIT=0`.)
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0**. Reserved V20 is present as `db/migrations/V20__exchange_task_states.sql` with a valid `V<n>__name.sql` name and `Change: expand`; no other migration version was touched.
- `git check-ignore -v .agent/pr-notes.md` → not ignored (tracked file); `git ls-files --error-unmatch .agent/pr-notes.md` → tracked. `git status --short` before commit → clean.
- Cross-checks: `IfMatch.parse` (428/422/412 semantics), JaCoCo `check` execution bound at 1.00 for INSTRUCTION/LINE/BRANCH/METHOD/CLASS and the `fast` profile's `excludedGroups=integration` were read from `apps/api/pom.xml` to confirm the gate boundary.

I did **not** run the database/integration/coverage/static-security gates. Per the worker contract these belong to the integrator: `bash db/check.sh`, `mvn -q -B verify` (runs the `@Tag("integration")` `ExchangeTaskIsolationTest` on real PostgreSQL plus JaCoCo 100%, OWASP and SpotBugs), and `scripts/check.sh`. No result from those gates is claimed here.

## Reviewer findings and dispositions

Three independent simulated specialists reviewed the in-scope files (paths, XCH-02 acceptance and the fast-gate evidence only). All three returned **no blocking defect and no required in-scope source change**:

- **Senior Java & Spring Boot** — transition correctness, transmission/clinical independence, server-side acknowledgment binding, CAS, authorization/error mapping all traceable to tests. One low-severity hardening idea: `ExchangeTaskService.reason()` bounds only presence, not length/characters, though `reason` is persisted to two `text` columns. Disposition: **non-blocking follow-up**; not changed here because it is outside the XCH-02 acceptance and the reviewer explicitly said it should not block acceptance.
- **Software Architect** — Java↔V20 transition matrices match one-for-one; channels independent; revision/time/actor bound to the reviewed version; concurrency/RLS/ledger/audit sound. Flagged (a) no direct SQL negative test of the V20 guard rejection paths, (b) no `Idempotency-Key` (documented project deferral; XCH-02 requires only `If-Match`), (c) "revision" interpretation question, (d) DB guard cannot check role for a hypothetical direct SQL writer. Disposition: **(a) recommended to integrator/QA** as added evidence, not a source change; **(b)** out of scope by documented decision; **(c)** recorded as a decision below; **(d)** defence-in-depth only — authorization is enforced before every write.
- **Senior Database** — V20 evidence `CHECK`, guard trigger, fail-closed `xch02_actor_id`, CAS, immutability triggers, grants and forced RLS all correct; change-safety checker not tripped. One out-of-scope observation: `db/check.sh` static table lists do not include the two new tables, so the shell gate does not assert V20 policy; the Java IT is the runtime proof. Disposition: **separate change**, not touched here.

Reviewer evidence is **simulated review**, not human or clinical acceptance. No human participant was involved.

## Defects found

No reproducible in-scope defect was found. The hardening ideas and verification-gap suggestions above are recorded as follow-ups, not as accepted defects.

## Decisions / notes for the integrator

- The complete S-G2-02 implementation (16 main files), 9 test files and the V20 migration were **already committed at this branch's base** `da4fbb4` (a prior attempt's work, reflected in the recycled `.agent/pr-notes.md`). This worker changed **no production or test source** after verification; its contribution is this PR description and the recorded executable evidence. The prior notes described S-G1-07 and were replaced.
- **"Revision" interpretation**: XCH-02's "timestamp and revision" is implemented as the work-item optimistic version the clinician reviewed (the `If-Match` version at acknowledgment), not the immutable order snapshot revision from XCH-01. This slice is deliberately self-contained (no V19 dependency) and the choice is documented in `ExchangeStateMachine`/V20 comments. Confirm with Product/Architecture before G2 sign-off if a different meaning was intended; it does not block this story.
- **Idempotency keys** are absent on transition routes by documented project deferral; a retry after a lost response gets 412 from the version CAS rather than the original outcome. This diverges from the literal ARC-04 sample contract but is outside XCH-02.
- Verify on the merge result: the `@Tag("integration")` `ExchangeTaskIsolationTest` (real PostgreSQL: V20 guard, forced RLS, actor derivation, two-tenant concealment, ledger immutability), JaCoCo 100% at `verify`, OWASP/SpotBugs and `scripts/check.sh`. These are the integrator's gates, not claims of this worker.
- Scope respected: only `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_02`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_02` and reserved migration V20 were in scope; no shared tracker, doc, workflow, other migration or source file was edited. No push, tag or `gh` call was made.
- Synthetic fixtures only; no patient data, credentials or raw logs were recorded. All gate results above are tool-generated; review findings are simulated; there is no human or clinical evidence.
main



