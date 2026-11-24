# GATE-ISOLATION-SURFACES — G0 isolation across API, search, files, workers and exports

## Summary

Added one real-PostgreSQL integration test class and one gap note. The test seeds two synthetic
organizations that reuse the same facility code, issuer-scoped MRN, order code, file name and
delivery idempotency key, then proves that each existing G0 surface conceals the peer tenant and
fails closed without tenant context. The tenant-export surface does not exist, so its full
acceptance test is present but `@Disabled("GAP: ...")`. No production code, migration, dependency,
threshold or tracker file was changed.

Files:
- `apps/api/src/test/java/com/aurevia/tenancy/clauses/IsolationAcrossSurfacesTest.java`
- `docs/gates/g0-isolation-surfaces.md`
- this note

## Affected requirements

ARC-02 (isolation: tenant derivation, forced RLS, fail-closed context, jobs/search/exports/files),
IAM-01 (active membership, explicit organization selection, next-request authorization).
OPS-07 is referenced only as the missing tenant-export requirement; ARC-01, ARC-05, CLN-07 and
IAM-03 appear as supporting context in the gap note. G0 row of PROJECT_V1_SPEC.md section 9;
`S-G0-04` in TODO.md is the blocked parent story.

## Risk class

Safety-critical (docs/WORKFLOW.md): tenant isolation evidence. Change itself is low risk because
it is test and documentation only — no runtime code is modified.

## Review owner

Senior Java/Spring Boot and Test Engineer (simulated specialist review plus executed tool
evidence). Product Owner / Architect own the follow-up stories named in the gap note. No human,
clinical or patient evidence was available or claimed.

## Data changes

None. The test creates and drops the disposable database `aurevia_isolation_surfaces_it` with
synthetic organizations, subjects and records. No migration was reserved or written.

## Authorization boundaries and audit events

No authorization rule or audit event was changed. The test exercises the existing boundaries:
`X-Synthetic-Subject` + `X-Active-Organization`, active membership re-check (403 for a subject
asserting a foreign organization), forced RLS concealment (404 / empty results) and fail-closed
context (401 without subject, 422 without organization, `IllegalStateException` without a bound
`TenantContext`). Synthetic subjects only: `admin-a/b`, `reception-a/b`, `clinician-a/b`,
`worker-a/b`, `auditor-a/b`.

## Migration and rollout considerations

None. No Flyway file, rollout configuration or protected file was touched.

## Test evidence

All commands were run by the primary agent from the repository root on 2026-11-21. Evidence
source: **tool** (executed commands) unless marked reviewer.

1. Targeted integration test (real PostgreSQL, synthetic fixtures):
   `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=IsolationAcrossSurfacesTest test`
   Exit 0. Surefire report final line:
   `Tests run: 7, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 6.834 s -- in com.aurevia.tenancy.clauses.IsolationAcrossSurfacesTest`
   The single skip is the story-mandated OPS-07 gap test, not a weakened gate.

