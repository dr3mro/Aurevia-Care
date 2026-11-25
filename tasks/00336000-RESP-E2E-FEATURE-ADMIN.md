- [-] RESP-E2E-FEATURE-ADMIN · Staff web end-to-end: the admin screens work on phone, tablet and desktop
  - agent: swarm-2
  - attempts: 22
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/e2e/responsive-admin.spec.ts
  - depends: RESP-E2E-PROJECTS
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `apps/staff-web/e2e/playwright.config.ts`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `apps/staff-web/e2e/responsive-admin.spec.ts`.
    Run `bash apps/staff-web/check.sh` and confirm 100% coverage on changed source files.

    1. Read the `admin` feature under `apps/staff-web/src/features` (facilities, staff, configuration and audit) and `e2e/playwright.config.ts`.
    2. Write `e2e/responsive-admin.spec.ts`: for every screen of the feature, at the phone, tablet and desktop projects, perform the main task of that screen with the keyboard and with touch (tap), and check no horizontal scroll, every value that is visible on desktop is still reachable on phone (cards or scroll region), and the screen works in English and Arabic.
    3. A real defect becomes a `test.fixme` with the reason and is listed under `## Why this is not finished`. Never weaken an assertion.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

