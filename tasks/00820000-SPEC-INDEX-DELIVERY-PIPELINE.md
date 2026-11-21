- [ ] SPEC-INDEX-DELIVERY-PIPELINE · Spec clauses: index of the `delivery-pipeline` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/delivery-pipeline/index.md
  - depends: SPEC-OPS-06-C01, SPEC-OPS-06-C02, SPEC-OPS-06-C03, SPEC-OPS-06-C04, SPEC-TESTRULES
  - details:
    1. List every `.md` file in `docs/spec/clauses/delivery-pipeline` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/delivery-pipeline/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

