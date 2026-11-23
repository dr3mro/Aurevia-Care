# FIX-PATIENT-APPOINTMENT-ACTIONS — fix pass (reachability, 2026-11-23)

## Summary

Completes the story's remaining acceptance gap. The earlier implementation wired appointment details and device revocation inside `AppointmentListPage` and proved every branch, but the installed signed-in shell (`RootNavigator`) built `pageProps` without a device source, so no `revoke-*` control rendered in the shipped app. This pass supplies that source through the real registration seam: `commandForSession` → `applyRegistration` → `eligibleTokens` produces the active devices of the verified patient under the active organization, and the result is passed to the page as its mount-time `initialDevices` seed. An injectable `deviceClient` port is forwarded only when a host supplies one.

The earlier root-typecheck repair (`e93f413a`) is retained; this pass does not repeat a test-only typing change. No API, database, contract, generated client, migration, threshold or test exclusion is touched.

## Affected requirements

- PAT-01 — verified adult patients see appointments; the app retrieves details after authentication; invalid device tokens are logged and device revocation is supported. This pass makes the revocation control reachable from the installed signed-in tab and proves tenant/patient scoping of the seeded device.

## Risk class

Cross-boundary (patient-app shell → appointments page → authenticated device command). Safety-relevant because the seeded device can become a destructive command target, so tenant, patient and state eligibility is enforced by the existing domain helpers and negative tests. No clinical decision, prescribing, release of information or signed data is involved.

## Review owner

- smartclinic-senior-mobile, smartclinic-senior-typescript — implementation specialists (skills loaded and brief applied).
- smartclinic-plan-reviewer — plan reviewed before code (first pass NO-GO on two Required gaps; both fixed, final GO).
- smartclinic-code-reviewer, smartclinic-coverage-auditor, smartclinic-devsecops — independent read-only stages after code, all GO.

## Data changes

None. No migration, backfill, table, policy, trigger, lock or volume impact. No OpenAPI or generated-client change; the server device DELETE route already exists and audits `DEVICE_REVOKE` server-side.

## Authorization boundaries and audit events

- The shell seeds only devices that survive `applyRegistration` and `eligibleTokens`: the tenant-key prefix from `SYNTHETIC_ORGANIZATION_ID`, `patientId === SYNTHETIC_PATIENT_ID` and `state === 'active'`. A foreign-patient or revoked registration produces no control; `patientPages.tsx` re-resolves a pressed id with `findEligibleDevice` before any confirmation, and `deviceClient.ts` encodes both identifiers into the scoped DELETE path.
- The synthetic token is a labelled fixture (`synthetic-device-token`) and is never rendered, logged or persisted by this change. No credential, provider token, response text or patient datum was added to code, tests, logs or this note.
- No audit event added or changed; revocation audit remains server-side.

## Migration and rollout considerations

None. This is a client-shell wiring change with no data migration or rollout gate. The synthetic seed stands in until account identity and an authoritative device source are wired (see task-requests).

## Test evidence

All commands run from the repository root unless a `cd` is shown. Evidence source: executed tool commands (tool evidence) plus bounded simulated reviewer verdicts; no simulated-user or human-participant evidence is claimed.

- RED first: `cd apps/patient-mobile && npx jest src/app/RootNavigator.test.tsx --no-coverage --runInBand` → `Test Suites: 1 failed, 1 total`; `Tests: 3 failed, 21 passed, 24 total`. The three new tests expecting a revoke control failed because the installed shell had no device source.
- GREEN after implementation, same command → `Test Suites: 1 passed, 1 total`; `Tests: 24 passed, 24 total`.
- Module typecheck final lines (tool evidence):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- Module lint final lines (tool evidence):
  ```
  > patient-mobile@0.1.0 lint
  > eslint .
  ```
- Root strict typecheck (`exactOptionalPropertyTypes` is enabled here, not in the module config) final lines (tool evidence):
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
- Canonical frontend gate: `bash apps/patient-mobile/check.sh` → exit 0; `All files | 100 | 100 | 100 | 100`; `Test Suites: 87 passed, 87 total`; `Tests: 1202 passed, 1202 total`; `Time: 9.804 s`. No threshold, exclusion or skip introduced.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0.
- `git merge --no-edit origin/main` → `Already up to date.` before and after the implementation stage; no conflict markers in changed TypeScript files.

Entry-point-to-test trace: `App` renders `<RootNavigator locale="en" />` with no device props → `RootNavigator` registers the synthetic signed-in device and projects eligible devices → `AppointmentsTab` → `AppointmentListPage` (mount-time seed) → `AppointmentListScreen` `revoke-{id}` control → `offerRevoke` (`findEligibleDevice`) → `DeviceRevocationConfirm` → `revoke` → `revokeDevice` → `createDeviceWriteClient.delete` → HTTP DELETE. The installed-shell test `sends one scoped DELETE and refreshes devices from the installed tab` asserts exactly one DELETE to `/api/v1/patient/appointments/devices/dev-1?patientId=synthetic-patient` and that the control disappears; the Cancel test asserts zero writes.

## Coverage

Command: `bash apps/patient-mobile/check.sh` from the repository root (canonical gate: `npm ci`, module typecheck, lint, Jest with enforced 100% coverage). Per-file numbers computed from `apps/patient-mobile/coverage/coverage-final.json` after that run.

Result: exit 0; `All files | 100 | 100 | 100 | 100`; global branches 1147/1147; `RootNavigator.tsx` statements 52/52, branches 24/24, functions 23/23. `Test Suites: 87 passed, 87 total`; `Tests: 1202 passed, 1202 total`. No test skipped, no threshold lowered, no exclusion, pragma, `@ts-ignore` or `any` added.

