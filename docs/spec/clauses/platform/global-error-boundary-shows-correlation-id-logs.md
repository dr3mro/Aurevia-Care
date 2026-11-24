# WEB-09 clause 5 — global error boundary shows a correlation ID and logs without patient data

## Clause

A global error boundary shows the correlation ID and logs the error without patient data.

## Status

Proven by tests

## Evidence

The clause test is [global-error-boundary-shows-correlation-id-logs.test.ts](../../../../apps/staff-web/tests/spec/platform/global-error-boundary-shows-correlation-id-logs.test.ts). It is an enabled Vitest suite in jsdom with inline synthetic marker constants only: no patient data, no server or database setup, no helper added to `src/`, no new dependency and no `it.skip`. It renders the same `ThemeProvider` + `I18nProvider` tree the installed shell uses around its global boundary. Result: 2 tests, 0 failures, 0 skipped.

Test class `WEB-09 clause 5: global error boundary shows a correlation ID and logs without patient data`:

- `showsCorrelationIdAndLogsOnlyIdAndErrorName` (rule met) — a child throws a synthetic `BoomFailure`; the `role="alert"` fallback contains `Something went wrong` and the mocked correlation ID `00000000-0000-0000-0000-0000000000bb`, and offers a `Retry` button. The console spy is filtered by that opaque ID so React's own render-failure log is excluded; exactly one boundary call remains and its single string equals `<correlationId> BoomFailure`.
- `refusesToLogThrownMessageOrPatientData` (negative) — the thrown message and child prop carry three distinctive synthetic markers (`MARKER-THROW-MESSAGE`, `MARKER-PROP`, `MARKER-PATIENT-MRN-0042`). After a fallback re-render the log still holds exactly one call, that line equals `<correlationId> BoomFailure`, and it contains none of the three markers. `crypto.randomUUID` is called once across the re-render, so one failure keeps one ID and one redacted line.

Production classes exercised through their normal entry point:

- [ErrorBoundary.tsx](../../../../apps/staff-web/src/app/ErrorBoundary.tsx) — `ErrorBoundary` wraps the shell; `recordFor` creates one `FailureRecord` per failure, logs only `${correlationId} ${errorName}` via `console.error`, and is idempotent across the render replay; `clearRenderFailure` resets the record on Retry.
- [AppShell.tsx](../../../../apps/staff-web/src/app/AppShell.tsx) — the installed shell wraps its entire rendered tree in `<ErrorBoundary>` (line 317), so the boundary is the global catcher, not an unused component.
- [StateViews.tsx](../../../../apps/staff-web/src/components/StateViews.tsx) — `ErrorState` renders the `role="alert"` panel with `stateViewText(locale, 'error')` (`Something went wrong`), the correlation ID in a `<code dir="ltr">` element and the `Retry` action.

The neighbouring [ErrorBoundary.test.tsx](../../../../apps/staff-web/src/app/ErrorBoundary.test.tsx) already covers healthy pass-through, retry after the cause is cleared, ID reuse across re-renders, a non-`Error` thrown value and the implementation contract. This clause file adds the requirement-ID acceptance framing and the decisive no-patient-data redaction assertion without re-running or weakening that matrix.

## Gap

None.
