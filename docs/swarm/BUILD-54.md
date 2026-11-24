# BUILD-54 · Patient app: OS-backed credential storage and no persistent clinical cache

PR #310, tested branch tip a8492d6bcbfb2afc0431d1be1d71f486809ba923.

Task: BUILD-54

Swarm worker 10. Local checks: passed. Agent result: ok.

# BUILD-54 — Patient app: OS-backed credential storage and no persistent clinical cache

## Summary

Added a transient, in-memory-only clinical cache, an idle-timeout sign-out guard, and a
re-authentication gate for sensitive account changes under `apps/patient-mobile/src/api`.
`clearToken` (the sign-out path already used by the app shell) now also discards all
transient clinical memory in a `finally` block, so sign-out and idle timeout leave no
clinical data in the process. Only the single session credential uses the OS secure store.

## Affected requirements

- PAT-02 (primary): OS-backed local credentials, no persistent clinical cache, authorize
  downloads, confirm intent for a local copy (download confirmation already existed in
  `features/reports/downloadFlow.ts` and was not changed).
- PAT-03 (supporting): renewed authentication for sensitive account changes.

## Risk class

Safety-critical (docs/WORKFLOW.md): patient credentials, authorization and clinical data
handling. Reviewed with synthetic data only; no real patient data or credentials used.

## Review owner

Senior React Native and Expo + Senior TypeScript (`aurevia-senior-mobile`,
`aurevia-senior-typescript` skills loaded; bounded simulated review via subagent).

## Data changes

None. No migrations, backfills or schema changes. No new dependency. No file changed
outside `apps/patient-mobile/src/api`.

## Authorization boundaries and audit events

Tenant/patient scope is unchanged. No new audit events. The only persistent value remains
the OS-backed session credential (`expo-secure-store` key `session` in `tokenStore.ts`,
key `aurevia.patient.session` in the existing `features/reports/credentials.ts`); clinical
payloads are process-local memory only.

## Migration and rollout considerations

None.

## Test evidence

Commands run from the repository root (tool evidence — executed by this worker):

- `bash apps/patient-mobile/check.sh` — PASS (also re-run after the specialist-review fixes).
  Final lines: `Test Suites: 44 passed, 44 total` / `Tests: 476 passed, 476 total` /
  `Time: 5.653 s`.
  Includes `npm ci`, `npm run typecheck`, `npm run lint`, `npm run test:coverage -- --ci`.
- `npm run typecheck` — exit 0, no output after the `tsc --noEmit` banner.
  Final lines: `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`.
- `npm run lint` — exit 0. Final lines: `> patient-mobile@0.1.0 lint` / `> eslint .`.
- Focused green run before implementation (red): `npx jest src/api/clinicalCache.test.ts
  src/api/idleSession.test.ts src/api/reauthentication.test.ts src/api/tokenStore.test.ts
  --runInBand` failed with `Cannot find module './clinicalCache'` etc. (4 suites, 0 tests) —
  the expected missing-behaviour failure.
- Focused green after implementation: `npx jest src/api --runInBand` → 5 suites /
  67 tests passed.

Entry-point-to-test trace: `clearToken()` (`src/api/tokenStore.ts`) is the sign-out entry
point already called by `src/app/RootNavigator.tsx`; `clinicalCache.test.ts` and
`tokenStore.test.ts` prove it clears memory. `createSignOutOnIdle`
(`src/api/idleSession.ts`) drives the same entry point on idle; `idleSession.test.ts`
proves the credential is removed and memory cleared after the idle deadline.
`createSensitiveActionGuard.authorizeSensitiveAction()` (`src/api/reauthentication.ts`)
is the sensitive-change decision point; `reauthentication.test.ts` proves stale/absent
sign-in returns `reauth_required`.

## Coverage

Command (focused, from `apps/patient-mobile`):
`npx jest src/api --runInBand --coverage --collectCoverageFrom='src/api/clinicalCache.ts'
--collectCoverageFrom='src/api/idleSession.ts' --collectCoverageFrom='src/api/reauthentication.ts'
--collectCoverageFrom='src/api/tokenStore.ts'`

