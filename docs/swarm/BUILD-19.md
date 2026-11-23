# BUILD-19 · Patient app: appointments with the facility time zone and confirmation

PR #292, tested branch tip efd50a61311f0fabf9220ac0f5584ada18d63d72.

Task: BUILD-19

Swarm worker 2. Local checks: passed. Agent result: ok.

# BUILD-19 — Patient app: appointments with the facility time zone and confirmation

Worker branch: `swarm/BUILD-19-418` (commit made on this branch, no push/tag/`gh`). The orchestrator had already marked the task `[-]` in tasks/; this worker did **not** edit a task in tasks/, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md, `docs/swarm`, `.opencode/`, `scripts/` or workflows. All fixtures are synthetic (`org-a`, `patient-1`, `America/New_York`, `synthetic-token`); no patient data, credential or real domain appears in code, tests or this note.

## Summary

The patient appointments kernel already implemented PAT-01's projection, confirmation, generic notice and auth-gated detail. This story closes the two outstanding acceptance items — a daylight-saving boundary test and a "cannot confirm twice" test — and makes the recorded confirmation time visible to the patient in the facility time zone, which the design reference calls for ("Confirmed with the time", `docs/design/approved/README.md`).

Production changes (all inside `apps/patient-mobile/src/features/appointments`):
- `appointments.ts`: `AppointmentView` now carries `confirmedLocal: string | null`; `appointmentView()` renders the recorded `confirmedAt` instant through the injected facility-zone `ZoneFormatter`, exactly as it renders the appointment start. This surfaces the recorded time in the facility zone without storing a derived local value.
- `AppointmentListScreen.tsx`: the confirmed badge now shows `Confirmed at <facility-local time>` instead of a bare `Confirmed`; the confirm control is still only rendered for `kind: 'pending'`, so a confirmed visit still cannot offer a second confirmation.

Test changes:
- `appointments.test.ts`: DST spring-forward boundary test using the same real `Intl.DateTimeFormat`/IANA formatter the authenticated app injects; fall-back-overlap test proving the appointment and its confirmation both derive from the IANA zone while the UTC instant stays unchanged; confirmed-state projection test for `confirmedLocal`.
- `confirmation.test.ts`: full derive → confirm → reload → confirm flow proving the second attempt returns `idempotent` with the first recorded timestamp.
- `AppointmentListScreen.test.tsx`: fixtures carry `confirmedLocal`; the confirmed case asserts the rendered time.

## Affected requirements

- **PAT-01** — list appointments with facility time zone and status; confirm where enabled and record the time; generic push text; details after authentication.
- **UX-01** — UTC instants with IANA time-zone context; daylight-saving transition test.

## Acceptance mapping

| Requirement / story step | Implementation | Test |
|---|---|---|
| PAT-01: each appointment in the facility zone with its status | `appointmentView()` (`localStart`, `timeZone`, `status`), `AppointmentListScreen` `time-`/`status-` rows | `appointments.test.ts` "keeps the facility time zone…"; `AppointmentListScreen.test.tsx` "renders status and facility time zone…" |
| PAT-01: confirm where enabled, record the time | `deriveConfirmation()`, `confirm()`; `AppointmentView.confirmedLocal`; confirmed badge shows the time | `confirmation.test.ts` derive/`confirm` cases; `AppointmentListScreen.test.tsx` "shows a confirmed visit…" now asserts `12:00` |
| PAT-01: notification text generic; details after sign-in | `notifications.ts` (`GENERIC_NOTICE`, `detailAfterAuth`) — unchanged, still covered | `notifications.test.ts` (pre-existing) |
| Story step 4: daylight-saving boundary | Real IANA formatter through `appointmentView()` | `appointments.test.ts` "…spring-forward transition" and "…fall-back overlap" |
| Story step 4: confirmed appointment cannot be confirmed twice | `confirm()` idempotent branch; confirm control hidden once `kind: 'confirmed'` | `confirmation.test.ts` "cannot confirm twice: replaying…"; `AppointmentListScreen.test.tsx` "shows a confirmed visit…" |

