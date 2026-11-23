- [x] RESP-STYLESHEET · Staff web: responsive base stylesheet with layout utility classes (new file)
  - pr: #544
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/src/theme/responsive.css, apps/staff-web/tests/theme/responsiveStylesheet.test.ts
  - depends: RESP-DESIGN
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/design/responsive-layout.md`, `docs/design/token-contract.md`.

    1. Read `docs/design/responsive-layout.md`, `docs/design/token-contract.md` and the inline styles in `apps/staff-web/src/app/AppShell.tsx`. The staff web has no stylesheet today; media queries cannot be written as inline styles.
    2. Write `responsive.css` using only CSS logical properties and the existing token names as CSS variables with the same fallback values: `.page` (padding and a maximum inline size), `.stack`, `.cluster` (wrapping row), `.grid-2` (one column on phones, two from 768 px), `.table-region` (scroll region), `.card-list` (card layout for rows), `.only-compact`, `.hide-compact`, `.touch` (minimum 44 px block and inline size when `(pointer: coarse)`), `.field` (full width, 16 px font so iOS does not zoom), `.sheet` (full-screen dialog on phones), `.app-shell` (drawer, rail and full rail layouts), safe-area padding with `env(safe-area-inset-*)`, `prefers-reduced-motion`, `overflow-wrap: anywhere` for long text, and `min-width: 0` on flex children. Keep the existing `.skip-link` and focus ring behaviour; do not remove them.
    3. Tests: read the file as text and assert there is no physical property (`margin-left`, `padding-right`, `left:` outside the skip link, `float`), that every class above exists, that the media query widths are 768 and 1280 only, and that the file is under 6 KB gzip.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

