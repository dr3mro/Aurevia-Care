# S-G3-01 — Patient appointment list, confirmation, generic push notifications and device revocation (PAT-01)

Worker branch: `swarm/S-G3-01-113`, forked from the single squashed snapshot commit `d79a776`. The orchestrator had already marked the task `[-]` in TODO.md; this worker did **not** edit TODO.md, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md or `docs/swarm`. No push, tag, `gh` or force-push was performed. All fixtures are synthetic (`org-a`/`org-b`, `patient-1`/`patient-2`, `synthetic-token`); no patient data or credentials appear in code, tests or this note.

## Scope and naming decision

- Story text names the API path `messaging/s_g3_01`, but CODING_STYLE.md forbids Java packages named after a story/task ID, and `TODO.md` + `.agent/pr-notes/REFACTOR-PACKAGES.md` record the canonical mapping `messaging/s_g3_01 → messaging/appointments` (all G3 siblings use `messaging/reports`, `messaging/units`, …). The API code therefore lives in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/appointments/`.
- Companion unit tests mirror that package under `apps/api/src/test/java/com/aurevia/smartclinic/messaging/appointments/`, exactly as the G3 sibling stories do. No other test path was touched.
- Mobile code is in `apps/patient-mobile/src/features/s_g3_01/`.
- **No V29 migration was created.** Reserved V29 is unused, matching every other G3 messaging slice (reports V30, enrollment V31, units V32, …), because this slice has no persistence adapter yet.
- 10 changed files total (9 code/test + this note), below the swarm's 15-file guard.

## Requirement and acceptance mapping

| ID | Behaviour | Where |
|---|---|---|
| PAT-01 | List appointments with facility time zone and status | `PatientAppointmentService.listAppointments`, `PatientAppointment`, `AppointmentStatus`, mobile `appointmentView`, `sortByScheduled`, `AppointmentListScreen` |
| PAT-01 | Confirm only where enabled; record a server timestamp | `PatientAppointmentService.confirm` (server-owned `confirmationEnabled`, `If-Match`-style version precondition, `Instant at`), mobile `deriveConfirmation` / `confirm` |
| PAT-01 | Status-change push with generic text; detail only after auth | `GenericPushNotice` (fixed template, no input, no resource id in deep link), `GenericPushNotice.reveals`, mobile `GENERIC_NOTICE`, `detailAfterAuth` |
| PAT-01 | Log invalid device tokens; support revocation | `recordInvalidToken`, `revokeDevice` (idempotent), `DeviceTokenStore.appendInvalidTokenLog`, mobile `invalidateToken` / `withInvalidatedDevice` / `withRevokedDevice` |
| PRD-03 / IAM-05 | Self-access only; one patient cannot touch another patient's device | new patient-ownership guards (see fixes below), verified-link recheck on every command |
| ARC-01 / ARC-02 | Tenant-scoped keys, verified link re-derived per command, fail closed | `authorize` at the top of all six commands; mobile `tenantKey` with NUL separator and tenant-prefix filtering |
| ARC-05 / OPS-02 | Generic notices, no PHI in push/audit/log, audit of allow and deny | fixed notice factory, provider token never passed to `AuditSink`, audit `DENIED` rows, bounded reason on invalid-token log |
| UX-01 | IANA zone retained and shown; RTL/localization is a separate story | `facilityTimeZone` reaches the screen; localized labels and RTL are deliberately deferred to S-G3-11 (see Decisions) |

## Test-first log (red → green)

All new behaviour was written as a failing test before production code:

1. **Cross-patient device mutation (mobile).** Red: `npm run typecheck` → `TS2554: Expected 3 arguments, but got 4` at `devices.test.ts` (new `withRevokedDevice(..., patientId)` / `withInvalidatedDevice(..., patientId)` calls) and `TS2322: Property 'patientId' does not exist` at six `AppointmentListScreen` renders. Green after adding the ownership parameter and screen filter.
2. **Confirmation disclosed an existing timestamp unauthenticated (mobile).** Red: `npx jest --runInBand --silent confirmation` → `1 failed, 9 passed`; expected `UNAUTHENTICATED`, received `idempotent` with `confirmedAt`. Green after the early auth guard.
3. **Backend security/audit fixes.** Red: `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=PatientAppointmentServiceTest test` → `COMPILATION ERROR … cannot find symbol: method findByTokenId(String)`. Green after adding the port method and its guards.
4. **Coverage regression from the new registration branch.** First green run showed JaCoCo `BRANCH missed=1` at `PatientAppointmentService.java:139` (same-patient re-registration). A new red/green test (`registerAllowsReRegisteringTheSamePatientsDeviceId`) restored 100%.

## Fixes applied (defects found and closed)

1. **High — cross-patient device revocation/invalidation (mobile).** `withDeviceState` keyed only by organization, so any valid local device id in the tenant could be revoked or invalidated regardless of owner, and `AppointmentListScreen` rendered a revoke control for every active device in the organization. Added a `patientId` ownership guard to `withDeviceState`/`withRevokedDevice`/`withInvalidatedDevice`, added a required `patientId` screen prop and filtered the rendered devices. Negative tests added for wrong-patient revoke/invalidate and for the screen control.
2. **Medium — cross-patient device-id rebinding (API + mobile).** `registerDevice` was an unscoped insert-or-replace and `withRegisteredDevice` overwrote an existing key, so a colliding `tokenId` could be rebound to another patient in the same tenant, stripping the displaced patient of revoke/invalidate access. Added `DeviceTokenStore.findByTokenId`, a 409 `DEVICE_CONFLICT` guard, and the equivalent mobile guard; positive same-patient re-registration stays allowed.
3. **Medium — fail-closed link recheck unverified on half the commands.** Added explicit 403/`FORBIDDEN` denial tests for `confirm`, `notifyStatusChange` and `recordInvalidToken`, each asserting no store write, gateway send or device write. Production already called `authorize()` on every command; these are regression tests that would catch its removal (line coverage alone would not).
4. **Medium — confirmation auth gap (mobile).** `confirm()` consulted `authenticated` only on the `pending` branch, so an unauthenticated replay of a `confirmed` state disclosed the stored timestamp. Authentication is now required on every branch.
5. **Low — wrong audit actor on push events.** `dispatch` audited `token.subject()` (the device owner) rather than the authenticated caller. Now threads the acting `subject` into `dispatch`; a test uses a different device owner to pin the correct actor.
6. **Low — duplicate invalid-token log rows.** `recordInvalidToken` unconditionally saved and logged a repeated provider disposition. It is now idempotent for an already-INVALID device while still auditing the retry.

## Exact commands and results

Executed locally in `/__w/Aurevia-Care/Aurevia-Care`, JDK 21.0.13, Maven 3.10.0, Node 22.23.5, Python 3.11, on branch `swarm/S-G3-01-113`.

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=PatientAppointmentServiceTest test` (after fixes) | **34 tests, 0 failures/errors, BUILD SUCCESS** |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` | **1552 tests, 0 failures/errors, exit 0** |
| JaCoCo report parsing of `apps/api/target/site/jacoco/jacoco.xml`, package `messaging/appointments` | INSTRUCTION 0 missed / 674 covered; BRANCH 0 / 54; LINE 0 / 102; COMPLEXITY 0 / 59; METHOD 0 / 32; CLASS 0 / 7 → **100%** |
| `npm run typecheck` (apps/patient-mobile) | exit 0 |
| `npm run lint` (apps/patient-mobile) | exit 0 |
| `npm run test:coverage -- --ci` (apps/patient-mobile) | **26 suites, 275 tests passed; all files 100% statements/branches/functions/lines** |
| `bash apps/patient-mobile/check.sh` | exit 0 (canonical module gate) |
| `bash scripts/frontend-check.sh` | exit 0 — "frontend standards: policy and tool commands passed" (includes Prettier and root Vitest) |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline`, `python3 -I scripts/project-name.py displayName` / `repositorySlug` | exit 0; "Aurevia Care" / "aurevia-care" |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK |

