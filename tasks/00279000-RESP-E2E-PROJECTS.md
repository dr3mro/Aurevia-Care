- [x] RESP-E2E-PROJECTS · Staff web end-to-end: add phone, tablet and desktop device projects to Playwright
  - evidence: merged on main 716b1751 (#891)
  - agent: swarm-6
  - attempts: 3
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/e2e/playwright.config.ts
  - depends: RESP-SHELL
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/staff-web/e2e/playwright.config.ts`, `docs/design/responsive-layout.md`.

    1. Read `e2e/playwright.config.ts`, `e2e/smoke.spec.ts` and `docs/design/responsive-layout.md`.
    2. Add projects: phone portrait 360x740 and 390x844 (touch, mobile), phone landscape 844x390, tablet portrait 768x1024 and 820x1180, tablet landscape 1024x768 and 1180x820, laptop 1280x720 and desktop 1920x1080. Use Chromium for all, and add WebKit and Firefox for the 390x844, 820x1180 and 1280x720 sizes only when the browsers are installed in CI (keep the existing project and its timeouts). Name projects by device class, for example `phone-portrait`.
    3. Do not change `smoke.spec.ts`. Run `npx playwright test --list` and paste the project names.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