New/changed branch → named test:

| Branch | Test |
|---|---|
| `registeredDevice ?? SYNTHETIC_REGISTERED_DEVICE` — right arm (omitted, fallback used) | RootNavigator.test.tsx `offers the signed-in device for revocation on the installed appointments tab` asserts `revoke-dev-1` is present. |
| `registeredDevice ?? SYNTHETIC_REGISTERED_DEVICE` — left arm (explicit device) | `does not offer a device registered to another patient` and `does not offer a revoked device` assert no control. |
| `deviceClient !== undefined ? { deviceClient } : {}` — present | `sends no DELETE when the patient cancels on the installed tab` (zero writes) and `sends one scoped DELETE and refreshes devices from the installed tab` (exactly one scoped DELETE). |
| `deviceClient !== undefined ? { deviceClient } : {}` — absent | every default render, e.g. `renders the English application title` (asserts the title and that the shell renders). |
| plain-const projection (`applyRegistration`/`commandForSession`/`eligibleTokens`) | no new branch in this file; pure-helper calls are executed by every render, and the helpers themselves retain their existing 100% branch coverage. |

The red state was captured before the production change, so these assertions are meaningful rather than vacuous.

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing, patient-association change, prescribing or critical result. All review evidence is tool output or bounded simulated reviewer verdicts; no human clinical, patient-participant or simulated-user evidence is claimed.

## Review verdicts

- smartclinic-plan-reviewer: GO (first pass NO-GO on two Required gaps — conditional hook hazard and strict-optional property assignment; both fixed, re-reviewed GO)
- smartclinic-senior-mobile: GO (bounded wiring proposal; Critical hook-placement and Required typing/waitFor items incorporated)
- smartclinic-code-reviewer: GO (registration→projection chain, rules of hooks, reachability from `App`, strict typing and truthful comments verified)
- smartclinic-coverage-auditor: GO (both new branch arms mapped to named asserting tests; no skip/exclusion/threshold change)
- smartclinic-devsecops: GO (no Critical or Required finding; tenant/patient/state scoping and synthetic-only fixtures verified; OWASP/SpotBugs not applicable to this TypeScript-only diff)

## Findings

- The seeded device is an interim synthetic fixture, not an authoritative device source. V1 has no server device-list route and account identity is unwired, so the installed shell still registers `dev-1` for the synthetic session. Reachability and scoping are now proven, but a real registration source remains a follow-up (task-request below).
- The patient device DELETE route is implemented by the API but still absent from the published OpenAPI (`contracts/openapi/missing-routes.txt`); carried forward from the original note as a separate follow-up, not changed here.
- Optional reviewer findings not adopted: a shell-level guessed-device-id test (the page-level `patientDeviceRevocationGuard.test.tsx` already proves the synchronous guard, and a guessed id cannot enter the tenant-keyed seed) and a shared synthetic-device fixture factory (one device; the installed-path assertion is the drift guard).
- Pre-existing, unrelated conflict markers remain in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` on `origin/main`; they are outside this story and were not touched.
- `scripts/check.sh` was not run: it invokes the long Java verification owned by the integrator. The canonical module gate, both typechecks and the constraint check were run instead.

```task-requests
[
  {
    "title": "Patient app: supply an authoritative tenant-scoped device registration source",
    "details": "RootNavigator now seeds the signed-in device through commandForSession/applyRegistration/eligibleTokens, but the installed shell still uses a synthetic DeviceToken because V1 has no device-list route and account identity is unwired. Wire a real host-provided registration event stream (or an authorized scoped device-list endpoint) as the source of registeredDevice, keep the tenant/patient eligibility helpers as the authority, and add an installed-shell test proving only the verified patient's active devices in the active organization are offered.",
    "scope": ["apps/patient-mobile/src/app", "apps/patient-mobile/src/features/notifications", "apps/patient-mobile/src/features/appointments"],
    "depends": ["FIX-PATIENT-APPOINTMENT-ACTIONS"]
  },
  {
    "title": "Document the patient device revocation route in the OpenAPI contract",
    "details": "DELETE /api/v1/patient/appointments/devices/{tokenId} is implemented by PatientAppointmentController but remains in contracts/openapi/missing-routes.txt. Document it and the device POST routes with the shared error shape, security requirement and Idempotency-Key header, remove the baseline lines and regenerate the clients.",
    "scope": ["contracts/openapi.yaml", "contracts/openapi", "apps/staff-web/src/api/generated/openapi.d.ts"],
    "depends": ["FIX-OPENAPI-01"]
  }
]
```

## Lessons for workers

- When a value must exist on a child's first render (a mount-time `useState` initializer), compute it as a plain `const` after any early return. A `useMemo` placed after `if (signedOut)` is a conditional hook and breaks the sign-in-again path.
- The repository-root `tsconfig.json` enables `exactOptionalPropertyTypes` while the module config does not. Forward an optional prop with `...(value !== undefined ? { value } : {})`, never `value,` shorthand, and run both typechecks.
- A control is only reachable if the installed shell supplies its input; 100% coverage of the page does not prove the shell wires it. Trace from `App` through the shell in an installed-component test.
- For async React 19 press flows, `await fireEvent.press(...)` then `await waitFor(...)` on the observable outcome; a synchronous assertion after the press sees stale state.
- Earlier findings in this task's issue are watchdog/monitor noise; the authoritative diagnosis is the debugger report plus the original worker's note, and the two TS errors from `e93f413a` are already fixed.
