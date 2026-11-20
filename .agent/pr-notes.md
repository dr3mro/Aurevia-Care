# fix-20261120-105844 — AUD-20261120-12 merge/check fix attempt 1

## What this attempt is

This is fix attempt 1 for `AUD-20261120-12` ("Patients cannot read instruction
content"). The original worker's implementation is already present on this
branch (`9c1162e`), plus an agent conflict resolution (`1631e42`) and the merge
of `origin/main` (`137e701`). The integrator could not merge the branch into
main cleanly and the worker-side `merge-main` check recorded
`merged but checks failed`. My job was to reproduce with `scripts/check.sh` on
this merge and fix the failing check **inside the declared scope only**:

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions`
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/instructions`

## Requirement IDs and acceptance mapping (unchanged from the story)

| Requirement | Served by the existing implementation |
|---|---|
| MSG-04, MSG-05 | Patients receive typed instruction title/body through `get` and `listForPatient`; reading is a distinct fact from acknowledgment. |
| PAT-02 | Patient sees instruction content only for a verified, linked chart with an active connection. |
| IAM-05 | Caller is bound to charts linked to the authenticated subject, not to a trusted query-string chart id. |
| MSG-01, MSG-07 | A revoked connection ends content access on the next read. |
| ARC-04 | Another patient's chart/thread is concealed with 404. |
| ARC-02, OPS-02 | Checks remain inside the guarded `execute` path; tenant scope comes from `TenantContext`. |

Acceptance cases (patient self-read, wrong-patient 404, revoked connection
403/empty list, staff branch unchanged) are implemented by the tests listed in
the entry-point-to-test trace below.

## Source-tree survey (done before any edit)

Read in full: `InstructionActions.java`, `InstructionService.java`,
`InstructionStore.java`, `InstructionController.java`, `InstructionContract.java`,
`InstructionInputs.java`, and the three test classes
(`InstructionContractTest`, `InstructionServiceTest`, `InstructionControllerTest`).
Grepped for all `InstructionStore` implementors and injectors: the only
implementation is `InstructionStore.InMemory`; no other production or test
class implements the interface, so adding the `verifiedPatientCharts` port
cannot break another implementation. Followed the sibling
`InstructionFactService.requirePatientAccess` pattern the original worker
mirrored. No new Java package or task-ID-named folder is introduced by the
existing change.

## Reproduction: exact commands and results (tool evidence)

All commands run from the repository root on merge commit `137e701`
(`swarm/AUD-20261120-12-289`). Environment: Java 21, Python 3.11.2, Maven 3,
no network access to Maven Central (Cloudflare HTTP 404 with a long
`retry-after`), no local PyYAML, no Doxygen, no shellcheck, Docker absent.

`scripts/check.sh` was decomposed into its individual stages so one
environment-blocked stage would not hide later results. The two documented
local artifacts were handled first: `opencode.json` was temporarily replaced
with the committed `HEAD` profile (the worktree copy is the CI profile under
`skip-worktree`, OPS-CW-003-01) and restored byte-for-byte afterwards; `git
status --short opencode.json` is empty at the end.

| Stage command | Exit | Result |
|---|---|---|
| `bash -n` over `scripts/*.sh infra/bootstrap/start.sh` | 0 | pass |
| `shellcheck scripts/*.sh infra/bootstrap/start.sh` | 127 | shellcheck not installed here (CI installs it) |
| `python3 -I scripts/project-name.py displayName` / `repositorySlug` | 0 | pass |
| `python3 -I scripts/check-pack.py` | 0 | "Delivery tooling validated: 33 agents, 33 skills" (with the HEAD profile in place) |
| `python3 -I scripts/check-docs.py` | 0 | pass, including the merged `.agent/pr-notes.md` and both per-story notes |
| `python3 -I scripts/check-secrets.py` | 0 | pass |
| `python3 -I scripts/check-names.py` | 0 | pass |
| `python3 -I scripts/check-migrations.py --require-baseline` | 0 | pass |
| `python3 -I -m unittest discover -s scripts/tests -q` | 1 | `ModuleNotFoundError: No module named 'yaml'` (no PyYAML in this worker) |
| `bash scripts/frontend-check.sh` | 0 | pass |
| `bash apps/api/check.sh -Pno-security` | 1 | surefire cannot resolve `surefire-junit-platform:3.5.6` from Central — **no test ran** |
| `bash apps/staff-web/check.sh` | 0 | pass |
| `bash apps/patient-mobile/check.sh` | 0 | pass |
| `bash db/check.sh` | 0 | pass |
| `bash scripts/docs.sh` | 1 | "Install Doxygen to build the developer reference" |

Targeted Java tests were attempted twice:
`mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`.
Both runs compiled all 196 test source files successfully (no Java compile
error), then failed before executing a single test with:

```
Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test:
org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 (absent):
Could not find artifact ... in central (https://repo.maven.apache.org/maven2)
```

A direct `mvn -B -f apps/api/pom.xml -Dspotbugs.skip=false -DskipTests
spotbugs:check` was run to cover the integrator-only static gate the original
worker skipped with `-Pno-security`: **exit 0, BugInstance size is 0, Error
size is 0**.

The relevant Python tooling modules that do not need PyYAML were run directly:
`test_java_conventions.py` 2/2, `test_check_names.py` 6/6,
`test_check_migrations.py` 35/35, `test_check_docs.py` 6/6,
`test_check_secrets.py` 5/5 — all OK.

## Root cause of the merge failure (found, but outside this worker's scope)

The conflict that forced `merge-main` into the agent resolution path is in the
**shared** `.agent/pr-notes.md`. The original worker overwrote that shared file
with its own story note (`1631e42`); main carried a different story's note
(`AUD-20261120-11`) in the same path. Both sides changed the same lines, so
`git merge origin/main` conflicts there. The resolution agent replaced main's
content with this story's note instead of preserving both, which is why the
branch still diverges from main on that file and why the integrator's merge is
not clean. This is a process defect (workers must write only
`.agent/pr-notes/<story-id>.md`), not a production-code defect.

Per the task rule "Change only files under the task's scope" and
"if a decision or evidence is missing, stop and say so", I did **not** edit
`.agent/pr-notes.md`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`,
`PROJECT.md`, `docs/swarm`, any workflow, script, agent or skill. The shared
notes file and the tracker handoff need the orchestrator/integrator.

## Coverage status

No production file was changed by this fix attempt, so no new production branch
was added. The original change's 100% JaCoCo evidence, recorded in
`.agent/pr-notes/AUD-20261120-12-20261120-101200.md` from
`SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`,
is: `InstructionActions` 25/25 branches, `InstructionService` 52/52 branches,
`InstructionStore$InMemory` 24/24 branches, zero missed lines/methods/classes;
the build reported "All coverage checks have been met."

**I could not re-confirm coverage myself in this worker**: `apps/api/check.sh`
never reached JaCoCo because surefire cannot resolve its JUnit Platform
provider without network access. I am therefore not claiming a locally verified
100% result; the coverage figure above is prior tool evidence from the original
worker, not a command I executed in this attempt.

## Entry-point-to-test trace (for the integrator)

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get`
  → `InstructionService.get` → `execute(InstructionActions.READ, ...)` →
  `isPatient()` → `requirePatientChartAccess` → `verifiedPatientCharts` /
  `connectionActive`. Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`,
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`,
  `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient verified-chart membership + connection filter. Tested by the same
  four tests; staff regression by `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts`
  and `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections`.
- Action matrix: `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`.

## New/changed production branches and their tests (from the original change)

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true / false | `actionMatrixSeparatesAdministrationClinicalAndRead` (patient true, `finance` false) |
| `InstructionService.get` `if (isPatient())` true / false | new patient `get` tests / `replacementSupersedesPriorRevisionAndNotifiesPatient` |
| `get` `else if (!staffAssignedToUnit(...))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` / staff `get` tests |
| `requirePatientChartAccess` chart-not-linked true / false | `patientCannotReadAnotherPatientsInstructionContent` / `patientReadsOwnInstructionContentAndOwnThread` |
| `requirePatientChartAccess` connection-inactive true / false | `patientReadRequiresAnActiveVerifiedConnection` / `patientReadsOwnInstructionContentAndOwnThread` |
| `listForPatient` `if (isPatient())` true / false | new patient tests / staff list tests |
| patient list verified-chart membership false / true | wrong-patient test / self-read test |
| patient list connection filter false / true | revoked-connection test / self-read test |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | all patient tests (present set) and staff tests (empty set) |

`isPatient()` uses `Stream.anyMatch`, which adds no JaCoCo branch in our code;
both outcomes are exercised.

## Defects found

1. **Process defect (out of scope)**: the original worker committed to the
   shared `.agent/pr-notes.md`, causing the unresolvable three-way merge on that
   file. Fix requires the orchestrator to restore main's version on the branch
   and keep each worker's note under `.agent/pr-notes/`.
2. **Environment blockers (not product defects)**: missing PyYAML and
   surefire-junit-platform provider in this worker, and unreachable Maven
   Central, prevent a local `scripts/check.sh` green run. No code change can
   correct these here.

No new product defect was found in the instruction implementation. No BUGS.md
change is in scope for this worker.

## Decisions

- Do not modify production or test code in this attempt: the implementation is
  complete, compiles (verified by the targeted Maven compile step) and has
  passing unit/integration/coverage evidence from the original worker, plus a
  clean SpotBugs run executed here. Adding code I cannot cover locally would
  risk the mandatory 100% gate.
- Do not touch the shared notes file or any tracker: they are owned by the
  orchestrator/integrator and outside the declared scope.
- Keep all evidence in this per-story notes file only.

## Evidence provenance

All evidence above is **tool-generated** (executed shell, Maven, SpotBugs,
policy scripts and Python unit modules) from this checkout using synthetic
fixtures only. Specialist skills `smartclinic-delivery`,
`smartclinic-senior-backend`, `smartclinic-senior-spring-security` and
`smartclinic-test-engineer` were loaded and their rules applied (reviewer
guidance, not a separate reviewer run). No subagent finding, simulated user
walkthrough, human participant or real patient data contributed to this
attempt.

## Why this is not finished — exact next steps for the orchestrator/integrator

1. Restore main's version of `.agent/pr-notes.md` on this branch (or drop the
   branch's change to that file) so the integrator's merge is clean. Keep the
   per-story notes under `.agent/pr-notes/`; this file is the PR description.
2. Re-run `scripts/check.sh` (or `scripts/worker-check.sh`) in the prepared
   agent image / Delivery runner where PyYAML, the surefire provider, Doxygen
   and cached NVD data are available. The Java source compiles cleanly here;
   the only unexecuted steps are environment-blocked, not observed failures.
3. If a Java test or coverage gate is then genuinely red, open a follow-up fix
   with the failing class and stack trace; the domain is self-contained in the
   two declared paths and has no other implementors to disturb.
