- [ ] RESP-README · Staff web README: how to test and develop responsive layouts
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/README.md
  - depends: RESP-E2E-PROJECTS
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `docs/design/responsive-layout.md`, `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Run `bash apps/staff-web/check.sh` and confirm 100% coverage on changed source files.

    1. Read `apps/staff-web/README.md` and `docs/design/responsive-layout.md`.
    2. Add one section "Responsive layout": the three width classes, the stylesheet and hook, how to run the device projects (`npm run e2e -- --project=phone-portrait`), how to emulate a phone in a desktop browser, and the rule that no feature may hide a clinical field on a small screen. Keep every other line.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

