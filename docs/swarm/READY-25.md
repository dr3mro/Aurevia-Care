# READY-25 · Patients: /patients search and register, /patients/:id overview

PR #277, tested branch tip 777f73e2aa2abf969bb38ac46fc342a56b511245.

Task: READY-25

Swarm worker 3. Local checks: passed. Agent result: ok.

# READY-25 — Patients: /patients search and register, /patients/:id overview

## Summary

Implemented the two patient staff-web screens inside `apps/staff-web/src/features/patients`
on branch `swarm/READY-25-410` (start SHA `de837cd`):

- `PatientsPage` (`/patients`): tenant-scoped name search validated to at least two
  characters before any request; results table showing name (`MixedScriptText`),
  MRN, issuer and date of birth; a Register action opening a form (display name,
  MRN, issuer, DOB or coded unknown-date reason, language) that posts to
  `POST /api/v1/patients` and maps server validation codes into a linked error
  summary.
- `PatientOverviewPage` (`/patients/:id`): identity, server-computed likely-duplicate
  warnings from `PatientView`, and a link to `/patients/:id/chart`.
- `features/patients/routes.ts` self-registers both routes per READY-51; `app/routes.tsx`
  and `app/navigation.ts` were not touched. No new dependency, no migration, no
  push/tag/gh call. Synthetic fixtures only; no patient data in URLs, page titles,
  logs or browser storage.

## Affected requirements

- **PRD-01** — one longitudinal chart per patient per organization: the overview
  links to the existing `/patients/:id/chart`; search and registration are the
  entry points into that chart.
- **IAM-03** — issuer-scoped MRN, normalized search preserving original spelling,
  DOB-or-coded-unknown-reason, language, and server-provided likely-duplicate
  warnings. Local validation mirrors `PatientService` patterns.
- **IAM-04** — likely-duplicate candidates are displayed with identity and MRN and
  are never merged or linked by this screen; the worklist/consolidation workflow
  belongs to the operations queues story (READY-31).

Acceptance mapping:

| Story step | Implementation | Test |
|---|---|---|
| Read controllers/views; endpoints | `domain.ts` wire types from `Patient.java`/`PatientView.java`; `api.ts` uses `buildRequestDescriptor` for `GET /api/v1/patients?name=`, `POST /api/v1/patients`, `GET /api/v1/patients/{id}` | `api.test.ts` URL/method/header/body assertions |
| 1. `PatientsPage` search + results + register form | `PatientsPage.tsx`, `PatientsScreen.tsx`, `RegisterPatientForm.tsx`, `messages.ts` | `PatientsPage.test.tsx` (states, search, Arabic render, register success/validation/conflict) |
| 2. `PatientOverviewPage` identity + warnings + chart link | `PatientOverviewPage.tsx`, `PatientOverviewScreen.tsx` | `PatientOverviewPage.test.tsx` (identity, duplicate warning present/absent, chart href, RTL, tenant switch) |
| 3. Min-2-char search before send; no browser storage | `domain.ts > searchError`; loader resolves locally until a validated search; `useApi`/`ApiPage` keep results in memory only | `rejects a name shorter than two characters before any request is sent`; safety scan found no `localStorage`/`sessionStorage` |
| 4. Tests: each state, short search, register success/error, duplicate warning, Arabic | 76 new tests across 5 files | `npx vitest run src/features/patients/` → 5 files / 76 tests passed |

## Risk class

Cross-boundary (staff web ↔ real patient API) and safety-sensitive for patient
identity: wrong-patient association is a hazard. The screen never merges records,
never invents identifiers, shows the opaque id beside the name, and relies on the
server for duplicate detection and authorization. All tests use synthetic data;
no clinical or human pilot acceptance is claimed.

## Review owner

Senior React and Vite specialist and Senior TypeScript specialist (bounded static
reviews completed; see Findings). Integrator owns final acceptance.

## Data changes

None. No Flyway migration, backfill, lock or volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

- Tenant comes only from `useActiveOrganization()` and is sent as
  `X-Active-Organization` by the shared request builder; no request body carries
  organization, actor or author.
