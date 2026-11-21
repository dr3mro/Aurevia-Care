- [ ] RESP-E2E-SOFT-KEYBOARD-AND-FORMS · Staff web end-to-end: softKeyboardAndForms across phone, tablet and desktop
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/e2e/softKeyboardAndForms.spec.ts
  - depends: RESP-E2E-PROJECTS
  - details:
    1. Read `e2e/playwright.config.ts`, `e2e/smoke.spec.ts` and `docs/design/responsive-layout.md`. List the routes from `src/app/routes.tsx` and the feature `routes.ts` files and use synthetic data only.
    2. On the phone projects the focused field is never hidden by the sticky action bar, the primary action is reachable, field types open the right keyboard (`inputmode`), and a conflict or error summary is visible without scrolling horizontally.
    3. Write `e2e/softKeyboardAndForms.spec.ts` with one test per route and project where it applies; failure messages name the route, project and measurement. If a check fails on a real defect, do not weaken the test and do not leave it red: mark that single test `test.fixme` with the reason and the route, and list each defect under `## Why this is not finished` so a follow-up task is written.
    4. Run `npx playwright test --config e2e/playwright.config.ts e2e/softKeyboardAndForms.spec.ts` if browsers are installed in the sandbox, otherwise run `npx playwright test --list` and say so in the notes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

