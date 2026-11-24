- [x] RESP-APP-README · Patient app README: tablets, landscape and how to test them
  - evidence: PR #648 merged to main (reconciled 2026-11-23)
  - pr: #648
  - attempts: 1
  - requirements: PAT-01, PAT-02, UX-01
  - scope: apps/patient-mobile/README.md
  - depends: RESP-APP-LAYOUT-HOOK
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/check-docs.py`, `apps/patient-mobile/README.md`.

    1. Read `apps/patient-mobile/README.md` and `docs/decisions/0005-patient-app-tablets.md`.
    2. Add a section "Tablets and landscape": the window classes, the hook, how to run the app on an iPad and an Android tablet simulator, and the screen-reader checks for both. Keep every other line.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

