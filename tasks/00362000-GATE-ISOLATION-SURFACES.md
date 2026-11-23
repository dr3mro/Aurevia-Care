- [x] GATE-ISOLATION-SURFACES · G0 isolation across API, search, files, workers and exports: two tenants with identical local IDs (test and gap note)
  - pr: #377
  - attempts: 1
  - requirements: ARC-02, IAM-01
  - scope: apps/api/src/test/java/com/aurevia/smartclinic/tenancy/clauses/IsolationAcrossSurfacesTest.java, docs/gates/g0-isolation-surfaces.md
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `docs/gates/g0-isolation-surfaces.md`, `scripts/check-docs.py`.

    1. Read `S-G0-04` in `TODO.md` (its blocker says the search, files, workers and exports surfaces did not all exist), the G0 row of section 9 of `SMARTCLINIC_V1_SPEC.md`, ARC-02, and the tenant, patient, documents, events and tenant-export packages under `apps/api/src/main/java/com/aurevia/smartclinic`. List which of the five surfaces exist today and the class that serves each.
    2. Write `IsolationAcrossSurfacesTest` with the same database fixtures the neighbouring integration tests use: create two organizations that reuse the same local identifiers (the same MRN, the same order code, the same file name) and prove, for each surface that exists, that tenant A never sees, reads, downloads, exports or processes tenant B data, and that a missing tenant context fails closed. Surfaces: API reads and writes; patient search; file and attachment retrieval; worker or outbox processing; tenant export.
    3. For a surface that does not exist yet, write the full test and mark it `@Disabled("GAP: <what is missing>")`; do not write production code and do not leave a failing test.
    4. Write `docs/gates/g0-isolation-surfaces.md`: a table with one row per surface (exists or not, class, test method, status Proven, Partly proven or Not built) and a list of the stories still needed to create each missing surface. Run `python3 -I scripts/check-docs.py` and the backend skill checks and paste the results.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

