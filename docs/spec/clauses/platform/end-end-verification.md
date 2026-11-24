# WEB-10 clause 1 — end-to-end verification evidence note

Requirement WEB-10 asks the critical journey of each supported role to run in a
real browser against staging with synthetic data, in English and Arabic RTL,
with a serious/critical accessibility scan and light/dark visual checks, on
every release candidate with the report retained. This note records only files,
tests and gates that exist in this repository today. Evidence source for the
commands in the notes file is the executing tool; the statements below come from
reading the linked files. No browser run, staging deployment, human review,
clinical sign-off or release-candidate evidence is claimed here.

## Clause

End-to-end verification.

## Status

Partly proven

## Evidence

This note proves clause 1 only: the end-to-end verification harness exists and
is configured. It does not claim acceptance of the bilingual journey or the
accessibility clauses, which belong to their own tasks.

- The staff-web clause-evidence test is
  [`end-end-verification.test.ts`](../../../../apps/staff-web/tests/spec/platform/end-end-verification.test.ts).
  `assertsEndToEndEvidenceHarnessIsConfigured` reads the harness sources and
  asserts, with concrete strings: an injectable `E2E_BASE_URL` and a local dev
  server only when it is unset in
  [`playwright.config.ts`](../../../../apps/staff-web/e2e/playwright.config.ts);
  an English LTR and Arabic RTL journey in
  [`reception-journey.spec.ts`](../../../../apps/staff-web/e2e/reception/reception-journey.spec.ts);
  the shared serious/critical axe gate in
  [`accessibility.ts`](../../../../apps/staff-web/e2e/accessibility.ts); the
  real screens mounted under `ThemeProvider` in the role harnesses (for example
  [`reception-harness.tsx`](../../../../apps/staff-web/e2e/reception/reception-harness.tsx))
  and the light/dark state in
  [`ThemeProvider.tsx`](../../../../apps/staff-web/src/theme/ThemeProvider.tsx);
  and one `*-journey.spec.ts` per `e2e/` role or workflow directory.
  `refusesSeriousAndCriticalAccessibilityViolations` is the negative test: it
  asserts that both the shared gate in `accessibility.ts` and the local gate in
  [`smoke.spec.ts`](../../../../apps/staff-web/e2e/smoke.spec.ts) filter exactly
  `serious` and `critical` (not `moderate` or `minor`) and assert an empty
  violations list, so a widened filter fails the test.
- The tests are static file-read assertions because jsdom cannot run a browser
  and the worker image has no Playwright binaries; the module
  [`check.sh`](../../../../apps/staff-web/check.sh) invokes `test:coverage`,
  whose `--exclude 'e2e/**'` flag in
  [`package.json`](../../../../apps/staff-web/package.json) leaves Playwright
  specs to their own runner, and the
  [README](../../../../apps/staff-web/README.md) documents the manual
  `npm run e2e` command. The harness is therefore configured but not executed by
  these tests.

## Gap

The clause is only partly proven: the harness is present and configured, while
the run-and-retain half of the requirement is absent. No `.github/workflows/`
file (including [`delivery.yml`](../../../../.github/workflows/delivery.yml))
invokes Playwright or `npm run e2e`, so no journey runs on a release candidate
and no Playwright report is uploaded as release evidence. No staging target is
configured either: `E2E_BASE_URL` is injectable, but no staging URL, seed data
or report-retention location is defined. There is also no light/dark **visual**
comparison: the harnesses import `ThemeProvider`, which proves wiring only, and
no `screenshot`, `toHaveScreenshot`, `colorScheme` or theme comparison exists in
the suite.

These gaps are recorded as prose here and as `task-requests` in the delivery
notes, deliberately not as skipped tests: a skip proves nothing and the
constraint gate forbids adding one.

1. **Run the journeys on every release candidate in CI.** Add a job that
   installs a browser binary, runs `npm run e2e` with `E2E_BASE_URL`, and
   uploads the Playwright report as an artifact tied to the candidate SHA.
   Files that would change: `.github/workflows/`, `apps/staff-web/package.json`,
   `apps/staff-web/README.md`, `apps/staff-web/e2e/playwright.config.ts` and
   this clause's test file (add the CI assertions). One CI task.
2. **Provide a staging harness and retain reports as evidence.** Define the
   staged dev build, its synthetic seed data and the report-retention location,
   then extend this evidence test to assert them. Files that would change:
   `apps/staff-web/e2e/`, `apps/staff-web/README.md` and this clause's test and
   note files. One engineering/evidence task.
3. **Add the light and dark visual check.** Add an actual theme comparison or
   screenshot assertion to the journeys and assert it here. Files that would
   change: `apps/staff-web/e2e/`, `apps/staff-web/src/theme/` (only if the theme
   hook needs a testable seam) and this clause's test file. One task, after the
   browser job exists.

That is at least three separate tasks. This task changes only the two declared
scope paths: no production code, existing test, contract, migration, dependency
or protected file was edited.
