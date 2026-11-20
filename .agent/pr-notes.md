# fix-20261120-151205 — AUD-20261120-12 merge/check repair (attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only
instruction reads deny the patient role**. The swarm-integrator reported:
"Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
main and this branch."

The Java fix was already present on branch `swarm/AUD-20261120-12-289` when
this repair started (six in-scope files under `messaging/instructions`). I
retained it verbatim and re-verified it. The merge blocker was **not** the Java
change: it was the shared note file `.agent/pr-notes.md`. AGENTS.md forbids a
worker from editing that shared path (notes must go only to
`.agent/pr-notes/<task-id>-<timestamp>.md`), but automated "Agent changes for
AUD-20261120-12" commits repeatedly overwrote it with worker note text. The
latest such commit, `7134d63`, again replaced main's `AUD-20261120-13` content.
Because other story PRs also touch that shared path, the integrator's sequential
merge could not resolve it and re-dispatched this repair.

**Root-cause fix in this attempt:** restore `.agent/pr-notes.md` to the exact
content of `origin/main` (verified byte-identical with
`git diff --quiet origin/main -- .agent/pr-notes.md` -> 0) and keep all evidence
in this worker-owned file. No production code was changed in this repair.

**Evidence source:** executed tool commands (tool evidence) from this checkout
with synthetic fixtures only. No reviewer subagent, simulated user, human
participant or real patient data contributed. `mvn verify` with PostgreSQL and
JaCoCo was executed locally (not just delegated) and is recorded below.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through both instruction read routes | `InstructionService.get` / `listForPatient`, `InstructionActions.READ` grants `patient` |
| MSG-05 | Reading is a separate fact and does not imply acknowledgment | unchanged fact model; reads project current + history + facts |
| PAT-02 | A patient sees content only for a verified, linked chart with an active connection | `requirePatientChartAccess`, patient branch of `listForPatient` |
| IAM-05 | Caller is bound to charts linked to the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts`, patient seed `linkPatientChart` |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `connectionActive` guards in both patient branches |
| ARC-04 | Another patient's thread or chart is concealed with 404 rather than 403 | `requirePatientChartAccess` / list membership -> `notFound()` |
| ARC-02 / OPS-02 | All checks stay inside the tenant-guarded `execute` path; tenant scope comes from `TenantContext`, never the request | `InstructionService.execute` (unchanged) |

Acceptance cases covered by tests: patient self-read of
`GET /api/v1/patient-instructions/{threadId}` and
`GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
active connection returns content; wrong-patient chart/thread -> 404; revoked
connection -> 403 / empty list; staff reads unchanged.

## Production change retained (from the story branch, not authored this run)

- `InstructionActions.READ` now grants the `patient` role in addition to staff
  senders, `tenant_admin` and `privacy_auditor`.
- `InstructionService.get`: patient callers go through
  `requirePatientChartAccess` (verified-chart membership + active connection);
  staff callers keep the existing `staffAssignedToUnit` check.
- `InstructionService.listForPatient`: patient callers resolve the requested
  `patientId` against charts verified for the authenticated subject and filter
  to active connections; the staff branch is unchanged.
- `InstructionStore`: new `verifiedPatientCharts(subject)` port method and
  `linkPatientChart` seed on the in-memory adapter.
- Tests: action-matrix patient assertion; service tests for positive self-read,
  wrong-patient denial and revoked connection; one HTTP-level positive test.

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
| `listForPatient` patient branch connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (false -> empty); `patientReadsOwnInstructionContentAndOwnThread` (true -> one revision) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all three patient service tests + HTTP patient test (present set); pre-existing staff tests run the port with an empty set |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests above.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` -> `InstructionController.get`
  -> `InstructionService.get` -> `execute(READ, ...)` -> `isPatient()` ->
  `requirePatientChartAccess` -> `verifiedPatientCharts` / `connectionActive`.
  Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`,
  `patientCannotReadAnotherPatientsInstructionContent`,
  `patientReadRequiresAnActiveVerifiedConnection`.
- `GET /api/v1/patient-instructions?patientId=...` ->
  `InstructionController.listForPatient` -> `InstructionService.listForPatient`
  -> patient verified-chart membership + active-connection filter; staff filter
  unchanged.

## Exact commands run and results (tool evidence)

Merge topology:
- `git rev-list --left-right --count HEAD...origin/main` -> `39 0`
  (branch ahead, main unchanged since the merge base `742f2bb`). Merging main
  into the branch is a no-op for the Java tree; the only divergence is the
  shared note file.
- `git restore --source=origin/main --staged --worktree -- .agent/pr-notes.md`
  then `git diff --quiet origin/main -- .agent/pr-notes.md` -> 0.
- `git show origin/main:.agent/pr-notes.md | head -1` ->
  `# AUD-20261120-13 — Document link accepts order or result subjects without verifying they exist`.

Fast unit tests (the worker gate; integrator runs DB/integration):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast test      EXIT: 0
  InstructionContractTest: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
  InstructionControllerTest: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  InstructionServiceTest: Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

Coverage command and result (100% gate, real PostgreSQL):
```
SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify
EXIT: 0
Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0
jacoco:0.8.16:check -> All coverage checks have been met.
BUILD SUCCESS, Total time: 01:18 min
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
python3 -I scripts/check-docs.py    -> exit 0
python3 -I scripts/check-secrets.py -> exit 0
python3 -I scripts/check-names.py   -> exit 0
python3 -I scripts/check-migrations.py --require-baseline -> exit 0
git diff --quiet origin/main -- .agent/pr-notes.md -> 0 (identical)
```

## Known local environment limits (not caused by this change)

- `bash scripts/check.sh` / `scripts/quick-check.sh` locally fail at
  `check-pack: opencode.json is stale` — the documented OPS-CW-003-01 artifact:
  `git ls-files -v opencode.json` -> `S` (skip-worktree), the worktree file is
  the CI profile, `git show HEAD:opencode.json` is the committed local profile.
  With the committed local profile in place,
  `python3 -I scripts/gen-opencode.py --check` -> exit 0. No config, agent or
  skill file was changed by this work; the worktree CI profile was restored
  after the check.
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
- This repair restores the shared note file to main and keeps all evidence in
  this worker-owned file, rather than editing the shared path again. That
  removes the repeated merge conflict without touching production code.

## Defects found

No new product defect. The audited defect (patient role denied on every
instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed by the retained change and re-verified here. The blocking
integration defect was the shared `.agent/pr-notes.md` divergence across fix
attempts and is repaired here. No BUGS.md change is in scope for this worker.