### Commands that did not pass, with cause

- `bash scripts/quick-check.sh` stops at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is a **worker-harness artifact**: `git ls-files -v opencode.json` reports `S` (skip-worktree), and the delivery skill documents that `agent-run.sh` copies the CI profile over the worktree file; `check-pack.py` therefore judges a stale profile. `git diff HEAD -- opencode.json` is empty and the file was not touched. The Java and policy steps of quick-check were run directly instead.
- `python3 -I -m unittest discover -s scripts/tests -q`: 164 tests, 10 not passing. Three `test_check_pack` failures are the same stale `opencode.json` artifact; two loader errors (`test_fast_tier`, `test_workflow_concurrency`) and `test_swarm` / `test_agent_run` failures assert live harness/repository state (e.g. `assertGreaterEqual(len(dispatchable), 10)` saw 9). None is in the touched scope and none was caused by this change.
- `mvn -q -B -f apps/api/pom.xml -Pfast -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check`: exit 1 with **6 pre-existing Medium findings, all outside this scope** (`messaging.conversations.ConversationRepository:180`, `messaging.governance.MessagingGovernanceController:48`, `patient.identity.IdentityProofingService:314`, `prescription.rx.RxController:47`, `prescription.rx.RxRepository:309`). **Zero findings in `messaging.appointments`.** Not fixed: AGENTS.md requires staying inside the declared scope and preserving unrelated work.
- `bash scripts/check.sh` / `scripts/docs.sh` / DB integration tests: not run by this worker. Doxygen, shellcheck and Docker are absent in this checkout, and the user assigned database and integration tests to the integrator.

