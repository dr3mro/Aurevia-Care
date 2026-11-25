# Stage K quality controls and deliverable gaps

Read-only QA analysis; no implementation claim. Evidence source: tool file reads.

## What is already enforced

- ORCA-101: per-layer gates exist — database (`check-migrations.py:198,253`, `delivery.yml:61`), Java/API (`apps/api/check.sh:14`), staff web (`delivery.yml:102`), mobile (`:104`), coverage (`coverage-check.sh:11-26`, `apps/api/pom.xml:139-147`) and docs (`check-docs.py:187-219`). No gate asserts the completion matrix.
- ORCA-102: `check-wiring.py:31-32,49-80` enforces six shape rules with a shrink-only baseline; `check-names.py:21-25` rejects story-ID names; `check-constraints.py:52-79` rejects added suppressions. No layering/dependency-graph test.
- ORCA-103: services, routes and stores are covered by `check-wiring.py:60-70` and `OpenApiRouteParityTest.java:62-94`; disconnected UI, unused code, missing authorization and integration gaps are not.
- ORCA-104: catalog, client, manifest and route parity are checked by `contracts/openapi/check.py:51-113`, the generator drift test and `OpenApiRouteParityTest`; explicit limits are in `contracts/openapi/README.md:79-84`.
- ORCA-112: `check-docs.py` validates links, IDs and structure; no stale-document detector.
- ORCA-113: JaCoCo, SpotBugs, OWASP, npm audit and no-suppression rules exist; mutation testing is opt-in (`scripts/mutation-check.sh:1-7`) and no trend metrics are recorded.

## Smallest missing checks

1. ORCA-101: add a matrix-path and pending-block rule to `check-docs.py` over `04-completion-and-safety.md:7-16`; command `python3 -I scripts/check-docs.py`.
2. ORCA-102: add an architecture-rule test under `apps/api/src/test/java/com/aurevia/it/contract/`; command `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ArchitectureRulesTest test` (not `-Pfast`).
3. ORCA-103: add `disconnected-ui` and `missing-auth` rules to `check-wiring.py`; UI reachability via `bash apps/staff-web/check.sh`.
4. ORCA-104: add POST-mapping↔catalog and DB-schema↔OpenAPI parity tests next to `OpenApiRouteParityTest`; keep `python3 -I contracts/openapi/check.py`.
5. ORCA-112: `scripts/check-doc-sync.py --base origin/main` needs a Git base, so it is blocked here.
6. ORCA-113: wire `scripts/mutation-check.sh 65` into Delivery; add trend counters only after a measured regression.

## Deliverable gaps by stage

- D: #35–#37 have design documents but no directory implementation; #39 has an inventory but no updated discovery config.
- E: #10, #12, #13 have registry/contract designs but no implementation.
- F: #14–#19 have `orca_core.py` helpers plus design documents, but no schema document or manifest artifact.
- G/H: #20 and #26 have analysis helpers but no runtime wiring.
- K: #29 needs trusted-runner evidence; #32/#33 need the missing parity tests; #38 needs `check-script-ownership.py`; #40 needs a full `scripts/check.sh` result.
- L/N: #45 has the WF disposition document; #42–#44, #46, #49, #50 need benchmark, canary, debt and independent-audit documents; #50 requires an independent reviewer.
- Owner-blocked: #6 and #22–#25 are recorded in `MEMORY.md`, not implemented by owner ruling.

## Unverifiable without Git/gh or live runners

`--require-base` checks, changed-line coverage, tracked-file discovery, all Actions evidence (exact SHA, ruleset, merge queue, release tags, arm64 smoke), live PostgreSQL and npm registry checks, and all human G0–G5 or clinical approvals. No repository check can move those to verified.
