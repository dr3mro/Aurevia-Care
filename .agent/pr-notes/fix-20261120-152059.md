# fix-20261120-152059 — AUD-20261120-12 merge/check repair (fix attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only
instruction reads deny the patient role**. The swarm-integrator reported:
"Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of
main and this branch."

Branch: `swarm/AUD-20261120-12-289`. `origin/main` (`742f2bb`) is an ancestor of
the branch HEAD; the Java tree already contains the in-scope fix when this repair
started. I retained the fix verbatim and re-verified it; no production behavior
was changed in this repair.

**Root cause of the merge conflict:** an automated "Agent changes for
AUD-20261120-12" commit (`5bc59f0`) had again overwritten the shared note file
`.agent/pr-notes.md` with worker text. AGENTS.md forbids a worker from editing
that shared path; notes belong only in this worker-owned file. Because other
story pull requests also touch the shared path, the integrator's sequential
merge could not resolve it and re-dispatched this repair. **Fix:** restore
`.agent/pr-notes.md` to the exact content of `origin/main` and keep every note
here, so the branch no longer changes the contested path at all.

**Evidence source:** executed tool commands (tool evidence) from this checkout
with synthetic fixtures only. Skills loaded: smartclinic-delivery,
smartclinic-senior-backend, smartclinic-senior-spring-security,
smartclinic-senior-java-build, smartclinic-test-engineer and smartclinic-debugger
(skill guidance only, no specialist subagent was delegated). No simulated user,
human participant, clinician or real patient data contributed.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through both instruction read routes | `InstructionService.get` / `listForPatient`, `InstructionActions.READ` grants `patient` |
| MSG-05 | Reading stays a separate fact and does not imply acknowledgment | fact model unchanged; reads project current + history + facts |
| PAT-02 | A patient sees content only for a verified, linked chart with an active connection | `requirePatientChartAccess`, patient branch of `listForPatient` |
| IAM-05 | Caller is bound to charts verified for the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts`, `linkPatientChart` seed |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `connectionActive` guards in both patient branches |
| ARC-04 | Another patient's thread or chart is concealed with 404, not 403 | `requirePatientChartAccess` / list membership -> `notFound()` |
| ARC-02 / OPS-02 | Checks stay inside the tenant-guarded `execute` path; tenant comes from `TenantContext` | `InstructionService.execute` (unchanged) |

Acceptance cases: patient self-read of `GET /api/v1/patient-instructions/{threadId}`
and `GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
active connection returns content; wrong-patient chart/thread -> 404; revoked
connection -> 403 / empty list; staff reads unchanged.

## Scope and change (retained from the story branch, not authored this run)

Files changed under the declared scope
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

No migration, contract, dependency, coverage threshold or exclusion change. No
test was weakened, skipped or deleted. No task/story-ID package or class name was
introduced.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test covering each side |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it through `execute` |
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

Merge topology and repair:
```
git branch --show-current                              -> swarm/AUD-20261120-12-289
git merge-base --is-ancestor origin/main HEAD          -> 0 (main already merged)
git diff --stat origin/main...HEAD                     -> .agent/pr-notes.md modified (merge blocker)
git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md
git diff --quiet origin/main -- .agent/pr-notes.md     -> 0 (byte-identical after restore)
```

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
  Surefire total: tests=2364 failures=0 errors=0 skipped=0
  jacoco:0.8.16:check -> met (no build failure)
```
JaCoCo counters read from `apps/api/target/site/jacoco/jacoco.xml` (tool
evidence): INSTRUCTION 90803/90803, BRANCH 6419/6419, LINE 15754/15754,
COMPLEXITY 7081/7081, METHOD 3820/3820, CLASS 785/785 — all 100.00%.
Per touched class (all counters 0 missed): `InstructionActions` (73/73
instructions, 25/25 branches, 11/11 lines, 3/3 methods), `InstructionService`
(950/950, 52/52, 166/166, 32/32), `InstructionStore$InMemory` (571/571, 24/24,
95/95, 29/29), `InstructionStore$InMemory$Connection` (12/12, n/a, 1/1, 1/1).
`InstructionStore` is an interface with no executable counters. No changed file
is below 100% on any counter.

Policy checks after restoring the shared file (all tool evidence):
```
python3 -I scripts/check-pack.py                        -> exit 0 (33 agents, 33 skills)
python3 -I scripts/check-docs.py                        -> exit 0
python3 -I scripts/check-secrets.py                     -> exit 0
python3 -I scripts/check-names.py                       -> exit 0
python3 -I scripts/check-migrations.py --require-baseline -> exit 0
```

## Known local environment limits (not caused by this change)

- `bash scripts/check.sh` (the story's requested reproduction) first failed at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  `git ls-files -v opencode.json` shows `S` (skip-worktree): the worktree file
  is the CI profile copied by `activate_ci_config`, the committed file is the
  local profile. Running `python3 -I scripts/gen-opencode.py` refreshed the
  worktree copy and `python3 -I scripts/check-pack.py` then passed. No config,
  agent or skill file was changed by this work.
- Final `bash scripts/check.sh` -> `EXIT=1`: it stops at line 21 (the Python
  tooling tests) with `Ran 271 tests ... FAILED (failures=6, errors=10)` and
  never reaches the Java module. Same command
  `python3 -I -m unittest discover -s scripts/tests -q`: `ModuleNotFoundError:
  No module named 'yaml'` breaks `test_fast_tier`, `test_workflow_concurrency`
  and several `test_swarm` cases (10 errors), plus 6 `test_agent_run` failures
  caused by this checkout's git/token state (for example
  `test_resolve_merges_main_into_a_conflicted_pr_branch` reads "merged but
  checks failed" from a prior attempt's environment). None of these tests
  touches `messaging/instructions`. PyYAML is not installed and `pip3` is
  unavailable in this worker image; the integrator's full gate environment
  supplies it. Recorded as an environment limitation, not a product defect.
  The Java half of the same gate was run directly and passed:
  `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security
  verify` -> EXIT 0 with JaCoCo 100% (see above).
- With `-Pno-security`, OWASP Dependency-Check and SpotBugs are skipped; both
  remain integrator/Delivery gates. No threshold or exclusion was changed.

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
- This repair restores the shared note file to main and keeps all evidence in
  this worker-owned file instead of editing the shared path again. That removes
  the repeated merge conflict without touching production code.
- No push, tag or `gh` call was made, as instructed; the integrator owns merge
  and publication.

## Defects found

No new product defect. The audited defect (patient role denied on every
instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed by the retained change and re-verified here. The blocking
integration defect was the shared `.agent/pr-notes.md` divergence across fix
attempts and is repaired here. No BUGS.md change is in scope for this worker.
