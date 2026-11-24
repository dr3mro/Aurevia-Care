- [ ] SPEC-INDEX-ARCHITECTURE · Spec clauses: index of the `architecture` clause notes with status counts
  - requirements: PRD-01
  - scope: docs/spec/clauses/architecture/index.md
  - depends: SPEC-LAYER-WEB-MOBILE-1, SPEC-LAYER-WEB-MOBILE-2, SPEC-LAYER-WEB-MOBILE-3, SPEC-LAYER-WEB-MOBILE-4, SPEC-LAYER-BACKEND-1, SPEC-LAYER-BACKEND-2, SPEC-LAYER-BACKEND-3, SPEC-LAYER-BACKEND-4, SPEC-LAYER-STORAGE-1, SPEC-LAYER-STORAGE-2, SPEC-LAYER-STORAGE-3, SPEC-LAYER-STORAGE-4, SPEC-LAYER-STORAGE-5, SPEC-LAYER-IDENTITY-1, SPEC-LAYER-IDENTITY-2, SPEC-LAYER-IDENTITY-3, SPEC-LAYER-IDENTITY-4, SPEC-LAYER-IDENTITY-5, SPEC-LAYER-INTEROPERABILITY-1, SPEC-LAYER-INTEROPERABILITY-2, SPEC-LAYER-INTEROPERABILITY-3, SPEC-LAYER-INTEROPERABILITY-4
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: SPEC-LAYER-WEB-MOBILE-1, SPEC-LAYER-WEB-MOBILE-2, SPEC-LAYER-WEB-MOBILE-3, SPEC-LAYER-WEB-MOBILE-4, SPEC-LAYER-BACKEND-1, SPEC-LAYER-BACKEND-2, SPEC-LAYER-BACKEND-3, SPEC-LAYER-BACKEND-4, SPEC-LAYER-STORAGE-1, SPEC-LAYER-STORAGE-2, SPEC-LAYER-STORAGE-3, SPEC-LAYER-STORAGE-4, SPEC-LAYER-STORAGE-5, SPEC-LAYER-IDENTITY-1, SPEC-LAYER-IDENTITY-2, SPEC-LAYER-IDENTITY-3, SPEC-LAYER-IDENTITY-4, SPEC-LAYER-IDENTITY-5, SPEC-LAYER-INTEROPERABILITY-1, SPEC-LAYER-INTEROPERABILITY-2, SPEC-LAYER-INTEROPERABILITY-3, SPEC-LAYER-INTEROPERABILITY-4.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `docs/spec/clauses/architecture/index.md`.

    1. List every `.md` file in `docs/spec/clauses/architecture` except `index.md`. Read the `## Status` line of each.
    2. Write `docs/spec/clauses/architecture/index.md`: a table with the clause (first sentence of `## Clause`, shortened), the requirement id, the status and a link to the note, then a count per status at the top.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

