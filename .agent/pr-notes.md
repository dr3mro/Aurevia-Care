# fix-20261120-175654 — AUD-20261120-12 (patient instruction reads; merge/check repair, fix attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only instruction reads deny the patient role**. The dispatched "Requested change" from the swarm-integrator says the merge of `main` and this branch could not be resolved automatically ("merged but checks failed", fix attempt 1 of 3) and asks for a reproduction of `scripts/check.sh` on that merge.

Branch: `swarm/AUD-20261120-12-289`; HEAD at start `7ef07e8`; `origin/main` merge base `2bb82e2` with **no** commits in `HEAD..origin/main`. The in-scope Java fix was already committed on this branch by earlier attempts of the same story; I retained it unchanged after independently verifying it against the story's acceptance criteria. My change removes the recurring merge blocker and records fresh evidence.

**Evidence source:** executed tool commands (tool evidence) from this checkout, synthetic fixtures only. Skills loaded: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-spring-security`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. No simulated user, human participant, clinician or real patient contributed to this work. No push, tag or `gh` call was made.

## Root cause of the failed merge / checks (what this fix changes)

The only branch-local change outside the declared scope was a committed edit to the **shared, orchestrator-owned `.agent/pr-notes.md`** (most recently by `7ef07e8 "Agent changes for AUD-20261120-12"`), plus a stray per-story note `.agent/pr-notes/fix-20261120-172525.md` from an earlier attempt. AGENTS.md forbids editing either the shared notes file or another run's note. When `main` advanced, the shared file diverged and every subsequent merge conflicted — this is the "merged but checks failed" loop.

Remediation in this change:

- Restored `.agent/pr-notes.md` to the exact `origin/main` contents (`git checkout origin/main -- .agent/pr-notes.md`): the final worktree diff against `origin/main` for that file is empty.
- Deleted the stray `.agent/pr-notes/fix-20261120-172525.md`; its accurate root-cause and evidence content is preserved in this note.
- Added only this note (`.agent/pr-notes/fix-20261120-175654.md`), which is this run's own file.

The final diff against `origin/main` is exactly the six in-scope Java files plus this note. No `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, workflow, `.opencode/` or `opencode.json` file is authored by this change.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient can read the instruction title/body the unit published via `GET /api/v1/patient-instructions` and `GET .../{threadId}` | `InstructionActions.READ` grants `patient`; `InstructionService.get/listForPatient` patient branch |
| MSG-05 | Patient content available to acknowledge the exact revision | Patient read returns the full `View` (current revision + history + facts) |
| PAT-02 | Only released content visible to the linked patient, never another chart | `requirePatientChartAccess` + `verifiedPatientCharts` binding |
| IAM-05 | A verified account may link several charts; reads bind to verified charts, not a caller-supplied id alone | `verifiedPatientCharts(subject).contains(patientId)`; `InstructionStore.InMemory.linkPatientChart` |
| ARC-02 | Server-side authorization re-derived per request; tenant scope enforced | `execute()` re-reads active roles; store keys include the organization id |
| ARC-04 | Existence concealed with 404; wrong patient / cross-tenant denied | `notFound()` on chart mismatch; cross-tenant verified-chart isolation |
| ARC-07 | Connection revocation ends future content access | `connectionActive` re-checked on every patient read |

## Change (retained unchanged, re-verified)

- `InstructionActions.java`: `READ` now grants `patient` in addition to the staff-sender roles plus `tenant_admin` and `privacy_auditor`. All other actions unchanged.
- `InstructionService.java`: `get()` and `listForPatient()` detect a patient caller (`isPatient()`) and take a patient branch. `requirePatientChartAccess()` binds the caller to a chart linked to its verified account and to an active patient-unit connection (404 wrong chart, 403 revoked connection). `listForPatient` checks the caller-supplied `patientId` against `verifiedPatientCharts(subject)` (404 on mismatch) and filters by `connectionActive`; the staff branch (unit assignment + active connection) is retained. A subject holding both `patient` and a staff role uses the stricter patient branch.
- `InstructionStore.java`: new tenant-scoped `verifiedPatientCharts(subject)` port method and `InMemory.linkPatientChart` seeding helper.
- Tests added to `InstructionServiceTest`, `InstructionControllerTest`, `InstructionContractTest`: patient self-read, wrong-patient denial, revoked-connection denial, multi-role stricter branch, cross-tenant identical-local-chart concealment and HTTP-route patient read.

No coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| New/changed branch | Covering test |
|---|---|
| `READ` allows `patient` (new matrix arm) | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread` |
| `get()` `isPatient()` true path | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`; `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| `get()` non-patient staff branch (retained) | `InstructionServiceTest.unitAssignmentAndConnectionAreValidatedOnEveryCommand`, `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `requirePatientChartAccess` chart mismatch → 404 | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`, `multiRolePatientCallerStillUsesTheStricterPatientBranch`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` |
| `requirePatientChartAccess` connection inactive → 403 | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` |
| `listForPatient` patient branch verified-chart containment true/false | `patientReadsOwnInstructionContentAndOwnThread` (true) / `patientCannotReadAnotherPatientsInstructionContent` + `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` (false) |
| `listForPatient` patient-branch `connectionActive` filter | `patientReadRequiresAnActiveVerifiedConnection` (empty result) |
| `listForPatient` staff branch unit + connection filters (retained) | `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `isPatient()` true/false | patient tests above / staff tests above |
| `InMemory.verifiedPatientCharts` present/absent subject, `linkPatientChart` | patient tests above (present) and staff read tests (absent → empty set) |

## Entry point -> test trace

`GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` → `InstructionService.get` → `execute(INSTRUCTION_READ)` → `isPatient()` → `requirePatientChartAccess(verifiedPatientCharts + connectionActive)` → `View`. Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `patientCannotReadAnotherPatientsInstructionContent`, `patientReadRequiresAnActiveVerifiedConnection`, `multiRolePatientCallerStillUsesTheStricterPatientBranch`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother`.

`GET /api/v1/patient-instructions?patientId=...` → `InstructionController.listForPatient` → `InstructionService.listForPatient` → patient branch verified-chart binding + `connectionActive` filter. Same tests cover success, wrong-patient 404, revoked-connection empty result, cross-tenant concealment and the retained staff branch.

## Exact commands run and results

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='Instruction*Test' test` — exit 0. Surefire reports: `InstructionContractTest` 13/13, `InstructionControllerTest` 3/3, `InstructionServiceTest` 20/20 (plus the sibling `acknowledgment` instruction-fact suites 7/45/11); **0 failures, 0 errors, 0 skipped** for the three instruction classes (36 tests).
- `mvn -B -f apps/api/pom.xml -Pno-security,fast verify` — the unit suites pass; `jacoco:report` generated `apps/api/target/site/jacoco/jacoco.xml`; the run ends at `jacoco:check` with the expected bundle-level warning because `-Pfast` excludes the `@Tag("integration")` suites. No threshold, exclusion or skip was changed.
- JaCoCo XML parsed for every touched production file (fast unit tier): `InstructionActions` LINE 11/11, BRANCH 25/25, METHOD 3/3; `InstructionService` LINE 166/166, BRANCH 52/52, METHOD 32/32; `InstructionStore$InMemory` LINE 95/95, BRANCH 24/24, METHOD 29/29; `InstructionController` 13/13 LINE; `InstructionInputs` 37/37 LINE, 32/32 BRANCH. **100% on all touched production classes.** Interface types (`InstructionStore`, `InstructionContract`) have 0/0 counters (no executable code).
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-names.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0. `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `bash scripts/quick-check.sh` → stopped at `check-pack: opencode.json is stale` (environment artifact, see below). Verified generator equivalence with Python: `git show HEAD:opencode.json == gen.render(ROOT, ci=False)` **True**, and worktree `opencode.json == gen.render(ROOT, ci=True)` **True** (the documented OPS-CW-003-01 false positive).
- `python3 -I -m unittest discover -s scripts/tests -q` → 271 tests, 9 failures / 10 errors, all pre-existing environment artifacts (missing `yaml` module, temp-repo git setup and the `opencode.json` skip-worktree artifact); unrelated to this Java change and outside the declared scope. No tooling file was modified.
- `git checkout origin/main -- .agent/pr-notes.md` + removal of the stray note, then `git diff --name-status origin/main` → only the six in-scope Java files (plus this note once added); the shared-file diff is empty.
- Integrator merge simulation: in a detached worktree at `origin/main`, `git merge --no-commit --no-ff eaa5399` → exit 0, "Automatic merge went well", **zero unmerged paths**; staged result is exactly the six in-scope Java files plus this note (273 insertions, 2 deletions). The recurring merge conflict is resolved.

The integrator runs the database and integration tests and the full `scripts/check.sh`; per the dispatch directive I ran the fast unit tier and the fast-tier coverage report only.

## Defects found / decisions

- **Merge blocker fixed:** the branch-local edit to the shared `.agent/pr-notes.md` was the recurring conflict; restoring it to `origin/main` makes the PR carry only scoped files.
- **Production fix retained unchanged:** it already satisfies the story (verified-chart + active-connection binding, wrong-patient 404, staff branch retained) with negative and positive tests. No production logic was rewritten.
- **No coverage weakening:** no threshold lowered, no exclusion, `@Disabled`, `.skip` or `xit` added.
- **Environment artifacts (not changed, out of scope):** `opencode.json` staleness is the `skip-worktree` CI-profile measurement artifact (OPS-CW-003-01, closed as false positive) and is orchestrator-owned; Python tooling failures are local-environment issues (no PyYAML, git sandbox). Reported accurately; the integrator judges these gates in its own job.
