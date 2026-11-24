- [x] RESP-VIEWPORT · Staff web: viewport meta, theme colour and safe-area support in `index.html`
  - evidence: PR #526 merged to main (reconciled 2026-11-23)
  - pr: #526
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: apps/staff-web/index.html, apps/staff-web/tests/indexability.test.ts
  - depends: RESP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.

    1. Read `apps/staff-web/index.html` and `tests/indexability.test.ts`.
    2. Set the viewport meta to `width=device-width, initial-scale=1, viewport-fit=cover` (never `user-scalable=no` or `maximum-scale`, which would break zoom for low-vision users). Add `<meta name="theme-color">` for light and dark with `media` attributes. Keep `noindex, nofollow` and `color-scheme`.
    3. Extend `indexability.test.ts` (keep its existing assertions) with: the viewport allows zoom, includes `viewport-fit=cover`, and both theme colours exist.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

