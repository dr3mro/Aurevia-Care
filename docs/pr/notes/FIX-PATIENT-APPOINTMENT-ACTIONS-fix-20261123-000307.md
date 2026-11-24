# FIX-PATIENT-APPOINTMENT-ACTIONS — fix pass (2026-11-23)

## Summary

Repairs the red integration gate left on this branch by the original implementation. The compiler at the repository root (`tsconfig.json`, `exactOptionalPropertyTypes: true`) reported exactly two `error TS…` failures, both in newly added test files. No production code, contract, migration, configuration, coverage threshold or exclusion was changed; no assertion or test behavior was weakened. The two edits make the optional/absent intent explicit in the type system so the root typecheck, the module gate and the integrator gate all pass without a suppression.

The full implementation context (acceptance, entry-point-to-test trace, branch map, deferred follow-ups) remains in the original worker's note `docs/pr/notes/FIX-PATIENT-APPOINTMENT-ACTIONS-20261122-223421.md`, which this fix pass does not supersede.

## Affected requirements

- PAT-01 — verified adult patients see appointments and released reports; appointment details and device revocation in the patient app. This pass changes only the static typing of two tests for that story.

## Risk class

Routine repair of a test-only static-typing defect on a cross-boundary story. No clinical decision, identity, permission, prescribing, release-of-information or data-migration behavior is touched.

## Review owner

- aurevia-senior-mobile / aurevia-senior-typescript — matching implementation specialists (skills loaded).
- aurevia-plan-reviewer — plan reviewed before code (VERDICT: GO).
- aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops — independent read-only stages after code.

## Data changes

None. No migration, backfill, table, policy, trigger or lock change. No OpenAPI, generated client or contract change.

## Authorization boundaries and audit events

None changed. Tenant/patient scoping, the authenticated DELETE adapter, 401 session termination and server-side `DEVICE_REVOKE` auditing are exactly as implemented and reviewed in the original note. No credential, provider token, response text or patient datum is added to code, tests, logs or these notes.

## Migration and rollout considerations

None. This is a test-source typing repair with no runtime effect.

## Changes in this pass

1. `apps/patient-mobile/src/features/appointments/appointmentActionsPresentation.test.tsx` — in `falls back to the status when no facility line was supplied`, replaced `appointment={{ ...row, facilityLine: undefined }}` with a rest-omitting destructure `const { facilityLine: _omitted, ...withoutFacility } = row;` and `appointment={withoutFacility}`, with a comment explaining why the key must be absent rather than explicitly `undefined`. Root cause: `AppointmentView.facilityLine?: string` (`appointments.ts:29`) does not accept an explicit `undefined` under `exactOptionalPropertyTypes` (TS2375); `AppointmentDetail` renders `facilityLine ?? status`, so omitting the key still exercises the fallback and the unchanged `'booked'` assertion.
2. `apps/patient-mobile/src/features/appointments/deviceClient.test.ts` — in `allows a loopback origin only for development builds`, widened only the test-local `finally` restore cast from `{ __DEV__?: boolean }` to `{ __DEV__?: boolean | undefined }`, with a comment. Root cause: `previous` is read from an optional flag and is therefore `boolean | undefined`; writing that back to an optional `boolean` property is TS2412. Lines 147/149/156 are unchanged; no production type was widened.

## Test evidence

All commands below were run from the repository root unless noted (tool evidence). Red state reproduced before the edits, green state after.

- `npm ci --no-audit --no-fund` (root): exit 0, `added 164 packages`.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund`: exit 0, `added 1408 packages`.
- `cd apps/staff-web && npm ci --no-audit --no-fund`: exit 0, `added 486 packages` (root tsconfig includes staff-web sources).
- RED `npm run typecheck` (root): exit 2, exactly two errors — `appointmentActionsPresentation.test.tsx(78,9): error TS2375` and `deviceClient.test.ts(164,7): error TS2412`.
- RED baseline (type-only defect): `cd apps/patient-mobile && npx jest src/features/appointments/appointmentActionsPresentation.test.tsx src/features/appointments/deviceClient.test.ts --no-coverage --runInBand` → `Test Suites: 2 passed, 2 total`, `Tests: 27 passed, 27 total`.
- GREEN `npm run typecheck` (root): exit 0. Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
- GREEN `cd apps/patient-mobile && npm run typecheck`: exit 0. Final lines (required module typecheck evidence):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- `cd apps/patient-mobile && npm run lint`: exit 0. Final lines:
  ```
  > patient-mobile@0.1.0 lint
  > eslint .
  ```
- `./node_modules/.bin/prettier --check <the two touched files>`: exit 0, `All matched files use Prettier code style!`
- `cd apps/patient-mobile && npx jest src/features/appointments/appointmentActionsPresentation.test.tsx src/features/appointments/deviceClient.test.ts --no-coverage --runInBand`: exit 0, `Test Suites: 2 passed, 2 total`, `Tests: 27 passed, 27 total`.
- `bash apps/patient-mobile/check.sh`: exit 0, `Test Suites: 87 passed, 87 total`, `Tests: 1197 passed, 1197 total`, `All files | 100 | 100 | 100 | 100`. No threshold, exclusion or skip introduced.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base`: exit 0.

