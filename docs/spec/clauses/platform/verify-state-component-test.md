# WEB-09 clause 6 — verify each state with a component test

## Clause

Verify each state with a component test.

## Status

Proven by tests

## Evidence

Test suite: `WEB-09 clause 6 — verify each state with a component test`
(Vitest `describe` block) in
`apps/staff-web/tests/spec/platform/verify-state-component-test.test.ts`.
It uses the same Vitest + jsdom configuration, synthetic fixtures and
`apps/staff-web/tests/setup.ts` cleanup as the neighbouring
`tests/spec/localization/` proofs. Staff web has no database or server in its
test path, so the suite contacts no endpoint and sets up no database. Each view
is mounted through `ThemeProvider` and `I18nProvider` with `initialLocale: 'en'`
and unmounted before the next mount, because the locale provider writes
`document.dir` and `document.lang` in an effect. All three tests are active
(none skipped) and pass.

Test methods (one positive rule proof and two refusal proofs):

- `eachRequiredStateHasAnExecutableComponentTest` — renders all nine state
  cases through their public components: loading and empty as polite `status`
  regions; error, timeout and offline as assertive `alert` regions with the
  generic heading and a single Retry control; forbidden as an assertive alert
  with no controls; conflict and stale-version as assertive alerts with the
  Compare, Reload and Copy my changes controls; and session-expired as an
  assertive alert with a Sign in control. For each case the test asserts the
  region role, the `aria-live` value, the English heading and the exact button
  set, then unmounts before the next case.
- `forbiddenRefusesRecoveryAndCorrelation` — supplies a synthetic correlation
  id and all five recovery callbacks to a `forbidden` transport error. The
  rendered state shows "You do not have access", no Correlation ID text and no
  correlation value, no Retry/Reload/Compare/Copy/Sign in button, and none of
  the supplied callbacks is invoked. This is the negative proof that a
  permission failure offers no unsafe recovery and leaks no diagnostic id.
- `staleVersionRefusesSilentLoss` — both `conflict` and `precondition-required`
  render the conflict state with compare, reload and copy controls, and never
  the generic "Something went wrong" view, so a stale write cannot silently
  discard typed work.

Production modules exercised through their normal entry points:

- `apps/staff-web/src/components/StateViews.tsx` — `LoadingState`,
  `EmptyState`, `ErrorState`, `ForbiddenState`, `ConflictState`,
  `SessionExpiredState`, `StateView.forTransportError` and the `noop` fallback.
- `apps/staff-web/src/components/stateViewMessages.ts` and
  `apps/staff-web/src/components/Button.tsx` — the bilingual labels and the
  native, keyboard-reachable action buttons.
- `apps/staff-web/src/api/transport.ts` — `TransportError` and
  `TransportErrorKind`, the typed failure carrying only a synthetic correlation
  id and no response text.
- `apps/staff-web/src/theme/ThemeProvider.tsx` and
  `apps/staff-web/src/i18n/I18nProvider.tsx` — the providers the shell mounts
  around every screen.

Corroborating component tests already on `main` for the other parts of the
same requirement: `apps/staff-web/src/components/StateViews.test.tsx` covers the
state components and their transport mapping in both locales;
`apps/staff-web/src/app/ErrorBoundary.test.tsx` covers the global boundary's
anonymised correlation id and no-patient-data logging; and the unsaved-changes
prompt is exercised in `apps/staff-web/src/features/chart/NotePage.test.tsx`,
`apps/staff-web/src/features/staffmsg/settings/SettingsPage.test.tsx` and
`apps/staff-web/src/features/pharmacyinbox/PharmacyItemPage.test.tsx`.
`docs/operations/production-readiness/README.md` lists these among the WEB-09
evidence items.

Entry-point-to-test trace: `apps/staff-web/src/App.tsx` mounts the theme,
locale and global error-boundary providers; `apps/staff-web/src/components/ApiPage.tsx`
turns one `useApi` result into `LoadingState`, `EmptyState` or
`StateView.forTransportError`, delegating recovery actions to the owning screen.
The new test mounts that provider pair directly and renders the same exported
state components, so every assertion runs through the entry point the screens
use. This change adds proof only: it does not change production code, an
existing test, the contract or a migration.

## Gap

None
