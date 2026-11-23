- [ ] SPEC-INDEX-CLAUSE · Spec clauses: index of the `it` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/it/index.md
  - depends: SPEC-SCENARIO-MISSED-COVERAGE-1, SPEC-SCENARIO-MISSED-COVERAGE-2, SPEC-SCENARIO-MISSED-COVERAGE-3, SPEC-SCENARIO-RETRACTED-DOCUMENT-1, SPEC-SCENARIO-RETRACTED-DOCUMENT-2, SPEC-SCENARIO-RETRACTED-DOCUMENT-3, SPEC-SCENARIO-PATIENT-CONTACTS-1, SPEC-SCENARIO-PATIENT-CONTACTS-2, SPEC-SCENARIO-PATIENT-CONTACTS-3, SPEC-SCENARIO-FAILED-EXCHANGE-1, SPEC-SCENARIO-FAILED-EXCHANGE-2, SPEC-SCENARIO-PATIENT-LINKAGE-1, SPEC-SCENARIO-PATIENT-LINKAGE-2, SPEC-SCENARIO-PATIENT-LINKAGE-3, SPEC-SCENARIO-SERVICE-FAILS-1, SPEC-SCENARIO-SERVICE-FAILS-2, SPEC-SCENARIO-SERVICE-FAILS-3
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: SPEC-SCENARIO-MISSED-COVERAGE-1, SPEC-SCENARIO-MISSED-COVERAGE-2, SPEC-SCENARIO-MISSED-COVERAGE-3, SPEC-SCENARIO-RETRACTED-DOCUMENT-1, SPEC-SCENARIO-RETRACTED-DOCUMENT-2, SPEC-SCENARIO-RETRACTED-DOCUMENT-3, SPEC-SCENARIO-PATIENT-CONTACTS-1, SPEC-SCENARIO-PATIENT-CONTACTS-2, SPEC-SCENARIO-PATIENT-CONTACTS-3, SPEC-SCENARIO-FAILED-EXCHANGE-1, SPEC-SCENARIO-FAILED-EXCHANGE-2, SPEC-SCENARIO-PATIENT-LINKAGE-1, SPEC-SCENARIO-PATIENT-LINKAGE-2, SPEC-SCENARIO-PATIENT-LINKAGE-3, SPEC-SCENARIO-SERVICE-FAILS-1, SPEC-SCENARIO-SERVICE-FAILS-2, SPEC-SCENARIO-SERVICE-FAILS-3.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `docs/spec/clauses/it/index.md`.

    1. List every `.md` file in `docs/spec/clauses/it` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/it/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

