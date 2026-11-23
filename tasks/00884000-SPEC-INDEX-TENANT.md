- [ ] SPEC-INDEX-TENANT · Spec clauses: index of the `tenant` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/tenant/index.md
  - depends: SPEC-ARC-01-C01, SPEC-ARC-01-C02, SPEC-ARC-01-C03, SPEC-ARC-01-C04, SPEC-ARC-01-C05, SPEC-ARC-01-C06, SPEC-ARC-01-C07, SPEC-OPS-07-C01, SPEC-OPS-07-C02, SPEC-OPS-07-C03, SPEC-OPS-08-C01, SPEC-OPS-08-C02, SPEC-OPS-08-C03, SPEC-OPS-08-C04
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: SPEC-OPS-07-C01, SPEC-OPS-07-C02, SPEC-OPS-07-C03, SPEC-OPS-08-C01, SPEC-OPS-08-C02, SPEC-OPS-08-C03, SPEC-OPS-08-C04.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `docs/spec/clauses/tenant/index.md`.

    1. List every `.md` file in `docs/spec/clauses/tenant` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/tenant/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

