# BUILD-53 · Patient app: push registration, generic notification text and invalid token handling

PR #308, tested branch tip bb1a45b1072e92e8b12acf868c9c392835162931.

Task: BUILD-53

Swarm worker 9. Local checks: passed. Agent result: ok.

# BUILD-53 — Patient app: push registration, generic notification text and invalid token handling

Task ID: `BUILD-53`. Stories worked: one (this one). Branch: `swarm/BUILD-53-419`, HEAD at start `b1445c7`. The orchestrator had already marked the task `[-]`, so this worker did **not** edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`, `AGENTS.md`, `.opencode/`, `scripts/` or workflows. No push, tag, `gh`, force-push or dependency change. All fixtures are synthetic (`org-a`/`org-b`, `patient-1`/`patient-2`, `synthetic-dev-1`); no patient data, real identifier or credential appears in code, tests or this note.

## Summary

Added a new patient push-notification domain module at `apps/patient-mobile/src/features/notifications/` (four new production files, three new test files). It is a pure domain kernel that reuses the appointments domain's tenant-scoped device registry and generic-notice types instead of duplicating them:

- `types.ts` — session, registration, delivery, inbox and logger contracts; re-exports the shared appointments primitives.
- `registration.ts` — `commandForSession` and `applyRegistration`: sign-in registers the device, sign-out revokes it through the appointments helpers.
- `payload.ts` — `buildPushPayload` and `notificationContent`: fixed generic text plus an opaque detail reference resolved only after the authentication gate in `detailAfterAuth`.
- `delivery.ts` — `dispatchNotice`: sends only to eligible devices, invalidates and logs a rejected token without ever logging its value, and returns the inbox unchanged on delivery failure.

No React screen was added: the story's acceptance criteria are domain behaviours and the required tests are logic-level, matching the established pre-wiring pattern of the patient-mobile G3 slices.

## Affected requirements

| ID | Behaviour | Where |
|---|---|---|
| PAT-01 | Push text is generic; detail is retrieved only after authentication | `payload.ts` (`buildPushPayload`, `notificationContent` reusing `buildGenericNotice` and `detailAfterAuth`); `payload.test.ts` |
| PAT-01 | Register device for push after sign-in; unregister at sign-out | `registration.ts`; `registration.test.ts` |
| PAT-01 | Log an invalid device token and stop using it | `delivery.ts` (`invalidateToken` + `NoticeLogger.invalidToken` + `withInvalidatedDevice`); `delivery.test.ts` |
| ARC-07 / MSG-05 | Notification failure leaves the item in the inbox | `dispatchNotice` returns `input.inbox` unchanged; `delivery.test.ts` |
| ARC-01 / ARC-02 | Tenant- and patient-scoped device selection; no cross-tenant or cross-patient dispatch | reuse of `eligibleTokens`, `tenantKey`, `withRegisteredDevice`, `withRevokedDevice`; negative tests in `registration.test.ts` and `delivery.test.ts` |
| OPS-02 | Provider token never reaches a log or notice | `invalidateToken` event contains only ids, reason and instant; asserted in `delivery.test.ts` |

## Risk class

Routine (contained patient-app domain addition, no deployment or shared-contract change). PAT-01 touches patient-facing notification content, so the generic-text and failure-path tests carry the safety weight; no new permission or clinical state machine was introduced.

## Review owner

`orevia-senior-mobile` (React Native/Expo, generic push notifications) with `orevia-test-engineer` for the branch map. Skill contents were loaded into this session; no additional reviewer was delegated because the work was bounded and all gates were executed directly.

## Data changes

None. No migration, schema, backfill or client dependency was added or changed. No reserved migration version was used.

## Authorization boundaries and audit events

No HTTP endpoint, credential or server-side authorization was added. Registration commands carry the active organization and the verified patient's device and delegate to the appointments domain's tenant- and patient-scoped helpers. The invalid-token log event is non-sensitive by construction (device id, patient id, bounded reason, instant) and is emitted through an injected `NoticeLogger`. No audit event was added or changed.

## Migration and rollout considerations

Not applicable — no persisted state, message schema or deployment topology changed. The module is an injectable domain kernel; wiring it to a native push provider and a real session store remains a separate integration story, as it does for the sibling patient-mobile slices.

## Test evidence

Evidence source: **executed tool commands by this worker** (no simulated user, clinician, reviewer or human participant).

Red phase — before production modules existed:

```
$ cd apps/patient-mobile && npx jest --runInBand --silent notifications
FAIL src/features/notifications/delivery.test.ts
FAIL src/features/notifications/registration.test.ts
FAIL src/features/notifications/payload.test.ts
  ● Test suite failed to run: Cannot find module './delivery' / './registration' / './payload'
