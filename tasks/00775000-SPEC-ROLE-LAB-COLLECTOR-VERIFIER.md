- [-] SPEC-ROLE-LAB-COLLECTOR-VERIFIER · Spec section 2 role matrix (API test): Lab collector / verifier
  - agent: swarm-10
  - attempts: 1
  - requirements: IAM-01, LAB-02
  - scope: apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleLabCollectorVerifierTest.java, docs/spec/clauses/authz/role-lab-collector-verifier.md
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Confirm the owning entry point and scope with the maintainer before implementation.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleLabCollectorVerifierTest.java`, `docs/spec/clauses/authz/role-lab-collector-verifier.md`.

    1. Read this clause of `SMARTCLINIC_V1_SPEC.md` (IAM-01) and the whole requirement it belongs to: "Role Lab collector / verifier: allowed workflow: Accession and collect / verify, release and correct results; scope constraint: Assigned requisitions. Site privileges determine separation of duties.." Then find where the code implements it today: the package `apps/api/src/main/java/com/aurevia/smartclinic/authz`, its neighbours, the controllers under `web`, the migrations under `db/migrations` and the contract under `contracts`. Write down the class, file or workflow names you find, or write that you found none.
    2. Write `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleLabCollectorVerifierTest.java` (Java) using the same fixtures, style and database setup as the neighbouring tests. It must prove only this clause: one test for the rule being met, and at least one negative test (for example the wrong role, another tenant, a stale version, missing input or the forbidden state) that shows the rule refusing what it must refuse. Name each test after the behaviour.
    3. If the clause is not built, or only partly built, do NOT leave a failing test and do NOT write production code. Write the complete test that would prove the missing behaviour and mark it `@Disabled("GAP: <what is missing>")`, so a later task can enable it. The pull request stays green.
    4. Write `docs/spec/clauses/authz/role-lab-collector-verifier.md` with these headings: `## Clause` (the sentence above, word for word); `## Status` (exactly one of: Proven by tests, Partly proven, Not built); `## Evidence` (test class and method names, and the production classes they exercise); `## Gap` (what is missing, which files would change, how many separate tasks it needs; or `None`).
    5. Do not change production code, an existing test, the contract or a migration. Run the checks from step 6 of the instructions (for API work also the SpotBugs check from the backend skill) and paste the result.
    Test every verb in the allowed workflow as permitted for this role, and at least three verbs of other roles as refused for it; then test that the scope constraint narrows access (another facility, another encounter, an unassigned item, a field outside its fields). For the Patient role, test against the patient endpoints.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

