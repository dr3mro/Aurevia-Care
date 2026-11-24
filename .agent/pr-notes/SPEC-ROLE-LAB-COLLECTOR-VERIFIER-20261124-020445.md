# SPEC-ROLE-LAB-COLLECTOR-VERIFIER — Lab collector / verifier API role proof

Task: `tasks/00775000-SPEC-ROLE-LAB-COLLECTOR-VERIFIER.md` (already `[-]`, agent `swarm-10`; worker does not edit trackers).
Requirements: IAM-01, LAB-02. Branch: `swarm/SPEC-ROLE-LAB-COLLECTOR-VERIFIER-1384`, base HEAD `ceda1551` (= `origin/main`).

## Brief assessment

The brief is internally consistent except for one minor routing detail: its "Routing" line lists
`smartclinic-senior-java-build` among the read-only specialists, but its "Skills to load" list and
plan explicitly drop it ("senior-java-build dropped: no build-file change"). I follow the explicit drop.
No build file is changed. The two scope paths were absent and are confirmed as planned new files.

One story/brief instruction could not be followed literally and is recorded under **Findings**: story
step 3 asks for an `@Disabled("GAP: ...")` test in the Java class, but AGENTS.md coverage rules and
`scripts/check-constraints.py` reject any added `@Disabled` line in `.java` (the machine gate blocks the
merge). The complete would-prove test, skip marker included, is frozen in the exempt Markdown doc; an
enabled characterization test in the Java class records today's permissive behaviour with a real assertion.

## Clause

Role Lab collector / verifier: allowed workflow: Accession and collect / verify, release and correct results; scope constraint: Assigned requisitions. Site privileges determine separation of duties..

## Status

Partly proven — role/verb enforcement exists for accession, collect and lab-result verify/correct with
structural collector/verifier separation; assigned-requisition scope and site-configurable privileges do not.

## Plan

Survey only → write the enabled verb/denial/characterization tests first → write the status note → run
targeted Maven test, SpotBugs (compiled, not skipped), docs/policy/Python checks → specialist reviews.
No production code, dependency, migration, route, contract or existing test changed.

## Evidence

**New test class** `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleLabCollectorVerifierTest.java`
(8 enabled tests, 0 skipped):

- `collectorCanAccessionAndCollect` — `accessionRequisition`/`LAB_ACCESSION_CREATE` and
  `collectSpecimen`/`LAB_SPECIMEN_COLLECT` ALLOWED for `lab_collector`.
- `verifierCanVerifyReleaseAndCorrect` — `verifyLabResult`/`SPECIALTY_LAB_VERIFY_RELEASE` ALLOWED for
  `lab_verifier`; the client's claimed `tenant_admin` role is inert; decision carries server org/subject/clock.
- `verifierCanIssueAndCorrectAReportThroughTheResultService` — FINAL then linked CORRECTED revision
  (v2→v3, `supersedesVersion` 2) with SUCCESS audits.
- `collectorCannotVerifyOrRelease` — gate 403 `FORBIDDEN` (sink `COMMAND_FORBIDDEN`) and service 403
  "missing laboratory permission" for both `verify` and `correct`, with DENIED audits.
- `verifierCannotAccessionOrCollect` — gate denials for both lab commands; service `collect` 403
  "missing laboratory permission".
- `receptionClinicianTenantAdminVerbsRefusedForLabRoles` — `registerPatient`, `bookAppointment`,
  `inviteStaff` 403 `FORBIDDEN` for both lab roles.
- `labActionDeniedWithoutActiveMembership` — gate `MEMBERSHIP_INACTIVE` and service "no active membership".
- `requisitionAssignmentDoesNotNarrowAccessToday` — green characterization of the absent scope.

**New note** `docs/spec/clauses/authz/role-lab-collector-verifier.md` with `## Clause`, `## Status`,
`## Evidence`, `## Gap`, including the frozen `@Disabled` would-prove test and the file/task estimate.

**Production classes surveyed (none changed):** `authz.specialty.SpecialtyRoles`, `SpecialtyActions`,
`lab.accession.LabActionPolicy`, `LabAccessionService`, `lab.result.LabRules`, `LabResultService`,
`labflow.collection.SpecimenCollectionService`, `labflow.results.ResultEntryService`,
`labflow.release.LabReportReleaseService`, `exchange.release.ReleaseActionPolicy`, `authz.ActionPolicy`,
`api.authorization.CommandCatalog` (lines 161-166), `CommandAuthorization`, `InMemoryDecisionSink`,
`tenant.TenantContext`; docs pattern `docs/spec/clauses/authz/facility-scope-further-narrow-access.md`.
Survey evidence (tool): `V26__laboratory_accession.sql` has no assignment column; `V93__membership_facility_scope.sql`
has `is_facility_out_of_scope(subject, facility_code)` but no lab class calls it (context-optimizer report).

## Test evidence

Commands run and their final lines (local, tool evidence):

