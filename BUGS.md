# SmartClinic defect register

Start with an empty register. Add findings only when supported by observed evidence. Record environmental blockers in PROGRESS.md.

| ID | Requirement | Severity | Environment | Steps | Expected | Observed | Evidence | Owner/status | Retest |
|---|---|---|---|---|---|---|---|---|---|
| QA-G0-01-01 | OPS-02 | High (review finding, not observed defect) | Reviewer static repro on delivery/g0; synthetic PG | Query audit_event after protected POST/GET | actor_id identifies acting user | review found literal NULL actor in appendAudit | QA reviewer report; fixed by V3 trigger deriving actor_id from app.actor GUC | Orchestrator, fixed | `FacilityIsolationTest.auditRecordsTheActingUser` green; 44 tests green |
| QA-G0-01-02 | ARC-02 | Medium (review finding, not observed defect) | Reviewer static repro on delivery/g0; synthetic PG | Call audit_verify_chain(other org) in one transaction, then read facility | caller context unchanged; cross-tenant denied | review found SECURITY DEFINER function overwrote app.current_org | QA/DB reviewers; fixed by invoker rights + active-org equality check | Orchestrator, fixed | `FacilityIsolationTest.auditVerifierDoesNotCrossTenants` green; db/check.sh green |
| DS-G0-01-01 | OPS-02/OPS-06 | High (credential exposure) | Committed pom.xml at 589c7d4 | Inspect apps/api/pom.xml | NVD key only in env | key literal present in tracked XML | DevSecOps reviewer; removed and secret-scan rule added | Orchestrator, fixed in 276974e; user to rotate key | `git grep 2424C98D` empty; check-secrets.py green |
| DS-G0-01-02 | IAM-01/ARC-02 | Medium (review finding) | db/migrations V2 | Review grants | runtime role has no write on authorization tables | INSERT/UPDATE granted on membership and role_assignment | DevSecOps/DB reviewers; grants reduced to SELECT | Orchestrator, fixed | db/check.sh green; 44 tests green |

Record reporter role and distinguish simulated, human pilot and automated evidence. Triage with the Product Owner. Link the regression test, corrective commit and retest outcome. Ideas with no observed defect go into TODO.md.
