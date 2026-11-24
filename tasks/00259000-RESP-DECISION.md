- [x] RESP-DECISION · Decision record: the staff web works on phones and tablets, not only 768 px and wider
  - evidence: PR #370 merged to main (reconciled 2026-11-23)
  - pr: #370
  - attempts: 1
  - requirements: WEB-07, UX-01
  - scope: docs/decisions/0004-responsive-staff-web.md
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/decisions/template.md`, `scripts/check-docs.py`.

    1. Read `docs/decisions/template.md`, `docs/decisions/README.md`, `docs/design/screens-and-navigation.md` (supported widths) and WEB-07 in `PROJECT_V1_SPEC.md`. Today WEB-07 and the design docs say the staff web shows a plain "use a larger screen" notice below 768 px.
    2. Write `docs/decisions/0004-responsive-staff-web.md` from the template. Decision: the staff web is fully usable on phones (320 to 767 px), tablets (768 to 1279 px, both orientations) and desktop (1280 px and above). State the breakpoints (compact 0, medium 768, expanded 1280), that navigation is an off-canvas drawer on phones, an icon rail on tablets and a full rail on desktop, that tables become labelled cards or labelled scroll regions on phones without hiding any clinical column, that touch targets are at least 44 by 44 CSS px for coarse pointers, that text works at 200 percent zoom and reflows at 320 px (WCAG 1.4.10), and that there is no offline clinical editing and no service worker cache of clinical data (PRD-06, PAT-02 spirit).
    3. Record the consequences: WEB-07 in `PROJECT_V1_SPEC.md` must be amended by a person (the swarm may not edit the specification); until then the compact layout floor in code follows this decision. List the supported browser matrix for phones (latest two majors of iOS Safari, Chrome on Android, Samsung Internet is out of scope). Status: Proposed, with the owner Product.
    4. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

