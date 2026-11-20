# AUD-20261119-01 — Constrain patient released-revisions endpoint to the verified patient chart link

## Requirement IDs

PAT-02, IAM-01, IAM-05, XCH-06, ARC-02, OPS-02. Classification: safety-critical (patient record access), so server-side negative authorization tests were required before any production change.

## Defect confirmed (red first)

`GET /api/v1/patients/{patientId}/released-revisions` passed the path `patientId` straight into `ReleaseService.visibleRevisions`, whose only check was staff membership plus a role from `{clinician, tenant_admin, reception, privacy_auditor}`. No check bound the authenticated subject to that patient. Receptionists and privacy auditors could enumerate any patient's released revision IDs; a verified patient (role `patient`) got 403. `ReleasedReportService` had the correct link-based rule but no runtime caller. Evidence: tool reads of the four named files plus two deliberately failing tests (below).

## Acceptance mapping

| ID | Acceptance | Evidence |
|---|---|---|
| A1 | Reception / privacy auditor requesting any patient's revisions is denied 403, no DB read of releases, denial audited | `ReleaseServiceTest.visibleRevisionsDeniesReceptionAndAuditorEnumeration`; `ReleasePolicyTest.patientScopedReadPolicyExcludesReceptionAndAuditor` |
| A2 | Verified patient linked to the path patient sees only currently visible revisions of that chart, with no staff membership or role | `ReleaseServiceTest.verifiedPatientSeesOnlyTheirOwnCurrentlyVisibleRevisions` |
| A3 | A subject whose verified link is to another chart (or has no link) is denied 403 | `ReleaseServiceTest.patientLinkedToAnotherChartCannotEnumerateThisPatient` |
| A4 | Link rule is tenant-scoped and live: unlink / cross-tenant does not grant access | Reused V10 `public.has_chart_access` live fold over append-only `account_chart_link`; forced RLS + transaction-local tenant GUC. `ReleaseRepository.hasVerifiedChartLink` |
| A5 | Clinician / tenant admin can still read patient-scoped revisions (pre-existing integration contract) | `PatientReleaseIsolationTest`: 14 tests, 0 failures on real PostgreSQL |
| A6 | Missing tenant context fails closed 401 and is not audited with a fabricated actor | `ReleaseServiceTest.patientScopedReadRequiresTenantContext` |
| A7 | Malformed / null `patientId` stays 422 | `ReleaseServiceTest.correctRejectsNullAndInvalidInputs` (pre-existing) |
| A8 | Accepted input and visible-revision fold unchanged | `ReleaseServiceTest.visibleRevisionsOnlyReturnsCurrentlyVisible` (pre-existing) |

## Implementation and entry-point-to-test trace

Production edits (all inside the two declared scope directories):

- `exchange/release/PatientReleasedRevisionsController.java` — Javadoc only; delegates unchanged.
- `exchange/release/ReleaseActionPolicy.java` — `READ`/`HISTORY` keep the wider `READERS` set; `ACCESS` (the patient-scoped query) now uses `PATIENT_SCOPED_READERS = {clinician, tenant_admin}`.
- `exchange/release/ReleaseRepository.java` — new `hasVerifiedChartLink(subject, patientId)` calling `public.has_chart_access`; `Boolean.TRUE.equals` avoids nullable unboxing and never catches a DB failure as "allow".
- `exchange/release/ReleaseService.java` — `visibleRevisions` now fails closed on null context, calls the live chart-link check first, falls back to the narrow staff role check only when there is no verified link, then applies the existing visibility fold and audit.

Trace: `GET /api/v1/patients/{patientId}/released-revisions` → `PatientReleasedRevisionsController.visibleRevisions` → `TenantContextInterceptor` → `ReleaseService.visibleRevisions` (guarded `TransactionTemplate`) → `ReleaseRepository.hasVerifiedChartLink` (`has_chart_access` live fold) or `authorize(RELEASE_ACCESS_QUERY)` → `findAuthorizedByPatient` + `visibleNow(clock)` → `appendAudit(ACCESS, SUCCESS/DENIED)`. Unit trace: `ReleaseServiceTest` (Mockito repository, fixed clock). HTTP/integration trace: `PatientReleaseIsolationTest` (RANDOM_PORT, synthetic auth, real PostgreSQL).

## Exact commands and observed results

Evidence source: executed tool commands unless stated otherwise.

