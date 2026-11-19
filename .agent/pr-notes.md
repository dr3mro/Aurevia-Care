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
- The worktree arrived with a stale `.agent/pr-notes.md` from S-G1-07 (recycled worker image); it was replaced with this file. This file is gitignored and must be force-added (`git add -f .agent/pr-notes.md`) alongside the scoped commit.
