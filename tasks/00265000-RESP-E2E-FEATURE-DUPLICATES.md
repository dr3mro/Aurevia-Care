- [ ] RESP-E2E-FEATURE-DUPLICATES · Staff web end-to-end: the duplicates screens work on phone, tablet and desktop
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/e2e/responsive-duplicates.spec.ts
  - depends: RESP-E2E-PROJECTS
  - details:
    1. Read the `duplicates` feature under `apps/staff-web/src/features` (duplicate review) and `e2e/playwright.config.ts`.
    2. Write `e2e/responsive-duplicates.spec.ts`: for every screen of the feature, at the phone, tablet and desktop projects, perform the main task of that screen with the keyboard and with touch (tap), and check no horizontal scroll, every value that is visible on desktop is still reachable on phone (cards or scroll region), and the screen works in English and Arabic.
    3. A real defect becomes a `test.fixme` with the reason and is listed under `## Why this is not finished`. Never weaken an assertion.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

