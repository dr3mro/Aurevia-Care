# fix-20261120-150124 — AUD-20261120-12 merge/check repair

## Task and provenance

Repair attempt 1 of 3 for the swarm-integrator report on story
**AUD-20261120-12 — Patients cannot read instruction content: the only instruction
reads deny the patient role**: *"Merge conflict with main could not be resolved
automatically (merged but checks failed). Reproduce with `scripts/check.sh` on the
merge of main and this branch."*

The story's Java fix was already present on branch `swarm/AUD-20261120-12-289`
when this repair started (production + test changes in the six in-scope files).
I retained that fix verbatim and verified it. The merge blocker was **not** the
Java change: it was the shared note file `.agent/pr-notes.md`. That file must
never be edited by a worker (AGENTS.md: notes go only to
`.agent/pr-notes/<task-id>-<timestamp>.md`), yet the automated "Agent changes for
AUD-20261120-12" commits repeatedly overwrote it with worker note text (commit
`37620c6` replaced main's `AUD-20261120-13` text with `fix-20261120-145144`
text). Because other story PRs also touch that shared path, the integrator's
sequential merge could not resolve it.

**Evidence source:** executed tool commands (tool evidence) from this checkout
with synthetic fixtures only. No reviewer subagent, simulated user, human
participant or real patient data contributed. The database/integration tier and
OWASP/SpotBugs/CI checks are the integrator's gates and were not run to
completion locally (see "Known local environment limits" below).

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through both instruction read routes | `InstructionService.get` / `listForPatient`, `InstructionActions.READ` grants `patient` |
| MSG-05 | Reading is a separate fact and does not imply acknowledgment | unchanged fact model; reads only project current + history + facts |
| PAT-02 | A patient sees content only for a verified, linked chart with an active connection | `requirePatientChartAccess`, patient branch of `listForPatient` |
| IAM-05 | Caller is bound to charts linked to the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts`, patient seed `linkPatientChart` |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `connectionActive` guards in both patient branches |
| ARC-04 | Another patient's thread or chart is concealed with 404 rather than 403 | `requirePatientChartAccess` / list membership → `notFound()` |
| ARC-02 / OPS-02 | All checks stay inside the tenant-guarded `execute` path; tenant scope comes from `TenantContext`, never the request | `InstructionService.execute` (unchanged) |

Acceptance cases verified by tests: patient self-read of
`GET /api/v1/patient-instructions/{threadId}` and
`GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
active connection returns content; wrong-patient chart/thread → 404; revoked
connection → 403 for `get` and empty list for `listForPatient`; staff branch
(unit assignment + active connection) unchanged.

## Changes (inside the declared scope)

- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main` so the integrator
  merge of this branch into main cannot conflict on the shared path. Verified:
  `git diff --quiet origin/main -- .agent/pr-notes.md` → identical.
- Wrote this worker-owned note file only; no other shared tracker, `.github/`,
  `.opencode/`, `opencode.json`, workflow, build file or migration was touched.
- Retained the six in-scope Java files verbatim (three production, three tests)
  that constitute the story fix:
  - `InstructionActions.allows`: `READ` also permits `patient`.
  - `InstructionStore`: new `verifiedPatientCharts(subject)` port with a
    tenant-keyed in-memory implementation and a `linkPatientChart` synthetic
    seed, mirroring the established `InstructionFactStore` verification pattern.
  - `InstructionService`: patient branch in `get` and `listForPatient`, plus
    private helpers `isPatient()` and `requirePatientChartAccess(...)`; staff
    branch unchanged.
  - Tests: action-matrix `patient` assertion; service tests for positive
    self-read, wrong-patient denial and revoked connection; one HTTP-level
    positive test.

No migration, contract or coverage-threshold change; no test weakened, skipped
or deleted; no ID-named folder or package introduced.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test covering each side |
|---|---|
| `InstructionActions.READ` `|| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it via `execute` |
| `InstructionService.get` `if (isPatient())` true/false | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread` (true); existing staff `get` tests (false) |
| `InstructionService.get` `else if (!staffAssignedToUnit(...))` true/false | existing `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, stranger 403); staff `get` tests (false) |
| `requirePatientChartAccess` chart-not-linked `if` true/false | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `requirePatientChartAccess` connection-inactive `if` true/false | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `InstructionService.listForPatient` `if (isPatient())` true/false | new patient list tests (true); `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff) |
| `listForPatient` patient branch `verifiedPatientCharts.contains(patient)` false/true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true) |
| `listForPatient` patient branch connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all three patient service tests + HTTP patient test (present set); pre-existing staff tests run the port with an empty set |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both outcomes
are exercised by the tests above.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` →
  `InstructionService.get` → `execute(READ, ...)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`,
  `patientCannotReadAnotherPatientsInstructionContent`,
  `patientReadRequiresAnActiveVerifiedConnection`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient` →
  patient verified-chart membership + active-connection filter; staff filter
  unchanged.

## Exact commands run and results (tool evidence)

Topology (why the merge conflict is a note-file problem):
- `git merge-base --is-ancestor origin/main HEAD` → 0 (YES); `git rev-parse
  HEAD origin/main` → `37620c6` / `742f2bb`; merge-base `742f2bb`. Merging main
  into this branch is a no-op, so the Java tree has no conflict.
- `git diff --name-status origin/main HEAD` → `.agent/pr-notes.md` (M),
  `.agent/pr-notes/fix-20261120-145144.md` (A) and the six in-scope Java files;
  the shared file on the branch began `# fix-20261120-145144 ...` while main's
  began `# AUD-20261120-13 ...`.

