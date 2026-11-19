# SmartClinic progress

Execution has yet to begin in the target repository. Each iteration appends the following fields.

| Field | Entry |
|---|---|
| Date, gate and slice | |
| Requirement IDs and acceptance criteria | |
| Baseline and existing changes | |
| Changed files | |
| Exact executed commands and results | |
| Reviewer and findings | |
| Unverified checks and blockers | |
| Status and next action | |
| Commit, if authorized | |

For every loop stage append one compact row. Update MEMORY.md's checkpoint pointer. After a pushed commit, record its CI evidence in the working checkpoint and include it in the next scoped commit to avoid a perpetual status-only commit cycle. The GitHub run remains durable evidence for the last pushed SHA.

| Story / stage | Status | Requirement IDs | Command, reviewer or evidence reference | Result and next action |
|---|---|---|---|---|
| Bootstrap | Ready for inventory | G0 | WORKSPACE.md | Discover code and baseline |
| Scaffold hardening | Verified locally, image unbuilt | OPS-06 | scripts/check.sh | Generated opencode.json with force-push and .env guards, docs/ID and secret checks, CI concurrency and timeouts. Docker image build and Doxygen not run in the authoring sandbox. Next: run scripts/verify.sh and scripts/docs.sh on a Docker host |
| G0 bootstrap · baseline | Done | IAM-01, IAM-02, ARC-02, OPS-02, G0 | Tool probe; `scripts/check.sh`; `git status/log`; `gh auth status`; context optimizer | check.sh green: 33 agents, 33 skills, 26 Python tests OK; all three app modules report implementation pending. Tree was clean at 782ac03; gh authenticated as dr3mro. Docker missing locally so image smoke is CI-only; PostgreSQL 18.6 is running locally on 5432 and accepts synthetic TCP connections, so real-database integration tests ARE observable here |
| G0-01 · story definition | Done | IAM-01, IAM-02, ARC-02, OPS-02, G0 | Product Owner, Scrum Master, Physician reviewer (design-stage, all simulated) | WIP-one story: tenant-scoped, membership-gated access with fail-closed revocation and OPS-02 audit. Acceptance N1–N4, E1–E8. Safety-critical work class: architect, database, Spring Security, DevSecOps, backend, test/java-build and QA routing. No human pilot evidence |
| G0-01 · implementation | Verified locally, CI pending | IAM-01, ARC-02, OPS-02, G0 | `mvn -B verify`; `bash db/check.sh`; `scripts/check.sh` (tool evidence) | 42 tests green including 10 real-PostgreSQL isolation scenarios (tools). JaCoCo 100% instruction/line/branch/method/class, build-failing. db/check verified FORCE RLS on all six tenant tables, no BYPASSRLS, non-owner runtime role, two tenants sharing code MAIN isolated, WITH CHECK cross-tenant insert rejected, append-only audit with valid hash chain. Red evidence: JDBC uuid/varchar cast, Mockito JDK27 inline failure, missing Flyway PostgreSQL module. Entry-point-to-test trace: `POST/GET /api/v1/facilities` → interceptor → guarded TransactionTemplate doBegin set_config → repository RLS → service policy → appendAudit → `FacilityIsolationTest` |
| G0-01 · security gate | Verified locally, CI pending | OPS-02, OPS-06, G0 | `NVD_API_KEY=<set> mvn -B verify`; `env -u NVD_API_KEY mvn -B verify`; `scripts/check.sh` (tool evidence) | OWASP Dependency-Check 12.1.0 found 8 high/critical CVEs in Boot 3.4.4 stack; upgraded to Boot 4.1.2 and PostgreSQL driver 42.7.14, all cleared. Migrated tests from removed TestRestTemplate to RestClient. SpotBugs 4.10.5.0 found 6 findings; fixed nullable unboxing and non-serializable field, documented 3 justified suppressions. Full gate green with and without the NVD key (cached data fallback). Skills devsecops/java-build/backend updated with the rules; `opencode.json` regenerated. No credential in repo; key supplied only via environment. Next: push, observe CI, check agent PRs |
| G0-01 · independent review fixes | Verified locally, CI pending | IAM-01, ARC-02, OPS-02, OPS-06 | QA, DevSecOps, DB and Spring Security reviewers (static, simulated); `mvn -B verify`; `bash db/check.sh`; `scripts/check.sh` (tool evidence) | Four reviewers found: exposed NVD key in pom (fixed in 276974e + secret-scan rule; user rotating), audit actor NULL (V3 trigger now derives from app.actor), audit verifier clobbered caller tenant context (now invoker-rights + active-org equality), over-broad runtime grants on authorization tables (reduced to SELECT), app-writable audit seq (trigger now forces NULL), migration role creation moved out-of-band. 44 tests green with JaCoCo 100%, db/check green, OWASP/SpotBugs green. Two high + two medium entries recorded in BUGS.md with retests. Next: observe exact-SHA CI, merge ready agent PRs |
