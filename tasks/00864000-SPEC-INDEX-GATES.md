- [ ] SPEC-INDEX-GATES · Spec clauses: index of the `gates` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/gates/index.md
  - depends: SPEC-G0-01, SPEC-G0-02, SPEC-G0-03, SPEC-G1-01, SPEC-G1-02, SPEC-G1-03, SPEC-G1-04, SPEC-G1-05, SPEC-G2-01, SPEC-G2-02, SPEC-G2-03, SPEC-G2-04, SPEC-G3-01, SPEC-G3-02, SPEC-G3-03, SPEC-G3-04, SPEC-G3-05, SPEC-G4-01, SPEC-G4-02, SPEC-G4-03, SPEC-G4-04, SPEC-G4-05
  - details:
    1. List every `.md` file in `docs/spec/clauses/gates` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/gates/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

