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

## Entry-point-to-test trace

`POST`-style service entry `PatientFlowPresentationService.presentation(PresentationQuery)` →
`TenantContext.current()` → `requireMembership` (re-derived, fail-closed) → `AppLocale`/`AppPlatform`
parse + `isValidTimeZone` → `FlowPolicyStore.policyVersion/approvedNotices` (tenant-keyed, forced-RLS
adapter in production) → immutable `FlowPresentation` (`List.copyOf`) → `appendAudit(FlowAudit)` →
return. `PatientFlowPresentationTest` exercises every branch against a synthetic store. The app-side
mirror is `createSyntheticFlowClient(...).presentation(subject, query)` → `FlowPresentation` →
`PatientFlowHome` render; `presentation.test.ts` and `patient-flow-home.test.tsx` cover that path
including RTL mirroring and generic-notice gating.

## Commands run and results (evidence source: executed tool commands)

Environment: Java 21.0.13, Maven 3.10.0, Node 22.23.5, npm 10.9.9, Python 3.11.2.

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
