# FIX-PATIENT-APPOINTMENT-ACTIONS — 2026-11-22

## Summary

Wired the two placeholder actions on the installed signed-in appointments page. Pressing **View details** now selects the exact row and shows a localized detail card with a Back control. Pressing **Revoke device** opens an explicit confirm/cancel step; Cancel sends nothing, Confirm issues exactly one authenticated `DELETE /api/v1/patient/appointments/devices/{id}?patientId=...`, then refreshes the tenant/patient-scoped device projection. 401 ends the session, an already-revoked device refreshes silently, and a transport failure keeps the device behind one generic message. No token, response body or clinical detail reaches the UI.

`Transport` exposes only GET/POST/PUT and parses JSON on every success, so a body-less 204 DELETE cannot be expressed through it. A real bounded authenticated DELETE adapter was built inside scope from the exported transport primitives; `src/api/transport.ts` was not edited and no request is faked as POST/PUT. The plan reviewer accepted the waiver of brief item 3 (a `ScreenStack` detail route): that shared component has no imperative push API and the page owns the loaded row, so page-local selection plus a real detail view with Back satisfies the acceptance without a new navigator dependency.

## Affected requirements

- PAT-01 — appointment details after authentication; generic push notices; device revocation.

## Risk class

Cross-boundary (patient-app UI plus authenticated command boundary), with defensive-security review because the change targets a device registration and ends sessions on 401. No clinical decision, signed data or prescribing is involved.

## Review owner

aurevia-senior-mobile (implementation), aurevia-plan-reviewer (plan), aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops (independent read-only stages).

## Data changes

None. No migration, backfill, table, policy or lock change. The server's `DELETE /api/v1/patient/appointments/devices/{tokenId}` already exists and audits `DEVICE_REVOKE` with `SUCCESS` in `PatientAppointmentService.revokeDevice`.

## Authorization boundaries and audit events

- The device id is encoded as a path segment and the verified patient id as a query parameter; the host supplies an already tenant-scoped device list, which `devicesForPatient`/`findEligibleDevice` index under the active organization before a pressed id can become a command target. Foreign, revoked and guessed ids resolve to no confirmation step.
- No credential, provider token or response text is stored, rendered or logged. The client reads the OS credential immediately before the request and reports only typed transport kinds plus an opaque correlation id.
- No audit event was added or changed; revocation audit remains server-side.

## Migration and rollout considerations

None. No contract or generated-client change is in scope; the DELETE route is already implemented on the API but is not yet published in OpenAPI (see Findings).

## Test evidence

Commands run from the repository root / module directory (tool evidence):

- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → `added 1408 packages`.
- `npx jest src/features/appointments/deviceClient.test.ts --no-coverage --runInBand` → red first (`Cannot find module './deviceClient'`), then `Test Suites: 1 passed, 1 total; Tests: 18 passed, 18 total`.
- `npx jest src/features/appointments/devices.test.ts --no-coverage --runInBand` → red on three new scoped-projection cases (`Test Suites: 1 failed...; Tests: 3 failed, 10 passed`), then green after implementation.
- `npx jest src/app/patientAppointmentActions.test.tsx --no-coverage --runInBand` → `Test Suites: 1 passed, 1 total; Tests: 7 passed, 7 total`.
- `npx jest src/app/patientDeviceRevocationGuard.test.tsx --no-coverage --runInBand` → `Test Suites: 1 passed, 1 total; Tests: 1 passed, 1 total`.
- `npx jest src/app/patientDeviceIsolationGuard.test.tsx src/features/appointments/appointmentActionsPresentation.test.tsx --no-coverage --runInBand` → green after fixing async-render assertions (`Test Suites: 2 passed; Tests: 8 passed`).
- `npx jest src/app/RootNavigator.test.tsx --no-coverage --runInBand` → `Test Suites: 1 passed, 1 total; Tests: 19 passed, 19 total`.
- `bash apps/patient-mobile/check.sh` → exit 0; `Test Suites: 87 passed, 87 total`; `Tests: 1197 passed, 1197 total`; `Time: 10.445 s`; no threshold, exclusion or skip introduced.

Typecheck final lines (tool evidence, `npm run typecheck` from `apps/patient-mobile`):

```
> patient-mobile@0.1.0 typecheck
> tsc --noEmit
```

Lint final lines (`npm run lint`):

```
> patient-mobile@0.1.0 lint
> eslint .
```

Entry-point-to-test trace: `RootNavigator` (signed-in tab, `RootNavigator.test.tsx` "opens the selected appointment detail from the installed signed-in tab") → `AppointmentsTab` → `AppointmentListPage` → `PatientResource` loader → `AppointmentListScreen` `details-{id}` press → `openDetails` → `AppointmentDetail`; device path: `AppointmentListScreen` `revoke-{id}` → `offerRevoke` (`findEligibleDevice`) → `DeviceRevocationConfirm` → `revoke` → `revokeDevice` → `createDeviceWriteClient.delete` → HTTP DELETE. The installed-page test asserts the selected detail, zero writes on Cancel, exactly one encoded DELETE on Confirm, the device refresh, and each failure branch.

