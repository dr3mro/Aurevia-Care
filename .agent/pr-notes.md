# S-G3-10 — Messaging audit, retention, legal hold, provenance and revocation effects

## Requirement IDs and acceptance mapping

Primary: **MSG-07**. Supporting: **ARC-07, OPS-02, OPS-07, PAT-02, UX-01, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-07, §7 ARC-07, §8 OPS-02/OPS-07.

| Acceptance behaviour (MSG-07) | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting, restricting conversations/files | `MessagingAuditContract.AuditAction` (9 workflow actions), `MessagingGovernanceService.recordAction` with server-derived outcome; denials appended via `deny` | `MessagingGovernanceServiceTest.recordActionAuditsEveryConversationAndFileAction`, `recordActionDeniesInactiveMembershipAndAuditsTheDenial`, `MessagingAuditDomainTest.everyAuditActionRoundTripsItsWireValue` |
| Apply configured retention and scoped expiring exports | `RetentionPolicy`, `MessagingResource`, `retentionDecision`, `applyRetention`, `createExport` (allowlisted scope, 60–86400s TTL) | `MessagingGovernanceServiceTest.retentionDecisionDistinguishesHoldWindowAndEligibleResources`, `applyRetentionSkipsHeldAndRecentResources`, `createExportBoundsScopeAndTtl`; mobile `audit.retentionEligible` |
| Legal holds suspend retention; release records actor/time/reason and preserves placement reason | `LegalHold` (+`active()`), `placeHold` (404/409), `releaseHold` (404/409), `isHeld` in retention decisions | `MessagingGovernanceServiceTest.placeHoldRequiresAnExistingResourceAndBlocksDuplicates`, `releaseHoldRecordsTheReleasingActorAndRejectsRepeats`, `releasedHoldPreservesPlacementReasonAndReasonIsAudited` |
| Preserve provenance when authorized staff link messages/instructions to the chart | `ProvenanceLink`, `linkToChart` (clinician-only, verified-patient binding), read-only `provenanceLinks` mobile projection | `MessagingGovernanceServiceTest.linkToChartPreservesProvenanceAndRequiresClinicalAuthorization`, `chartLinkRequiresValidSourceTypeAndVerifiedPatientBinding`; mobile `exposes staff-filed provenance read-only...` |
| Revocation stops new messaging and future attachment access | `recordRevocation`, `requireMessagingAllowed`, `requireAttachmentAccess` (fail closed on `revokedAt != null`) | `MessagingGovernanceServiceTest.newMessagingIsRefusedThroughARevokedConnection`, `attachmentAccessFailsClosedAfterRevocation`, `recordRevocationCapturesBlockingAndRecordAccessBasis`; mobile `blocks attachments only through a revoked connection` |
| Record prior-conversation readability under the verified record-access policy; preserve sender/recipient duties | `RecordAccessPolicy`, `RevocationEffect` (fail-closed `UNKNOWN` policy), `RetentionDuty` | `MessagingGovernanceServiceTest.missingRecordAccessPolicyFailsClosed`, `retentionDutiesPreserveSenderAndRecipientWindows`; mobile `priorConversationsReadable`, fails-closed test |
| Requests for copies follow the site record-access procedure; audit access itself | `CopyRequest`, `requestCopy`, `auditTrail` (audits its own read) | `MessagingGovernanceServiceTest.copyRequestIsRoutedToRecordAccessAndAudited`, `auditTrailReadsTenantEventsAndAuditsItsOwnRead` |
| No clinical payload in governance facts; tenant isolation; OPS-02 actor/purpose/scope/time/action/resource/correlation/outcome | `AuditEvent` has only non-sensitive fields; every store method carries `organizationId`; fresh membership+role check per request | `MessagingAuditDomainTest.auditEventCarriesOnlyNonSensitiveGovernanceFacts`, `syntheticStoreKeepsEveryGovernanceSurfaceTenantScoped`; `MessagingGovernanceServiceTest.crossTenantRecordsAreConcealed` |

