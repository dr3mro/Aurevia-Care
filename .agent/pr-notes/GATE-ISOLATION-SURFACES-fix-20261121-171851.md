# GATE-ISOLATION-SURFACES — fix pass (verification + fail-closed write evidence)

## Summary

Fix pass on the committed G0 cross-surface isolation evidence (base SHA `5a52d7ee`,
branch `swarm/GATE-ISOLATION-SURFACES-447`). The original story is already implemented:
one real-PostgreSQL integration test
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/IsolationAcrossSurfacesTest.java`)
and one gap note (`docs/gates/g0-isolation-surfaces.md`). I re-ran the canonical test and every
applicable in-scope gate: all are green. I found no reproducible defect in the two files in scope.

The one substantive change in this pass closes a genuine evidence gap against acceptance step 2
("a missing tenant context fails closed"): the fail-closed test previously asserted 401/422 only
on GET (read) endpoints, although "API reads and writes" is a named surface. I added two
write-side assertions to `missingTenantContextFailsClosedOnEverySurface` — `POST /api/v1/facilities`
with no subject must answer 401, and with a subject but no `X-Active-Organization` must answer 422 —
and updated the gap note to state that fail-closed is proven on writes as well as reads.
No production code, migration, dependency, threshold or tracker file was changed.

## Affected requirements

ARC-02 (tenant derivation, forced RLS, fail-closed context, jobs/search/exports/files) and
IAM-01 (active membership, explicit organization selection, next-request authorization).
OPS-07 is referenced only as the missing tenant-export requirement; G0 row of
`PROJECT_V1_SPEC.md` section 9 and `S-G0-04` in `TODO.md` are the parent context.
No new requirement ID was introduced.

## Risk class

Safety-critical (docs/WORKFLOW.md): tenant-isolation evidence. The change itself is low risk —
test and documentation only; no runtime code is modified.

## Review owner

Senior Java/Spring Boot and Test Engineer (skills loaded this pass: `orevia-delivery`,
`orevia-senior-backend`, `orevia-test-engineer`, `orevia-senior-java-build`).
Product Owner / Architect own the follow-up stories named in the gap note. Evidence source is
**tool** (commands I executed) and **simulated specialist review** (skills/prior review). No
human, clinical or patient evidence was available or claimed.

## Data changes

None. The test creates and drops the disposable database `orevia_isolation_surfaces_it`
with synthetic organizations, subjects and records only. No migration was reserved or written;
the reserved-version rule is not exercised by this story.

## Authorization boundaries and audit events

No authorization rule or audit event was changed. The added assertions exercise the existing
`X-Synthetic-Subject` + `X-Active-Organization` boundary on a state-changing request: a missing
subject is refused 401 and a missing explicit organization is refused 422 before any tenant-scoped
SQL runs. Synthetic subjects only (`admin-a/b`, `reception-a/b`, `clinician-a/b`, `worker-a/b`,
`auditor-a/b`); no real patient data, credential or token is used.

## Migration and rollout considerations

None. No Flyway file, rollout configuration or protected file was touched. Verified that
`origin/main` has no new migration or change to the patient/document/events/tenancy/web-facility
packages since this branch's merge base (`45c27ab2`), so the test remains valid against current main.

## Test evidence

All commands were run by the primary agent from the repository root on 2026-11-21. Evidence
source: **tool** (executed commands) unless marked reviewer/simulated.

1. Canonical focused integration test (real PostgreSQL, synthetic fixtures), run AFTER the fix:
   `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=IsolationAcrossSurfacesTest test`
   Exit 0. Surefire report final line:
   `Tests run: 7, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 9.345 s -- in com.aurevia.tenancy.clauses.IsolationAcrossSurfacesTest`
   The single skip is the story-mandated OPS-07 gap test, not a weakened gate. The two new
   write-side assertions are inside `missingTenantContextFailsClosedOnEverySurface` and passed on
   the first run (the behavior already existed; the assertions document it — this pass adds no
   production code, so there is no red phase to a production change).

2. Documentation/naming/policy gates (all re-run after the edits):
   - `python3 -I scripts/check-docs.py` → exit 0 (no output).
   - `python3 -I scripts/check-names.py` → exit 0 (no output).
   - `python3 -I scripts/check-wiring.py` → exit 0 (only pre-existing "fixed; delete from baseline" notes).
   - `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (no output).
   - `python3 -I scripts/check-secrets.py` → exit 0 (no output).

3. Tooling unit tests:
   `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 527 tests in 15.076s`,
   `FAILED (failures=3)`. The only three failures are `test_check_pack.GeneratorTests.test_check_mode_detects_drift`,
   `test_check_pack.PackTests.test_committed_opencode_json_is_generated` and
   `test_check_pack.PackTests.test_repository_pack_is_valid`, all reporting
   `opencode.json is stale`. This is the documented OPS-CW-003-01 cloud-worker artifact, not a
   defect from this change (see Findings). `python3 -I scripts/gen-opencode.py --check` exits 1
   for the same reason in this worktree.

4. Frontend definition of done (patient-mobile / staff-web `check.sh`, typecheck): NOT APPLICABLE.
   This story changes one Java test and one Markdown file; no frontend file is touched, so there
   is no `error TS…` to fix and no `tsc --noEmit` surface.

