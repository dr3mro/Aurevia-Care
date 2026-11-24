# WEB-09 clause 2 — resilient screen states

## Clause

Every screen defines loading, empty, error, timeout or offline, forbidden, stale-version conflict and session-expired states.

## Status

Proven by tests

## Evidence

Spec-level Vitest evidence is `apps/staff-web/tests/spec/platform/screen-defines-loading-empty-error-timeout-offline.test.ts`
(`.ts` with `createElement`, real `ThemeProvider` and `I18nProvider`, synthetic
fixtures only). It renders and unmounts each state on its own so no two alert
panels share a query, and asserts outcomes rather than incidental text:

- `rendersEveryResilientStateWithHeadingsAndRecoveryActions` — positive proof.
  Mounts all six state components (seven renders: a second `ErrorState` covers
  the missing-id fallback) and asserts the h2 heading and live-region role:
  `LoadingState` (polite `status`), `EmptyState` (polite `status`), `ErrorState`
  (assertive `alert`, correlation id `corr-synthetic-web09` shown and its Retry
  callback fired), a second `ErrorState` with no id showing the `not available`
  fallback, `ForbiddenState`, `ConflictState` (Compare, Reload and Copy my
  changes each fire once), and `SessionExpiredState` (Sign in fires once). It
  then drives `StateView.forTransportError` through all eleven
  `TransportErrorKind` values and asserts the exact heading per kind; the
  generic-error kinds expose the synthetic correlation id and the four
  dedicated kinds (`session-expired`, `forbidden`, `conflict`,
  `precondition-required`) do not.
- `refusesWrongStateAndPreservesTypedWork` — negative proof. The `forbidden`
  and `conflict` transport mappings must not render the generic error heading
  or a Retry button. On a representative real screen, `SettingsPage` with a
  synthetic conflict rejection must keep the typed reply in the live textarea
  after the conflict, after Compare and after Copy my changes, while the
  compare and working-copy panels echo it.

Production symbols exercised through the normal React entry points:
`apps/staff-web/src/components/StateViews.tsx` (`LoadingState`, `EmptyState`,
`ErrorState`, `ForbiddenState`, `ConflictState`, `SessionExpiredState`,
`StateView.forTransportError`, `actionsOrEmpty`), `stateViewMessages.ts`
(`stateViewText`), `apps/staff-web/src/api/transport.ts` (`TransportError` and
`TransportErrorKind`), `ApiPage.tsx` (which maps `useApi` results to loading,
empty and a mapped error state), and the fixed `ForbiddenPage.tsx` and
`SessionExpiredPage.tsx` that reuse the shared views. Neighbouring tests show
the same states on real screens and routes: `src/components/StateViews.test.tsx`,
`tests/components/StateViews.responsive.test.tsx`,
`src/components/ApiPage.test.tsx`, `tests/components/ApiPage.responsive.test.tsx`,
`src/features/radworklist/RadWorklistPage.test.tsx`,
`src/features/staffmsg/settings/SettingsPage.test.tsx`, and the route registry
in `src/app/routes.test.tsx` (which fixes `/forbidden` and `/session-expired`).

Verified locally, from the repository root:

- `bash apps/staff-web/check.sh` — generated-client check, `tsc --noEmit`,
  ESLint (zero warnings), production build, WEB-08 gzip budget and Vitest with
  per-file 100% coverage: `Test Files 253 passed (253)`, `Tests 2979 passed
  (2979)`, `No files with missing coverage.` — coverage summary Statements
  100% (5107/5107), Branches 100% (2634/2634), Functions 100% (1697/1697),
  Lines 100% (4903/4903). No test is skipped or suppressed.

"Proven by tests" is limited to the sentence quoted above. The remaining
sentences of WEB-09 (leaving with unsaved changes, the global error boundary
showing a correlation id and logging without patient data, and component
verification of each state) are outside this clause's claim; they are evidenced
by `src/features/staffmsg/settings/SettingsPage.test.tsx` and `src/app/ErrorBoundary.test.tsx`
in their own work and are not re-proven here.

## Gap

None.
