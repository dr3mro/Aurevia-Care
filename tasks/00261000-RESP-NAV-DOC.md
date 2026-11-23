- [x] RESP-NAV-DOC · Docs: update the supported widths in the screen and navigation map
  - pr: #512
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: docs/design/screens-and-navigation.md
  - depends: RESP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/design/screens-and-navigation.md`, `scripts/check-docs.py`.

    1. Read `docs/design/screens-and-navigation.md` and `docs/decisions/0004-responsive-staff-web.md`.
    2. Replace the statement that below 768 px the staff web shows a "use a larger screen" notice with the responsive rules from the decision: drawer on phones, icon rail from 768 to 1279 px, full rail from 1280 px. Update the navigation row and the supported widths paragraph only; keep every other line.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