## Coverage

Command: `npx jest --coverage --ci --runInBand` plus the canonical `bash apps/patient-mobile/check.sh` (both from `apps/patient-mobile`).

Result: 100% statements, branches, functions and lines on every touched production file; global thresholds met; 87 suites / 1197 tests. Per-file results: `patientPages.tsx` 100/100/100/100, `deviceClient.ts` 100/100/100/100, `devices.ts` 100/100/100/100, `AppointmentDetail.tsx` 100/100/100/100, `DeviceRevocationConfirm.tsx` 100/100/100/100, `AppointmentListScreen.tsx` 100/100/100/100, `patientDesign.tsx` 100/100/100/100. No test skipped, no threshold lowered, no exclusion or ignore comment added.

Branch → named test:

| Branch | Test |
|---|---|
| revokeDevice: 204 success, both ids encoded | deviceClient.test.ts `sends exactly one DELETE with both identifiers encoded into the patient-scoped path` |
| revokeDevice: 401 | `maps a 401 failure to unauthenticated without any response text` |
| revokeDevice: 404 NOT_FOUND | `maps a concealed device (404 NOT_FOUND) to alreadyRevoked` |
| revokeDevice: 409 / 412 conflict | `maps a 409 conflict to alreadyRevoked`, `maps a 412 conflict to alreadyRevoked` |
| revokeDevice: wrapped other error | `returns a typed failure for other errors and never exposes response text` |
| adapter: non-HTTPS origin / dev localhost on/off | `rejects a non-HTTPS origin before any request`, `allows a loopback origin only for development builds` |
| adapter: missing / blank credential, no fetch | `fails closed without a credential and never calls fetch`, `fails closed when the stored credential is blank` |
| adapter: credential read throws (controller undefined branch) | `wraps a credential-read failure as a typed network failure` |
| adapter: 204 ok, body not parsed, explicit language | `issues one authenticated DELETE for a body-less 204 without reading a body` |
| adapter: default language value | `defaults Accept-Language to English when the host omits it` |
| adapter: 401 / 404 / 409 / 412 / 500 | corresponding `throws a typed ...` tests |
| adapter: rejected fetch / aborted deadline | `wraps a rejected fetch as a typed network failure`, `maps an aborted deadline to a timeout failure` |
| adapter: timer cleanup on early failure (`timer !== undefined` both sides) | covered by the missing-credential tests and every error test; the deadline uses a captured non-null controller so there is no unreachable optional-chain arm |
| devices: scoped loader active/foreign/revoked/empty | devices.test.ts `loads only active devices of the verified patient in the active organization` |
| devices: re-resolve found / foreign / revoked / missing / empty | `re-resolves a pressed id and refuses foreign, stale and revoked devices` |
| devices: refresh target found / foreign / missing / empty, no mutation | `refreshes the offered devices after one revocation without touching a foreign row` |
| page: selected row found / not found, detail and Back | patientAppointmentActions.test.tsx `opens the exact row whose details control was pressed and closes with Back` |
| page: foreign/revoked rows not offered | `offers only active devices of the verified patient in the active tenant`; patientDeviceIsolationGuard.test.tsx `opens confirmation only for a device that resolves to this patient and tenant` |
| page: cancel / confirm / 401 / already-revoked / failure | the five named installed-page cases in patientAppointmentActions.test.tsx |
| page: double-press guard | patientDeviceRevocationGuard.test.tsx `drops a second Confirm that arrives before the first DELETE settles` |
| screen: selected cue and detailContent render | AppointmentListScreen.test.tsx plus installed-page tests |
| detail: confirmed null / present, facility fallback, Back, en/ar | appointmentActionsPresentation.test.tsx four AppointmentDetail cases |
| detail: dateLabel and timeLabel defined vs their fallbacks | `prefers the civil date and clock time supplied by the loader` (defined arms) and `shows the selected row and closes with Back in %s` (fallback arms) |
| confirm step: error null / present, pending on/off, Cancel and Confirm, en/ar | appointmentActionsPresentation.test.tsx two DeviceRevocationConfirm cases |
| design: every new key present in both locales | patientDesign.test.tsx `has a non-empty %s translation for every screen key` |

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing, patient association change, prescribing or critical result. All review evidence is tool output or bounded simulated reviewer verdicts; no human clinical or patient-participant evidence is claimed.

## Review verdicts

- aurevia-plan-reviewer: GO (first pass NO-GO on ScreenStack and the double-press test; both closed, and the ScreenStack waiver was accepted on the shared-component/push-API evidence)
- aurevia-devsecops: GO (no Critical or Required finding; one Optional dev-loopback-IP note, not adopted because the strict localhost rule is deliberately fail-closed; OWASP/SpotBugs not applicable to this frontend-only change)
- aurevia-code-reviewer: GO (reachability gap documented with the exact next step and task-requests; dead key removed; scoped-input invariant documented)
- aurevia-coverage-auditor: GO (all branch arms now named with asserting tests; unreachable optional chain removed; no suppression, skip or threshold change)