Result: `All files 100% statements / 100% branches / 100% functions / 100% lines`;
each of `clinicalCache.ts`, `idleSession.ts`, `reauthentication.ts`, `tokenStore.ts` = 100% on
all four metrics. Full module gate also passed the 100% global threshold (476 tests).

New branches and the test that takes each:

- `clinicalCache.ts` — no `if`/ternary/switch/catch branches. Functions `keyFor`
  (via `cacheClinical`/`readClinical`), `cacheClinical`, `readClinical`, `clearClinicalCache`,
  `clinicalCacheSize` all exercised by `clinicalCache.test.ts` (store/read, namespace
  isolation, missing entry, no persistent write, clear).
- `idleSession.ts`, `arm()` guard `timer !== undefined` false (first arm) — every
  `createIdleSessionGuard` test; true (re-arm) — `idleSession.test.ts` "resets the deadline
  when touch is called after activity".
- `idleSession.ts`, deadline callback body (`timer = undefined; onIdle()`) —
  "calls onIdle only after the full idle interval elapses" and "does not fire onIdle again
  after the deadline has elapsed".
- `idleSession.ts`, `dispose()` guard `timer !== undefined` true — "cancels the deadline on
  dispose"; false — "allows dispose after the deadline has already fired".
- `idleSession.ts`, `createSignOutOnIdle` callback (`clearToken()`) — both
  `createSignOutOnIdle` tests ("signs out and clears memory…", "keeps the session when
  activity resets the deadline").
- `reauthentication.ts`, `||` left side true (`authenticatedAt === null`) — "requires
  re-authentication when no sign-in has been recorded".
- `reauthentication.ts`, `||` left false / right true (stale sign-in) — "requires
  re-authentication once the last sign-in is older than the window".
- `reauthentication.ts`, both sides false (fresh) — "allows a sensitive action when the
  last sign-in is within the window" and "exactly at the freshness boundary".
- `reauthentication.ts`, `recordAuthentication` — "refreshes the authentication time…";
  `reset` — "forgets the recorded authentication on reset…".
- `tokenStore.ts`, `try/finally` success outcome — existing "removes the token from the
  session key"; failure outcome — "still clears clinical memory when secure storage
  removal fails" (mocked `deleteItemAsync` rejects, cache still emptied).
- `tokenStore.ts`, `saveToken` blank guard `token.trim().length === 0` true — "refuses to
  store a blank credential"; false — every successful `saveToken` test.
- `idleSession.ts`, `createSignOutOnIdle` `.catch(() => undefined)` rejection path —
  "clears memory without an unhandled rejection when the keychain delete fails"
  (mocked `deleteItemAsync` rejects; memory cleared and no `unhandledRejection` event).

## Clinical and architecture review

No signed data, cross-organization sharing, patient association, prescribing or critical
result is produced by this slice; no real-clinical sign-off is claimed. A bounded simulated
`aurevia-senior-mobile` review was requested and returned static findings only (it did
not re-run the module gate); dispositions are below. All review evidence here is from
executed tools and simulated review, not a human participant.

## Specialist review findings and dispositions

- **F1 (High) — two credential stores / different keys.** `features/reports/credentials.ts`
  (outside scope) writes key `aurevia.patient.session`, while `tokenStore.ts` wrote `session`,
  so sign-out/idle could leave the reports download token in the keychain. **Fixed in scope:**
  `tokenStore.saveToken/loadToken/clearToken` now use the shared key
  `aurevia.patient.session`; `clearToken()` therefore removes the real credential. Added a
  blank-token guard (`INVALID_CREDENTIAL`) matching `SecureCredentialStore.save`. Test:
  `tokenStore.test.ts` key assertions + "refuses to store a blank credential". Full removal
  of the duplicate store is a follow-up for the owner of `features/reports` (out of scope).
- **F2 (High) — idle guard has no production caller.** Cannot be fixed inside `src/api`.
  **Disposition:** follow-up for the app-shell owner — instantiate `createSignOutOnIdle` at
  the root, call `touch()` on activity/`AppState`, `dispose()` on unmount. The guard's
  `onIdle` a
