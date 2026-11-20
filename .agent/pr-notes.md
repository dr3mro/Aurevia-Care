# fix-20261120-154526 — AUD-20261120-12 (merge/check repair, fix attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only
instruction reads deny the patient role**. The swarm-integrator reported:
"Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
main and this branch."

Branch: `swarm/AUD-20261120-12-289` (HEAD `c318e49` at start). `origin/main` is
an ancestor of this branch; the in-scope Java fix was already present on the
branch when this repair started. I retained it verbatim, re-verified it, and
changed no production behavior. The only change authored this run is (a)
restoring the shared `.agent/pr-notes.md` to its `origin/main` bytes so the
branch no longer modifies a path contested by parallel PRs, and (b) writing
this worker-owned notes file.

**Evidence source:** executed tool commands (tool evidence) from this checkout
with synthetic fixtures only. Skills loaded (instruction/guidance only; no
specialist subagent was delegated): `smartclinic-delivery`,
`smartclinic-senior-backend`, `smartclinic-senior-spring-security`,
`smartclinic-senior-java-build`, `smartclinic-test-engineer`. No simulated user,
human participant, clinician or real patient data contributed to this work.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through both instruction read routes | `InstructionActions.READ` grants `patient`; `InstructionService.get` / `listForPatient` patient branches |
| MSG-05 | Reading stays a separate fact and does not imply acknowledgment | fact model unchanged; reads project current + history + facts |
| PAT-02 | A patient sees content only for a verified, linked chart with an active connection | `InstructionService.requirePatientChartAccess`, patient branch of `listForPatient` |
| IAM-05 | Caller is bound to charts verified for the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts` port + `linkPatientChart` seed |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `connectionActive` guards in both patient branches |
| ARC-04 | Another patient's thread or chart is concealed with 404, not 403 | `requirePatientChartAccess` / list membership -> `notFound()` |
| ARC-02 / OPS-02 | Checks stay inside the tenant-guarded `execute` path; tenant comes from `TenantContext` | `InstructionService.execute` (unchanged) |

Acceptance cases: patient self-read of `GET /api/v1/patient-instructions/{threadId}`
and `GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
active connection returns content; wrong-patient chart/thread -> 404; revoked
connection -> 403 (single thread) / empty list (list); staff reads unchanged.

## Scope and change (retained on the branch, not authored this run)

Files under the declared scope
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions` and
`apps/api/src/test/java/com/aurevia/smartclinic/messaging/instructions`:

- `InstructionActions.java` — `READ` now grants `patient` alongside staff
  senders, `tenant_admin` and `privacy_auditor`.
- `InstructionService.java` — `get` sends patient callers through
  `requirePatientChartAccess` (verified-chart membership + active connection)
  while the staff branch keeps `staffAssignedToUnit`; `listForPatient` validates
  the requested chart against charts verified for the authenticated subject and
  filters to active connections; new private `isPatient` and
  `requirePatientChartAccess` helpers.
- `InstructionStore.java` — new `verifiedPatientCharts(subject)` port method,
  its tenant-keyed in-memory implementation and a `linkPatientChart` test seed.
- `InstructionContractTest`, `InstructionServiceTest`, `InstructionControllerTest`
  — action-matrix patient grant; positive self-read, wrong-patient 404, revoked
  connection 403/empty and one HTTP-level positive read.

Authored this run: restore `.agent/pr-notes.md` to `origin/main`; add this file.
No migration, contract, dependency, coverage threshold or exclusion change. No
test was weakened, skipped or deleted. No task/story-ID package or class name was
introduced. Production packages remain business-domain named
(`messaging.instructions`).

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test covering each side |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (patient `true` at line 174; `finance` `false` at line 180) |
| `InstructionService.get` `if (isPatient())` true/false | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread` (true); existing staff `get` tests (false) |
| `InstructionService.get` `else if (!staffAssignedToUnit(...))` true/false | existing `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (stranger, true); staff `get` tests (false) |
| `requirePatientChartAccess` chart-not-linked `if` true/false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `requirePatientChartAccess` connection-inactive `if` true/false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `InstructionService.listForPatient` `if (isPatient())` true/false | `patientReadsOwnInstructionContentAndOwnThread` (true); `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff) |
| `listForPatient` patient branch `verifiedPatientCharts.contains(patient)` false/true | `patientCannotReadAnotherPatientsInstructionContent` (404); `patientReadsOwnInstructionContentAndOwnThread` (one revision) |
| `listForPatient` patient branch connection filter false/true | `patientReadRequiresAnActiveVerifiedConnection` (empty); `patientReadsOwnInstructionContentAndOwnThread` (one revision) |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all patient service tests + HTTP patient test (present set); pre-existing staff tests run the port with an empty set |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both outcomes
are exercised. JaCoCo reports 52/52 branches on `InstructionService` and 25/25 on
`InstructionActions`, confirming every branch above is covered.

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