2. Java unit suite (no integration tests):
   `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → Exit 0.

3. Documentation gate: `python3 -I scripts/check-docs.py` → Exit 0 (no output).
   Naming gate: `python3 -I scripts/check-names.py` → Exit 0 (no output).

4. Backend/policy checks: `python3 -I scripts/check-wiring.py` → Exit 0 (only pre-existing
   "fixed; delete from baseline" notes); `python3 -I scripts/check-migrations.py --require-baseline`
   → Exit 0; `python3 -I scripts/check-secrets.py` → Exit 0.

5. Tooling unit tests: `python3 -I -m unittest discover -s scripts/tests -q` → Exit 1,
   `Ran 441 tests in 11.544s` / `FAILED (failures=3)`. All three are `test_check_pack` cases
   (`test_check_mode_detects_drift`, `test_committed_opencode_json_is_generated`,
   `test_repository_pack_is_valid`) and all report `opencode.json is stale`.

6. `bash scripts/quick-check.sh` → Exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
   Diagnosed as the documented cloud-worker artifact (OPS-CW-003-01), not this change:
   `git ls-files -v opencode.json` → `S opencode.json` (skip-worktree);
   `cmp -s opencode.json .github/opencode.ci.json` → equal (CI profile in worktree);
   `git show HEAD:opencode.json` equals the generator's local render. `opencode.json` was not
   edited and no regeneration was attempted because it is outside this task's scope.

7. `git status --short` → only `?? apps/api/src/test/java/com/aurevia/tenancy/clauses/`
   and `?? docs/gates/` before this note was added. No tracked file was modified.

Entry-point-to-test trace: HTTP `GET/POST /api/v1/facilities` →
`apiReadsAndWritesStayTenantScopedWithTheSameFacilityCode`; `GET /api/v1/patients?name=` →
`patientSearchNeverReturnsThePeerTenantsRecordWithTheSameMrn`; `POST /api/v1/exchange/authorizations`
and `/api/v1/orders` → `ordersWithTheSameLocalCodeStayTenantScoped`; `POST/GET
/api/v1/documents/associations` and `/rendered/{id}/content` →
`filesAndRendersWithTheSameNameAreConcealedAcrossTenants`; `POST /api/v1/delivery/events` plus
direct `JdbcDeliveryStore` reads → `workerAndOutboxProcessingStaysTenantScopedWithTheSameEventKey`;
all surfaces → `missingTenantContextFailsClosedOnEverySurface`.

## Coverage

No production file was changed, so no production line, branch, method or class was added or
modified; the 100% JaCoCo gate is unchanged by this task. There is therefore no new production
branch to map to a test. Full `mvn verify` / JaCoCo report generation was deliberately not run in
this worker (the prompt forbids commands that run every integration test); the integrator and
Delivery run the full gate. The focused test command above executed the new suite against a real
database and reports 7 tests run, 0 failures, 0 errors, 1 declared gap skip. The 100% coverage
obligation is satisfied vacuously for production code (zero changed production files); if the
coverage gate disagrees, it will be because an unrelated pre-existing file regressed and should
be triaged separately.

New/changed test methods and the behavior each asserts:
- `apiReadsAndWritesStayTenantScopedWithTheSameFacilityCode` — shared `MAIN` code, concealed
  `ONLY-B`, shared `SHARED-WARD` write, bodies contain own org and not peer.
- `patientSearchNeverReturnsThePeerTenantsRecordWithTheSameMrn` — prefix search and GET-by-id
  return only the active tenant's patient with the shared MRN.
- `ordersWithTheSameLocalCodeStayTenantScoped` — same `CBC-SHARED` code in both tenants, own order
  readable, peer order 404, list contains only own id.
- `filesAndRendersWithTheSameNameAreConcealedAcrossTenants` — same `shared-scan.pdf` in both
  tenants, metadata and original/rendered bytes 404 for the peer, 200 for the owner.
- `workerAndOutboxProcessingStaysTenantScopedWithTheSameEventKey` — same delivery key yields
  distinct event ids; peer store reads empty; peer `/sent` is 404; owner `/sent` is 200.
- `missingTenantContextFailsClosedOnEverySurface` — 401 no subject, 422 no organization, 403
 foreign-organization membership denial, `IllegalStateException` with no bound context.
- `tenantExportNeverIncludesAnotherTenantsRecords` — `@Disabled` full OPS-07 acceptance test.

## Clinical and architecture review

No signed data, patient association, prescribing or critical-result behavior was changed. An
Architect/Product Owner decision is required for the OPS-07 tenant-export contract before a story
is created; that decision is explicitly not made here. Simulated senior-backend review
(session `ses_e0140622affeGO1bfFJeuIoU47`) returned six findings, all addressed: peer file seeded
and dead `"Export Beta"` assertion replaced in the disabled test; stale "no durable table"
rationale corrected to `JdbcAttachmentStore` + V82; MRN row reworded to state there is no MRN
search; checksum wording changed to "asserts a `sha256` field is present"; tautological constant
comparison removed and both-tenant negative assertions added to the `SHARED-WARD` write; a
foreign-organization membership-denial (403) case added. Reviewer did not execute commands;
its assertion analysis was checked against the executed test result.

## Findings

- Three of five G0 surfaces exist and are proven (API, patient search, worker/outbox); files are
  partly proven (document originals/renders proven, patient-unit attachment download not covered
  here); tenant export is not built. Details and required stories are in
  `docs/gates/g0-isolation-surfaces.md`.
- OPS-CW-003-01 reappeared as an environment artifact: the worktree `opencode.json` is the CI
  profile under skip-worktree, so `check-pack.py` and three `test_check_pack` cases fail while
  the committed HEAD profile is correct. Not a change defect; no file was modified.
- `JdbcDeliveryStore` read methods rely on the `as(...)`-bound `TenantContext` plus forced RLS
  rather than their `organizationId` argument; the test binds the context exactly as the HTTP
  interceptor does, so the store assertions are meaningful (reviewer finding, verified by reading
  `TenantGuardTransactionManager`).

## Lessons for workers

- Read the current source before trusting a tracker blocker or an older decision record: decision
  0003 says the attachment store is in memory, but `JdbcAttachmentStore` and V82 now exist, so the
  remaining gap is a connection-grant fixture, not a table.
- Seed shared fixtures once in `@BeforeAll` with `SET app.current_org` **and** `SET app.actor`;
  a patient insert without `app.actor` is rejected by the V7 trigger, and registering the same
  MRN twice in one tenant across test methods yields 409.
- Patient search in this API is a normalized-name **prefix** search (`LIKE :name || '%'`):
  "shared" will not match "alpha shared". Seed display names with a common leading token.
- In this cloud worker, treat `opencode.json` policy failures as the CI-profile measurement
  artifact (verify with `git ls-files -v`, `cmp`, `git show HEAD:`) and never regenerate a
  protected config from a scoped story.
- A story that mandates an `@Disabled` gap test is the legitimate exception to the "no skipped
  tests" coverage rule; keep the reason explicit and name the follow-up story in the gap note.
