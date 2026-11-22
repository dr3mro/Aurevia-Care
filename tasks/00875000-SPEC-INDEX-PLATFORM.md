- [ ] SPEC-INDEX-PLATFORM · Spec clauses: index of the `platform` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/platform/index.md
  - depends: SPEC-WEB-07-C01, SPEC-WEB-07-C02, SPEC-WEB-07-C03, SPEC-WEB-07-C04, SPEC-WEB-07-C05, SPEC-WEB-08-C01, SPEC-WEB-08-C02, SPEC-WEB-08-C03, SPEC-WEB-08-C04, SPEC-WEB-09-C01, SPEC-WEB-09-C02, SPEC-WEB-09-C03, SPEC-WEB-09-C04, SPEC-WEB-09-C05, SPEC-WEB-09-C06, SPEC-WEB-10-C01, SPEC-WEB-10-C02, SPEC-WEB-10-C03, SPEC-WEB-13-C01, SPEC-WEB-13-C02, SPEC-WEB-13-C03, SPEC-WEB-13-C04
  - details:
    1. List every `.md` file in `docs/spec/clauses/platform` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/platform/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

