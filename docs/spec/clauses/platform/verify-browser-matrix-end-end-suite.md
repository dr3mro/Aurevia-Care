# WEB-07 clause 5 — evidence note

## Clause

Verify with a browser matrix in the end-to-end suite.

## Status

Partly proven

## Evidence

This note records only files, tests and gates that exist in this repository
today. The staff-web clause-evidence test is
[`verify-browser-matrix-end-end-suite.test.ts`](../../../../apps/staff-web/tests/spec/platform/verify-browser-matrix-end-end-suite.test.ts).
The statements below come from reading the linked files; the commands and their
results are recorded in the delivery notes. No browser was launched, no matrix
run is recorded here, and no human or clinical reviewer evidence is claimed.

- The owning entry point is
  [`e2e/playwright.config.ts`](../../../../apps/staff-web/e2e/playwright.config.ts).
  It declares nine Chromium device projects from `deviceViewports`, including
  `tablet-portrait-768x1024` at 768 × 1024 and `laptop-1280x720` at 1280 × 720,
  and it adds conditional WebKit and Firefox variants for the 390, 820 and 1280
  px representatives. The test methods
  `declaresBrowserMatrixForSupportedEnginesAndViewports`,
  `rejectsMatrixMissingSafariEngine`,
  `rejectsMatrixMissingCompactOrDesktopViewport` and
  `rejectsMatrixNotWiredIntoTheEndToEndSuite` read that config as text, assert
  the engines, the two WEB-07 viewport floors and the wiring into `projects`,
  and prove the check is not a no-op by mutating synthetic copies and asserting
  that it throws. The config is inspected as text rather than imported because
  `isBrowserInstalled()` removes WebKit and Firefox when their binaries are
  absent, which would hide part of the declared matrix in this image.
- The suite is invoked by the `e2e` script in
  [`package.json`](../../../../apps/staff-web/package.json) and documented in the
  [staff-web README](../../../../apps/staff-web/README.md). The clause test
  asserts that script as well, so a config that is declared but never run is
  detected. The project names and the cross-browser policy are described in
  [responsive-layout.md](../../../design/responsive-layout.md).
- Supporting evidence that already exists on this branch, not duplicated by the
  clause test: [`browserSupport.ts`](../../../../apps/staff-web/src/features/system/browserSupport.ts)
  and [`browserSupport.test.ts`](../../../../apps/staff-web/tests/features/system/browserSupport.test.ts)
  prove that unknown clients and Internet Explorer are refused at supported
  widths. That belongs to the surrounding WEB-07 requirement rather than to
  clause 5's browser-matrix sentence.

## Gap

The matrix is declared but not executed: no job under `.github/workflows`
installs a browser or runs `npm run e2e`, and no report from a matrix run exists
in the repository. Without browser binaries the config resolves only Chromium
projects, so the WebKit and Firefox rows are declared but not exercised. WebKit
proxies Safari and Chromium proxies Edge — there is no separate Safari or Edge
engine project — and the 768 px compact viewport is Chromium-only. These gaps
are recorded as prose here and as task requests in the delivery notes, not as a
skipped test: a skip proves nothing and the constraint gate forbids adding one.

1. **Run the declared matrix in CI.** Add a job that installs Chromium, WebKit
   and Firefox and runs `npm run e2e` for the supported device projects on every
   release candidate, retaining the report as evidence. Files that would change:
   `.github/workflows/`, `apps/staff-web/package.json` and this clause note.
   One CI task.
2. **Close the proxy-engine and 768 px gaps.** Decide and document whether
   WebKit and Chromium are accepted proxies for Safari and Edge, and extend the
   cross-browser viewport set so the 768 px compact band also runs on WebKit and
   Firefox, or record an approved exception. Files that would change:
   `apps/staff-web/e2e/playwright.config.ts`, `docs/design/responsive-layout.md`
   and this clause note. One engineering and approval task.

That is two separate tasks. This change touches only the two declared scope
paths: no production code, existing test, contract, migration, dependency or
protected file was edited.
