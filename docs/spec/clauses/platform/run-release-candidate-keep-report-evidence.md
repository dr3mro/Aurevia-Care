# Run the end-to-end suite on every release candidate and keep the report as evidence (WEB-10)

## Clause

Run it on every release candidate and keep the report as evidence.

## Status

Not built

## Evidence

Surveyed the repository with tool searches and file reads. The end-to-end suite and its npm script exist, but nothing runs them on a release candidate and no report is kept.

- The clause is clause 3 of WEB-10 in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) (line 158); line 163 assigns WEB-10 evidence to CI and the end-to-end suite.
- The suite itself is present: [smoke.spec.ts](../../../../apps/staff-web/e2e/smoke.spec.ts) checks the home page title and skip link, an axe scan that fails only on serious or critical violations, the Arabic right-to-left toggle and the absence of horizontal scrolling at 768 px. [playwright.config.ts](../../../../apps/staff-web/e2e/playwright.config.ts) defines the browser/viewport matrix and the injectable `E2E_BASE_URL`. [package.json](../../../../apps/staff-web/package.json) (line 19) declares the `e2e` script.
- The report is not durable: `reporter: 'list'` in [playwright.config.ts](../../../../apps/staff-web/e2e/playwright.config.ts) (line 116) prints to the console. No `html`, `json`, `junit` or `blob` reporter writes a report file.
- [README.md](../../../../apps/staff-web/README.md) (lines 67-71) states the suite is deliberately excluded from `check.sh` because the worker image has no browser binaries, and that "a person must add a CI job that installs Chromium and runs it".
- Workflow survey (tool evidence): `grep -rniE 'npm run e2e|playwright' .github/workflows/` returned no match. [delivery.yml](../../../../.github/workflows/delivery.yml) uploads only the `developer-reference` and `smoke-tested-image-*` artifacts; [release-app-image.yml](../../../../.github/workflows/release-app-image.yml) builds and publishes the image with no end-to-end job. No workflow declares an artifact named for `playwright-report`, `e2e-report`, `blob-report` or `test-results`.
- The active evidence test `apps/staff-web/tests/spec/platform/run-release-candidate-keep-report-evidence.test.ts` records this assessment: `recordsThatTheSuiteExistsButItsReporterWritesOnlyToTheConsole` reads the real suite files, and `recordsThatNoRealWorkflowRunsTheSuiteOrKeepsAReport` reads every real workflow. `detectsACandidateWorkflowThatRunsE2eAndKeepsAReport` and `refusesAWorkflowWithoutAnE2eStepOrWithoutAKeptReport` are synthetic detector self-tests, not clause satisfaction.

## Gap

Missing: a CI trigger that installs Chromium and runs `npm run e2e` on every release candidate against the staged build with synthetic data, a file-based Playwright reporter, and an artifact upload step that retains the report as durable evidence.

Files that would change once the work is authorized: `.github/workflows/delivery.yml` and/or `.github/workflows/release-app-image.yml` for the release-candidate job, `apps/staff-web/e2e/playwright.config.ts` for the reporter, and the two evidence files for this clause — the currently green empty-workflow assertion must be updated and the `Status` changed from `Not built` to `Proven by tests` in the same change, otherwise the improvement turns this test red.

This needs one CI task plus one evidence-update task. The workflow is outside a swarm worker's scope, so the worker notes carry a `task-requests` block for the CI task instead of a patch.
