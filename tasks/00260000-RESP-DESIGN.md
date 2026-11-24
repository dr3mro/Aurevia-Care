- [x] RESP-DESIGN · Design guide: responsive layout patterns for the staff web
  - evidence: PR #521 merged to main (reconciled 2026-11-23)
  - pr: #521
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: docs/design/responsive-layout.md
  - depends: RESP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/design/responsive-layout.md`, `docs/design/accessibility-patterns.md`.

    1. Read `docs/decisions/0004-responsive-staff-web.md`, `docs/design/screen-recipe.md`, `docs/design/token-contract.md`, `docs/design/accessibility-patterns.md` and `docs/design/rtl-and-localization.md`.
    2. Write `docs/design/responsive-layout.md`: the breakpoints and what changes at each; the page grid and maximum content width; navigation per breakpoint; a table of every component pattern (data table, form, dialog, tab strip, toolbar, badge row, note editor, chart timeline, banner) with its phone, tablet and desktop behaviour; touch target and spacing rules; safe-area insets; on-screen keyboard behaviour (the focused field stays visible and the primary action reachable); orientation change without losing typed work (WEB-09); pointer versus touch (hover is never the only way); right-to-left behaviour; zoom and text-spacing rules; and the test method for each rule (jsdom, Playwright projects, axe).
    3. Use only logical CSS properties in every example. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

