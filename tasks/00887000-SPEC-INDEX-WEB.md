- [ ] SPEC-INDEX-WEB · Spec clauses: index of the `web` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/web/index.md
  - depends: SPEC-ARC-03-C01, SPEC-ARC-03-C02, SPEC-ARC-03-C03, SPEC-ARC-03-C04, SPEC-ARC-03-C05, SPEC-ARC-03-C06, SPEC-ARC-03-C07, SPEC-ARC-03-C08, SPEC-ARC-04-C01, SPEC-ARC-04-C02, SPEC-ARC-04-C03, SPEC-ARC-04-C04, SPEC-ARC-04-C05, SPEC-ARC-04-C06, SPEC-WEB-02-C01, SPEC-WEB-02-C02, SPEC-WEB-02-C03, SPEC-WEB-02-C04, SPEC-WEB-02-C05, SPEC-WEB-02-C06, SPEC-WEB-03-C01, SPEC-WEB-03-C02, SPEC-WEB-03-C03, SPEC-WEB-03-C04, SPEC-WEB-03-C05, SPEC-WEB-04-C01, SPEC-WEB-04-C02, SPEC-WEB-04-C03, SPEC-WEB-04-C04, SPEC-WEB-04-C05, SPEC-WEB-04-C06, SPEC-WEB-05-C01, SPEC-WEB-05-C02, SPEC-WEB-05-C03, SPEC-WEB-05-C04, SPEC-WEB-05-C05, SPEC-WEB-05-C06
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: SPEC-ARC-03-C01, SPEC-ARC-03-C02, SPEC-ARC-03-C03, SPEC-ARC-03-C04, SPEC-ARC-03-C05, SPEC-ARC-03-C06, SPEC-ARC-03-C07, SPEC-ARC-03-C08, SPEC-ARC-04-C01, SPEC-ARC-04-C02, SPEC-ARC-04-C03, SPEC-ARC-04-C04, SPEC-ARC-04-C05, SPEC-ARC-04-C06, SPEC-WEB-02-C01, SPEC-WEB-02-C02, SPEC-WEB-02-C03, SPEC-WEB-02-C04, SPEC-WEB-02-C05, SPEC-WEB-02-C06, SPEC-WEB-03-C01, SPEC-WEB-03-C02, SPEC-WEB-03-C03, SPEC-WEB-03-C04, SPEC-WEB-03-C05, SPEC-WEB-04-C01, SPEC-WEB-04-C02, SPEC-WEB-04-C03, SPEC-WEB-04-C04, SPEC-WEB-04-C05, SPEC-WEB-04-C06, SPEC-WEB-05-C01, SPEC-WEB-05-C02, SPEC-WEB-05-C03, SPEC-WEB-05-C04, SPEC-WEB-05-C05, SPEC-WEB-05-C06.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `docs/spec/clauses/web/index.md`.

    1. List every `.md` file in `docs/spec/clauses/web` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/web/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

