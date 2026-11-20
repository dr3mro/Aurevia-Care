# fix-20261120-124101 — AUD-20261120-12 merge/check repair (attempt 1 of 3)

## What this attempt is

Repair for the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):
`Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with scripts/check.sh on the merge of main
and this branch.`

Branch: `swarm/AUD-20261120-12-289`, start HEAD `1dceeea`
(`Agent changes for AUD-20261120-12`), clean tree, strict descendant of
`origin/main` (`git rev-list --left-right --count origin/main...HEAD` = `0 15`,
so the merge base equals `origin/main`). No push, tag or `gh` call was made.
Synthetic fixtures only. The orchestrator owns `[-]`/`[x]` in `TODO.md`; this
worker did not edit any shared tracker.

**Outcome:** the in-scope implementation is complete and verifiably green at
100% JaCoCo. The merge/check failure was an out-of-scope process defect: the
branch re-committed its note into the **shared** `.agent/pr-notes.md`, which
conflicts with the different story note `main` keeps in that path. This attempt
restores that shared file to `origin/main` so the branch diverges from `main`
only in the two declared instruction directories and in per-story note files
(new note files cannot conflict). No production or test code was changed by
this attempt; the in-scope Java changes were already present on the branch.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where verified |
|---|---|---|
| MSG-04 | Patients receive typed instruction title/body through `get` and `listForPatient`. | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| MSG-05 | Patient read is a separate fact from acknowledgment; no acknowledgment is implied on read. | The patient read branch only reads `store.current`/`history`/`facts`; it appends no fact. |
| PAT-02 | Patient sees only instruction content for a verified, linked, active chart. | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`, `patientReadRequiresAnActiveVerifiedConnection` |
| IAM-05 | Caller is bound to patient charts linked to the authenticated account subject; several charts may be linked. | `verifiedPatientCharts(subject)` membership check in `get`/`listForPatient`; `linkPatientChart` synthetic seed in tests. |
| MSG-01 / MSG-07 | A revoked patient-unit connection ends future content access on the next read. | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` |
| ARC-04 | Another patient's chart/thread is concealed with 404 (existence not revealed). | `patientCannotReadAnotherPatientsInstructionContent` asserts 404 for `get` and `listForPatient` on an unlinked chart. |
| ARC-02 / OPS-02 | All checks stay inside the existing guarded `execute` path; tenant scope is from `TenantContext`, not the request. | `missingContextAndCrossTenantLocalIdentifiersAreIsolated`, full `mvn verify` under forced-RLS integration tier. |

## Root cause of the merge/check failure (defect found)

`.agent/pr-notes.md` is a **shared** file that AGENTS.md forbids editing; notes
must go only to `.agent/pr-notes/<story>-<timestamp>.md`. The original
`Agent changes for AUD-20261120-12` commit overwrote the shared file with the
AUD-12 note, while `main` keeps a different note (AUD-13) in that path. Each
later "fix" commit (`c314ce8`, `c8dbf72`, `78c7024`) was titled "restore shared
pr-notes to unblock AUD-12 merge" but actually **wrote its own fix-note content
into the shared file again** rather than restoring `origin/main`'s bytes — so
the divergence persisted and the integrator could not merge cleanly / checks
failed on the merge result.

Evidence (tool): `git diff --stat origin/main HEAD -- .agent/pr-notes.md` showed
150 insertions / 110 deletions before the fix; `git diff --stat
742f2bb origin/main -- .agent/pr-notes.md` was empty (main never changed the
file since the merge base), proving the divergence came solely from this branch.

**Fix applied:** `git checkout origin/main -- .agent/pr-notes.md`. After it,
`git diff origin/main -- .agent/pr-notes.md` is empty and the branch differs
from `main` only in 6 in-scope Java files plus per-story note files. No note
was written to any file directly under `.agent/` other than this dedicated
`fix-20261120-124101.md`.

## In-scope change (already on the branch, re-verified here)

Scope: `messaging/instructions` main + test only. No migration, contract,
tracker, build file, agent or skill was changed. No new package or task-ID-named
folder was introduced (`scripts/check-names.py` exit 0).

- `InstructionActions.allows`: `READ` now also permits `patient` (additive OR;
  staff sender roles, `tenant_admin` and `privacy_auditor` unchanged).
- `InstructionStore`: new `verifiedPatientCharts(subject)` port returning the
  charts linked to the authenticated patient account (IAM-05), with a
  tenant-keyed in-memory implementation and a `linkPatientChart` synthetic seed.
  This mirrors the established `InstructionFactStore.verifiedPatientCharts`
  pattern used by `InstructionFactService.requirePatientAccess`.
- `InstructionService`:
  - `get` — patient callers are bound via `requirePatientChartAccess`; staff
    callers keep the `staffAssignedToUnit` guard.
  - `listForPatient` — patient callers may only request a chart from their own
    verified link set, then see current revisions with an active connection;
    the staff filter is unchanged.
  - New private helpers `isPatient()` and `requirePatientChartAccess(...)`.
