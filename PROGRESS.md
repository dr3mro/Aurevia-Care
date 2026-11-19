# SmartClinic progress

Execution is in progress. G0-01 is complete and merged; S-G0-02 is queued. Each iteration appends the following fields.

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
| G0 bootstrap · baseline | Done | IAM-01, IAM-02, ARC-02, OPS-02, G0 | Tool probe; `scripts/check.sh`; `git status/log`; `gh auth status`; context optimizer | check.sh green: 33 agents, 33 skills, 26 Python tests OK; all three app modules report implementation pending. Tree was clean at 782ac03; gh authenticated as dr3mro. Docker missing locally so image smoke is CI-only; PostgreSQL 18.6 is running locally on 5432 and accepts synthetic TCP connections, so real-database integration tests ARE observable here |
| G0-01 · story definition | Done | IAM-01, IAM-02, ARC-02, OPS-02, G0 | Product Owner, Scrum Master, Physician reviewer (design-stage, all simulated) | WIP-one story: tenant-scoped, membership-gated access with fail-closed revocation and OPS-02 audit. Acceptance N1–N4, E1–E8. Safety-critical work class: architect, database, Spring Security, DevSecOps, backend, test/java-build and QA routing. No human pilot evidence |
| G0-01 · implementation | Verified locally, CI pending | IAM-01, ARC-02, OPS-02, G0 | `mvn -B verify`; `bash db/check.sh`; `scripts/check.sh` (tool evidence) | 42 tests green including 10 real-PostgreSQL isolation scenarios (tools). JaCoCo 100% instruction/line/branch/method/class, build-failing. db/check verified FORCE RLS on all six tenant tables, no BYPASSRLS, non-owner runtime role, two tenants sharing code MAIN isolated, WITH CHECK cross-tenant insert rejected, append-only audit with valid hash chain. Red evidence: JDBC uuid/varchar cast, Mockito JDK27 inline failure, missing Flyway PostgreSQL module. Entry-point-to-test trace: `POST/GET /api/v1/facilities` → interceptor → guarded TransactionTemplate doBegin set_config → repository RLS → service policy → appendAudit → `FacilityIsolationTest` |
| G0-01 · security gate | Verified locally, CI pending | OPS-02, OPS-06, G0 | `NVD_API_KEY=<set> mvn -B verify`; `env -u NVD_API_KEY mvn -B verify`; `scripts/check.sh` (tool evidence) | OWASP Dependency-Check 12.1.0 found 8 high/critical CVEs in Boot 3.4.4 stack; upgraded to Boot 4.1.2 and PostgreSQL driver 42.7.14, all cleared. Migrated tests from removed TestRestTemplate to RestClient. SpotBugs 4.10.5.0 found 6 findings; fixed nullable unboxing and non-serializable field, documented 3 justified suppressions. Full gate green with and without the NVD key (cached data fallback). Skills devsecops/java-build/backend updated with the rules; `opencode.json` regenerated. No credential in repo; key supplied only via environment. Next: push, observe CI, check agent PRs |
| G0-01 · independent review fixes | Verified locally, CI pending | IAM-01, ARC-02, OPS-02, OPS-06 | QA, DevSecOps, DB and Spring Security reviewers (static, simulated); `mvn -B verify`; `bash db/check.sh`; `scripts/check.sh` (tool evidence) | Four reviewers found: exposed NVD key in pom (fixed in 276974e + secret-scan rule; user rotating), audit actor NULL (V3 trigger now derives from app.actor), audit verifier clobbered caller tenant context (now invoker-rights + active-org equality), over-broad runtime grants on authorization tables (reduced to SELECT), app-writable audit seq (trigger now forces NULL), migration role creation moved out-of-band. 44 tests green with JaCoCo 100%, db/check green, OWASP/SpotBugs green. Two high + two medium entries recorded in BUGS.md with retests. Next: observe exact-SHA CI, merge ready agent PRs |
| G0-01 · CI optimization and merge | Verified in GitHub Actions | OPS-06, G0 | `gh run view` / `gh pr checks` (tool evidence): green run https://github.com/dr3mro/Aurevia-Care/actions/runs/44442227258 on SHA f9bc461; PRs #1-#5 merged to main | delivery.yml split into small dependent jobs (policy → java/database + docs → per-arch image smoke → release) with reusable composite actions and Maven/OWASP caches. All required checks green on the exact SHA: Policy and tooling, Java and database verification, Developer reference, Verify and smoke image (amd64), Verify and smoke image (arm64). Merge conflict with main's dependabot action bumps resolved by keeping the refactor with checkout@v7, upload-artifact@v7, download-artifact@v8. Merge commit e1dc7cd on main. CI time dropped from ~7-12 min to ~2-3 min. Next story: S-G0-02 staff lifecycle and immutable membership history (IAM-02) |

| H-G0-01 · housekeeping | Local work done; CI pending workflow | OPS-06, IAM-02, G0 | `bash apps/api/check.sh`; `bash db/check.sh`; `scripts/check.sh`; `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `git grep`/`git log`/`git ls-remote` (tool evidence) | Re-ran gates: surefire 44 tests, 0 failures/errors/skips; JaCoCo INSTRUCTION 655/655, LINE 136/136, BRANCH 40/40, METHOD 50/50, CLASS 13/13 = 1.0000 each (from target/site/jacoco/jacoco.csv + jacoco.xml). db/check and check.sh green. check-docs.py green. Removed the NVD key literal from BUGS.md and added a note-scan rule; `git grep` on HEAD finds no key after this commit. Reconciled stale headers/rows, TODO status, WORKSPACE/MEMORY checkpoint, Java 21 baseline, action versions, and extra shell denies. All reviewer findings are simulated/static, not human clinical evidence. No push or gh per execution rules |

| H-G0-01 · plan index | Done locally | OPS-06, G0 | `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `scripts/check.sh` (tool evidence) | Created PLAN.md as the canonical all-story index: 64 checkboxes across G0–G5 plus cross-cutting contracts, with S-G0-01 the only filled box and its evidence linked. TODO.md, WORKSPACE.md and MEMORY.md point to it. All three checks green |

## Blocked

Resolved by user decision 2026-11-19: keep the NVD key; no rotation and no history purge. No force-push or history rewrite was performed. Residual risk: the full literal remains in published commits 589c7d4 and 57efab8; the current tree is clean and the secret is supplied only through the `NVD_API_KEY` environment variable.

Next story: S-G0-02 — staff lifecycle and immutable membership history (IAM-02). Acceptance focus: invite/deactivate, effective dates, immutable grantor history, permission change on next protected request.
