# fix-20261120-125245 — AUD-20261120-12 merge/check repair (fix attempt 1 of 3)

## What this attempt is

Repair for the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):

> Merge conflict with main could not be resolved automatically (merged but checks
> failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
> main and this branch.

Branch `swarm/AUD-20261120-12-289`, start HEAD `10304dc` (`Agent changes for
AUD-20261120-12`), clean tree, strict descendant of `origin/main`
(`git rev-list --left-right --count origin/main...HEAD` = `0 15`; merge base =
`origin/main` `742f2bb`). No push, tag or `gh` call. Synthetic fixtures only.
The orchestrator owns `[-]`/`[x]` in `TODO.md`; this worker edited no shared
tracker.

## Root cause and fix (process defect, not code)

Reproduced by comparing the branch with `origin/main`:

- The scoped implementation (patient-authorized instruction reads) was **already
  complete and correct** on the branch — it is the 6 files under the two declared
  `messaging/instructions` directories (see diff below).
- The **only** file the branch changed *outside scope* was the shared
  `.agent/pr-notes.md`. On `main` that path holds the note for a different story
  (`AUD-20261120-13`); the branch's repeated `Agent changes for AUD-20261120-12`
  commits overwrote it with the ADR-12 note. Two sides, same file, different
  content → unresolvable merge conflict. The prior fix commits
  (`fix-20261120-120143`, `-120958`, `-123238`, `-124101`) each restored the
  shared file, but a later `Agent changes` commit re-modified it, re-creating the
  conflict (the loop visible in the branch log).

Fix applied: `git checkout origin/main -- .agent/pr-notes.md`. The shared file
now matches `main` byte-for-byte; the branch diverges from `main` only in the two
declared instruction directories and in per-story note files (new, uniquely named
files cannot conflict). No production or test code was changed by this attempt.

## Requirement IDs and acceptance mapping (scoped change, already on branch)

| Requirement | Acceptance | Where verified |
|---|---|---|
| MSG-04 / MSG-05 | A verified patient receives typed instruction title/body via `get` and `listForPatient`; reading is a separate fact from acknowledgment | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| PAT-02 | Patient sees only instruction content for a verified, linked, active chart | same positive tests |
| IAM-05 | Caller bound to charts linked to the authenticated account subject via `InstructionStore.verifiedPatientCharts` | `patientReadsOwnInstructionContentAndOwnThread`; several charts allowed, caller-supplied `patientId` validated against the link set |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `patientReadRequiresAnActiveVerifiedConnection` (403 for `get`, empty list for `listForPatient`) |
| ARC-04 | Another patient's chart / unlinked chart concealed with 404 | `patientCannotReadAnotherPatientsInstructionContent` |
| ARC-02 / OPS-02 | Checks stay in the guarded `execute` path, tenant from `TenantContext`, not the request | all patient tests; staff branch unchanged |
| IAM-01 (staff) | Staff reads still require unit assignment + active connection | every pre-existing staff test; `unitAssignmentAndConnectionAreValidatedOnEveryCommand` |

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` →
  `InstructionService.get` → `execute(READ, …)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=…` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient branch (link membership + connection filter). Wrong patient:
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.

## New/changed branches and their tests (100% coverage)

No branch was added by *this* attempt. The branches introduced by the original
story change are all covered (JaCoCo report read per class, below):

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial) |
| `InstructionService.get`: `if (isPatient())` true/false | patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff) |
| `get`: `else if (!staffAssignedToUnit(…))` true/false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, 403); staff `get` tests (false) |
| `requirePatientChartAccess`: chart-not-linked if true/false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwn…` (false) |
| `requirePatientChartAccess`: connection-inactive if true/false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwn…` (false) |
| `listForPatient`: `if (isPatient())` true/false | patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts`, `listForPatientConceals…` (false, staff) |
| `listForPatient` patient branch: link membership false/true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwn…` (true) |
| `listForPatient` patient branch: connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwn…` (true → one revision) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all three patient service tests + controller patient test (present set); staff tests run the port with empty links |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both outcomes
are exercised by the tests above.

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test` → exit 0.
  Surefire: `InstructionContractTest` 13/13, `InstructionServiceTest` 18/18,
  `InstructionControllerTest` 3/3, 0 failures / 0 errors / 0 skipped.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast verify` → exit 1 on
  `jacoco:check` **only** because the `fast` profile excludes `@Tag("integration")`
  classes; 92 unrelated integration-only classes lose coverage. This is an
  artifact of the fast tier, not of the touched code (see next line).
- JaCoCo `apps/api/target/site/jacoco/jacoco.xml` read per touched class (tool evidence):
  - `InstructionActions`: instruction 73/73, line 11/11, branch 25/25, method 3/3, class 1/1.
  - `InstructionService`: instruction 950/950, line 166/166, branch 52/52, method 32/32, class 1/1.
  - `InstructionStore$InMemory`: instruction 571/571, line 95/95, branch 24/24, method 29/29, class 1/1.
  - `InstructionStore` (interface) and `InstructionStore$InMemory$Connection` have no missed counters.
  All missed counts are 0 — **100% on every file touched**.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` → exit 0;
  `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check → All coverage checks have been met.`; `BUILD SUCCESS`.
  (OWASP and SpotBugs intentionally skipped via `-Pno-security`; they are the
  integrator's gate.) Full log: `/tmp/opencode/verify-aud12-fix.log`.
- `python3 -I scripts/check-names.py`, `check-docs.py`, `check-secrets.py`,
  `check-migrations.py --require-baseline` → all exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2/2 OK.
- `git diff origin/main -- .agent/pr-notes.md` → empty after the restore
  (shared file no longer diverges from main).

Note: `python3 -I scripts/check-pack.py` reports "opencode.json is stale" in this
worker. This is the documented cloud-worker skip-worktree artifact
(OPS-CW-003-01 / BUGS row 13): the worktree file is the CI profile while the
committed file is the local profile, and `git show HEAD:opencode.json` matches
the generator's local profile. No config/agent/skill file was changed by this
work, so `opencode.json` was not regenerated (regenerating here would mask the
CI profile and edit a file outside scope). Policy checks on the committed profile
are green in CI.

## Decisions

- Wrong-chart / wrong-patient reads return **404 (NOT_FOUND)** to conceal whether
  another patient's chart exists (ARC-04); a revoked connection on a linked chart
  returns 403. This mirrors `InstructionFactService.requirePatientAccess`.
- The `patientId` query parameter is kept for contract compatibility but is
  validated against the caller's verified link set (IAM-05) rather than trusted;
  a patient with several verified charts may still choose among them.
- The staff branch is unchanged; patient handling is additive.
- Restoring the shared `.agent/pr-notes.md` to `main` is a *revert of a forbidden
  edit*, not a content change: the net committed state of that file equals
  `main`, so it cannot conflict. Notes for this attempt live only in this
  uniquely named per-story file.

## Defects found

- Process defect (fixed): this branch repeatedly re-committed its note into the
  shared `.agent/pr-notes.md`, the sole source of the merge conflict with main.
- No new product defect. No BUGS.md change is in scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven/JaCoCo, Git and Python
policy scripts) from this checkout with synthetic fixtures only. No reviewer
subagent finding, simulated-user walkthrough, human participant or real patient
data contributed to this change. The specialist skills (debugger, backend,
spring-security, test-engineer) were consulted to frame the diagnosis; no
specialist produced the evidence. The code diff was derived by reading the
existing source (tool evidence).
