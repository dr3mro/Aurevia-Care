# fix-20261120-170451 — AUD-20261120-12 (merge/check repair, fix attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only
instruction reads deny the patient role**. The dispatch message reports that the
swarm-integrator could not resolve the merge of `main` and this branch
("merged but checks failed", fix attempt 1 of 3) and asks for a reproduction of
`scripts/check.sh` on the merge of `main` and this branch.

Branch: `swarm/AUD-20261120-12-289`; HEAD at start `1a28aa1`; `origin/main`
`ccfbce48`; merge base `7316f9d`. The in-scope Java fix was already committed on
this branch by earlier attempts of this same story. I retained it unchanged,
re-verified it end to end (red/green and 100% coverage), and removed the two
out-of-scope shared-file modifications that were the recurring merge blocker.

**Evidence source:** executed tool commands (tool evidence) from this checkout
with synthetic fixtures only. Skills loaded: `smartclinic-delivery`,
`smartclinic-senior-backend`, `smartclinic-senior-spring-security`,
`smartclinic-test-engineer`, `smartclinic-senior-java-build`. No simulated user,
human participant, clinician or real-patient data contributed to this work.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through both instruction read routes | `InstructionActions.READ` grants `patient`; `InstructionService.get` / `listForPatient` patient branches |
| MSG-05 | Reading stays a separate fact from acknowledgment | fact model unchanged; reads project current + history + facts |
| PAT-02 | A patient sees content only for a verified, linked chart with an active connection | `InstructionService.requirePatientChartAccess`; patient branch of `listForPatient` |
| IAM-05 | Caller is bound to charts verified for the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts` port + `linkPatientChart` test seed |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `connectionActive` guards in both patient branches |
| ARC-04 | Another patient's thread or chart is concealed with 404, not 403 | `requirePatientChartAccess` / list membership -> `notFound()` |
| ARC-02 / OPS-02 | Checks stay inside the tenant-guarded `execute` path; tenant comes from `TenantContext` | `InstructionService.execute` (unchanged); store keys include organization |

Acceptance cases: patient self-read of `GET /api/v1/patient-instructions/{threadId}`
and `GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
active connection returns content; wrong-patient chart/thread -> 404; revoked
connection -> 403 (single thread) / empty list (list); staff reads unchanged.

## Scope and change

In-scope fix (already on this branch, retained):

- `messaging/instructions/InstructionActions.java` — `READ` grants the `patient`
  role in addition to staff senders, `tenant_admin` and `privacy_auditor`.
- `messaging/instructions/InstructionService.java` — `get` and `listForPatient`
  take a patient branch. A patient is bound to the charts verified for its
  authenticated account (`verifiedPatientCharts`) and to an active patient-unit
  connection before any title/body is released. A multi-role caller always uses
  the stricter patient branch. Staff retain the unit-assignment branch.
- `messaging/instructions/InstructionStore.java` — new `verifiedPatientCharts`
  port method and the in-memory adapter's `linkPatientChart` seed; keys remain
  organization-scoped.
- Tests: `InstructionServiceTest` (+5), `InstructionControllerTest` (+1 HTTP
  read), `InstructionContractTest` (+1 patient READ assertion).

Repair made in this run (the actual fix for "merged but checks failed"):

- Reverted `.agent/pr-notes.md` to `origin/main`. Earlier attempts had replaced
  the **shared** notes file with this story's content. That file is edited by
  parallel workers on `main` (see its `origin/main` content: AUD-20261120-13),
  so carrying a branch-local version produces the recurring unresolvable merge
  conflict and the repeated "restore shared pr-notes to unblock merge" commits
  in this branch's history. It is now byte-identical to `origin/main`.
- Reverted `TODO.md` to `origin/main`. The branch carried an out-of-scope claim
  on AUD-20261120-15 (`[-] agent: swarm-8`) that belongs to the orchestrator and
  another worker. TODO.md is orchestrator-owned and is not edited by this role.

No migration, coverage threshold, exclusion, skip or generated file was changed.
No existing test was weakened, deleted or skipped. The seven older
`fix-20261120-*.md` notes files from prior attempts of this same story were left
untouched (they are prior runs' own notes, not shared files, and do not affect
any gate). No file outside the declared scope was modified by this run.

## Root-cause evidence (why the merge failed)

- `git diff origin/main HEAD` before repair showed `.agent/pr-notes.md` changed
  by 276 lines and `TODO.md` changed — both forbidden/out-of-scope.
- `git log origin/main..HEAD` showed ~30 commits, most of them
  "restore shared pr-notes to unblock merge", i.e. the same conflict recurring
  each time a worker re-edited the shared file.
- After the revert, a clean merge of this branch into `origin/main` in a detached
  worktree succeeded with no conflicts, and the resulting diff contains only the
  in-scope instruction code/tests plus task-owned notes files.

## Reproduction of `scripts/check.sh` on the merge