Entry-point-to-test trace: HTTP `POST /api/v1/facilities` with no subject → 401 assertion and
with no `X-Active-Organization` → 422 assertion in `missingTenantContextFailsClosedOnEverySurface`;
existing traces unchanged (GET facilities → `apiReadsAndWritesStayTenantScopedWithTheSameFacilityCode`,
GET patients → `patientSearchNeverReturnsThePeerTenantsRecordWithTheSameMrn`, POST authorizations/orders
→ `ordersWithTheSameLocalCodeStayTenantScoped`, documents →
`filesAndRendersWithTheSameNameAreConcealedAcrossTenants`, POST delivery events plus direct
`JdbcDeliveryStore` reads → `workerAndOutboxProcessingStaysTenantScopedWithTheSameEventKey`).

## Coverage

No production file was changed, so no production line, branch, method or class was added or
modified; the 100% JaCoCo gate is unchanged by this task and there is no new production branch to
map to a test. Full `mvn verify` / JaCoCo report generation was deliberately not run in this
worker (the prompt forbids commands that run every integration test; the integrator and Delivery
run the full gate). The obligation is satisfied vacuously for production code (zero changed
production files).

Test methods and the behavior each asserts (unchanged list plus the strengthened fail-closed test):
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
- `missingTenantContextFailsClosedOnEverySurface` — 401 no subject and 422 no organization on
  five read surfaces, **now also on `POST /api/v1/facilities` (writes)**; 403 foreign-organization
  membership denial; `IllegalStateException` with no bound `TenantContext`.
- `tenantExportNeverIncludesAnotherTenantsRecords` — `@Disabled` full OPS-07 acceptance test.

## Clinical and architecture review

No signed data, patient association, prescribing or critical-result behavior was changed. An
Architect/Product Owner decision is still required for the OPS-07 tenant-export contract before a
story is created; that decision is explicitly not made here. The prior simulated senior-backend
review (session `ses_e0140622affeGO1bfFJeuIoU47`, recorded in the original note) returned six
findings, all addressed; this pass adds no new review finding. The reviewer did not execute
commands; its assertion analysis was checked against the executed test result.

## Findings

- No reproducible defect exists in the two in-scope files: the canonical test is green and all
  in-scope policy/doc gates pass. The fix pass therefore makes only the fail-closed write-evidence
  strengthening described above.
- Three of five G0 surfaces are fully proven (API, patient search, worker/outbox); files are partly
  proven (document originals/renders proven, patient-unit attachment download not covered here);
  tenant export is not built. Details and required stories are in `docs/gates/g0-isolation-surfaces.md`.
- OPS-CW-003-01 reappears as an environment artifact: the worktree `opencode.json` is flag `S`
  (skip-worktree) and equals `.github/opencode.ci.json` (the CI profile), while `git show HEAD:opencode.json`
  equals the generator's local render. I verified this by generating to the worktree and observing
  no git diff, then restoring the worktree file; no tracked file was modified. Consequently
  `gen-opencode.py --check`, `check-pack.py` via `quick-check.sh`, and three `test_check_pack`
  cases report a stale file during the agent step. This is a protected-file/tooling measurement
  artifact, not a defect of this test/docs change, and is out of the task scope (a swarm worker may
  not edit `opencode.json`, `scripts/` or config). The trusted worker/integrator gate runs with the
  local profile restored, where these checks are green.

## Why this is not finished

The story itself is finished and all in-scope gates pass. The only failing checks are the
out-of-scope OPS-CW-003-01 artifact described above:
- Failed command: `python3 -I -m unittest discover -s scripts/tests -q` (3 `test_check_pack` failures)
  and `python3 -I scripts/gen-opencode.py --check` (exit 1, "opencode.json is out of date").
- Most likely cause: `agent-run.sh` copied the CI `opencode.json` over the worktree file under
  skip-worktree for the agent step; the committed HEAD profile is correct.
- What I already tried: confirmed `git ls-files -v opencode.json` → `S`, worktree equals
  `.github/opencode.ci.json`, generator's local render equals `git show HEAD:opencode.json`,
  and regenerating produced no tracked diff. I did not regenerate or commit the protected config.
- Exact next step: no worker action — the integrator/Delivery re-run these checks with the local
  profile restored (as recorded in MEMORY.md OPS-CW-003-01) and they are green there. If a re-run
  with the committed local profile still fails, that is a separate tooling defect for the
  maintainer, not this story.

## Lessons for workers

- Re-verify before assuming a fix is needed: re-run the canonical focused test and the fast
  policy/doc gates first. A `-fix-` note name does not guarantee a reproducible in-scope defect.
- When the acceptance says "fails closed on every surface", assert it on writes as well as reads;
  a read-only fail-closed test leaves half of an "API reads and writes" surface unproven.
- In this cloud worker, treat `opencode.json` policy failures as the CI-profile measurement
  artifact (verify with `git ls-files -v`, `cmp`, `git show HEAD:`) and never regenerate a
  protected config from a scoped story.
- A story that mandates an `@Disabled` gap test is the legitimate exception to the "no skipped
  tests" coverage rule; keep the reason explicit and name the follow-up story in the gap note.