Entry-point-to-test trace for the underlying story is unchanged and recorded in the original note: `RootNavigator` → `AppointmentsTab` → `AppointmentListPage` → `AppointmentListScreen` `details-{id}` → `openDetails` → `AppointmentDetail`, and `revoke-{id}` → `offerRevoke` → `DeviceRevocationConfirm` → `revoke` → `revokeDevice` → `createDeviceWriteClient.delete` → HTTP DELETE. The two touched tests are the presentation cases for the detail fallback and the development-loopback guard of that adapter.

## Coverage

Command: `bash apps/patient-mobile/check.sh` from the repository root (canonical gate: `npm ci`, `npm run typecheck`, `npm run lint`, `npm run test:coverage -- --ci`).

Result: exit 0; `All files | 100 | 100 | 100 | 100` (statements/branches/functions/lines); `Test Suites: 87 passed, 87 total`; `Tests: 1197 passed, 1197 total`. Touched production files remain at 100/100/100/100: `patientPages.tsx`, `deviceClient.ts`, `devices.ts` (and, from the original implementation, `AppointmentDetail.tsx`, `DeviceRevocationConfirm.tsx`, `AppointmentListScreen.tsx`, `patientDesign.tsx`).

This pass adds or removes no production branch, so no new branch-to-test mapping is introduced. The two changed lines are already executed by asserting tests:

| Changed line | Executing test and assertion |
|---|---|
| `appointmentActionsPresentation.test.tsx` — `appointment={withoutFacility}` | `falls back to the status when no facility line was supplied` asserts `detail-facility` contains `booked`, proving the `facilityLine ?? status` fallback with the key absent. |
| `deviceClient.test.ts` — widened `__DEV__` restore | `allows a loopback origin only for development builds` sets the flag true (no throw) then false (throws `/HTTPS/`) and restores it in `finally`; the surrounding suites pass with the restore executed. |

No test skipped, no threshold lowered, no exclusion or coverage-ignore comment added; no `@ts-ignore`/`@ts-expect-error`.

## Findings

- The module-local `apps/patient-mobile/tsconfig.json` does not enable `exactOptionalPropertyTypes`; the repository-root `tsconfig.json` does. A green module `npm run typecheck` or even `bash apps/patient-mobile/check.sh` therefore does not prove the root gate green. This is exactly how the earlier run false-greend and is now captured as a lesson.
- Pre-existing and out of scope: committed conflict markers remain in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` (from an earlier `origin/swarm/BUILD-37-469` merge). They were present before this task, are unrelated to the patient app and were not touched.
- Carried forward from the original note (not fixed here, separate follow-ups already requested): the installed `RootNavigator` supplies no device source so no `revoke-*` control renders in the shipped shell, and the patient device DELETE route is implemented but not yet published in OpenAPI. Neither is in this fix pass's scope and neither causes a gate failure.
- `scripts/check.sh` was not run: it invokes the long Java verification owned by the integrator. The canonical module gate and the exact failing root typecheck were run instead.

## Lessons for workers

- For any frontend change, run the root `npm run typecheck` (`tsc -p tsconfig.json`) as well as the module gate. `exactOptionalPropertyTypes` is enabled only at the root; a module-local green is not proof.
- To express "optional property absent" in a typed fixture, omit the key with a rest destructure (`const { key: _unused, ...rest } = row;`). Do not write `key: undefined` when the target type is `key?: T` — it fails TS2375 under strict optional typing.
- When a test captures an optional global flag and restores it in `finally`, the restore cast must admit `undefined` (`{ __DEV__?: boolean | undefined }`), or TS2412 fails under the root config. Keep the widening test-local; never widen a production type to satisfy a test.
- The earlier findings in this task's issue are watchdog/monitor noise; the authoritative diagnosis is the debugger report with the two exact TS errors and the original worker's note.

## Review verdicts

- aurevia-code-reviewer: GO (test-only diff; no Critical, Required or Optional finding; types made honest without widening production types or weakening assertions)
- aurevia-coverage-auditor: GO (no production branch added or removed; facility fallback and dev-loopback branches still asserted; no skip, exclusion or threshold change)
- aurevia-devsecops: GO (test-only diff; no PHI, secrets, dependency, isolation or audit change; OWASP/SpotBugs not applicable to a frontend test-only pass; no tool run by the read-only reviewer)
