# UX-01 clause 3 — evidence note

## Clause

Test daylight-saving transitions, accessibility against WCAG 2.2 AA on staff web, and native screen-reader flows on both mobile platforms.

## Status

Partly proven

## Evidence

This note records only files, tests and gates that exist in this repository
today. The staff-web clause-evidence test is
`apps/staff-web/tests/spec/localization/test-daylight-saving-transitions-accessibility-against-wcag.test.ts`.
Evidence source for the commands in the notes file is the executing tool; the
statements below come from reading the linked files. No human, clinical,
screen-reader or mobile-device evidence is claimed here.

### Daylight-saving transitions — implemented and proven

- [`formatInstant`](../../../../apps/staff-web/src/i18n/format.ts) delegates
  DST handling to `Intl.DateTimeFormat` with an IANA zone. The test methods
  `rendersDSTTransitionsAsDistinctLocalTimes`, `sameInstantDiffersBetweenZones`,
  `keepsCivilDateOnTheRecordedDay` and `rejectsUnknownIANAZone` exercise the
  built behavior with the synthetic fixtures from
  [`synthetic.ts`](../../../../apps/staff-web/src/fixtures/synthetic.ts).
  They prove the spring-forward clock jump, the fall-back repeated wall clock,
  a different rendering of one instant in two zones, a stable civil day and a
  fail-closed `RangeError` for an unknown zone.
- The DST design decision is recorded in the staff-web
  [README](../../../../apps/staff-web/README.md) and
  [format.ts](../../../../apps/staff-web/src/i18n/format.ts).

### WCAG 2.2 AA patterns on staff web — implemented patterns proven, full audit not built

- [`AppShell.tsx`](../../../../apps/staff-web/src/app/AppShell.tsx) renders the
  skip link, `banner`, `main`, `contentinfo`, the named `role="status"` live
  region and the named navigation group; the theme and language `Button`
  components expose `aria-pressed`. The test method
  `exposesAccessibleNamesRolesAndPressedState` renders the real shell inside
  the existing `ThemeProvider`, `I18nProvider` and `RouterProvider` and asserts
  those names, roles, the skip link target and order, the polite live region and
  the pressed state transition. The patterns are specified in
  [accessibility-patterns.md](../../../../docs/design/accessibility-patterns.md).
- The only automated accessibility scan in the repository is
  [`e2e/smoke.spec.ts`](../../../../apps/staff-web/e2e/smoke.spec.ts), which
  runs axe against the development-only `/design-preview` route in English and
  Arabic. It lives in the Playwright suite, which the README and the module
  `check.sh` deliberately exclude because the worker image has no browser.
  There is no axe dependency in the Vitest tooling, so a full WCAG 2.2 AA audit
  over every screen and state is not proven by this test.

### Native screen-reader flows on both mobile platforms — out of scope and not built

- No named VoiceOver (iOS) or TalkBack (Android) per-screen pass is held in the
  repository. The native patterns are specified in
  [accessibility-patterns.md](../../../../docs/design/accessibility-patterns.md),
  but the clause's mobile half belongs to `apps/patient-mobile`, not this
  staff-web test. The gap is recorded as prose here and as task requests in the
  delivery notes, so a later mobile task can add the native evidence without a
  skipped test in this suite.

## Gap

The clause is only partly proven: DST behavior and the implemented staff-web
ARIA patterns have real passing tests, while the full automated WCAG 2.2 AA
audit and the native screen-reader flows are absent. Those two gaps are recorded
below and as task requests in the delivery notes. They are intentionally not
carried as skipped tests: a skip proves nothing and the constraint gate forbids
adding one, so the missing behaviour is described here instead.

1. **Full automated WCAG 2.2 AA audit on every staff screen and state.** Add an
   axe-core unit or browser runner to the staff-web tooling and a CI job that
   installs Chromium, then extend the scan from `/design-preview` to every
   screen and state (loading, empty, error, RTL, dialogs, worklists, critical
   banners). Files that would change: `apps/staff-web/package.json`, the module
   `check.sh`, `apps/staff-web/e2e/`, `.github/workflows/` and this clause's
   test file (add the audit assertions). One engineering task plus one CI task.
2. **Native VoiceOver and TalkBack flows on both platforms.** Run and record a
   named screen-reader pass per screen on iOS and Android, including Arabic
   RTL content, as required by
   [accessibility-patterns.md](../../../../docs/design/accessibility-patterns.md).
   Files that would change: `apps/patient-mobile/` test and evidence files and
   this clause's test file (add the native-pass assertions). One
   human/simulated-user evidence task; a per-site launch gate also needs
   qualified reviewers.

That is at least four separate tasks (two engineering/CI, two mobile evidence).
This task changes only the two declared scope paths: no production code,
existing test, contract, migration, dependency or protected file was edited.
