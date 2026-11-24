# WEB-10 clause 2 — evidence note

## Clause

Automate the critical journey of each supported role in a real browser against staging with synthetic data, in English and in Arabic right-to-left, with an accessibility scan that reports zero serious or critical violations and light and dark visual checks.

## Status

Partly proven

## Evidence

This note records only files, tests and artifacts that exist in this repository
today. The staff-web clause-evidence test is
[`automate-critical-journey-supported-role-real-browser.test.ts`](../../../../apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts).
Evidence source for the commands in the delivery notes is the executing tool;
the statements below come from reading the linked artifacts. No staging run,
browser execution, human, clinical or visual review is claimed here: the worker
image has no browser, so the Playwright suite is inspected as committed source
rather than executed.

### Real-browser journeys with synthetic data — implemented for seven domains, not all roles

- Seven committed Playwright specs drive a real browser through a complete
  journey with only stubbed `/api/v1/**` boundaries and synthetic fixtures:
  [reception](../../../../apps/staff-web/e2e/reception/reception-journey.spec.ts),
  [laboratory](../../../../apps/staff-web/e2e/labflow/lab-bilingual-journey.spec.ts),
  [order entry](../../../../apps/staff-web/e2e/orderentry/orderentry-journey.spec.ts),
  [radiology](../../../../apps/staff-web/e2e/radflow/radiology-journey.spec.ts),
  [clinical note](../../../../apps/staff-web/e2e/clinicalnote/clinicalnote-journey.spec.ts),
  [pharmacy](../../../../apps/staff-web/e2e/pharmacyflow/pharmacy-journey.spec.ts) and
  [print](../../../../apps/staff-web/e2e/print/print-journey.spec.ts). Every spec
  switches to Arabic through the visible `العربية` control and asserts the
  document `dir`, so the RTL path is pinned. Five of them (reception, lab,
  order, clinical note, print) are parameterised over an explicit English and
  Arabic `JourneyWords` set, so both locales are asserted word for word rather
  than incidentally.
- [`playwright.config.ts`](../../../../apps/staff-web/e2e/playwright.config.ts)
  takes `E2E_BASE_URL` from the environment, so the base URL is injectable,
  while defaulting to a locally started Vite dev server. The journeys themselves
  mount dev-only harnesses with in-browser API interception, so "against staging"
  is not yet proven; the injectable URL is a prerequisite, not the evidence.
- The test method `browserJourneysCoverSupportedRolesBilinguallyWithBlockingAxeGate`
  reads those committed artifacts and asserts their presence, the Arabic/RTL
  markers, the two locale word sets, the staging base URL and the axe gate. It
  would fail if any of them were removed.

### Wrong-role refusal — implemented and proven

- The test method `wrongRoleIsRefusedAnotherRolesJourney` exercises the real
  [`src/app/navigation.ts`](../../../../apps/staff-web/src/app/navigation.ts)
  exports `Role`, `routeAccessRoles`, `navigationFor` and `canAccessRoute`.
  It proves that a `reception` user is refused the `tenant_admin` admin-facilities
  route and the `lab` specimen-accessions route, while the authorised roles are
  granted them. This mirrors the rail: a link hidden for a role cannot be opened
  by pasting its URL.

### Accessibility scan — shared gate implemented and proven, partial coverage

- [`e2e/accessibility.ts`](../../../../apps/staff-web/e2e/accessibility.ts)
  runs axe through `AxeBuilder` and keeps only findings whose impact is
  `serious` or `critical`, failing the journey on any of them. The same filter
  is duplicated, deliberately, in
  [`e2e/smoke.spec.ts`](../../../../apps/staff-web/e2e/smoke.spec.ts) so a new
  screen cannot quietly widen the gate. The test asserts the exact filter and
  that reception, lab and clinical note import and call the shared gate.
- The browser journeys mount the real screens through their real providers
  (`ThemeProvider`, `I18nProvider`, and `ActiveOrganizationProvider` on several
  harnesses; only the clinical-note harness also uses `RouterProvider`, and none
  mount `AppShell`). The jsdom evidence test itself exercises only
  `src/app/navigation.ts`; it does not run axe or a browser.

### Light and dark visual checks — not built

- No e2e spec asserts a screenshot or snapshot, and none switches the theme
  during a journey. [`ThemeProvider`](../../../../apps/staff-web/src/theme/ThemeProvider.tsx)
  mirrors the mode onto `document.documentElement.dataset.theme`, but nothing
  compares the two rendered states. The active `GAP:` test method pins this
  absence and will fail on the day a visual check is added.

## Gap

The clause is only partly proven. The missing halves are pinned by active GAP
assertions in the evidence test, not by skipped tests: a skip proves nothing and
the repository forbids adding one. All browser CI, protected workflow files and
new production code are outside this task's two-file scope, so none were edited.

1. **A journey for every supported role.** `Role` declares twelve members; only
   seven domain journeys exist and the base `staff`, `therapist`, `finance`,
   `operator`, `unit_staff`, `tenant_admin` and `privacy_auditor` roles have no
   dedicated critical journey. Files that would change: new specs under
   `apps/staff-web/e2e/` with their synthetic API stubs and harnesses, this
   clause's test (replace the journey-set assertion) and this note. One
   engineering task.
2. **The blocking axe gate on every journey.** The shared gate is called by
   three of the seven journeys; order entry, radiology, pharmacy and print have
   no axe scan of their own. Files that would change: those four spec files and
   this note. One engineering task.
3. **A real staging target, a release-candidate run and a kept report.** The
   harnesses are dev-only, so the injectable base URL cannot yet prove a staged
   run. No file under `.github/workflows/` invokes Playwright, and the reporter
   is `list` with no artifact, so "every release candidate" and report retention
   are not evidenced (release cadence and report retention are scoped to the
   sibling clause-3 story). Files that would change: `.github/workflows/`
   (protected — owner or trusted integrator), the dev-only harnesses,
   `apps/staff-web/e2e/playwright.config.ts`, `apps/staff-web/check.sh` and a
   report artifact location. One CI task plus owner approval for the workflow.
4. **Light and dark visual checks.** No screenshot/snapshot comparison exercises
   both theme modes. Files that would change: `apps/staff-web/e2e/**`, the
   `ThemeProvider` toggle path and `docs/design/token-contract.md` (a production
   change would carry its own 100% coverage duty). One engineering task.

That is at least four separate tasks (three engineering/CI and one with owner
approval), plus a follow-up to replace the GAP tripwire and update this note when
the evidence lands. This task changes only the two declared scope paths: no
production code, existing test, contract, migration, dependency or protected
file was edited.
