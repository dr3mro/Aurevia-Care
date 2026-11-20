# fix-20261120-135535 — AUD-20261120-12 merge/check repair

## Task and provenance

Repair of the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role").

> Merge conflict with main could not be resolved automatically (merged but checks
> failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
> main and this branch.

Worker branch `swarm/AUD-20261120-12-289`, starting HEAD `a7070d1`
(`Agent changes for AUD-20261120-12`), clean tree. `origin/main` (`742f2bb`) is
an ancestor of HEAD (merge-base `742f2bb`), so locally the "merge of main and
this branch" is the current HEAD tree; there is no local divergence to resolve.
No push, tag or `gh` call. Synthetic fixtures only. The orchestrator owns the
`[-]`/`[x]` marks in `TODO.md`; this worker did not edit `TODO.md`, `MEMORY.md`,
`WORKSPACE.md`, `BUGS.md`, `PROJECT.md` or `docs/swarm`.

## Requirement IDs and acceptance mapping (scoped change already on the branch)

| Requirement | Acceptance | Where verified |
|---|---|---|
| MSG-04 / MSG-05 | A verified patient receives typed instruction title/body through `get` and `listForPatient`; reading is a separate fact from acknowledgment | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| PAT-02 | Patient sees only instruction content for a verified, linked, active chart | same positive tests |
| IAM-05 | Caller bound to charts linked to the authenticated subject via `InstructionStore.verifiedPatientCharts`; a caller-supplied `patientId` is validated, not trusted | `patientReadsOwnInstructionContentAndOwnThread`, `patientCannotReadAnotherPatientsInstructionContent` |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read (403 for `get`, empty list for `listForPatient`) | `patientReadRequiresAnActiveVerifiedConnection` |
| ARC-04 | Another patient's / unlinked chart concealed with 404 | `patientCannotReadAnotherPatientsInstructionContent` |
| ARC-02 / OPS-02 | Checks stay inside the guarded `execute` path; tenant from `TenantContext`, not the request | all patient tests; staff branch unchanged |
| IAM-01 (staff) | Staff reads still require unit assignment plus an active connection | every pre-existing staff test, `unitAssignmentAndConnectionAreValidatedOnEveryCommand` |

## Root cause (process defect, not a code defect)

The scoped implementation was already complete and verified: exactly six
production/test files under the two declared `messaging/instructions`
directories. The only out-of-scope modification was the shared
`.agent/pr-notes.md`. On `main` that path holds another story's note
(`AUD-20261120-13`); this branch's repeated `Agent changes for AUD-20261120-12`
commits overwrote it with this story's repair note. `scripts/agent-run.sh`
auto-commits every uncommitted change (`git add -A`), so any worker that writes
to the shared file re-poisons the branch; two swarm writers touching that one
path is the integrator conflict. This is the same recurring defect recorded in
`fix-20261120-105844.md` … `fix-20261120-134636.md`.

## Reproduce and fix (tool evidence)

- `git show HEAD:.agent/pr-notes.md` began
  `# fix-20261120-134636 — AUD-20261120-12 merge/check repair` while
  `git show origin/main:.agent/pr-notes.md` began `# AUD-20261120-13 — ...`.
  A 3-way merge on that path drops `main`'s AUD-13 note / conflicts in the
  reverse direction. Defect reproduced by reading both blobs.
- `bash scripts/check.sh` → exit 1 at `check-pack: opencode.json is stale:
  run scripts/gen-opencode.py`. This is the documented local cloud-worker
  artifact: `git ls-files -v opencode.json` = `S` (skip-worktree), the worktree
  file is the CI profile while the committed file is the local profile. This
  change touched no config, agent or skill file; the integrator/CI measures the
  committed local profile, where the gate is green. Log:
  `/tmp/opencode/check-aud12-135535.log`.
- HEAD truth verified by importing `scripts/gen-opencode.py` and calling
  `render(Path("."), ci=False)`: `git show HEAD:opencode.json == render(local)`
  → **True**. Regenerating `opencode.json` from this worker would be wrong.
- Fix applied: `git checkout origin/main -- .agent/pr-notes.md`. This reverts a
  forbidden edit rather than changing content; `git diff --numstat origin/main
  -- .agent/pr-notes.md` is empty afterwards, so the branch no longer touches
  that path. `git merge-tree --write-tree origin/main HEAD` → exit 0, plain tree
  hash `8bc48f6f…`, no conflict on the shared path for the committed state.
- This run writes notes only to this uniquely named file, so the auto-commit
  cannot re-poison the shared file.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` →
  `InstructionService.get` → `execute(READ, …)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=…` → `InstructionController.listForPatient`
  → `InstructionService.listForPatient` → patient branch (link membership +
  connection filter). Wrong patient:
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.
- Staff branch: `staffAssignedToUnit` guard unchanged; all pre-existing staff
  tests pass.

## New/changed branches and their covering tests (100% coverage)

This repair attempt adds no production code and therefore no new branches. The
branches introduced by the original story change remain covered by the tests
listed below, confirmed against the JaCoCo CSV generated by the full verify run
in this attempt (tool evidence):

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it through `execute` |
| `InstructionService.get`: `if (isPatient())` true/false | patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff) |
| `get`: `else if (!staffAssignedToUnit(…))` true/false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, 403); staff `get` tests (false) |
| `requirePatientChartAccess`: chart-not-linked if true/false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwn…` (false) |
| `requirePatientChartAccess`: connection-inactive if true/false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwn…` (false) |
| `listForPatient`: `if (isPatient())` true/false | patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts`, `listForPatientConceals…` (false, staff) |
| `listForPatient` patient branch: link membership false/true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwn…` (true) |
| `listForPatient` patient branch: connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwn…` (true) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all three patient service tests + controller patient test (present set); staff tests run the port with empty links |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests above.

## Commands run and results (tool evidence)

- `git status` → branch `swarm/AUD-20261120-12-289`, clean tree at `a7070d1`;
  `git branch -a`, `git rev-parse`, `git merge-base` → `origin/main` (`742f2bb`)
  is an ancestor, merge-base `742f2bb`; no local divergence.
- `git show HEAD:.agent/pr-notes.md` vs `git show origin/main:.agent/pr-notes.md`
  → poisoned copy vs AUD-13 copy (defect reproduced).
- `bash scripts/check.sh` → exit 1 at `check-pack` (local skip-worktree
  artifact, documented above); log `/tmp/opencode/check-aud12-135535.log`.
- `python3 -I -m unittest` of `gen-opencode.py` + `render(Path("."), ci=False)`
  → `git show HEAD:opencode.json == render(local)` = **True** (committed file
  correct).
- `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  → exit 0; 34 tests, 0 failures / 0 errors / 0 skipped
  (`InstructionServiceTest` 18, `InstructionContractTest` 13,
  `InstructionControllerTest` 3). Log `/tmp/opencode/aud12-135535-fasttests.log`.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
  → exit 0; `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check → All coverage checks have been met.`; BUILD SUCCESS.
  Java 21.0.13, Maven 3.10.0, PostgreSQL 18.6 accepting connections on
  127.0.0.1:5432. (OWASP and SpotBugs skipped via `-Pno-security`; integrator
  gate.) Log `/tmp/opencode/aud12-135535-verify.log`.
- Per-class JaCoCo from `apps/api/target/site/jacoco/jacoco.csv`
  (missed/covered, all missed = 0 → 100% on every file touched by the story):
  - `InstructionActions`: INSTRUCTION 0/73, BRANCH 0/25, LINE 0/11, METHOD 0/3.
  - `InstructionService`: INSTRUCTION 0/950, BRANCH 0/52, LINE 0/166, METHOD 0/32.
  - `InstructionStore.InMemory`: INSTRUCTION 0/571, BRANCH 0/24, LINE 0/95, METHOD 0/29.
  - `InstructionController`: INSTRUCTION 0/58, LINE 0/13, METHOD 0/7.
- Fix applied: `git checkout origin/main -- .agent/pr-notes.md`;
  `git diff --numstat origin/main -- .agent/pr-notes.md` empty afterwards.
  `git merge-tree --write-tree origin/main HEAD` → exit 0, tree
  `8bc48f6f83919834a4bea7e07275258254bcf184`, no conflict.

## Decisions

- Wrong-chart / wrong-patient reads return 404 (NOT_FOUND) to conceal whether
  another patient's chart exists (ARC-04); a revoked connection on a linked chart
  returns 403. Mirrors `InstructionFactService.requirePatientAccess`.
- The `patientId` query parameter is kept for contract compatibility but is
  validated against the caller's verified link set (IAM-05), not trusted.
- The staff branch is unchanged; patient handling is additive.
- Restoring the shared `.agent/pr-notes.md` to `main` reverts a forbidden edit:
  the net committed state equals `main`, so it cannot conflict or lose a note.

## Defects found

- Process defect (fixed for this branch): worker runs re-committed their note into
  the shared `.agent/pr-notes.md`, the sole source of the integrator conflict.
  Permanent remedy for the owning pipeline (outside this worker's scope): keep
  `scripts/agent-run.sh` copying the note only to the artifact (`$out/pr-notes.md`,
  which it already does) and have every agent write exclusively to its uniquely
  named per-task file — never to `.agent/pr-notes.md`. Reported for the
  orchestrator; no script or workflow file was edited here.
- No new product defect. No BUGS.md change is in scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven/JaCoCo, Git and Python
policy scripts) from this checkout with synthetic fixtures only. No reviewer
subagent finding, simulated-user walkthrough, human participant or real patient
data contributed to this change. The scoped code diff was derived by reading the
existing source (tool evidence).
