- [ ] SPEC-LAYER-STORAGE-1 · Spec section 7 decision "Storage" part 1 (evidence note): Managed PostgreSQL for authoritative records and…
  - requirements: ARC-06
  - scope: docs/spec/clauses/architecture/storage-managed-postgresql-authoritative-records-transactional.md
  - details:
    1. Read this clause of `SMARTCLINIC_V1_SPEC.md` (ARC-06) and the whole requirement it belongs to: "Architecture decision Storage: Managed PostgreSQL for authoritative records and transactional outbox." Then find where the code implements it today: the code, workflows, infrastructure files and documents in this repository. Write down the class, file or workflow names you find, or write that you found none.
    2. Write `docs/spec/clauses/architecture/storage-managed-postgresql-authoritative-records-transactional.md` with these headings: `## Clause` (the sentence above, word for word); `## Status` (exactly one of: Built and verified, Built but not verified, Partly built, Not built); `## Evidence` (the files, tests, workflow runs or commands that show it, with relative links to files that exist today; nothing invented); `## Gap` (what is missing, which files would change, and how many separate tasks it needs); `## How to verify` (the exact commands or checks a reviewer runs, or the human evidence a site must produce before G5).
    3. A clause that needs a human, a partner, a cloud account or a signed decision (not code) gets status Not built unless the repository already holds that evidence; say who must supply it. Do not claim evidence that is not in the repository.
    4. Do not change code, workflows, contracts or migrations. Run `python3 -I scripts/check-docs.py` and paste the result.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

