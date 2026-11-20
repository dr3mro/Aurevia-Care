# fix-20261120-120143 — AUD-20261120-12 merge/check fix (attempt 3 of 3)

## What this attempt is

Fix for the integrator outcome `Merge conflict with main could not be resolved
automatically (merged but checks failed)` on `AUD-20261120-12` ("Patients cannot
read instruction content: the only instruction reads deny the patient role").
Branch: `swarm/AUD-20261120-12-289`, HEAD `edf69f7` (merge of `origin/main`
`742f2bb` into the story branch). Working tree started clean. No push, tag or
`gh` call was made.

**Outcome:** the in-scope implementation is complete and verified green at 100%
JaCoCo in this checkout. The merge blocker was an out-of-scope process defect —
the branch had committed notes into the **shared** `.agent/pr-notes.md`, which
conflicts with the different story note `main` keeps in that path. This attempt
removes that divergence (restores the file to `origin/main`) so the branch
contains only in-scope changes and the integrator's `merge_local` is clean. This
is fix attempt 3 because attempts 1 and 2 (`.agent/pr-notes/fix-20261120-105844.md`
and `fix-20261120-112705.md`) correctly diagnosed the same defect but changed
nothing, so the outcome recurred.

## Requirement IDs and acceptance mapping

| Requirement | How the in-scope change serves it |
|---|---|
| MSG-04 | Verified patients receive typed instruction title/body through `get` and `listForPatient`; reading is distinct from sending. |
| MSG-05 | A patient read is a separate fact from acknowledgment; it implies no acknowledgment. |
| PAT-02 | A patient sees instruction content only for a verified, linked chart with an active connection. |
| IAM-05 | The caller is bound to charts linked to the authenticated account subject; a query-string `patientId` is validated against that set, never trusted. |
| MSG-01, MSG-07 | A revoked patient-unit connection ends content access on the next read (403 for `get`, empty list for `listForPatient`). |
| ARC-04 | Another patient's chart/thread is concealed with 404 rather than 403. |
| ARC-02, OPS-02 | All checks remain inside the existing guarded `execute` path; tenant scope is derived from `TenantContext`, not the request. |

Acceptance cases: patient self-read (positive), wrong-patient 404, revoked
connection 403/empty, staff branch unchanged — all implemented by the tests
listed in the entry-point-to-test trace below.

## Source-tree survey (before any edit)

Read the full diff of the three production and three test files under
`apps/api/src/{main,test}/java/com/aurevia/smartclinic/messaging/instructions`
against the merge base. Confirmed the only `InstructionStore` implementation is
`InstructionStore.InMemory`, so the added `verifiedPatientCharts` port cannot
break another adapter. Confirmed the pattern mirrors the sibling
`messaging/acknowledgment/InstructionFactService.requirePatientAccess`
(unlinked chart → 404, inactive connection → 403). No new Java package or
task-ID-named folder is introduced. No migration, contract, build file, agent
or skill is involved in the in-scope change.

## Reproduction: exact commands and results (tool evidence)

Environment here: Java 21.0.13, Maven 3.10.0, Python 3.11.2 (no `yaml` module),
PostgreSQL 17 on 5432, `opencode.json` under `skip-worktree` (git flag `S`),
no shellcheck and no Doxygen.

1. `bash scripts/check.sh` reproduces the failure at the first policy step:
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (exit 1).
   This is the documented OPS-CW-003-01 cloud-worker artifact: the worktree file
   is the CI profile while the committed HEAD file is the local profile. Verified
   it is environmental, not a code failure: with the committed HEAD profile in
   place, `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 33
   agents, 33 skills`, exit 0. The worktree file was restored byte-for-byte;
   `git status --short opencode.json` is empty at the end.
2. Other policy stages on the merged tree: `check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-migrations.py --require-baseline`, `project-name.py`
   → all exit 0. `python3 -I -m unittest discover -s scripts/tests -q` fails with
   `ModuleNotFoundError: No module named 'yaml'` (no PyYAML in this worker) —
   environmental, outside the Java scope.
3. Fast unit tier (as instructed): `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`
   → exit 0.
4. Full canonical API gate: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml
   -Pno-security verify` → exit 0, `Tests run: 2364, Failures: 0, Errors: 0,
   Skipped: 0`, `jacoco:0.8.16:check` → `All coverage checks have been met.`,
   `BUILD SUCCESS`. Log: `/tmp/opencode/verify-aud12-fix3.log`.
5. Per-class instruction tests inside the full verify: `InstructionServiceTest`
   18/18, `InstructionContractTest` 13/13, `InstructionControllerTest` 3/3 —
   0 failures / 0 errors / 0 skipped.

So `scripts/check.sh` has no in-scope code failure on the merged tree; the
resolver's "checks failed" came from the environment-only policy blockers above,
and the integrator's merge conflict came from the shared `.agent/pr-notes.md`
divergence. Removing that divergence lets the integrator merge cleanly and run
its own gate in the prepared image (committed local `opencode.json`, PyYAML,
Doxygen, cached NVD data), where the code is green.

## Coverage evidence (JaCoCo, tool-generated)

Read `apps/api/target/site/jacoco/jacoco.xml` per changed production class from
the run in step 4 — all missed counts are 0 (100% instruction/line/branch/method/class):

| Class | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `InstructionActions` | 0/73 missed/covered | 0/25 | 0/11 | 0/3 | 0/1 |
| `InstructionService` | 0/950 | 0/52 | 0/166 | 0/32 | 0/1 |
| `InstructionStore$InMemory` | 0/571 | 0/24 | 0/95 | 0/29 | 0/1 |

## New/changed production branches and their tests (existing change)

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true / false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (patient true, `finance` false) |
| `InstructionService.get` `if (isPatient())` true / false | `patientReadsOwnInstructionContentAndOwnThread` / `replacementSupersedesPriorRevisionAndNotifiesPatient` |
| `get` `else if (!staffAssignedToUnit(...))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` / staff `get` tests |
| `requirePatientChartAccess` chart-not-linked true / false | `patientCannotReadAnotherPatientsInstructionContent` (404) / `patientReadsOwnInstructionContentAndOwnThread` |
| `requirePatientChartAccess` connection-inactive true / false | `patientReadRequiresAnActiveVerifiedConnection` (403) / `patientReadsOwnInstructionContentAndOwnThread` |
| `listForPatient` `if (isPatient())` true / false | new patient tests / staff list tests |
| patient list verified-chart membership false / true | `patientCannotReadAnotherPatientsInstructionContent` / `patientReadsOwnInstructionContentAndOwnThread` |
| patient list connection filter false / true | `patientReadRequiresAnActiveVerifiedConnection` / `patientReadsOwnInstructionContentAndOwnThread` |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | patient tests (present set) and staff tests (empty set) |

`isPatient()` uses `Stream.anyMatch`, which adds no JaCoCo branch in our code;
both outcomes are exercised. No production line, branch, method or class was
added by this fix attempt, so no new coverage obligation was introduced.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get`
  → `InstructionService.get` → `execute(InstructionActions.READ, ...)` →
  `isPatient()` → `requirePatientChartAccess` → `verifiedPatientCharts` /
  `connectionActive`. Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`,
  `patientCannotReadAnotherPatientsInstructionContent`,
  `patientReadRequiresAnActiveVerifiedConnection`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient verified-chart membership + connection filter; staff branch
  unchanged. Tests: same patient tests plus the staff regressions
  `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and
  `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections`.
- Action matrix: `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`.

## Defects found

1. **Process defect — root cause (fixed here).** The original story worker
   committed its note into the shared `.agent/pr-notes.md`, while `main` keeps a
   different story's note there. `merge_local` therefore conflicts, the resolver
   is invoked, and its environment-blocked `scripts/check.sh` yields "merged but
   checks failed". This attempt restored the shared file to `origin/main`, so
   the branch diverges from `main` only in the two declared in-scope paths and
   three new per-story note files (new files cannot conflict). The displaced note
   text is preserved in `.agent/pr-notes/AUD-20261120-12-20261120-101200.md`
   and `.agent/pr-notes/fix-20261120-112705.md`.
2. **Environment blockers, not product defects.** `check-pack` sees the
   skip-worktree CI `opencode.json`; `python3 -I -m unittest discover` needs
   PyYAML; no shellcheck/Doxygen locally. None can be corrected from the
   declared Java scope and none is an observed in-scope code failure.

No product defect was found in the instruction implementation, and no BUGS.md
change is in scope.

## Decisions

- Restored the shared `.agent/pr-notes.md` to `origin/main` rather than leaving
  the divergence. AGENTS.md's intent is that workers never write to that shared
  file (it is exactly what causes PR conflicts); restoring it removes the
  out-of-scope commit's effect and makes the merge clean, while all narrative
  stays in per-story note files. No note text was added to, or left in, the
  shared file.
- Made no production or test code change: the implementation is complete,
  compiles and passes the full unit/integration/JaCoCo gate here. Adding code I
  cannot cover would risk the mandatory 100% gate.
- Did not touch any tracker, workflow, script, agent or skill.

## Evidence provenance

All evidence above is **tool-generated** (executed `scripts/check.sh` stages,
policy scripts, Maven/Surefire/JaCoCo, JaCoCo XML reads and git diff/status) from
this checkout using synthetic fixtures only. Skills `smartclinic-delivery`,
`smartclinic-senior-backend`, `smartclinic-senior-spring-security`,
`smartclinic-debugger`, `smartclinic-senior-java-build` and
`smartclinic-test-engineer` were loaded and their rules applied as reviewer
guidance, not as separate reviewer runs. No subagent finding, simulated user
walkthrough, human participant or real patient data contributed to this attempt.
