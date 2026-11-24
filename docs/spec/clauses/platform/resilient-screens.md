# WEB-09 clause 1 — resilient screens

## Clause

Resilient screens.

## Status

Proven by tests

## Evidence

Test class: `WEB-09 clause 1 — resilient screens` (Vitest `describe` block) in
[`apps/staff-web/tests/spec/platform/resilient-screens.test.ts`](../../../../apps/staff-web/tests/spec/platform/resilient-screens.test.ts).
The suite is plain TypeScript and uses `createElement` with the same
`ThemeProvider` + `I18nProvider` wrapping as the neighbouring staff-web tests. It
uses synthetic fixtures only, contacts no endpoint and sets up no database. All
15 cases are active (11 table-driven cases plus four behaviour cases); none is
skipped. The full WEB-09 sentence was already implemented on `main`, so this
story added proof only and changed no production file.

Test methods:

- `definesEveryRequiredStateForEachTransportFailureKind: $kind` — table-driven
  over every one of the 11 members of the closed `TransportErrorKind` union. It
  first mounts `LoadingState` and `EmptyState` and asserts their `role="status"`,
  `aria-live="polite"` announcements and `stateViewText('en', …)` headings. For
  each kind it then asserts the `role="alert"` / `aria-live="assertive"` mapping,
  the exact `stateViewText('en', …)` heading, the exact set of recovery controls
  (compare/reload/copy for conflict and precondition-required, sign in for
  session expired, retry for csrf-missing/not-found/invalid/rate-limited/server/
  offline/timeout, none for forbidden), that each control invokes its matching
  callback and no other, and that an `UNTRUSTED_RESPONSE_TEXT` code is never
  echoed. The error-kind cases also assert the correlation ID when present and
  the `correlationUnavailable` fallback when absent, covering the timeout,
  offline and fallback arms.
- `keepsTypedWorkAndWiresCompareReloadAndCopyOnAStaleConflict` — mounts
  `SettingsPage` with an injected save command that rejects a synthetic
  `TransportError('conflict')`, edits the reply field and submits. It asserts the
  conflict alert, that the typed reply survives the stale response, that Compare
  and the working copy both preserve it, that Reload restores the authoritative
  value and clears the alert while keeping the working copy, and that a
  `beforeunload` event is prevented while dirty but not after reload or a
  successful save. This is the unsaved-leave guard and the "never lose typed
  work" rule.
- `logsOnlyTheCorrelationIdAndErrorNameFromTheGlobalBoundary` — mounts the real
  `ErrorBoundary` around a child that throws a message containing a synthetic
  patient-shaped token. It asserts the fallback renders the opaque correlation
  ID, that neither the alert nor the boundary's own log line contains the token,
  and that the single log line attributable to that ID is exactly
  `${id} RenderFailure`. It also asserts that this is the only plain-string log
  line emitted, so a regression that logged patient text as its own line would
  be caught even without a correlation ID; React's printf-formatted render log
  is excluded by that distinction. `clearRenderFailure()`
  is called before the case and after every case because the boundary keeps one
  module-level failure across React's replay.
- `refusesConflictRecoveryAndDataEchoForForbiddenAndExpiredSessions` — the
  negative case. Forbidden exposes no control at all; session expired exposes
  only Sign in. Both assert that no correlation ID and no response-text payload
  is echoed, and that none of the compare/reload/copy/retry callbacks fires.
- `wiresTheGlobalErrorBoundaryAtTheApplicationRoot` — reads
  `apps/staff-web/src/app/AppShell.tsx` and asserts, on comment-free source and
  without line numbers or return-statement formatting, that the shell imports
  `ErrorBoundary` and encloses both the `app-shell` region and `<main id="main">`
  within it. This closes the
  reachability trace from the application entry point to the boundary that the
  log proof mounts.

Production modules exercised through their normal entry points:

- `StateView.forTransportError`, `LoadingState`, `EmptyState`, `ErrorState`,
  `ForbiddenState`, `ConflictState` and `SessionExpiredState` in
  [`apps/staff-web/src/components/StateViews.tsx`](../../../../apps/staff-web/src/components/StateViews.tsx)
  — the exhaustive kind-to-state mapping, the announcement semantics and the
  recovery controls.
- `stateViewText` in
  [`apps/staff-web/src/components/stateViewMessages.ts`](../../../../apps/staff-web/src/components/stateViewMessages.ts)
  — the bilingual labels the assertions pin against instead of hard-coded text.
- `TransportError` and the closed `TransportErrorKind` union in
  [`apps/staff-web/src/api/transport.ts`](../../../../apps/staff-web/src/api/transport.ts)
  — the typed failure contract; response body text is discarded before it
  reaches a view.
- `SettingsPage` and its `useUnsavedGuard`, conflict recovery and working-copy
  state in
  [`apps/staff-web/src/features/staffmsg/settings/SettingsPage.tsx`](../../../../apps/staff-web/src/features/staffmsg/settings/SettingsPage.tsx)
  — the integrating screen that keeps typed work and guards navigation.
- `ErrorBoundary`, `recordFor` and `clearRenderFailure` in
  [`apps/staff-web/src/app/ErrorBoundary.tsx`](../../../../apps/staff-web/src/app/ErrorBoundary.tsx)
  — the PHI-free log line and the correlation ID shown in the fallback.

Entry-point-to-test trace: `AppShell.tsx` wraps `<main id="main">` in
`ErrorBoundary` (asserted by `wiresTheGlobalErrorBoundaryAtTheApplicationRoot`);
shared data pages reach the same states through `ApiPage.tsx`, whose loading,
empty, error and session-expiry paths are proven by
`apps/staff-web/src/components/ApiPage.test.tsx`; the state components themselves
are proven by `apps/staff-web/src/components/StateViews.test.tsx`; the global
boundary's runtime behaviour is proven by
`apps/staff-web/src/app/ErrorBoundary.test.tsx`; and conflict recovery plus the
unsaved-leave guard are proven by
`apps/staff-web/src/features/staffmsg/settings/SettingsPage.test.tsx`. This spec
adds the single clause-level matrix, the app-root wiring assertion and the
`beforeunload` assertions in one place; it does not replace those tests.

## Gap

None. The clause is implemented and the proof above is green with no skipped
cases. This story deliberately changed no production code, existing test,
contract, migration or configuration. The proof covers the shared state contract,
one representative integrating screen and the global boundary; per-screen
adoption of all seven states remains the responsibility of each screen's own
tests under `apps/staff-web/src/features` and the WEB-10 browser suite, which are
outside this clause test's scope.
