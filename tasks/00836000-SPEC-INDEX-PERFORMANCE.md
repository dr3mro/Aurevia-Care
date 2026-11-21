- [ ] SPEC-INDEX-PERFORMANCE · Spec clauses: index of the `performance` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/performance/index.md
  - depends: SPEC-OPS-03-C01, SPEC-OPS-03-C02, SPEC-OPS-03-C03
  - details:
    1. List every `.md` file in `docs/spec/clauses/performance` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/performance/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

