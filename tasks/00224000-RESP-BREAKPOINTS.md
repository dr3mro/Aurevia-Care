- [x] RESP-BREAKPOINTS · Staff web: breakpoint tokens and a `useBreakpoint` hook (new files)
  - pr: #515
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/theme/breakpoints.ts, apps/staff-web/src/theme/useBreakpoint.ts, apps/staff-web/tests/theme/breakpoints.test.ts
  - depends: RESP-DECISION
  - details:
    1. Read `apps/staff-web/src/theme/tokens.ts`, `ThemeProvider.tsx` and `docs/decisions/0004-responsive-staff-web.md`.
    2. Add `breakpoints.ts` exporting the widths (medium 768, expanded 1280), the media query strings and a pure function `breakpointFor(width)` returning `compact`, `medium` or `expanded`. Add `useBreakpoint.ts`: a hook built on `window.matchMedia` that returns the current class, updates on change, cleans up its listener and falls back to `expanded` when `matchMedia` does not exist (server render and old tests). Also export `usePointerCoarse()` (query `(pointer: coarse)`) and `useOrientation()`.
    3. Tests: boundary widths 767, 768, 1279 and 1280; the hook updates when a mocked media query fires; listeners are removed on unmount; the fallback without `matchMedia`.
    4. Do not edit `ThemeProvider.tsx` or `tokens.ts`.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

