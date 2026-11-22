- [ ] SPEC-INDEX-MESSAGING · Spec clauses: index of the `messaging` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/messaging/index.md
  - depends: SPEC-MSG-01-C01, SPEC-MSG-01-C02, SPEC-MSG-01-C03, SPEC-MSG-01-C04, SPEC-MSG-01-C05, SPEC-MSG-02-C01, SPEC-MSG-02-C02, SPEC-MSG-02-C03, SPEC-MSG-02-C04, SPEC-MSG-02-C05, SPEC-MSG-02-C06, SPEC-MSG-02-C07, SPEC-MSG-03-C01, SPEC-MSG-03-C02, SPEC-MSG-03-C03, SPEC-MSG-03-C04, SPEC-MSG-03-C05, SPEC-MSG-03-C06, SPEC-MSG-03-C07, SPEC-MSG-03-C08, SPEC-MSG-03-C09, SPEC-MSG-04-C01, SPEC-MSG-04-C02, SPEC-MSG-04-C03, SPEC-MSG-04-C04, SPEC-MSG-04-C05, SPEC-MSG-04-C06, SPEC-MSG-05-C01, SPEC-MSG-05-C02, SPEC-MSG-05-C03, SPEC-MSG-05-C04, SPEC-MSG-05-C05, SPEC-MSG-05-C06, SPEC-MSG-06-C01, SPEC-MSG-06-C02, SPEC-MSG-06-C03, SPEC-MSG-06-C04, SPEC-MSG-06-C05, SPEC-MSG-06-C06, SPEC-MSG-07-C01, SPEC-MSG-07-C02, SPEC-MSG-07-C03, SPEC-MSG-07-C04, SPEC-MSG-07-C05, SPEC-MSG-07-C06, SPEC-MSG-07-C07, SPEC-ARC-07-C01, SPEC-ARC-07-C02, SPEC-ARC-07-C03, SPEC-ARC-07-C04, SPEC-ARC-07-C05, SPEC-ARC-07-C06, SPEC-ARC-07-C07, SPEC-ARC-07-C08
  - details:
    1. List every `.md` file in `docs/spec/clauses/messaging` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/messaging/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