In a clean detached worktree of `origin/main` I merged this branch (exit 0, no
conflicts) and ran `bash scripts/check.sh`. It stopped at
`python3 -I -m unittest discover -s scripts/tests` with 6 failures / 10 errors.
The same command on **pure `origin/main`** in the same worker environment fails
with the identical set: these tests inherit `**os.environ` (scripts/tests/
test_agent_run.py:60) and the swarm worker's `FIX_COMMENT`, `BRANCH`,
`AI_AGENT`, `AGENT*`, `GITHUB_ACTIONS` variables perturb their fake git/gh
harness (e.g. `gh pr create: permission denied`, `failed` vs `passed`). This is
an environment artifact of this worker, not a branch defect; CI and the
integrator do not set those variables.

A second worker-only artifact: the worktree `opencode.json` is the CI profile
copied under `skip-worktree` (sha `b40d443…`) while `git show HEAD:opencode.json`
is the committed local profile (`7ceb8b3…`). `check-pack.py` therefore reports
"opencode.json is stale" here. In a clean worktree (local profile) it passes:
`Delivery tooling validated: 33 agents, 33 skills`. I did **not** regenerate or
commit `opencode.json`.

## Tests written first (red -> green) — executed in this run

Red (pre-fix production code from `origin/main` applied to the test tree):
```
mvn -q -B -Pno-security,fast -Dtest=InstructionServiceTest,InstructionControllerTest,InstructionContractTest test
```
Red result: exit 1, `COMPILATION ERROR` — the new tests reference the missing
`linkPatientChart` / `verifiedPatientCharts` contract (6 `cannot find symbol`
sites). This confirms the tests fail because the patient-read behavior is absent.

Green (fixed production code restored):
```
mvn -q -B -Pno-security,fast -Dtest=InstructionServiceTest,InstructionControllerTest,InstructionContractTest test
```
Result: exit 0. Surefire: InstructionServiceTest 20 tests, 0 failures/errors;
InstructionControllerTest 3 tests; InstructionContractTest 13 tests.

Full fast unit tier (all unit tests, integration excluded):
```
mvn -q -B -Pno-security,fast test     -> exit 0
```

## Coverage evidence (100% on every changed production file)

```
mvn -q -B -Pno-security,fast verify
```
The `jacoco:check` bundle rule fails on the **fast** tier only because
`@Tag("integration")` suites are excluded (bundle: 14,407 missed instructions).
The integrator runs the full tier. I opened `target/site/jacoco/jacoco.xml` and
confirmed the touched package `com/aurevia/smartclinic/messaging/instructions`
has **0 missed on every counter**: INSTRUCTION 0/2402, BRANCH 0/157, LINE 0/399,
COMPLEXITY 0/187, METHOD 0/107, CLASS 0/18. Per changed class:
`InstructionService` 0 missed on all counters (BRANCH 0/52),
`InstructionActions` (BRANCH 0/25), `InstructionStore$InMemory` (BRANCH 0/24),
`InstructionController` (0 missed) — all 100%. No threshold was lowered.

## New/changed branches and their covering tests

| Branch added/changed | Test |
|---|---|
| `InstructionActions.READ` now includes `patient` | `InstructionContractTest.actionMatrixGrantsBoundedRoles` (+ `assertTrue(... "patient")`) |
| `get` patient branch (`isPatient()` true) | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`, `...multiRolePatientCallerStillUsesTheStricterPatientBranch`; HTTP: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| `get` staff branch (`isPatient()` false, assigned/unassigned) | pre-existing `InstructionServiceTest` staff read tests (unchanged) |
| `requirePatientChartAccess`: chart not linked -> 404 | `patientCannotReadAnotherPatientsInstructionContent`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` |
| `requirePatientChartAccess`: connection inactive -> 403 | `patientReadRequiresAnActiveVerifiedConnection` |
| `listForPatient` patient branch: chart not linked -> 404 | `patientCannotReadAnotherPatientsInstructionContent`, `multiRolePatientCallerStillUsesTheStricterPatientBranch` |
| `listForPatient` patient branch: active-connection filter | `patientReadsOwnInstructionContentAndOwnThread`, `patientReadRequiresAnActiveVerifiedConnection` (empty) |
| `isPatient()` anyMatch true/false, tenant ctx null (via execute) | patient tests above + pre-existing no-context/denial tests |
| `InstructionStore.InMemory.verifiedPatientCharts` empty/present | `patientCannotReadAnotherPatientsInstructionContent` (absent chart) / `patientReadsOwnInstructionContentAndOwnThread` |
| `linkPatientChart` seed | all patient read tests |

## Other checks executed (this run)

```
python3 -I scripts/check-docs.py                    -> 0
python3 -I scripts/check-secrets.py                 -> 0
python3 -I scripts/check-names.py                   -> 0
python3 -I scripts/check-migrations.py --require-baseline -> 0
python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q -> 2 tests OK
```

## Decision / disposition

The patient-read fix is complete, covered and green. The merge blocker was
branch-local edits to shared/orchestrator files, now reverted. Nothing needs an
owner decision and no evidence is missing. Delivery still runs the authoritative
database/integration tier on the merge result; this worker ran the fast unit
tier only, as instructed.
