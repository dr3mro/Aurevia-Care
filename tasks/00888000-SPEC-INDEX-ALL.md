- [ ] SPEC-INDEX-ALL · Spec clauses: coverage summary across every domain
  - requirements: PRD-01
  - scope: docs/spec/clauses/index.md
  - depends: SPEC-INDEX-ARCHITECTURE, SPEC-INDEX-AUDIT, SPEC-INDEX-AUTHZ, SPEC-INDEX-CHART, SPEC-INDEX-CONFIGURATION, SPEC-INDEX-DELIVERY-PIPELINE, SPEC-INDEX-DOCUMENTS, SPEC-INDEX-EDGE, SPEC-INDEX-EVENTS, SPEC-INDEX-EXCHANGE, SPEC-INDEX-FINANCE, SPEC-INDEX-GATES, SPEC-INDEX-CLAUSE, SPEC-INDEX-LAB, SPEC-INDEX-LAUNCH-GATES, SPEC-INDEX-LOCALIZATION, SPEC-INDEX-MESSAGING, SPEC-INDEX-MONITORING, SPEC-INDEX-OBSERVABILITY, SPEC-INDEX-PATIENT, SPEC-INDEX-PATIENT-ACCESS, SPEC-INDEX-PERFORMANCE, SPEC-INDEX-PLATFORM, SPEC-INDEX-POLICY, SPEC-INDEX-PRESCRIPTION, SPEC-INDEX-PRODUCT, SPEC-INDEX-RADIOLOGY, SPEC-INDEX-RECOVERY, SPEC-INDEX-RELEASE, SPEC-INDEX-SCHEDULING, SPEC-INDEX-TENANCY, SPEC-INDEX-TENANT, SPEC-INDEX-THERAPY, SPEC-INDEX-VULNERABILITY-MANAGEMENT, SPEC-INDEX-WEB
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Current starting points to inspect are `scripts/check-docs.py`.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.
    Await these open prerequisites before implementation: SPEC-INDEX-ARCHITECTURE, SPEC-INDEX-AUDIT, SPEC-INDEX-AUTHZ, SPEC-INDEX-CHART, SPEC-INDEX-CONFIGURATION, SPEC-INDEX-DELIVERY-PIPELINE, SPEC-INDEX-DOCUMENTS, SPEC-INDEX-EDGE, SPEC-INDEX-EVENTS, SPEC-INDEX-EXCHANGE, SPEC-INDEX-FINANCE, SPEC-INDEX-GATES, SPEC-INDEX-CLAUSE, SPEC-INDEX-LAB, SPEC-INDEX-LAUNCH-GATES, SPEC-INDEX-LOCALIZATION, SPEC-INDEX-MESSAGING, SPEC-INDEX-MONITORING, SPEC-INDEX-OBSERVABILITY, SPEC-INDEX-PATIENT, SPEC-INDEX-PATIENT-ACCESS, SPEC-INDEX-PERFORMANCE, SPEC-INDEX-PLATFORM, SPEC-INDEX-POLICY, SPEC-INDEX-PRESCRIPTION, SPEC-INDEX-PRODUCT, SPEC-INDEX-RADIOLOGY, SPEC-INDEX-RECOVERY, SPEC-INDEX-RELEASE, SPEC-INDEX-SCHEDULING, SPEC-INDEX-TENANCY, SPEC-INDEX-TENANT, SPEC-INDEX-THERAPY, SPEC-INDEX-VULNERABILITY-MANAGEMENT, SPEC-INDEX-WEB.
    These scope paths are absent in this snapshot and need confirmation as planned paths or stale references: `docs/spec/clauses/index.md`.

    1. Read every `docs/spec/clauses/<domain>/index.md`.
    2. Write `docs/spec/clauses/index.md`: one row per domain with the number of clauses and the count of Proven / Built and verified, Partly, and Not built, then the totals and the percentage proven, and links to the domain indexes. Add a list of the ten domains with the most Not built clauses.
    3. Run `python3 -I scripts/check-docs.py` and paste the result.
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.


## Start now: independent tasks (new files only, no dependencies)

Each task adds new files in its own folder, depends on nothing and does not touch a file another open task owns, so the swarm can run all of them at once while the pull request queue drains.

