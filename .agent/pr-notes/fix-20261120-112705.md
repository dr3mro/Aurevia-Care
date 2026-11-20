# fix-20261120-112705 — AUD-20261120-12 merge/check fix attempt

## What this attempt is

Fix attempt for `AUD-20261120-12` ("Patients cannot read instruction content:
the only instruction reads deny the patient role"). The integrator reported
`merged but checks failed` when merging the story branch into `main`; the task
asks me to reproduce with `scripts/check.sh` on the merge of `main` and this
branch and to fix the failing check **inside the declared scope only**:

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions`
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/instructions`

I am on branch `swarm/AUD-20261120-12-289` at merge commit `acdfcde` (merge of
`origin/main` `b495e96` into the story branch). `origin/main` equals the
merge base, so this checkout is exactly the combined state the integrator
tested. Working tree started clean. No push, tag or `gh` call was made.

**Outcome:** the in-scope implementation is already complete and, in this
worker, verifiably green at 100% JaCoCo. No in-scope production defect exists
to fix. The failure is (a) an out-of-scope process defect in the shared
`.agent/pr-notes.md` and (b) environment-only check blockers. See
"Defects found" and "Decision / escalation" below.

## Requirement IDs and acceptance mapping

| Requirement | How the in-scope change serves it |
|---|---|
| MSG-04 | Verified patients receive typed instruction title/body through `get` and `listForPatient`; reading is a distinct operation from sending. |
| MSG-05 | A patient read is a separate fact from acknowledgment; reading implies no acknowledgment. |
| PAT-02 | A patient sees instruction content only for a verified, linked chart with an active connection; withdrawn revisions are not released. |
| IAM-05 | The caller is bound to charts linked to the authenticated account subject; a query-string `patientId` is validated against that set, never trusted. |
| MSG-01, MSG-07 | A revoked patient-unit connection ends content access on the next read (403 for `get`, empty list for `listForPatient`). |
| ARC-04 | Another patient's chart/thread is concealed with 404 rather than 403. |
| ARC-02, OPS-02 | All checks remain inside the existing guarded `execute` path; tenant scope is derived from `TenantContext`, not the request. |

Acceptance cases implemented by the existing tests: patient self-read
(positive), wrong-patient denial (404), revoked connection (403/empty),
staff branch unchanged.

## Source-tree survey (before any edit)

Read in full: `InstructionActions.java`, `InstructionService.java`,
`InstructionStore.java`, `InstructionController.java`, `InstructionContract.java`,
`InstructionInputs.java` and the three test classes `InstructionContractTest`,
`InstructionServiceTest`, `InstructionControllerTest`. Grepped all
`InstructionStore` implementors/injectors: the only implementation is
`InstructionStore.InMemory`, so the added `verifiedPatientCharts` port cannot
break another adapter. Compared with the sibling
`messaging/acknowledgment/InstructionFactService.requirePatientAccess`
(lines 348-358): it uses the identical contract — unlinked chart → 404,
inactive connection → 403. No new Java package or task-ID-named folder is
introduced. No migration, contract, build file, agent or skill is involved.

## Reproduction: exact commands and results (tool evidence)

Environment here: Java 21, Maven 3.10, Python 3.11.2 (no `yaml` module),
PostgreSQL available, no shellcheck, no Doxygen, `opencode.json` under
`skip-worktree` (`git ls-files -v` flag `S`).

1. `bash scripts/check.sh` → **exit 1**, stopping at the first policy step:
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
   This is the documented OPS-CW-003-01 cloud-worker artifact: the worktree
   file is the CI profile while the committed HEAD file is the local profile.
   It is environmental, not a code failure, and is outside this scope.
2. Individual policy checks: `check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-migrations.py --require-baseline`,
   `project-name.py` → all **exit 0**. Only `check-pack.py` fails (same
   artifact). `python3 -I -m unittest discover -s scripts/tests -q` fails with
   `ModuleNotFoundError: No module named 'yaml'` (no PyYAML here).
3. Fast unit tier:
   `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **exit 0**.
4. Full canonical API gate with JaCoCo:
   `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
   → **exit 0**, `Tests run: 2353, Failures: 0, Errors: 0, Skipped: 0`,
   `jacoco:0.8.16:check` → `All coverage checks have been met.`,
   `BUILD SUCCESS`. Log: `/tmp/opencode/verify-aud12-fix.log`.
5. Direct static-analysis gate:
   `mvn -B -f apps/api/pom.xml -Dspotbugs.skip=false -Dowasp.skip=true -DskipTests spotbugs:check`
   → **exit 0**, `BugInstance size is 0`, `Error size is 0`, `BUILD SUCCESS`.
   Log: `/tmp/opencode/spotbugs-aud12.log`.
6. Full gate with the default `security` profile
   (`SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh`) could **not** be
   completed here: the run stalled for over 20 minutes with no new output after
   the test phase, consistent with OWASP Dependency-Check waiting on an
   unreachable NVD endpoint (this worker has no network to Maven Central/NVD;
   the prior attempt recorded the same blocker). I terminated the stalled run
   (exit 143). This is an environment blocker, not an observed failure. The
   integrator/Delivery runner has cached NVD data and runs this gate.
7. Per-story instruction classes in the full verify:
   `InstructionServiceTest` 18/18, `InstructionContractTest` 13/13,
   `InstructionControllerTest` 3/3 — 0 failures / 0 errors / 0 skipped.

## Coverage evidence

JaCoCo `apps/api/target/site/jacoco/jacoco.xml` read per changed class from the
run in step 4 (tool evidence): `InstructionActions`, `InstructionService` and
`InstructionStore$InMemory` each report zero missed instructions, lines,
branches, methods and classes (the original worker recorded 25/25, 52/52 and
24/24 branches respectively). The build reports `All coverage checks have been
met.`

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
both outcomes are exercised.

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

## Defects found

1. **Process defect, out of scope (root cause of the unresolvable merge).** The
   original story worker committed its note into the shared `.agent/pr-notes.md`
   (`1631e42`), while `main` carried a different story's note in that path.
   `git merge origin/main` therefore conflicts there; the earlier resolution
   preserved both sections, so the branch still diverges from `main` on that
   file and the integrator's merge is not clean. The harness explicitly expects
   per-story notes under `.agent/pr-notes/<task-id>.md`
   (`scripts/agent-run.sh:197-198`, `scripts/swarm.py:698`, `1066`). Fixing this
   requires restoring `main`'s version of the shared file on the branch — a
   file outside this task's declared scope and forbidden by AGENTS.md
   ("Notes go only to the file your prompt names … Never … commit the shared
   `.agent/pr-notes.md`"). I did not change it.
2. **Environment blockers, not product defects.** `check-pack` sees the
   skip-worktree CI `opencode.json`; `python3 -I -m unittest discover` needs
   PyYAML; no shellcheck/Doxygen locally; OWASP Dependency-Check stalls without
   network/cached NVD data. None can be corrected from the declared Java scope.

No new product defect was found in the instruction implementation. No BUGS.md
change is in scope.

## Decision / escalation

- No production or test code change was needed: the implementation is complete,
  compiles and passes the full unit/integration/JaCoCo gate in this worker.
  Adding code I cannot justify would risk the mandatory 100% gate.
- I did not touch the shared notes file, any tracker, workflow, script, agent
  or skill. All evidence is in this per-story notes file only.
- **Escalation:** the orchestrator/integrator must restore `main`'s version of
  `.agent/pr-notes.md` on this branch (dropping the branch's change to that
  file) so the merge is clean; per-story notes stay under `.agent/pr-notes/`.
  Re-run `scripts/check.sh` in the prepared image where PyYAML, the committed
  `opencode.json`, shellcheck, Doxygen and cached NVD data are available.

## Evidence provenance

All evidence above is **tool-generated** (executed `scripts/check.sh`,
individual policy scripts, Maven/Surefire/JaCoCo and file reads) from this
checkout using synthetic fixtures only. Skills `smartclinic-delivery`,
`smartclinic-senior-backend`, `smartclinic-senior-spring-security` and
`smartclinic-senior-java-build` were loaded and their rules applied; that is
reviewer guidance, not a separate reviewer run. No subagent finding, simulated
user walkthrough, human participant or real patient data contributed.
