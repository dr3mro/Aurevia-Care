# WEB-07 clause 4 — unsupported browsers see a plain message and no data

## Clause

Unsupported browsers see a plain message and no data.

## Status

Proven by tests

## Evidence

Test class: `WEB-07 clause 4 — unsupported browsers see a plain message and no data` (Vitest
`describe` block) in
`apps/staff-web/tests/spec/platform/unsupported-browsers-see-plain-message-data.test.ts`.
The suite uses the same Vitest + jsdom configuration, synthetic fixtures and `tests/setup.ts`
cleanup as the neighbouring `tests/spec/` tests. Staff web has no database or server in its test
path, so the suite contacts no endpoint and sets up no database. All three tests are active
(none skipped) and pass; no production code, existing test, contract or migration was changed by
this evidence task.

Test methods:

- `showsOnlyThePlainMessageAndNoApplicationDataForUnsupportedClient` — positive proof. A
  synthetic Internet Explorer user agent (`MSIE 10`) at the 1280 px desktop width makes the real
  `isSupportedBrowser` guard return `false`. The unsupported branch renders the real
  `UnsupportedBrowserPage` under `ThemeProvider` and `I18nProvider initialLocale="en"`. The
  assertions find the single translated heading, detail and action, and find no navigation, table
  or synthetic clinical label (`Synthetic Reception`, `Synthetic Alpha Clinic`, `Staff Portal`),
  proving that the plain message carries no application data.
- `doesNotShowThePlainUnsupportedMessageToSupportedClient` — negative refusal. A synthetic
  current desktop Chrome user agent (`Chrome/130`) at 1280 px makes the real guard return `true`;
  that result is asserted directly as a named behaviour. The test then mirrors the entry decision
  locally with that real guard and confirms that no plain unsupported heading, detail or action is
  rendered on the accepted branch. This is a component-level mirror, not the production entry
  point: the production proof that a supported client mounts `<App />` and not the plain notice is
  the existing `tests/main.test.tsx` case cited in the trace below.
- `failsClosedToThePlainMessageWhenTheViewportIsNarrowerThanTheReflowFloor` — corroborating
  boundary mirror. A supported user agent at 319 px, one pixel below the 320 px reflow floor of
  decision 0004, makes the guard return `false` and renders only the plain message with no
  application data. This mirrors an already-proven boundary and is not new coverage.

Production symbols exercised through their normal entry points:

- `isSupportedBrowser(userAgent, width)` in
  `apps/staff-web/src/features/system/browserSupport.ts` — the pure client-support policy;
  Internet Explorer and unknown engines fail closed, and widths below `MIN_SUPPORTED_WIDTH`
  (320 px) are unsupported.
- `UnsupportedBrowserPage` in
  `apps/staff-web/src/features/system/UnsupportedBrowserPage.tsx` and `SystemNotice` in
  `apps/staff-web/src/features/system/SystemNotice.tsx` — the plain, data-free surface with one
  action; no shell, fetch or browser storage.
- `systemText` and `systemMessages` in `apps/staff-web/src/features/system/messages.ts` — the
  English source of the title, detail and action labels.
- `ThemeProvider` and `I18nProvider` in `apps/staff-web/src/theme/ThemeProvider.tsx` and
  `apps/staff-web/src/i18n/I18nProvider.tsx` — the providers the supported and unsupported
  entry trees need.

Entry-point-to-test trace: `apps/staff-web/src/main.tsx` calls `isSupportedBrowser` at mount and
on viewport change, mounting the provider-wrapped `UnsupportedBrowserPage` for a failed check and
`<App />` for a passing one. The new test drives that same decision at component level with the
real guard and real plain-message component. The other half of the trace — that a supported
client mounts the real `<App />` and not the plain page — is asserted against the production
entry point by the existing active test `tests/main.test.tsx`
("mounts the app when the browser check passes" and "mounts only the plain unsupported notice and
no app for an unsupported browser"). The full user-agent and width matrix is asserted by
`tests/features/system/browserSupport.test.ts`, and the data-free content of the page is also
asserted by `tests/features/system/systemPages.test.tsx`
("shows no application data on the unsupported page").

Where the clause is implemented today (source survey result): the rule is the pure guard above,
the plain message is `UnsupportedBrowserPage` rendering `SystemNotice`, and the wiring is the
`main.tsx` entry decision. No production gap was found, so this task adds clause-specific
evidence only.

## Gap

None.
