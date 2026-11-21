- [ ] SPEC-INDEX-DOCUMENTS · Spec clauses: index of the `documents` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/documents/index.md
  - depends: SPEC-CLN-07-C01, SPEC-CLN-07-C02, SPEC-CLN-07-C03, SPEC-CLN-07-C04, SPEC-CLN-07-C05, SPEC-ARC-06-C01, SPEC-ARC-06-C02, SPEC-ARC-06-C03, SPEC-ARC-06-C04, SPEC-ARC-06-C05
  - details:
    1. List every `.md` file in `docs/spec/clauses/documents` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/documents/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