Test Suites: 3 failed, 1 passed, 4 total
```

Green phase — targeted tests:

```
$ cd apps/patient-mobile && npx jest --runInBand --silent --testPathPatterns='features/notifications'
Test Suites: 3 passed, 3 total
Tests:       12 passed, 12 total
Snapshots:   0 total
Time:        0.67 s
```

Typecheck final lines (the required frontend evidence):

```
$ cd apps/patient-mobile && npm run typecheck
> patient-mobile@0.1.0 typecheck
> tsc --noEmit

TYPECHECK_EXIT=0
```

Strict lint (`npx eslint . --max-warnings=0`) exited 0 after moving the `import type` above the type re-export in `types.ts`; the first run reported one `import/first` warning, which was fixed rather than suppressed.

Canonical module gate:

```
$ bash apps/patient-mobile/check.sh
...
 .../notifications |     100 |      100 |     100 |     100 |
  delivery.ts      |     100 |      100 |     100 |     100 |
  payload.ts       |     100 |      100 |     100 |     100 |
  registration.ts  |     100 |      100 |     100 |     100 |
  types.ts         |       0 |        0 |       0 |       0 |   (type-only; no runtime code)

Test Suites: 1 failed, 43 passed, 44 total
Tests:       1 failed, 465 passed, 466 total
Snapshots:   0 total
Time:        11.71 s
Ran all test suites.
CHECK_EXIT=1
```

The single failure is the out-of-scope structural registry test `src/features/domain-layout.test.ts` (see **Why this is not finished**). All 12 new notification tests pass inside that run and every touched file is at 100%.

Root policy check:

```
$ bash scripts/check.sh
check-pack: opencode.json is stale: run scripts/gen-opencode.py
ROOT_CHECK_EXIT=1
```

This is the documented cloud-worker harness artifact, not caused by this change: `git ls-files -v opencode.json` reports `S` (skip-worktree) and `git diff HEAD -- opencode.json` is empty. `AGENTS.md` and the delivery skill record that `agent-run.sh` copies the CI profile over the worktree file.

Entry-point-to-test trace: `commandForSession` / `applyRegistration` → `registration.test.ts` (sign-in registers, sign-out revokes, tenant/patient isolation); `notificationContent` / `buildPushPayload` → `payload.test.ts` (generic notice, auth-gated detail); `dispatchNotice` → `delivery.test.ts` (invalid token dropped and logged, accepted token kept, empty recipient set, cross-tenant/patient exclusion, inbox retained on failure).

## Coverage

Focused command:

```
$ cd apps/patient-mobile && npx jest --runInBand --coverage --collectCoverageFrom='src/features/notifications/**/*.ts' --testPathPatterns='features/notifications'
All files        |     100 |      100 |     100 |     100 |
 delivery.ts     |     100 |      100 |     100 |     100 |
 payload.ts      |     100 |      100 |     100 |     100 |
 registration.ts |     100 |      100 |     100 |     100 |
 types.ts        |       0 |        0 |       0 |       0 |   (interfaces/types only; no executable statements)
Test Suites: 3 passed, 3 total
Tests:       12 passe
