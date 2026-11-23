- [x] RESP-INDEX · Docs: link the responsive layout guide and the two decision records
  - pr: #543
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: docs/reference/index.md
  - depends: RESP-DESIGN, RESP-APP-DECISION
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/design/responsive-layout.md`, `scripts/check-docs.py`.

    1. Read `docs/reference/index.md`. List which of these exist now: `docs/design/responsive-layout.md`, `docs/decisions/0004-responsive-staff-web.md`, `docs/decisions/0005-patient-app-tablets.md`.
    2. Add one line for each page that exists and is not linked, with a one-line description, in the section where it fits. Do not link a page that does not exist.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

## Pending human work, prepared for the swarm (decision packs, evidence packs and one isolation test)

The human tasks `S-G1-09`, `S-G2-11`, `S-G3-13`, `S-G4-07`, `S-G5-01` to `S-G5-04`, `S-G5-12` and `S-G0-04` need a person to accept or decide. These tasks prepare the evidence and the decision packs so a person only has to read and sign. They never record an approval.