1. Baseline before changes — `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; 1785 tests, 0 failures, 0 errors.
2. Red after adding only the two acceptance tests — same command → exit 1; 2 failures: `ReleasePolicyTest.patientScopedReadPolicyExcludesReceptionAndAuditor` ("expected: <false> but was: <true>") and `ReleaseServiceTest.visibleRevisionsDeniesReceptionAndAuditorEnumeration` ("Expected ApiException to be thrown, but nothing was thrown").
3. Green after implementation — same command → exit 0; `fast surefire: reports=155 tests=1785 failures=0 errors=0 skipped=0` (clean `surefire-reports` first so the count is from this run).
4. Real DB / full tier — `mvn -B -f apps/api/pom.xml -Pno-security verify` → exit 1 with `Tests run: 2017, Failures: 61, Errors: 7`. Every failure belongs to classes already listed in TODO.md DLV-01/02/03 (identity, consolidation, conversation, prescription transmission, chart record, exchange task, trend, accession, check-in) and is unrelated to this story. Story classes in that same run: `ReleaseServiceTest` 57/0/0, `ReleasePolicyTest` 10/0/0, `PatientReleaseIsolationTest` 14/0/0.
5. Coverage from that run's JaCoCo exec (`mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report`): `ReleaseRepository` INSTRUCTION 384/384, BRANCH 4/4, LINE 92/92, METHOD 20/20; `ReleaseActionPolicy` 44/44, BRANCH 6/6, METHOD 3/3; `PatientReleasedRevisionsController` 11/11, METHOD 2/2; `ReleaseService` INSTRUCTION 1206/1223, BRANCH 121/122, METHOD 25/25 — the only missed instructions/lines are pre-existing `correct()` lines 210–213 (held-release correction), untouched by this story.
6. Static analysis — `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false compile spotbugs:check` → exit 0, `BugInstance size is 0`. OWASP was not re-run: `pom.xml` and dependencies are unchanged, so the dependency set and CVE disposition are unchanged.
7. Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` each exit 0.

## Decisions and rationale

- Reused the authoritative V10 `has_chart_access` fold from `ReleaseRepository` rather than adding a new port and Spring bean. The rule stays in one place (the database), no constructor or wiring change is needed, and the check runs inside the guarded transaction. This followed the bounded Senior Spring Security reviewer finding, which explicitly recommended this as the smallest fully in-scope option (reviewer report, simulated review; code verified by the primary agent).
- Kept `clinician`/`tenant_admin` as the fallback staff path. Deleting all staff access would break the existing G2 integration gate, which legitimately uses the endpoint to inspect release visibility. Removed only `reception` and `privacy_auditor` from the patient-scoped `ACCESS` action; `READ`/`HISTORY` are unchanged, so audit and clinical read workflows keep working.
- A verified link to the exact path patient is the patient's authorization; a link to any other chart is treated exactly like no link and falls through to the staff role check, which a patient account cannot satisfy.
- `TenantContext.current() == null` returns 401 before any SQL or audit write; no synthetic actor is fabricated.

## Defects and environment notes

- The reported authorization defect is confirmed and fixed; regression tests added.
- `python3 -I scripts/check-pack.py` reported `opencode.json is stale` in this worker. Cause is environmental, not this change: `git ls-files -v opencode.json` → `S` (skip-worktree); the worktree file hashes to the CI profile (`.github/opencode.ci.json`, `279aa1e7…`), while `git show HEAD:opencode.json` hashes to `14bba0da…` and is unmodified (`git diff opencode.json` empty). This is the documented OPS-CW-003-01 measurement artifact — no file was regenerated or committed.
- The full integration tier is already red on this branch for pre-existing reasons tracked as DLV-01/02/03; no attempt was made to weaken, skip or retag those tests.

## Scope and evidence disclosure

All production edits are confined to the two declared scope directories. Two existing unit-test files under `apps/api/src/test/java/.../it/exchangerelease/` were edited as well: the story requires failing-first negative tests and the repository enforces 100% coverage, neither of which can be delivered from main-source directories alone. No other files were changed.

Evidence sources: executed tool commands (test runs, SpotBugs, JaCoCo XML, git inspection) for all results; one bounded simulated Senior Spring Security reviewer for the design critique; no simulated-user or human/clinical feedback. No patient data or credentials were used.

## Residual / not verified

- No HTTP-level test was added that seeds `patient_account` + `account_chart_link`, confirms a patient gets 200, then unlinks and confirms 403 on the next request. Patient self-access and wrong-chart denial are verified at the service boundary with synthetic fixtures; the live unlink fold is covered indirectly by the reused V10 function and by `IdentityProofingIsolationTest` (which is currently red for unrelated pre-existing reasons). Adding the HTTP-level case is a follow-on test story; the integrator runs the DB/integration gate.
- `docs/reference/maintainer.md` was not updated with a release-trace row because `docs/` is outside this story's scope; the entry-point-to-test trace above supplies the same information for this PR.