## Why this is not finished

- **Not end-to-end reachable in the shipped shell.** Tool evidence: `apps/patient-mobile/src/app/RootNavigator.tsx:353-359` builds `pageProps` with no device source, so `AppointmentListPage` seeds an empty device list and no `revoke-*` control renders in the installed app; the real `createDeviceWriteClient` default is reached only in unit tests. No command failed: `bash apps/patient-mobile/check.sh` exits 0 with 1197 passing tests and 100% coverage.
- **Most likely cause.** V1 has no server device-list GET (`PatientAppointmentController.java` exposes only device POST, DELETE and POST-invalid) and no in-app registry: `features/notifications/registration.ts` is purely functional, returning a new `Map` without storing it. There is no authoritative device source for the host to pass without inventing a registration, which would be fake data in production.
- **What was tried.** The page accepts a tenant-scoped host seed and injectable write port; the installed-page tests inject synthetic devices and prove detail selection, zero writes on Cancel, exactly one DELETE on Confirm, refresh, 401, already-revoked and transport failure. The real DELETE adapter sends the actual authenticated request; no POST/PUT substitution was used and `src/api/transport.ts` was not edited.
- **Exact next step.** Implement the first `task-requests` entry: wire the registered-device source (or a new scoped device-list endpoint) into `AppointmentsTab` and add an installed-shell test that renders a real revoke control. The second entry publishes the DELETE route in OpenAPI. Until then revocation is test-complete but not production-reachable.

## Findings

- No DELETE-capable transport and no server device-list GET exist (`src/api/transport.ts:108-113`; `PatientAppointmentController.java` exposes POST/DELETE/POST-invalid only). The real DELETE adapter is built in scope; the host-owned device seed is empty by default, so a clinic must supply a tenant-scoped registry before a patient sees a revoke control. `withRevokedDevice` is the deterministic V1 refresh. This is recorded as a follow-up below, not hidden.
- The backend conceals an already-revoked or unknown device as 404 `NOT_FOUND` (`PatientAppointmentService.java:154-162`); the brief also asks that 409/412 settle as `alreadyRevoked`, so conflict is mapped the same way. Other failures remain typed `failure`.
- The DELETE route is implemented by the API but absent from the published OpenAPI (`contracts/openapi/missing-routes.txt:6`); no contract or generated client was edited in this scope.
- Pre-existing, unrelated conflict markers are committed in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` on this base. They were present before this story, are outside scope and were not touched.
- `scripts/check.sh` was not run: it invokes the long Java verification owned by the integrator. The canonical module gate `bash apps/patient-mobile/check.sh` was run and passed.

```task-requests
[
  {
    "title": "Patient app: supply a tenant-scoped device registry to the appointments tab",
    "details": "The appointments page derives eligible devices from an injectable tenant-scoped seed and defaults to empty because no server device-list GET exists. Wire the installed RootNavigator tab to the registered device source (features/notifications) so a patient can actually see and revoke an active device, with tests proving only the verified patient's active devices in the active organization are offered.",
    "scope": ["apps/patient-mobile/src/app", "apps/patient-mobile/src/features/notifications", "apps/patient-mobile/src/features/appointments"],
    "depends": ["BUILD-53"]
  },
  {
    "title": "Document the patient device revocation route in the OpenAPI contract",
    "details": "DELETE /api/v1/patient/appointments/devices/{tokenId} is implemented by PatientAppointmentController but remains in contracts/openapi/missing-routes.txt line 6. Document it and the device POST routes from the controller with the shared error shape, security requirement and Idempotency-Key header, remove the baseline lines and regenerate the clients.",
    "scope": ["contracts/openapi.yaml", "contracts/openapi", "apps/staff-web/src/api/generated/openapi.d.ts"],
    "depends": ["FIX-OPENAPI-01"]
  }
]
```

## Lessons for workers

- The patient `Transport` has no DELETE and parses JSON on every success, so a 204 command needs its own bounded authenticated adapter; never route a body-less success through POST/PUT and call it a success.
- `findEligibleDevice` re-resolves a pressed local id against the host's tenant-scoped list; a flat device row carries no organization field, so the host must supply an already scoped list. Do not add an "other tenant" assertion to a flat-list unit test — it would be wrong by construction.
- React 19's `act` scopes in this module require `await render(...)` and `await fireEvent.press(...)`; un-awaited presses leave overlapping scopes and make unrelated assertions fail nondeterministically.
- `toHaveTextContent` matches the full string here; use a regex for a substring such as a time inside a formatted label.
- Earlier findings in this task's issue are watchdog/monitor noise; start from the code state and this notes file, not from the repeated "Ready now: CW-001" lines.
