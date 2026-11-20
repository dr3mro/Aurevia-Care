# fix-20261120-123238 — AUD-20261120-12 merge/check repair

## What this attempt is

Repair for the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):
`Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with scripts/check.sh on the merge of main
and this branch.`

Branch: `swarm/AUD-20261120-12-289`, start HEAD `1de77f0`
(`Agent changes for AUD-20261120-12`), clean tree, strict descendant of
`origin/main` `742f2bb` (merge base equals `origin/main`). No push, tag or `gh`
call was made. Synthetic fixtures only.

**Outcome:** the in-scope implementation is complete and verifiably green at
100% JaCoCo. The merge/check failure was an out-of-scope process defect: the
branch re-committed its note into the **shared** `.agent/pr-notes.md`, which
conflicts with the different story note `main` keeps in that path. This attempt
restores that shared file to `origin/main` so the branch diverges from `main`
only in the two declared instruction directories and in per-story note files
(new notes files cannot conflict). No production or test code was changed by
this attempt; the in-scope Java changes were already present on the branch.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where verified |
|---|---|---|
| MSG-04 | Verified patient receives typed instruction title/body | `InstructionService.get` / `listForPatient` patient branch; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| MSG-05 | Read is a separate fact from acknowledgment; no acknowledgment implied | Patient read path only releases content; `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` |
| PAT-02 | Patient sees content only for a verified, linked, active chart | `requirePatientChartAccess` link + connection checks; positive/revoked tests |
| IAM-05 | Caller bound to charts linked to the authenticated subject, several charts allowed | `InstructionStore.verifiedPatientCharts` + `linkPatientChart`; wrong-chart test |
| MSG-01 / MSG-07 | Revoked connection ends content access on next read | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` (403 get, empty list) |
| ARC-04 | Another patient's chart/thread concealed with 404 | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent` |
| ARC-02 / OPS-02 | Checks stay inside guarded `execute`; tenant scope from `TenantContext`, not the request | Unchanged `execute` path; tenant-keyed in-memory store |

## In-scope change (present on branch, verified here)

- `InstructionActions.java`: `READ` now also permits `patient`.
- `InstructionService.java`: `get` and `listForPatient` gain a patient branch
  that binds the caller to charts linked to its authenticated subject
  (`verifiedPatientCharts`) and to an active patient-unit connection; the staff
  branch (`staffAssignedToUnit`) is byte-for-byte unchanged. Added private
  helpers `isPatient()` and `requirePatientChartAccess(...)`.
- `InstructionStore.java`: new `verifiedPatientCharts(subject)` port and a
  tenant-keyed, synthetic `linkPatientChart` seed in `InMemory`.
- Tests: action-matrix `patient` assertion, three service tests (positive
  self-read, wrong-patient 404, revoked connection) and one HTTP-level positive
  test. No threshold, exclusion or skip was changed; no existing test weakened.

No migration, contract, tracker, build, agent or skill file was touched by the
in-scope work. No task-ID-named package or folder was introduced.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get`
  → `InstructionService.get` → `execute(InstructionActions.READ, ...)` →
  `isPatient()` → `requirePatientChartAccess` → `verifiedPatientCharts` /
  `connectionActive`. Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient-branch verified-chart membership + connection filter. Wrong patient
  → `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.

