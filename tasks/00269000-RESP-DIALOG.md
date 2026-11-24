- [x] RESP-DIALOG · Staff web: responsive `Dialog` component
  - evidence: PR #596 merged to main (reconciled 2026-11-23)
  - pr: #596
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/components/Dialog.tsx, apps/staff-web/tests/components/Dialog.responsive.test.tsx
  - depends: RESP-BREAKPOINTS, RESP-STYLESHEET
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/design/responsive-layout.md`, `apps/staff-web/src/theme/useBreakpoint.ts`.

    1. Read the component, its existing test, `docs/design/responsive-layout.md`, `apps/staff-web/src/theme/responsive.css` and `apps/staff-web/src/theme/useBreakpoint.ts`.
    2. Make it work at 320, 360, 390, 768, 820, 1024 and 1280 px, left-to-right and right-to-left: no horizontal page scroll; no clipped or overlapping text; long English and Arabic strings wrap; columns stack on phones; coarse pointers get 44 by 44 CSS px targets; inputs use 16 px text; safe-area insets are respected; everything uses CSS logical properties; no clinical column or field is hidden with `display: none`; text at 200 percent zoom stays readable. On phones a dialog is a full-screen sheet with a sticky header and action bar, scroll lock on the page behind it, focus trapped and returned, Escape and a visible close button; on tablets and desktop it stays a centred dialog with a maximum width.
    3. Use the classes in `responsive.css` and the hook rather than new CSS. If a class you need is missing, do not edit the stylesheet: use logical inline styles and record the missing class under `## Lessons for workers`.
    4. Add a new test file (named in `scope`) that mocks `matchMedia` for compact, medium and expanded and asserts the structure at each (for example drawer or rail, cards or table, stacked or side-by-side fields), the labels and roles, the keyboard order, and the right-to-left case. Existing tests must still pass. jsdom cannot lay out pixels, so test classes, attributes and structure, and leave pixel checks to the Playwright tasks.
    5. Do not change behaviour, text, API calls or exported names.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