- `mvn -f apps/api/pom.xml -Pno-security -Dtest=RoleLabCollectorVerifierTest test` →
  `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`.
  This is the entry-point trace: each verb runs through `CommandAuthorization.synthetic` (real command
  gate) and the owning `SpecimenCollectionService`/`ResultEntryService` denial/verification paths.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` →
  `BUILD SUCCESS` (quiet, exit 0). SpotBugs 4.10.5.0, 0 findings. The brief's command without
  `-Dspotbugs.skip=false` was vacuous under `-Pno-security` (log: `Spotbugs plugin skipped`); I used the
  compiled, non-skipped form from the senior-backend skill instead.
- `python3 -I scripts/check-docs.py` → exit 0, no output.
- `python3 -I -m unittest discover -s scripts/tests` → `Ran 1432 tests ... FAILED (failures=6)`; all 6
  failures are `test_check_pack` `opencode.json is stale` assertions. Root cause confirmed: worktree
  `opencode.json` is the CI runtime profile under skip-worktree (`git ls-files -v opencode.json` → `S`),
  while `git show HEAD:opencode.json` matches `gen.render(Path('.'))` exactly (verified locally, exit 0).
  This is the documented OPS-CW-003-01 worker-image false positive; no protected file was edited and the
  integrator owns that gate. The other 1426 tooling tests pass.
- `bash scripts/worker-check.sh` → `check-pack: opencode.json is stale: run scripts/gen-opencode.py`,
  exit 1, failing before any later stage (same artifact; not re-diagnosed on a later pass).
- Remaining worker-gate stages run individually: `check-secrets`, `check-names`, `check-wiring`,
  `check-constraints`, `check-migrations --require-baseline`, `check-docs` → all PASS. No frontend,
  Python or migration file changed, so their module checks are unaffected; the integrator runs the full
  gate and the real-PostgreSQL integration suites.

Skipped-test count in the new Java class: **0**. The would-prove test is a fenced snippet in the Markdown
doc (allowed by `check-constraints.py`'s `.md` exemption); `check-constraints.py` passes on this change.

## Coverage

**No production code changed.** The change adds one test class and one Markdown status note only;
there is no new production line, branch, method or class to cover, so the 100% production-coverage
claim is "not applicable — no production code changed". The 8 new tests all assert observable outcomes
(Decision flags/reason codes, ApiException status+code+message, audit rows, report revisions, specimen
state). The full JaCoCo gate is owned by the integrator/Delivery; I did not run `mvn verify`.

## Findings

1. Story step 3 vs the quality gate: an `@Disabled` Java test would fail `scripts/check-constraints.py`
   (JAVA rule `disabled test`) via `quick-check.sh`/`worker-check.sh` and `quality-gate.sh`, and AGENTS.md
   forbids a skipped test. Resolution: enabled characterization test in Java (green, asserted) plus the
   complete frozen `@Disabled` test in the exempt Markdown doc. A later enablement task must delete the
   characterization and move the snippet into the Java class. No gate threshold, exclusion or suppression
   was changed.
2. "Release and correct results" is only partly a lab-role verb: `CommandCatalog.synthetic()` has no
   lab-correct command, and XCH-06 `authorizeRelease`/`correctRelease` are DECIDER-only (clinician,
   tenant_admin). Final-report verify/correct is proved through `ResultEntryService`; the XCH-06 release
   commands are not lab-verifier verbs, and `LabReportReleaseService` has no role check. Recorded in the doc.
3. The brief's SpotBugs command was vacuous under `-Pno-security`; corrected with `-Dspotbugs.skip=false`.
4. Six Python tooling failures are the documented skip-worktree `opencode.json` artifact, not a change defect.
5. Final verification after all review fixes: targeted `mvn -f apps/api/pom.xml -Pno-security -Dtest=RoleLabCollectorVerifierTest test` → `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`; `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0; `check-docs`, `check-constraints`, `check-secrets`, `check-names`, `check-wiring`, `check-migrations --require-baseline` → all PASS. `git status --short` shows only the two scoped files plus this notes file; tracked diff vs `origin/main` is empty.

## Lessons for workers

- Before adding an `@Disabled` GAP test to Java, run `scripts/check-constraints.py`: it rejects any added
  Java skip. Freeze the would-prove test in the exempt Markdown clause note and keep an asserted
  characterization test in Java so the scope gap is still proven by an observable outcome.
- `mvn -Pno-security ... spotbugs:check` silently skips SpotBugs; add `-Dspotbugs.skip=false` and compile first.
- In cloud workers `opencode.json` is the CI profile under skip-worktree; confirm with `git ls-files -v`
  (`S`) and compare `git show HEAD:opencode.json` to `gen.render(Path('.'))` before calling the 6
  `test_check_pack` failures a defect. Never regenerate or commit that file.

## Review verdicts

- smartclinic-plan-reviewer: GO (initial NO-GO with four Required gaps — all closed and re-reviewed)
- smartclinic-senior-backend: GO
- smartclinic-senior-spring-security: GO (initial NO-GO on denial assertions — full status+code+message triples added and re-reviewed)
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

All review findings were Critical/Required-free at final verdict. Applied fixes: full denial triples
(status + `FORBIDDEN` code + exact message), distinct VERIFY/CORRECT DENIED audit assertions, positive
service-level verifier issue/correct proof, concrete 403 GAP target, fully-qualified `anyInt()` in the
frozen snippet, `LabAccessionService` reclassified as surveyed-not-exercised, and an explicit statement
that real forced-RLS tenant concealment is out of this mocked-repository clause proof. No suppression,
exclusion, lowered threshold or enabled-test skip was added.

## Why this is not finished

Not applicable — all scoped gates pass or are documented known worker-image false positives. If a
reviewer returns a Critical/Required finding, it is fixed here before completion.
