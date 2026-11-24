# BUILD-20 · Patient app: released reports with revision and per-download authorization

PR #296, tested branch tip 64245458a3729998ddb4fb3d3f47d737e5f1daa2.

Task: BUILD-20

Swarm worker 3. Local checks: passed. Agent result: ok.

# BUILD-20 — Patient app: released reports with revision and per-download authorization

## Summary

PAT-02 already had the report list, correction context, OS-backed credential storage and
user-intent confirmation in `apps/patient-mobile/src/features/reports`. The missing property was
that a download authorization was only bound to the report id and revision. This change binds every
download to the authenticated patient and to a still-current release: the client is constructed for
one patient, and each server authorization must carry a matching `patientId` and a `releaseState` of
`current` before any document reference is produced. A withdrawn release or a wrong-patient
authorization now fails closed with `RELEASE_WITHDRAWN` / `WRONG_PATIENT`.

Scope kept to `apps/patient-mobile/src/features/reports`. No dependency, migration or file outside
scope was changed. Synthetic identifiers only (`report-1`, `patient-1`, `patient-2`, `Clinic A`).

## Affected requirements

- PAT-02 — Show released reports with revision, issuing organization, effective date and correction
  context. Authorize every download against the linked patient and current release. Protect local
  credentials with OS-backed storage. Avoid persistent clinical caches. Confirm user intent for
  saving a local copy.

Acceptance mapping:
- "revision, issuing organization, effective date and correction context" — already implemented in
  `ReleasedReportsScreen.tsx` / `presentation.ts`; proven by `reports-screen.test.tsx`
  `shows revision, issuer, effective date, correction context and language` and
  `report-core.test.ts` `shows correction context only when both parts exist`.
- "Every download checks the linked patient and the current release" — `ReleasedReportClient` is
  bound to one patient and `parseAuthorization` (the single choke point for every
  `authorizeDownload`) checks `patientId` and `releaseState`.
- "a withdrawn release stops access" — `releaseState === 'withdrawn'` throws `RELEASE_WITHDRAWN`;
  no `DownloadAuthorization` is returned.
- Tests "withdrawn release", "wrong patient", "corrected report shows its context" — all present
  (see Test evidence).

## Risk class

Safety-critical (docs/WORKFLOW.md): patient-link / access authorization on a clinical document
download. Defensive, fail-closed behavior; the server remains the authority for the release and
patient link. No real clinical or patient sign-off was obtained — simulated engineering review only.

## Review owner

Primary agent (integrator). Bounded specialist review requested from `orevia-senior-mobile`.
No human clinical or privacy reviewer participated.

## Data changes

None. No database migration, backfill, lock or volume impact; no rollback needed.

## Authorization boundaries and audit events

- Client-side defense in depth only: the server still owns the verified patient link and the
  current-release decision. The client refuses to emit a document reference unless the
  authorization names the patient this client is bound to and reports a current release.
- No new audit events. No patient data in logs, URLs or alert payloads: the native confirmation
  alert carries generic text only (unchanged). Token stays in `expo-secure-store`; no clinical
  cache was introduced.

## Migration and rollout considerations

None beyond the normal patient-app release. Callers composing `ReleasedReportClient` must now pass
the authenticated patient id as the third constructor argument; the only caller in this repository
is the test suite (app composition is outside this task's scope). Failing closed on an unset or
wrong patient id is intentional.

## Test evidence

Evidence source: executed tool commands (local), plus a simulated senior-mobile specialist review.
No simulated-user (patient) run and no human participant evidence were gathered for this change.

Entry-point-to-test trace: `ReleasedReportClient.authorizeDownload(reportId, revision)` (protected
POST `/api/v1/released-reports/{id}/download`) -> `parseAuthorization(body, reportId, revision,
this.patientId)` -> `WRONG_PATIENT` / `RELEASE_WITHDRAWN`. Proved by
`report-core.test.ts` `ReleasedReportClient (PAT-02)` tests
`stops download access when the current release has been withdrawn` and
`denies a download authorized for a different linked patient`; the screen-to-authorization path
is proved by `reports-screen.test.tsx` `screen to authorization integration (PAT-02)`.

Commands run (from `apps/patient-mobile`, then repo root):

1. Red (before production change):
   `npx jest --ci --runInBand src/features/reports/report-core.test.ts src/features/reports/reports-screen.test.tsx`
   -> `Test Suites: 1 failed, 1 passed, 2 total; Tests: 4 failed, 23 passed, 27 total`
   (malformed-shape loop + withdrawn + wrong-patient assertions failed as expected).
2. Green (targeted):
   `npx jest --ci --runInBand src/features/reports/report-core.test.ts src/features/reports/reports-screen.test.tsx`
   -> `Test Suites: 2 passed, 2 total; Tests: 27 passed, 27 total`.
3. Typecheck: `npm run typecheck` -> `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`, exit 0,
   no output (no `error TS…`).
4. TARGETED COVERAGE:
   `npx jest --ci --runInBand --coverage --collectCoverageFrom='src/features/reports/*.{ts,tsx}' 'src/features/reports/.*\.test\.'`
   -> `All files | 100 | 100 | 100 | 100`; `reportClient.ts | 100 | 100 | 100 | 100`.
5. FULL MANDATORY GATE (from repo root): `bash apps/patient-mobile/check.sh`
   - typecheck final lines: `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`
   - lint final lines: `> patient-mobile@0.1.0 lint` / `> eslint .`
   - coverage: `All files | 100 | 100 | 100 | 100`;
     `...atures/reports | 100 | 100 | 100 | 100` with `reportClient.ts | 100 | 100 | 100 | 100`
   - `Test Suites: 41 passed, 41 total`
   - `Tests:       457 passed, 457 total`
   - `All files | 100 | 100 | 100 | 100`; `reportClient.ts | 100 | 100 | 100 | 100` — exit 0.
   (Final full-gate rerun after the reviewer finding fix; earlier run was 456 tests, also green.)

Type-only `types.ts` files report `0 | 0 | 0 | 0` (no executable statements); this is the existing
repository pattern and does not lower the 100% global threshold. No threshold, exclusion, `@ts-ignore`,
skip or disabled test was added.

## Coverage

Command (full area touched): `bash apps/patient-mobile/check.sh` plus the targeted coverage command
above. Result: 100% statements / branches / functions / lines for every file with runtime code
(`reportClient.ts` 100/100/100/100; all reports production files 100%).

New branches and the test that takes each side:

| New branch (production) | Test |
|---|---|
| `isReleaseState`: `value === 'current'` true | `authorizes a download through the server with the exact revision` (fixture `releaseState: 'current'`, resolves) |
| `isReleaseState`: first false, `value === 'withdrawn'` true | `stops download access when the current release has been withdrawn` |
| `isReleaseState`: both comparisons false | `rejects every malformed authorization shape` (`releaseState: 'pending'` and `releaseState: null`) |
| field guard `\|\| !isText(patientId)` | `rejects every malformed authorization shape` (`patientId: ''`, `patientId: 7`) |
| field guard `\|\| !isReleaseState(releaseState)` | `rejects every malformed authorization shape` (`releaseState: 'pending'`, `releaseState: null`) |
| `if (patientId !== expectedPatientId)` true | `denies a download authorized for a different linked patient` |
| `if (patientId !== expectedPatientId)` false | `authorizes a download through the server with the exact revision` (matching patient, resolves) |
| `if (releaseState === 'withdrawn')` true | `stops download access when the current release has been withdrawn` |
| `if (releaseState === 'withdrawn')` false | `authorizes a download through the server with the exact revision` (matching patient, resolves) |
| returned authorization now ca
