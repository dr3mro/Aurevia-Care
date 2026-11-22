- [x] RESP-SHELL · Staff web: responsive application shell (drawer, icon rail, full rail)
  - pr: #704
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/app/AppShell.tsx, apps/staff-web/tests/app/AppShell.responsive.test.tsx
  - depends: RESP-BREAKPOINTS, RESP-STYLESHEET, RESP-DRAWER, RESP-ORGSWITCH, RESP-BUTTON
  - details:
    1. Read `AppShell.tsx`, `navigation.ts`, `NavigationDrawer.tsx`, `OrgSwitcher.tsx`, `docs/design/screens-and-navigation.md` and `docs/design/responsive-layout.md`.
    2. Use `useBreakpoint`: compact shows a slim header (menu button, title, organization switcher, user menu collapsed into one button) and the drawer; medium shows the icon rail with accessible names and tooltips that also work on focus and long press; expanded shows the full rail. Keep the skip link, the landmarks, focus to `<main>` on route change, the theme toggle, the locale toggle and sign out; they may move into the user menu on phones but stay reachable in two taps. The rail is on the inline-start side in both directions.
    3. The main content uses the page class and never scrolls horizontally. Safe-area insets apply to the header and the bottom edge.
    4. Tests: the structure at compact, medium and expanded; the landmarks at each; skip link; focus moves to main on navigation; right-to-left; the existing `AppShell.test.tsx` still passes untouched.
    5. Do not edit `routes.tsx`, `router.tsx` or `navigation.ts`.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