## Review evidence and dispositions

Three bounded, read-only specialist reviews were commissioned (senior backend, senior mobile, independent QA). All findings were reviewer analysis of source; none involved a simulated user, clinician or human participant.

- Fixed: mobile cross-patient revocation and screen exposure; mobile registration rebinding (QA F2); mobile confirmation auth (mobile finding 2); backend registration rebinding and its coverage gap; backend fail-closed denial tests (backend F1, QA F4); backend audit actor (backend F3); backend invalid-token idempotency (backend F5).
- Deferred with rationale:
  - **QA F1 (blocker for G3 gate evidence, not a code defect in scope):** this slice is a pure domain kernel. The five collaborators are interfaces with no implementations, there is no controller/HTTP adapter, no V29 schema and no real push gateway, so none of the seven PAT-01 behaviours is reachable from an authenticated request at runtime. This is the deliberate pre-wiring pattern of every G3 messaging slice and is explicitly documented in `PatientAppointmentService` and `PushGateway`. **This worker cannot produce system-boundary evidence inside the declared scope; the integrator and the follow-on wiring/API stories must supply it before G3 acceptance.** Flagged here as required per the stop-and-report instruction.
  - **Backend F2 (push outbox/dedup):** synchronous dispatch has no durable outbox and no client dedup key. Deferred to cross-cutting story X-02 (durable events, outbox, dedup, replay); the slice has no persistence adapter yet.
  - **Backend F4 (status cross-check):** `notifyStatusChange` uses the supplied enum only for the audit action key; a wrong status would mislabel an audit event but cannot change generic push text. Low impact, deferred.
  - **Mobile findings 3 and 5:** localized status/RTL labels and a screen-level timestamp assertion are owned by S-G3-11 (iOS/Android flows, accessibility and RTL). The pure `confirm` timestamp path is covered by unit tests.
  - **Mobile finding 4 (bare local id in detail callback):** the authenticated app re-scopes through the server's tenant context and `detailAfterAuth` takes an opaque reference; changing the callback payload is a wiring decision for the integration story.
  - **Unconstrained `recordInvalidToken.reason`:** the parameter is documented as an internal non-sensitive disposition code and is bounded on the mobile client; HTTP-level boundary validation belongs to the future adapter (X-01/X-04), not this domain kernel.

## Evidence provenance

- Gate results, counts and coverage numbers: **executed tool commands** run by this worker (Maven, npm, Jest, JaCoCo XML, Python policy scripts).
- Defects and dispositions: **three simulated specialist reviewers**; no simulated-user, clinician or human-participant evidence is claimed.
- No real-patient or G5 launch evidence exists or is implied.
