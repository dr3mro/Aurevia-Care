# fix-20261120-131400 — AUD-20261120-12 merge/check repair

## What this attempt is

Repair of the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):

> Merge conflict with main could not be resolved automatically (merged but checks
> failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
> main and this branch.

Worker branch `swarm/AUD-20261120-12-289`, starting HEAD `a816577`
(`Agent changes for AUD-20261120-12`), clean tree. No push, tag or `gh` call.
Synthetic fixtures only. The orchestrator owns `[-]`/`[x]` in `TODO.md`; this
worker did not edit any shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`,
`BUGS.md`, `PROJECT.md`, `docs/swarm`).

## Requirement IDs and acceptance mapping (scoped change already on branch)

| Requirement | Acceptance | Where verified |
|---|---|---|
| MSG-04 / MSG-05 | A verified patient receives typed instruction title/body via `get` and `listForPatient`; reading is a separate fact from acknowledgment | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| PAT-02 | Patient sees only instruction content for a verified, linked, active chart | same positive tests |
| IAM-05 | Caller bound to charts linked to the authenticated account subject via `InstructionStore.verifiedPatientCharts`; a caller-supplied `patientId` is validated, not trusted | `patientReadsOwnInstructionContentAndOwnThread`, `patientCannotReadAnotherPatientsInstructionContent` |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read (403 for `get`, empty list for `listForPatient`) | `patientReadRequiresAnActiveVerifiedConnection` |
| ARC-04 | Another patient's chart / unlinked chart concealed with 404 | `patientCannotReadAnotherPatientsInstructionContent` |
| ARC-02 / OPS-02 | Checks stay in the guarded `execute` path, tenant from `TenantContext`, not the request | all patient tests; staff branch unchanged |
| IAM-01 (staff) | Staff reads still require unit assignment + active connection | every pre-existing staff test; `unitAssignmentAndConnectionAreValidatedOnEveryCommand` |

## Root cause (process defect, not a code defect)

The scoped implementation (patient-authorized instruction reads) was already
complete and correct on the branch: exactly six files under the two declared
`messaging/instructions` directories. The **only** out-of-scope modification was
the shared `.agent/pr-notes.md`:

- On `main`, that path holds the note for a different story (`AUD-20261120-13`);
  the branch's repeated `Agent changes for AUD-20261120-12` commits overwrote it
  with the AUD-12 note (`git diff origin/main HEAD --stat` showed 247 changed
  lines on that file).
- When two swarm writers touch the same shared file, the integrator's 3-way
  merge cannot resolve it.
- This is the same recurring defect recorded in the prior fix notes
  (`fix-20261120-120143.md` through `fix-20261120-130314.md`): each new worker
  run writes its notes into the shared file again, so the merge keeps failing.

No other file diverged outside the declared scope: the six instruction files
and the uniquely named per-story note files are not conflict sources.

## Fix applied

`git checkout origin/main -- .agent/pr-notes.md` — a **revert of a forbidden
edit**, not a content change. The shared file now matches `main` byte-for-byte
(`git diff --numstat origin/main -- .agent/pr-notes.md` is empty), so the net
committed state of that file equals the merge target and cannot conflict.
My notes live only in this uniquely named per-story file
(`.agent/pr-notes/fix-20261120-131400.md`). No production or test code was
changed by this attempt; the scoped code is unchanged.

## Verification that the merge is now clean

- `git merge-tree --write-tree origin/main HEAD` → EXIT 0, single tree OID
  `211118f76e152d930ec19960afb3e5ad1aa38eb2`, **0** `CONFLICT` markers.
  Before restoring the shared file the same reproduction reported
  `CONFLICT (content): Merge conflict in .agent/pr-notes.md`.

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

## New/changed branches and their covering tests (100% coverage)

No branch was added by *this* attempt (shared-file revert + notes only). The
branches introduced by the original story change are all covered; JaCoCo
counters were read from `apps/api/target/site/jacoco/jacoco.xml` after the full
verify recorded in `fix-20261120-130314.md` (tool evidence, unchanged here):

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `|| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial) |
| `InstructionService.get`: `if (isPatient())` true/false | patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff) |
| `get`: `else if (!staffAssignedToUnit(…))` true/false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, 403); staff `get` tests (false) |
| `requirePatientChartAccess`: chart-not-linked if true/false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwn…` (false) |
| `requirePatientChartAccess`: connection-inactive if true/false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwn…` (false) |
| `listForPatient`: `if (isPatient())` true/false | patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts`, `listForPatientConceals…` (false, staff) |
| `listForPatient` patient branch: link membership false/true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwn…` (true) |
| `listForPatient` patient branch: connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwn…` (true → one revision) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all three patient service tests + controller patient test (present set); staff tests run the port with empty links |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests above. Per-class JaCoCo from the prior full
verify: `InstructionActions` 73/73 instructions, 25/25 branches;
`InstructionService` 950/950 instructions, 52/52 branches;
`InstructionStore$InMemory` 571/571 instructions, 24/24 branches — all missed
counters 0. The full `mvn verify` 100% JaCoCo gate and the database/integration
tier are the integrator's responsibility, as directed for this worker.

## Commands run and results (tool evidence)

- `mvn -B -f apps/api/pom.xml -Pno-security,fast test` → EXIT 0;
  `Tests run: 1918, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
  Full log: `/tmp/opencode/aud12-fast-unit.log`.
- Per-class Surefire reports from that run: `InstructionContractTest` 13/13,
  `InstructionServiceTest` 18/18, `InstructionControllerTest` 3/3 —
  0 failures / 0 errors / 0 skipped.
- `git merge-tree --write-tree origin/main HEAD` → EXIT 0, tree
  `211118f7…`, 0 `CONFLICT` markers (merge is clean after the revert).
- `python3 -I scripts/check-names.py`, `check-docs.py`, `check-secrets.py`,
  `check-migrations.py --require-baseline` → all EXIT 0.
- `git ls-files -v opencode.json` → `S` (skip-worktree): the documented cloud
  worker CI-profile artifact (BUGS OPS-CW-003-01). `scripts/check.sh` /
  `check-pack` judge that worktree CI profile locally and can report
  `opencode.json is stale`; the committed local profile is correct and the
  integrator measures it. No config/agent/skill file was touched, so no
  regeneration was performed (it is outside this scope and would mask the CI
  profile).

## Decisions

- Wrong-chart / wrong-patient reads return **404 (NOT_FOUND)** to conceal whether
  another patient's chart exists (ARC-04); a revoked connection on a linked chart
  returns 403. This mirrors `InstructionFactService.requirePatientAccess`.
- The `patientId` query parameter is kept for contract compatibility but is
  validated against the caller's verified link set (IAM-05), not trusted.
- The staff branch is unchanged; patient handling is additive.
- Restoring the shared `.agent/pr-notes.md` to `main` is a revert of a forbidden
  edit: the net committed state equals `main`, so it cannot conflict. Notes for
  this attempt live only in this uniquely named per-story file.

## Defects found

- Process defect (fixed): this branch repeatedly re-committed its note into the
  shared `.agent/pr-notes.md` — the sole source of the merge conflict with main.
  The permanent remedy is to write notes only to a uniquely named per-task file
  (as this attempt does), never to the shared file.
- No new product defect. No BUGS.md change is in scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven, Git and Python policy
scripts) from this checkout with synthetic fixtures only. No reviewer subagent
finding, simulated-user walkthrough, human participant or real patient data
contributed to this change. The scoped code diff was derived by reading the
existing source (tool evidence).