Entry-point-to-test trace: `POST /api/v1/messaging-governance/actions` → `MessagingGovernanceController.recordAction` → `MessagingGovernanceService.recordAction` → `requireContext` + `authorize` (fresh membership/role via `MessagingGovernanceActions`) → `InMemoryMessagingGovernanceStore.appendAudit`. `POST .../legal-holds`, `POST .../exports`, `POST .../chart-links`, `POST .../connections/{id}/revocation-effects`, `POST .../copy-requests`, `GET .../audit`, `GET .../retention/{id}` follow the same guarded path; `MessagingGovernanceServiceTest.controllerRoutesDelegateWithoutChangingFields` traces every route to its delegate. Patient side: `createSyntheticGovernanceClient` + `MessagingAuditPanel`, exercised in `src/features/s_g3_10/index.test.tsx`.

## Commands run and results (evidence source: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. 1463 tests, 0 failures/errors/skipped; s_g3_10 = 38 tests (`MessagingAuditDomainTest` 13, `MessagingGovernanceServiceTest` 25). This is the exact Java fast tier from `scripts/quick-check.sh`.
- `mvn -q -B -f apps/api/pom.xml jacoco:report` → **EXIT 0**. All 24 s_g3_10 production classes show **0 missed instructions, branches, lines and methods** (100% coverage) in `target/site/jacoco/jacoco.csv`.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → **EXIT 0** (1272 packages).
- `npx jest src/features/s_g3_10/index.test.tsx --runInBand` → **EXIT 0**, 16 tests pass.
- `npx jest --coverage --runInBand` → **EXIT 0**, 25 suites / 230 tests pass; s_g3_10 `audit.ts` and `MessagingAuditPanel.tsx` at 100% statements/branches/functions/lines (`types.ts` is type-only, 0/0, consistent with every sibling feature).
- `cd apps/patient-mobile && npm run typecheck` → **EXIT 0**; `npm run lint` → **EXIT 0**.
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0** (gaps allowed; reserved V38 not required by the checker).
- `git status` → clean before this note (base snapshot `da4fbb4`).

I did **not** run `db/check.sh`, `mvn verify`, or any real-PostgreSQL integration suite: per the worker contract those belong to the integrator. There are no s_g3_10 integration fixtures to add (this story, like S-G3-04/S-G3-07, uses a tenant-keyed in-memory adapter with unit-level acceptance).

## Defects found

No in-scope production or test defect was found. The complete, green implementation was already present in the base snapshot at `da4fbb4`; running the fast unit tiers and a full JaCoCo/TS coverage review confirmed it is correct and fully covered. No in-scope source change was necessary.

Non-blocking observation for the integrator (not a spec violation, no change made): `linkToChart` verifies a verified connection exists for the patient in the active tenant, but the synthetic store has no message-to-patient map, so it cannot additionally prove that `messageId`/`conversationId` belongs to that patient. Ownership of message↔patient binding belongs to the conversation/instruction services that call this governance guard (MSG-01/ARC-07); filing is a clinician-only action. Left unchanged to avoid out-of-scope store/interface churn.

## Decisions / notes for the integrator

- No production or test source was changed by this worker; in-scope code was already complete and green. This branch's new commit updates only this ignored-then-tracked `.agent/pr-notes.md` (the PR description), matching the established worker precedent for an already-complete story.
- Reserved migration version **V38** is intentionally unused: this story's scope is the in-memory governance adapter plus patient-mobile feature, and `db/migrations` is outside the declared scope. Migration contiguity passes without it.
- Scope respected: only `apps/api/.../messaging/s_g3_10` and `apps/patient-mobile/src/features/s_g3_10` were reviewed; no shared tracker, no other migration version, no credentials or patient data touched.
- Coverage (JaCoCo 100% line/branch/method/instruction/class and mobile 100% statements/branches/functions/lines) confirmed from locally executed reports; no fabricated or pending-as-covered results.
- Evidence source: executed tool commands only. Synthetic fixtures throughout. No simulated reviewer, simulated user, human or clinical evidence was used for this story; real-patient approval remains a G5 gate.
