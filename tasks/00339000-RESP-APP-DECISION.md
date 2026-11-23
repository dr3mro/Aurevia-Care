- [x] RESP-APP-DECISION · Decision record: the patient app supports tablets and landscape
  - pr: #362
  - attempts: 1
  - requirements: PAT-01, PAT-02, UX-01
  - scope: docs/decisions/0005-patient-app-tablets.md
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/decisions/template.md`, `scripts/check-docs.py`.

    1. Read `docs/decisions/template.md`, `apps/patient-mobile/app.json` (iOS `supportsTablet` is false and orientation is portrait only) and the section 7 mobile row in `SMARTCLINIC_V1_SPEC.md`.
    2. Write `docs/decisions/0005-patient-app-tablets.md`: the patient app runs on iPhone, iPad, Android phones and Android tablets in portrait and landscape; layout classes compact, regular and large by window width (600 and 840 dp) rather than by device type; two-pane inbox and thread on large widths; text scales with system font size up to 200 percent; split-screen and slide-over on iPad keep working; screen-reader flows (UX-01) are tested on phone and tablet. Status Proposed, owner Product.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