## New/changed branches and their tests (100% coverage)

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (`patient` true; existing `finance` false); service tests through `execute` |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | three patient service tests + controller patient test (present); pre-existing staff tests (empty set) |
| `InstructionService.get`: `if (isPatient())` true/false | new patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff) |
| `InstructionService.get`: `else if (!staffAssignedToUnit(...))` true/false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, 403); staff `get` tests (false) |
| `requirePatientChartAccess`: chart-not-linked true/false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `requirePatientChartAccess`: connection-inactive true/false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `listForPatient`: `if (isPatient())` true/false | new patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts`, `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff) |
| `listForPatient` patient branch: verified-chart membership false/true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true) |
| `listForPatient` patient branch: connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision) |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests listed above.

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  → exit 0. Surefire: `InstructionContractTest` 13/13, `InstructionServiceTest`
  18/18, `InstructionControllerTest` 3/3, 0 failures / 0 errors / 0 skipped.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
  → exit 0, `BUILD SUCCESS`; `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check` → `All coverage checks have been met.` (OWASP/SpotBugs
  intentionally skipped via `-Pno-security`; they are the integrator's gate).
  Log: `/tmp/opencode/verify-aud12-current.log`.
- JaCoCo `apps/api/target/site/jacoco/jacoco.xml`, per changed class (tool evidence):
  - `InstructionActions`: instruction 73/73, line 11/11, branch 25/25, method 3/3, class 1/1.
  - `InstructionService`: instruction 950/950, line 166/166, branch 52/52, method 32/32, class 1/1.
  - `InstructionStore$InMemory`: instruction 571/571, line 95/95, branch 24/24, method 29/29, class 1/1.
  All missed counts 0 — 100% on every file touched.
- `bash scripts/check.sh` → exit 1, stops at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker artifact (OPS-CW-003-01), not a code defect — see
  "Environment-only blockers" below.

## Root cause of the merge conflict and this fix

Each prior fix attempt restored the shared `.agent/pr-notes.md` to `origin/main`,
but a later `Agent changes for AUD-20261120-12` commit (most recently `1de77f0`)
wrote that story's note back into the shared file. `main` keeps a different
story's note (`# AUD-20261120-13 ...`) in that path, so the branch diverged in a
file it must never touch; the integrator's merge conflicted and checks failed on
the merged result. This attempt again restores the shared file to `origin/main`
and places all notes only in per-story files, so the branch's only divergence is
the two declared instruction directories plus new note files — none of which
can conflict. Evidence: `git diff origin/main..HEAD -- .agent/pr-notes.md` is now
empty after restoration.

## Environment-only blockers (not code defects; no change warranted here)

1. `check-pack.py` / `gen-opencode.py --check`: `opencode.json is out of date`.
   `git ls-files -v opencode.json` reports flag `S` (skip-worktree); the worktree
   file is the CI profile copied by `agent-run.sh`, while the committed file is
   the correct local profile. The committed `HEAD` profile is green in CI. Do
   not regenerate from a worker with the CI profile in place (OPS-CW-003-01).
2. `python3 -I -m unittest discover -s scripts/tests -q` cannot run here:
   interpreter is Python 3.11.2 without PyYAML (`ModuleNotFoundError: yaml`) and
   no `pip`. These Python tooling tests are unrelated to the Java instructions
   domain and run in the CI worker image where PyYAML is installed.

## Defects found

No new product defect introduced. The audited defect (patient role denied on
every instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed and verified. The recurring merge defect is the shared
`.agent/pr-notes.md` being written by a late agent commit; corrected here by
restoration. No BUGS.md change is in scope for this worker.

## Decisions

- Wrong-chart / wrong-patient access returns **404** to conceal existence
  (ARC-04), consistent with `InstructionFactService.requirePatientAccess`; a
  revoked connection on a linked chart returns 403.
- The `patientId` query parameter is retained for contract compatibility but
  validated against the caller's verified chart set (IAM-05), never trusted.
- The staff branch is unchanged; patient handling is additive.
- The verified-chart lookup lives in `InstructionStore` to keep the instructions
  package self-contained and inside the declared scope.
- The shared `.agent/pr-notes.md` is restored to `origin/main`; all notes go
  only to this per-story file.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven/Surefire/JaCoCo and
scripts) from this checkout using synthetic fixtures only. No reviewer subagent
finding, simulated-user walkthrough, human participant or real patient data
contributed to this change. The action matrix and store pattern were derived by
reading existing source (tool evidence), not from a human reviewer.