- Registration writes carry a fresh `Idempotency-Key` (`crypto.randomUUID()`) per
  user action; no `If-Match` is sent because the patient contract has no version
  precondition. A 409 renders the conflict state and keeps typed work.
- 403 renders `ForbiddenState`; 401 renders `SessionExpiredState`. The API remains
  authoritative for permissions and audit (`PATIENT_REGISTER`, `PATIENT_SEARCH`,
  `PATIENT_READ` on the server). No audit event is added or changed by this UI slice.
- Patient names and MRNs appear only in rendered cells, never in a URL, the page
  title, a log or browser storage. Routes use the opaque id only.

## Migration and rollout considerations

None. Both new routes are code-split (`PatientsPage` 1873 B gzip,
`PatientOverviewPage` 823 B gzip, both under the 250 KB WEB-08 budget). The shared
`useRole()` still reports the synthetic `reception` role until READY-11 lands; the
navigation model and the API remain the authorization gates.

## Test evidence

Commands executed, with final lines:

- TDD red: `npx vitest run src/features/patients/` → `Test Files  5 failed (5)`,
  `Tests  no tests` (the five new test files could not resolve their not-yet-written modules).
- TDD green (feature only): `npx vitest run src/features/patients/` →
  `Test Files  5 passed (5)`, `Tests  76 passed (76)`.
- Canonical frontend gate: `bash apps/staff-web/check.sh` → exit 0; `gen:api:
  generated client matches the contract`; `✓ built in 122ms`; every bundle chunk
  `ok`; `Test Files  54 passed (54)`; `Tests  616 passed (616)`;
  `Statements   : 100% ( 1553/1553 )`, `Branches     : 100% ( 784/784 )`,
  `Functions    : 100% ( 507/507 )`, `Lines        : 100% ( 1486/1486 )`.
- `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) final lines:
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Exit 0; no `error TS…`.
- `npm run lint` (`eslint . --max-warnings 0`) → exit 0, no output.
- `node scripts/check-bundle-size.mjs` → all chunks `ok`, including
  `PatientsPage-6yTVE3j1.js 1873 ok` and `PatientOverviewPage-BgHrs8E0.js 823 ok`.
- `python3 -I scripts/check-docs.py` → exit 0; `python3 -I scripts/check-names.py`,
  `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-wiring.py` → exit 0.
- Not run: `scripts/check.sh` at repository root, because it invokes
  `apps/api/check.sh` → `mvn -q -B verify` (the full OWASP/SpotBugs/JaCoCo plus every
  integration test), which this worker is explicitly forbidden to run; the
  integrator and Delivery run that gate. The mandated frontend check
  `bash apps/staff-web/check.sh` was run and passed.

Entry-point-to-test trace: `createRoutes()` in `src/app/routes.tsx` collects
`features/patients/routes.ts` → lazy `PatientsPage`/`PatientOverviewPage` →
`ApiPage` → `api.ts` descriptors → mocked `send()`. Proven by
`PatientsPage.test.tsx > lazily loads the registered patients page` and
`PatientOverviewPage.test.tsx > lazily loads the overview page with the bound patient id`.
Named command tests: `posts a registration and announces the created patient`,
`registers with a coded unknown-date reason instead of a date of birth`,
`links a server validation code to its field and keeps the typed values`,
`shows the conflict state for a 409 without losing the typed form`,
`shows a likely-duplicate warning with the candidate identity`.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step).
Result: `Test Files  54 passed (54)`, `Tests  616 passed (616)`,
`Statements 100% (1553/1553)`, `Branches 100% (784/784)`,
`Functions 100% (507/507)`, `Lines 100% (1486/1486)`.
Every new branch and its test:

`messages.ts`
- `dobReasonKey` `code ?? ''` defined vs missing → `maps every coded reason to its message key` / `falls back for an unmapped or missing code`.
- `dobReasonKey` mapped vs fallback → same two tests.
- `patientsMessage` placeholder present vs absent → `fills named placeholders and leaves unknown ones visible`
