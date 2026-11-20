# fix-20261120-130314 — AUD-20261120-12 merge/check repair (fix attempt 1 of 3)

## What this attempt is

Repair of the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):

> Merge conflict with main could not be resolved automatically (merged but checks
> failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
> main and this branch.

Worker branch `swarm/AUD-20261120-12-289`; starting HEAD `bf47726`
(`Agent changes for AUD-20261120-12`), clean tree, strict descendant of
`origin/main` (`git rev-list --left-right --count origin/main...HEAD` = `0 19`;
merge base = `origin/main` `742f2bb`). No push, tag or `gh` call. Synthetic
fixtures only. The orchestrator owns `[-]`/`[x]` in `TODO.md`; this worker did
not edit any shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`,
`PROJECT.md`, `docs/swarm`).

## Root cause (process defect, not a code defect)

The scoped implementation (patient-authorized instruction reads) was already
complete and correct on the branch: exactly six files under the two declared
`messaging/instructions` directories. The **only** out-of-scope modification was
the shared `.agent/pr-notes.md`:

- On `main`, that path holds the note for a different story (`AUD-20261120-13`);
  the branch's repeated `Agent changes for AUD-20261120-12` commits overwrote it
  with the AUD-12 note (`137` insertions / `105` deletions vs `main`).
- When two swarm writers touch the same shared file, the integrator's 3-way
  merge cannot resolve it.

Reproduced deterministically with `git merge-tree --write-tree` in a temporary
worktree: with another story's note present in `.agent/pr-notes.md` on the
`main` side, merging `bf47726` produced:

```
CONFLICT (content): Merge conflict in .agent/pr-notes.md
```

No other file diverged outside the declared scope; the six instruction files
and the per-story note files are not a conflict source (new, uniquely named
files cannot conflict).

## Fix applied

`git checkout origin/main -- .agent/pr-notes.md` — a **revert of a forbidden
edit**, not a content change. The shared file now matches `main` byte-for-byte
(`git diff --numstat origin/main -- .agent/pr-notes.md` is empty), so the net
committed state of that file equals the merge target and it cannot conflict.
My notes live only in this uniquely named per-story file. No production or test
code was changed by this attempt; the scoped code is unchanged.

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
counters were read from `apps/api/target/site/jacoco/jacoco.xml` after a full
verify (tool evidence):

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

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests above.

## Commands run and results (tool evidence)

- `git rev-list --left-right --count origin/main...HEAD` → `0	19` (strict descendant).
- `git diff --numstat origin/main HEAD -- .agent/pr-notes.md` → `137	105` before fix;
  empty after `git checkout origin/main -- .agent/pr-notes.md`.
- Merge reproduction (before fix): `git merge-tree --write-tree <sim-main> bf47726`
  → `CONFLICT (content): Merge conflict in .agent/pr-notes.md`.
- Merge verification (after fix, new HEAD `7a66f10`): the same `git merge-tree
  --write-tree <sim-main> 7a66f10` returned only a tree OID
  (`cd20b0f0…`) with **0** `CONFLICT` markers — the unresolvable merge is resolved.
- `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  → EXIT 0; `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0` (13 contract,
  18 service, 3 controller). Full log: `/tmp/opencode/aud12-targeted.log`.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
  → EXIT 0; `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check → All coverage checks have been met.`; `BUILD SUCCESS`.
  Full log: `/tmp/opencode/aud12-verify.log`. (OWASP and SpotBugs intentionally
  skipped via `-Pno-security`; they are the integrator's gate.)
- `python3 -I scripts/check-names.py`, `check-docs.py`, `check-secrets.py`,
  `check-migrations.py --require-baseline` → all EXIT 0.
- `bash scripts/check.sh` → EXIT 1, stopping at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker skip-worktree artifact (OPS-CW-003-01), not a change
  defect: `git ls-files -v opencode.json` = `S`; `git show HEAD:opencode.json`
  matches `gen-opencode.render(ci=False)` (True) and the worktree file matches
  `render(ci=True)` (True). No config/agent/skill file was touched, so
  `opencode.json` was not regenerated (it would mask the CI profile and edit a
  file outside scope). The integrator measures the committed local profile,
  where the check is green.

### Per-class JaCoCo coverage (after full verify)

- `InstructionActions`: INSTRUCTION 73/73, BRANCH 25/25, LINE 11/11, METHOD 3/3, CLASS 1/1.
- `InstructionService`: INSTRUCTION 950/950, BRANCH 52/52, LINE 166/166, METHOD 32/32, CLASS 1/1.
- `InstructionStore$InMemory`: INSTRUCTION 571/571, BRANCH 24/24, LINE 95/95, METHOD 29/29, CLASS 1/1.
- `InstructionController`: INSTRUCTION 58/58, LINE 13/13, METHOD 7/7, CLASS 1/1.
- `InstructionStore$InMemory$Connection`: INSTRUCTION 12/12, LINE 1/1, METHOD 1/1, CLASS 1/1.

All missed counters are 0 — 100% on every file touched by the story.

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
- No new product defect. No BUGS.md change is in scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven/JaCoCo, Git and Python
policy scripts) from this checkout with synthetic fixtures only. No reviewer
subagent finding, simulated-user walkthrough, human participant or real patient
data contributed to this change. The scoped code diff was derived by reading the
existing source (tool evidence).