- Tests: action-matrix assertion, three service tests (positive self-read,
  wrong-patient, revoked connection) and one HTTP-level positive test.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get`
  → `InstructionService.get` → `execute(InstructionActions.READ, …)` →
  `isPatient()` → `requirePatientChartAccess` → `verifiedPatientCharts` /
  `connectionActive`. Tested by
  `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`
  and `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=…` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient branch verified-chart membership + connection filter. Wrong-patient
  denial by `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.

## New/changed branches and their tests (100% coverage)

| Production branch | Test that covers both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` — true / false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it through `execute`. |
| `InstructionStore.InMemory.verifiedPatientCharts` and `linkPatientChart` | All three patient service tests + the controller patient test (present set); pre-existing staff tests run the port without links. |
| `InstructionService.get`: `if (isPatient())` true / false | New patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff). |
| `InstructionService.get`: `else if (!staffAssignedToUnit(…))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, stranger 403); staff `get` tests (false). |
| `requirePatientChartAccess`: chart-not-linked `if` true / false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `requirePatientChartAccess`: connection-inactive `if` true / false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `InstructionService.listForPatient`: `if (isPatient())` true / false | New patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff). |
| `listForPatient` patient branch: `verifiedPatientCharts.contains(patient)` false / true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true). |
| `listForPatient` patient branch: connection filter false / true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision). |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests listed above. No threshold, exclusion or
skip was added or lowered; no existing test was weakened.

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  — exit 0. Surefire: `InstructionContractTest` 13/13,
  `InstructionServiceTest` 18/18, `InstructionControllerTest` 3/3; 0 failures /
  0 errors / 0 skipped. (RED before implementation was documented by the
  original worker as a compile failure on the missing `InMemory.linkPatientChart`
  API; see `.agent/pr-notes/AUD-20261120-12-20261120-101200.md`.)
- `mvn -B -f apps/api/pom.xml -Pno-security verify` — exit 0;
  `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check (check)` → `All coverage checks have been met.`;
  `BUILD SUCCESS`. Full log: `/tmp/opencode/verify-aud12-fix.log`.
- JaCoCo `apps/api/target/site/jacoco/jacoco.xml` read per touched class (tool
  evidence; `covered/total`):
  - `InstructionActions`: LINE 11/11, BRANCH 25/25, METHOD 3/3, CLASS 1/1, INSTRUCTION 73/73.
  - `InstructionService`: LINE 166/166, BRANCH 52/52, METHOD 32/32, CLASS 1/1, INSTRUCTION 950/950.
  - `InstructionStore$InMemory`: LINE 95/95, BRANCH 24/24, METHOD 29/29, CLASS 1/1, INSTRUCTION 571/571.
  - `InstructionController`: LINE 13/13, METHOD 7/7, CLASS 1/1, INSTRUCTION 58/58 (no branches).
  All missed counts are 0 — 100% on every file touched.
- Policy checks (all exit 0): `python3 -I scripts/check-docs.py`,
  `python3 -I scripts/check-names.py`, `python3 -I scripts/check-secrets.py`,
  `python3 -I scripts/check-migrations.py --require-baseline`,
  `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q`
  (2/2 OK). `check-docs.py` was re-run after this note file was written.
- Reproducing the story command `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker skip-worktree false positive (OPS-CW-003-01, MEMORY.md),
  unrelated to this change: `git ls-files -v opencode.json` reports flag `S`;
  verified with `gen-opencode.render` that
  `git show HEAD:opencode.json == render(ci=False)` (True),
  `git show HEAD:.github/opencode.ci.json == render(ci=True)` (True), and the
  worktree file `== render(ci=True)` (True). The committed profile is correct and
  the pack check is green at HEAD in CI; do not regenerate `opencode.json` from
  a worker where the CI profile is checked out. `shellcheck` is not installed
  locally, so shell lint is skipped here (CI runs it). Database and integration
  tests are the integrator's gate; they were exercised here only through the
  full local `mvn verify` above.

## Decisions

- The merge conflict is a process hygiene defect, not a code defect: restored
  the shared `.agent/pr-notes.md` to `origin/main` and published this attempt's
  note only to its own per-story file. No production or test code needed change.
- Wrong-chart and wrong-patient access return **404 (NOT_FOUND)** rather than
  403, to conceal whether another patient's chart exists (ARC-04), matching
  `InstructionFactService.requirePatientAccess`. A revoked connection on an
  otherwise linked chart returns 403.
- The `patientId` query parameter is kept for contract compatibility, but for a
  patient caller it is validated against the charts linked to the authenticated
  subject (IAM-05) instead of being trusted. A patient with several verified
  charts (IAM-05) may still choose among them.
- The staff branch is unchanged; patient handling is additive, so no existing
  staff authorization behavior was weakened.

## Defects found

No new product defect was introduced. The audited defect (patient role denied
on every instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed. The process defect (shared `pr-notes.md` rewrite causing
the merge/check failure) is corrected in this attempt. No BUGS.md change is in
scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Git, Maven/Surefire/JaCoCo,
policy scripts and unit suites) from this checkout using synthetic fixtures
only. No reviewer subagent finding, simulated user walkthrough, human
participant or real patient data contributed to this change. The action matrix
and store pattern were derived by reading the existing source (tool evidence),
not from a human reviewer.