## Risk class

Routine (docs/WORKFLOW.md): a contained patient-app presentation/domain projection change with no API, database, contract or deployment boundary. Synthetic data only.

## Review owner

Senior React Native and Expo / Senior TypeScript (skills loaded). No clinical or safety-critical review required: no signed data, prescription, critical result or patient association is introduced.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback/forward-fix needed.

## Authorization boundaries and audit events

No new authorization boundary or audit event. Confirmation remains gated by `confirm(state, authenticated, at)` returning `UNAUTHENTICATED` on every branch before any timestamp or eligibility is disclosed, and by server-owned `confirmationEnabled`/status in `deriveConfirmation()`. Tenant-local identifiers are still scoped by `tenantKey()`. The recorded time is a UTC instant written by the server; the client only formats it for display.

## Migration and rollout considerations

None. No feature flag, schema change or rollout step.

## Test evidence

All commands run from the repo root unless noted. Targeted:

- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → `added 1408 packages in 14s`.
- `cd apps/patient-mobile && npm run typecheck` → exit 0. Final lines:
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
  No `error TS…`.
- `cd apps/patient-mobile && npm run lint` → exit 0 (no output).
- `cd apps/patient-mobile && npx jest --runInBand src/features/appointments` → `Test Suites: 6 passed, 6 total`, `Tests: 38 passed, 38 total`.

Mandatory full frontend gate:

- `bash apps/patient-mobile/check.sh` → **exit 0**. Final lines:
  ```
  Test Suites: 41 passed, 41 total
  Tests:       458 passed, 458 total
  Snapshots:   0 total
  Time:        3.453 s, estimated 10 s
  Ran all test suites.
  ```
  (typecheck final lines, verbatim from the same script: `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`, exit 0.)

Entry-point-to-test trace: `providers.ts:loadAppointments()` (authenticated call) → `appointmentView()` → `AppointmentListScreen` `time-`/`status-`/`confirmed-` rows → `AppointmentListScreen.test.tsx` and `appointments.test.ts`. The confirmation flow: `deriveConfirmation()` → `confirm()` → `confirmation.test.ts` "cannot confirm twice…".

No `jest.*` ambient globals are used in the changed tests, so no `@jest/globals` import is required.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (runs `npm run test:coverage -- --ci` with the repo's 100% global thresholds in `jest.config.ts`). Result: **100% statements / branches / functions / lines on every touched production file**; global 100% (458 tests, 41 suites). Every touched file in the report:

| File | Stmts | Branch | Funcs | Lines |
|---|---|---|---|---|
| `src/features/appointments/appointments.ts` | 100 | 100 | 100 | 100 |
| `src/features/appointments/confirmation.ts` | 100 | 100 | 100 | 100 |
| `src/features/appointments/AppointmentListScreen.tsx` | 100 | 100 | 100 | 100 |
| `src/features/appointments/types.ts` | 100 | 100 | 100 | 100 |

New or changed branches and the test that takes each side:

- `appointmentView()` `confirmedLocal = confirmation.kind === 'confirmed' ? formatter.format(...) : null`:
  - `confirmed` side — `appointments.test.ts` "formats the recorded confirmation instant in the facility zone when confirmed" (expects the formatted value) and "renders both the appointment and its confirmation … across the fall-back overlap".
  - `else`/`null` side — `appointments.test.ts` "keeps the facility time zone, status and derived confirmation state" (expects `confirmedLocal: null`) and both spring-forward cases (pending).
- `AppointmentListScreen` confirmed branch (`kind === 'confirmed' ? <Text> : null`): no new branch; its content assertion was strengthened in "shows a confirmed visit without a confirmation action" (expects `12:00`), and the `null`/pending/unavailable sides remain covered by the pre-existing screen tests.
- `confirmation.