Red → green (test-first proof that the new tests detect the defect). With only
`InstructionActions.java` reverted to `origin/main` (store/service/test changes
kept), the new tests failed:
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test
RED EXIT: 1
Tests run: 13, Failures: 1 ... InstructionContractTest
Tests run: 18, Failures: 0, Errors: 3 ... InstructionServiceTest
Tests run: 3, Failures: 0, Errors: 1 ... InstructionControllerTest
InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead:174 expected: <true> but was: <false>
```
The production file was restored from backup; `git diff --quiet` confirmed the
tree back to branch state.

Green (fast unit tests), final tree:
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test
EXIT: 0
InstructionContractTest: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
InstructionControllerTest: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
InstructionServiceTest: Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

Coverage command and result (100% gate, PostgreSQL available):
```
SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify
EXIT: 0
Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0
jacoco:0.8.16:check -> All coverage checks have been met.
BUILD SUCCESS, Total time 01:16 min
```
JaCoCo `apps/api/target/site/jacoco/jacoco.xml` per touched class (tool
evidence) — every counter 0 missed:

| Class | Instruction | Line | Branch | Method | Class |
|---|---|---|---|---|---|
| `InstructionActions` | 73/73 | 11/11 | 25/25 | 3/3 | 1/1 |
| `InstructionService` | 950/950 | 166/166 | 52/52 | 32/32 | 1/1 |
| `InstructionStore$InMemory` | 571/571 | 95/95 | 24/24 | 29/29 | 1/1 |
| `InstructionStore$InMemory$Connection` | 12/12 | 1/1 | n/a | 1/1 | 1/1 |

`InstructionStore` is an interface and emits no executable counters. No file I
touched is below 100% on any counter.

Policy checks after restoring the shared file:
```
python3 -I scripts/check-docs.py   -> exit 0
python3 -I scripts/check-secrets.py -> exit 0
python3 -I scripts/check-names.py   -> exit 0
git diff --quiet origin/main -- .agent/pr-notes.md -> 0 (identical)
```

## Known local environment limits (not caused by this change)

Reported for transparency; left to the integrator's proper image, not worked
around here:
- `bash scripts/check.sh` locally fails at `check-pack: opencode.json is stale`
  — the documented OPS-CW-003-01 artifact: `git ls-files -v opencode.json` →
  `S` (skip-worktree), worktree file is the CI profile, `git show
  HEAD:opencode.json` is the committed local profile. No config/agent/skill file
  was changed by this work.
- The Python tooling suite under this sandbox has 6 failures / 10 errors in
  `test_agent_run.py` / `test_swarm.py` (`ModuleNotFoundError: No module named
  'yaml'` and git/gh sandbox assertions). Python `yaml` is absent locally and
  the integrator image has it; unrelated to the Java instructions domain.
- With `-Pno-security`, OWASP Dependency-Check is skipped (certificate error
  reaching the NVD feed) and SpotBugs is skipped; both remain integrator gates.

## Decisions

- Wrong-chart / wrong-patient reads return **404 (NOT_FOUND)** to conceal whether
  another patient's chart exists (ARC-04), matching
  `InstructionFactService.requirePatientAccess`; a revoked connection on an
  otherwise linked chart returns 403.
- The `patientId` query parameter is kept for contract compatibility, but for a
  patient caller it is validated against charts linked to the authenticated
  subject (IAM-05); a patient with several verified charts may still choose.
- The staff branch is unchanged; patient handling is additive, so no existing
  staff authorization behavior is weakened.
- The repair restores the shared note file to main and keeps all evidence in
  this worker-owned file, rather than editing the shared path again.

## Defects found

No new product defect. The audited defect (patient role denied on every
instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed by the retained change. The blocking integration defect
was the shared `.agent/pr-notes.md` divergence across fix attempts and is
repaired here. No BUGS.md change is in scope for this worker.
