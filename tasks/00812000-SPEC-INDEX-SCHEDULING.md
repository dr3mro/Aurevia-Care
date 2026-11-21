- [ ] SPEC-INDEX-SCHEDULING · Spec clauses: index of the `scheduling` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/scheduling/index.md
  - depends: SPEC-CLN-01-C01, SPEC-CLN-01-C02, SPEC-CLN-01-C03, SPEC-CLN-01-C04, SPEC-CLN-01-C05, SPEC-CLN-02-C01, SPEC-CLN-02-C02, SPEC-CLN-02-C03, SPEC-CLN-02-C04, SPEC-CLN-02-C05
  - details:
    1. List every `.md` file in `docs/spec/clauses/scheduling` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/scheduling/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

