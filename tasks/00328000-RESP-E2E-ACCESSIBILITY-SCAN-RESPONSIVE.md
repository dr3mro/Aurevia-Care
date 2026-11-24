- [-] RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE · Staff web end-to-end: accessibilityScanResponsive across phone, tablet and desktop
  - agent: swarm-3
  - attempts: 2
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/e2e/accessibilityScanResponsive.spec.ts
  - depends: RESP-E2E-PROJECTS
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `docs/design/responsive-layout.md`, `apps/staff-web/src/app/routes.tsx`.
    Start with the existing assertions in `apps/staff-web/e2e/smoke.spec.ts` and add a failing case for the acceptance gap.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `apps/staff-web/e2e/accessibilityScanResponsive.spec.ts`.
    Run `bash apps/staff-web/check.sh` and confirm 100% coverage on changed source files.

    1. Read `e2e/playwright.config.ts`, `e2e/smoke.spec.ts` and `docs/design/responsive-layout.md`. List the routes from `src/app/routes.tsx` and the feature `routes.ts` files and use synthetic data only.
    2. An axe scan with the WCAG 2.2 AA tags at 360, 768 and 1280 px, in English and Arabic, reports zero serious or critical violations on every route (WEB-10).
    3. Write `e2e/accessibilityScanResponsive.spec.ts` with one test per route and project where it applies; failure messages name the route, project and measurement. If a check fails on a real defect, do not weaken the test and do not leave it red: mark that single test `test.fixme` with the reason and the route, and list each defect under `## Why this is not finished` so a follow-up task is written.
    4. Run `npx playwright test --config e2e/playwright.config.ts e2e/accessibilityScanResponsive.spec.ts` if browsers are installed in the sandbox, otherwise run `npx playwright test --list` and say so in the notes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

