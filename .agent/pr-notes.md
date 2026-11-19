
# S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance
=======

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

- **RED** `npx jest src/features/s_g3_01 --silent` → **EXIT 1**, 5 failures / 25 pass: patient-scope assertion failure plus `TypeError: withRevokedDevice/withInvalidatedDevice is not a function`; and `deriveConfirmation({confirmationEnabled:false}, true)` wrongly returned `pending`.
- **RED** `npm run typecheck` → **EXIT 2**: `TS2305` missing exports, `TS2554` expected 2 arguments got 3. (Each failure is caused by the missing behaviour, not an unrelated error.)
- **GREEN** `npx jest --coverage --collectCoverageFrom='src/features/s_g3_01/*.ts' ... src/features/s_g3_01` → **EXIT 0**, 30/30 tests, **100% statements/branches/functions/lines** for every s_g3_01 source file.
- **GREEN** `npm run typecheck`, `npm run lint` → EXIT 0.

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

- Story: S-G3-06 (swarm fix attempt 1 of 3), branch `swarm/S-G3-06-90`, base SHA `7cd80d6` (contains the verified Doxygen fix). This delivery commit adds the PR-notes artifact.
- Primary requirements: **MSG-03** (attachments, explicit send, recipient shown before send, quarantine scan, clean-verdict access, sender binding, removal/replacement), **ARC-06** (validate size/type/checksum, private quarantine, unique object keys and verified object version, mediated downloads, keep clinical payloads out of logs/URLs).
- Supporting requirements exercised by the implementation: MSG-01 / ARC-07 (connection-scoped binding), IAM-01 (fresh actor snapshot every command), ARC-02 (tenant-scoped store).

## Failure being fixed (defect)

The fast merge gate (`scripts/integrator-gate.sh`) failed on the merge of `main` and this branch. Relevant log tail:

```
apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java:338: error: argument 'organizationId' from the argument list of com.aurevia.smartclinic.messaging.s_g3_06.AttachmentContract.Actor has multiple @param documentation sections
... same for 'subject', 'roles', 'patientId', 'unitId'
```

This is a Doxygen error (`docs/Doxyfile`: `WARN_NO_PARAMDOC = YES`, `WARN_AS_ERROR = FAIL_ON_WARNINGS`, run by `scripts/docs.sh`). Root cause: the `Actor` record repeated its five component `@param` tags on the record declaration **and** on its compact constructor and its two static factory methods. Doxygen folds a record's compact constructor and factory methods into a single canonical-constructor documentation entity, so each component appeared in more than one `@param` documentation section. The same pattern existed on the `View` and `PublishRequest` compact constructors.

## Fix

Document each record component exactly once, on the record declaration, and carry **no** `@param` tags on compact constructors or static factory methods (they keep only `@return`/prose). This satisfies `WARN_NO_PARAMDOC` without producing duplicate sections. The change is present at branch HEAD commit `7cd80d6` in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java` (`Actor`, `View`, `PublishRequest`). No further source change was needed; this attempt verifies and records the fix.

## Red → green evidence (synthetic, tool-generated)

- RED reproduction: a minimal synthetic record `/tmp/doxrepro/src/Repro.java` with `@param` on both the record declaration and its compact constructor/factory, checked with the repository `docs/Doxyfile` (doxygen 1.9.4), reproduced the exact failure:
  `error: argument 'organizationId' from the argument list of repro.Repro.Actor has multiple @param documentation sections` (exit 1).
- GREEN verification: the same minimal record with component `@param` only on the record declaration → doxygen exit 0, no warnings.
- GREEN verification of the actual scope: a Doxyfile with `INPUT = apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06` and `WARN_AS_ERROR = FAIL_ON_WARNINGS` over the current files → exit 0, zero warnings/errors.
- `grep -n "@param organizationId" .../AttachmentContract.java` shows one declaration-level tag per record; no constructor/factory duplicates remain.

## Exact commands run and results

| Command | Result |
|---|---|
| `doxygen docs/Doxyfile` (focused Doxyfile, scope package only) | exit 0, 0 warnings/errors |
| minimal synthetic duplicate-`@param` record + doxygen | exit 1, exact "multiple @param documentation sections" error (reproduction) |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0; 297 tests, 0 failures/errors/skipped; `AttachmentAcceptanceTest` 28 tests OK; BUILD SUCCESS |
| `cd apps/patient-mobile && npm ci --no-audit --no-fund` | exit 0 (1272 packages) |
| `cd apps/patient-mobile && npm run typecheck && npm run lint && npm test -- --ci --runInBand` | exit 0; tsc clean, eslint clean; Jest 1 suite / 22 tests passed |

Pre-existing/environmental, out of scope (not caused by this change, not modified):
- `python3 -I -m unittest discover -s scripts/tests -q` → 162 tests, 6 failures / 4 errors. Failures include `test_repository_pack_is_valid`, `test_committed_opencode_json_is_generated`, `test_check_mode_detects_drift` (`opencode.json is stale`), `test_agent_run.*` (environment-specific git refs) and import errors for `test_fast_tier` / `test_workflow_concurrency`. The stale `opencode.json` is the documented cloud-worker CI-profile/skip-worktree artifact; the rest are unrelated to S-G3-06.
- Full `doxygen docs/Doxyfile` locally reports one error outside this scope: `apps/api/src/main/java/com/aurevia/smartclinic/g1/s_g1_04/TemplateService.java:23: unable to resolve link ... Actor for \link command`. Local doxygen is 1.9.4 (CI installs via apt); this is outside the declared scope and was left untouched.
- No local `main` ref is present, so the exact "merge of main and this branch" could not be reproduced locally; the integrator's failure log above is the authoritative red evidence. The integrator runs the database and integration tests; per worker rules only the fast unit tests were run here.

## Acceptance mapping (requirement → evidence)

- MSG-03: validate type/magic/size/checksum (`AttachmentInputs`, `FileType`), bind upload to a server-side verified connection grant and subject (`AttachmentService.upload`), explicit send of named clean files to one recipient unit (`publish`), quarantine verdict gating (`scan`), clean-verdict-only mediated downloads (`download`), removal/replacement (`remove`), and unit inbox of published patient-supplied material (`inbox`). Evidence: `AttachmentAcceptanceTest` 28 tests (Java fast tier), patient-mobile `attachments.test.tsx` 22 tests.
- ARC-06: private quarantine state machine, unique versioned object key, mediated handle carrying no bytes/file name/URL, and non-sensitive append-only event facts (`AttachmentContract`, `AttachmentService`, `AttachmentStore`). Evidence: same suites.
- Doxygen/doc gate: component `@param` documented once on each record declaration; scope docs build clean (commands above).

## Decisions

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

## Defects found

- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.

## Decision / stop notice

Per the task rule "if a decision or evidence is missing, stop and say so in
`.agent/pr-notes.md`": the merge-gate failure cannot be reproduced from this shallow
clone, the only supplied evidence is a benign log line from a passing out-of-scope
test, and there is no in-scope defect to fix. I therefore made **no code change** and
stop, committing only these notes. If the integrator still observes a non-zero
`scripts/check.sh` on the merge, the failing step and its exit code must be supplied;
if it is a genuine staff-web regression it belongs to a staff-web-scoped worker, not
this IAM-06 backend slice.

---

# S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering

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

