- [x] RESP-DRAWER · Staff web: off-canvas navigation drawer component for phones (new files)
  - pr: #608
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/app/NavigationDrawer.tsx, apps/staff-web/tests/app/NavigationDrawer.test.tsx
  - depends: RESP-BREAKPOINTS, RESP-STYLESHEET
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/staff-web/src/app/navigation.ts`, `docs/design/responsive-layout.md`.

    1. Read `src/app/AppShell.tsx`, `src/app/navigation.ts`, `docs/design/accessibility-patterns.md` and `docs/design/responsive-layout.md`.
    2. Write `NavigationDrawer`: a menu button (`aria-expanded`, `aria-controls`, label from the shell messages) that opens a modal navigation drawer with the same role-scoped groups and links the rail shows. It traps focus, closes on Escape, on a link choice and on a backdrop tap, returns focus to the menu button, locks page scroll while open, has a swipe-free close button (never gesture-only), opens from the inline-start side so right-to-left mirrors it, and respects `prefers-reduced-motion`. Items the role cannot use are not rendered.
    3. Tests: open and close by button, Escape and backdrop; focus trap order; focus return; the links equal `navigationGroupsFor(role)`; right-to-left mirroring through the `dir` attribute; reduced motion.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

