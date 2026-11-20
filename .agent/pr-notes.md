# fix-20261120-181506 — AUD-20261120-12 (patient instruction reads; merge/check repair)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only instruction reads deny the patient role**. The dispatched "Requested change" from the swarm-integrator reports that the merge of `main` and this branch could not be resolved automatically ("merged but checks failed", fix attempt 1 of 3) and asks for a reproduction of `scripts/check.sh` on that merge.

Branch: `swarm/AUD-20261120-12-289`; HEAD at start `cb9ddbcd`; `origin/main` at `2bb82e226` (the branch's merge base, with no `HEAD..origin/main` commits). The six in-scope Java changes (three production, three test files) were already committed on this branch by earlier attempts of the same story; I retained them unchanged after independently re-verifying them against the acceptance criteria. My change removes the recurring merge blocker and records fresh evidence.

**Evidence source:** executed tool commands (tool evidence) from this checkout, synthetic fixtures only. Skills loaded: `smartclinic-delivery` (full loop), plus `smartclinic-senior-backend`, `smartclinic-senior-spring-security`, `smartclinic-test-engineer` and `smartclinic-senior-java-build` per TODO.md "Instructions for every agent". No simulated user, human participant or clinician contributed to this work; no real patient data was used. No push, tag or `gh` call was made.

## Root cause of the failed merge / checks (what this fix changes)

The only branch-local change outside the declared scope was a committed edit to the **shared, orchestrator-owned `.agent/pr-notes.md`** (carried forward by `6fdc54b`, `7ef07e8`, `c265c3b` and `cb9ddbc`), plus a superseded per-run note `.agent/pr-notes/fix-20261120-175654.md`. AGENTS.md forbids editing the shared notes file and requires notes to go only to this run's named file. When `main` advances, the shared file diverges and every subsequent merge conflicts — the "merged but checks failed" loop.

Remediation in this change:

- Restored `.agent/pr-notes.md` to the exact `origin/main` contents (`git checkout origin/main -- .agent/pr-notes.md`): its final diff against `origin/main` is empty.
- Removed the superseded `.agent/pr-notes/fix-20261120-175654.md` from an earlier attempt; its accurate root-cause and evidence content is preserved in git history and summarised here.
- Added only this note, this run's own file.

No `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, workflow, `.opencode/`, `opencode.json`, migration or OpenAPI file is authored by this change.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient can read the instruction title/body via `GET /api/v1/patient-instructions` and `GET .../{threadId}` | `InstructionActions.READ` grants `patient`; patient branches in `InstructionService.get/listForPatient` |
| MSG-05 | Patient content available to acknowledge the exact revision | Patient read returns the full `View` (current revision + history + facts) |
| PAT-02 | Only released content visible to the linked patient, never another chart | `requirePatientChartAccess` + verified-chart binding |
| IAM-05 | A verified account may link several charts; reads bind to verified charts, not a caller-supplied id alone | `verifiedPatientCharts(subject).contains(patientId)`; `InstructionStore.InMemory.linkPatientChart` |
| ARC-02 | Server-side authorization re-derived per request; tenant scope enforced | `execute()` re-reads active roles per call; store keys include the organization id |
| ARC-04 | Existence concealed with 404; wrong-patient / cross-tenant denied | `notFound()` on chart mismatch; cross-tenant verified-chart isolation |
| ARC-07 | Connection revocation ends future content access | `connectionActive` re-checked on every patient read |

## Retained production/test change (re-verified, not rewritten)

- `InstructionActions.java`: `READ` now grants `patient` in addition to the staff-sender roles plus `tenant_admin` and `privacy_auditor`. Other actions unchanged.
- `InstructionService.java`: `get()` and `listForPatient()` detect a patient caller (`isPatient()`) and take a patient branch. `requirePatientChartAccess()` binds the caller to a chart linked to its verified account and to an active patient-unit connection (404 wrong chart, 403 revoked connection). `listForPatient` checks the caller-supplied `patientId` against `verifiedPatientCharts(subject)` (404 on mismatch) and filters by `connectionActive`; the staff branch (unit assignment + active connection) is retained. A subject holding both `patient` and a staff role uses the stricter patient branch.
- `InstructionStore.java`: new tenant-scoped `verifiedPatientCharts(subject)` port method and `InMemory.linkPatientChart` seeding helper (the only implementation; no interface implementation is broken).
- Tests: `InstructionServiceTest` (5 new tests), `InstructionControllerTest` (1 HTTP test), `InstructionContractTest` (patient-READ matrix assertion) — patient self-read, wrong-patient denial, revoked-connection denial, multi-role stricter branch, cross-tenant identical-local-chart concealment, HTTP patient read.

No coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| New/changed branch | Covering test |
|---|---|
| `READ` allows `patient` (new matrix arm) | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread` |
| `get()` `isPatient()` true path | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`; `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| `get()` non-patient staff branch (retained) | `InstructionServiceTest.unitAssignmentAndConnectionAreValidatedOnEveryCommand`, `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `requirePatientChartAccess` chart mismatch → 404 | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`, `multiRolePatientCallerStillUsesTheStricterPatientBranch`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` |
| `requirePatientChartAccess` connection inactive → 403 | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` |
| `listForPatient` patient branch verified-chart containment true / false | `patientReadsOwnInstructionContentAndOwnThread` (true) / `patientCannotReadAnotherPatientsInstructionContent` + `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` (false) |
| `listForPatient` patient-branch `connectionActive` filter (empty result) | `patientReadRequiresAnActiveVerifiedConnection` |
| `listForPatient` staff branch unit + connection filters (retained) | `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `isPatient()` true / false | patient tests above / staff tests above |
| `InMemory.verifiedPatientCharts` present / absent subject, `linkPatientChart` | patient tests (present) and staff read tests (absent → empty set) |

## Entry point -> test trace

`GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` → `InstructionService.get` → `execute(INSTRUCTION_READ)` → `isPatient()` → `requirePatientChartAccess(verifiedPatientCharts + connectionActive)` → `View`. Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `patientCannotReadAnotherPatientsInstructionContent`, `patientReadRequiresAnActiveVerifiedConnection`, `multiRolePatientCallerStillUsesTheStricterPatientBranch`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother`.

`GET /api/v1/patient-instructions?patientId=...` → `InstructionController.listForPatient` → `InstructionService.listForPatient` → patient branch verified-chart binding + `connectionActive` filter. The same tests cover success, wrong-patient 404, revoked-connection empty result, cross-tenant concealment and the retained staff branch.

## Red / green evidence

- **Red:** with the three production files restored to `origin/main` (`git checkout origin/main -- <3 files>`), `mvn -q -B -f apps/api/pom.xml -Pno-security -Pfast -Dtest='InstructionServiceTest,InstructionControllerTest,InstructionContractTest' -Dsurefire.failIfNoSpecifiedTests=false test` → exit 1 at `testCompile`: `cannot find symbol: method linkPatientChart(String,String)` on `InstructionStore.InMemory`. The new tests depend on the new behavior; they cannot pass on the base code.
- **Green:** after `git checkout HEAD -- <3 files>`, the same command → exit 0. Surefire reports: `InstructionServiceTest` 20/20, `InstructionControllerTest` 3/3, `InstructionContractTest` 13/13 — 36 tests, 0 failures, 0 errors, 0 skipped. Production files were confirmed restored (`git status` clean for them).

## Exact commands run and results

- `bash scripts/check.sh` → exit 1, stopped at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Reproduced on the merge state of this branch (linear from `origin/main`).
- Worktree/HEAD profile verification (Python + `git show HEAD:opencode.json`): `HEAD opencode.json == gen.render(ROOT, ci=False)` **True**; `.github/opencode.ci.json == gen.render(ROOT, ci=True)` **True**; worktree `opencode.json == gen.render(ROOT, ci=True)` **True**, `== ci=False` **False**. The committed file is correct; the worktree is the CI profile copied under `skip-worktree`. This is the documented OPS-CW-003-01 false positive, not a defect of this change; `opencode.json` is orchestrator-owned and out of scope, so it was not regenerated.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Pfast verify` → exit 1 at `jacoco:check` with the bundle-level threshold, because `-Pfast` excludes the `@Tag("integration")` suites that cover the DB repositories. This is expected for a worker fast run; the integrator runs the full `apps/api/check.sh` with PostgreSQL.
- Per-file JaCoCo inspection (`apps/api/target/site/jacoco/jacoco.csv`, fast unit tier): every `messaging.instructions` class has **0 missed instructions, 0 missed branches, 0 missed lines and 0 missed methods**, including `InstructionActions` (IC 73, BC 25, LC 11, MC 3), `InstructionService` (IC 950, BC 52, LC 166, MC 32), `InstructionStore$InMemory` (IC 571, BC 24, LC 95, MC 29) and `InstructionController` (IC 58, LC 13, MC 7). **100% on every production file touched by this story.**
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-names.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0. `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `python3 -I -m unittest discover -s scripts/tests -q` → 271 tests, 9 failures / 10 errors, all pre-existing local-environment artifacts: `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent), temp-repo git state (`git branch -D` failed), and `check-pack` reading the CI-profile worktree file. None touches `messaging/instructions`; no tooling file was modified.
- `git checkout origin/main -- .agent/pr-notes.md` and removal of the superseded note; `git diff --stat origin/main` → exactly the six in-scope Java files (191 insertions, 2 deletions) plus this note once added; shared-file diff empty.
- `git merge-tree --write-tree origin/main HEAD` → exit 0 with a single tree oid (no conflict markers), confirming the merge is clean after the shared-file revert.

The integrator runs the database and integration tests and the full `scripts/check.sh`; per the dispatch directive I ran the fast unit tier and its coverage report only.

## Defects found / decisions

- **Merge blocker fixed:** the branch-local edit to the shared `.agent/pr-notes.md` was the recurring conflict; restoring it to `origin/main` makes the PR carry only scoped files.
- **Production fix retained unchanged:** it already satisfies the story (verified-chart + active-connection binding, wrong-patient 404, staff branch retained) with positive and negative tests. No production logic was rewritten in this fix attempt.
- **No coverage weakening:** no threshold lowered, no exclusion, `@Disabled`, `.skip` or `xit` added.
- **Environment artifacts (not changed, out of scope):** the `opencode.json` staleness is the `skip-worktree` CI-profile measurement artifact (OPS-CW-003-01, closed as false positive) and is orchestrator-owned; the Python tooling failures are local-environment issues (missing PyYAML, git sandbox). Both are reported accurately; the integrator judges these gates in its own job.
- **Coverage caveat stated explicitly:** I could not run the full `mvn verify` with the real PostgreSQL integration tier locally (the integrator owns that gate), so bundle-level 100% is not claimed from my run. I did prove 100% instruction/branches/lines/methods on every production file I touched using the fast unit tier's JaCoCo report.