Fast unit tests (worker gate; integrator runs DB/integration):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='Instruction*Test' test
  -> EXIT 0
  InstructionContractTest:  tests=13 failures=0 errors=0 skipped=0
  InstructionControllerTest: tests=3  failures=0 errors=0 skipped=0
  InstructionServiceTest:   tests=18 failures=0 errors=0 skipped=0
```

Coverage command and result — full suite against real PostgreSQL, 100% gate:
```
SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify
  -> EXIT 0
  Surefire aggregate: tests=2364 failures=0 errors=0 skipped=0 (210 suites)
```
JaCoCo counters read from `apps/api/target/site/jacoco/jacoco.xml` (tool
evidence): INSTRUCTION 90803/90803, BRANCH 6419/6419, LINE 15754/15754,
COMPLEXITY 7081/7081, METHOD 3820/3820, CLASS 785/785 — all 100.00%. Per touched
class (all counters 0 missed): `InstructionActions` (73/73 instructions, 25/25
branches, 11/11 lines, 3/3 methods), `InstructionService` (950/950, 52/52,
166/166, 32/32), `InstructionStore$InMemory` (571/571, 24/24, 95/95, 29/29),
`InstructionStore$InMemory$Connection` (12/12, 1/1 line, 1/1 method).
`InstructionStore` is an interface with no executable counters. No changed file
is below 100% on any counter.

Policy checks (tool evidence):
```
python3 -I scripts/check-names.py                        -> exit 0
python3 -I scripts/check-secrets.py                      -> exit 0
python3 -I scripts/check-docs.py                         -> exit 0
python3 -I scripts/check-migrations.py --require-baseline -> exit 0
```

Merge topology and repair:
```
git branch --show-current                          -> swarm/AUD-20261120-12-289
git diff --stat origin/main...HEAD                 -> shared .agent/pr-notes.md modified (merge blocker)
git checkout origin/main -- .agent/pr-notes.md
git diff --quiet origin/main -- .agent/pr-notes.md -> 0 (byte-identical after restore)
```

Known local environment limits (not caused by this change, not a product defect):
- `bash scripts/check.sh` could not be used as the sole reproduction here: it
  stops at `check-pack: opencode.json is stale` because `git ls-files -v
  opencode.json` shows `S` (skip-worktree). The worktree file is the CI profile
  copied by `activate_ci_config`; the committed HEAD file is the local profile
  (documented in MEMORY.md, OPS-CW-003-01). No config/agent/skill file was edited
  by this work.
- `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 271 tests ...
  FAILED (failures=9, errors=10)` in this worker image: `ModuleNotFoundError: No
  module named 'yaml'` (PyYAML absent, `pip3` unavailable) and `test_agent_run`
  cases that read "merged but checks failed" from a prior attempt's checkout
  state. None of those tests touches `messaging/instructions`. The Java half of
  the gate was run directly and passed (coverage command above). With
  `-Pno-security` OWASP Dependency-Check and SpotBugs are skipped; both remain
  integrator/Delivery gates. No threshold or exclusion was changed.
- No push, tag or `gh` call was made, as instructed; the integrator owns merge
  and publication.

## Decisions

- Wrong-chart / wrong-patient reads return **404 NOT_FOUND** to conceal whether
  another patient's chart exists (ARC-04), matching
  `InstructionFactService.requirePatientAccess`; a revoked connection on an
  otherwise linked chart returns 403.
- The `patientId` query parameter is retained for contract compatibility, but
  for a patient caller it is validated against charts linked to the authenticated
  subject (IAM-05); a patient with several verified charts may still choose.
- The staff branch is unchanged; patient handling is additive, so no existing
  staff authorization behavior is weakened.
- The repeated merge conflict was caused by the shared `.agent/pr-notes.md`
  being modified on the branch (AGENTS.md forbids workers from editing that
  path). Restoring it to `origin/main` removes the contested path from the
  branch; all evidence stays in this worker-owned file.

## Defects found

No new product defect. The audited defect (patient role denied on every
instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed by the retained change and re-verified here. The blocking
integration issue was the shared `.agent/pr-notes.md` divergence across fix
attempts and is repaired here. No BUGS.md change is in scope for this worker.
