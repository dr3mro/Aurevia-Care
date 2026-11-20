# fix-20261120-120958 — AUD-20261120-12 merge/check repair

## What this attempt is

Repair for the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):
`Merge conflict with main could not be resolved automatically (merged but checks
failed). Fix attempt 1 of 3. Reproduce with scripts/check.sh on the merge of main
and this branch.`

Branch: `swarm/AUD-20261120-12-289`. Start HEAD `9820a0e` (clean tree, equal to
`origin/swarm/AUD-20261120-12-289`, strict descendant of `origin/main` `742f2bb`).
No push, tag or `gh` call was made. Synthetic fixtures only.

**Outcome:** the in-scope implementation is complete and green. The merge/check
failure was an out-of-scope process defect: a late commit (`9820a0e`, "Agent
changes for AUD-20261120-12", 12:07:23Z — 16 s *after* the prior fix `c314ce8`
restored it) re-committed the branch's note into the **shared** `.agent/pr-notes.md`.
That file is owned by `main` (which keeps a different story's note there), so the
branch diverged from `main` in a forbidden file, the integrator's `merge_local`
conflicted, the resolver merged main in and `scripts/check.sh` failed on the
merged result. This attempt restores the shared file to `origin/main`, leaving
the branch with only in-scope Java changes and per-story note files (new files
cannot conflict). No production or test code was changed by this attempt.

## Requirement IDs and acceptance mapping

| Requirement | How the in-scope change serves it |
|---|---|
| MSG-04 | Verified patients receive typed instruction title/body via `get` and `listForPatient`; reading is distinct from sending. |
| MSG-05 | A patient read is a separate fact from acknowledgment; it implies no acknowledgment. |
| PAT-02 | A patient sees instruction content only for a verified, linked chart with an active connection. |
| IAM-05 | The caller is bound to charts linked to the authenticated account subject; a query-string `patientId` is validated against that set, never trusted. |
| MSG-01, MSG-07 | A revoked patient-unit connection ends content access on the next read (403 for `get`, empty list for `listForPatient`). |
| ARC-04 | Another patient's chart/thread is concealed with 404 rather than 403. |
| ARC-02, OPS-02 | All checks remain inside the guarded `execute` path; tenant scope is derived from `TenantContext`, not the request. |

Acceptance cases (patient self-read positive, wrong-patient 404, revoked
connection 403/empty, staff branch unchanged) are implemented by the tests in
the entry-point-to-test trace below. No acceptance criterion was weakened.

## Source-tree survey (before any edit)

Read the full `origin/main..HEAD` diff. Production changes are confined to three
files in `messaging/instructions` (`InstructionActions`, `InstructionService`,
`InstructionStore`) and three test files in the mirrored test package. Confirmed
`InstructionStore.InMemory` is the only implementation, so the added
`verifiedPatientCharts` port cannot break another adapter, and the pattern
mirrors the sibling `messaging/acknowledgment/InstructionFactService.requirePatientAccess`.
No new Java package, task-ID-named path, migration, contract or threshold change
is present. The sole out-of-scope change was the shared `.agent/pr-notes.md`.

## Reproduction: exact commands and results (tool evidence)

Environment: Java 21.0.13, Maven 3.10.0, PostgreSQL 17 accepting on 5432 with
`SMARTCLINIC_PG_ADMIN=postgres`, `opencode.json` under `skip-worktree` (git flag
`S`), no shellcheck.

1. `git merge-tree --write-tree --name-only HEAD origin/main` → exit 0, no
   conflict (HEAD already contains `origin/main`). The original conflict was
   against an earlier `main` state in which the shared note file differed.
2. `bash scripts/check.sh` (on the merged state = current HEAD) → exit 1,
   first failure `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
   This is the documented OPS-CW-003-01 cloud-worker artifact: the worktree file
   is the CI profile under `skip-worktree` while the committed `HEAD` file is the
   local profile. It is environmental, not caused by this branch: `git ls-files
   -v opencode.json` → `S`.
3. Policy scripts on the same tree — `check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-migrations.py --require-baseline` — all exit 0,
   including with the divergent shared file still in place. So the shared file
   did not fail a policy check here; it caused the *merge* conflict that sent the
   PR to the resolver, whose environment-blocked `scripts/check.sh` produced
   "merged but checks failed".
4. Fast unit tier: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` →
   exit 0. Instruction suites: `InstructionContractTest` 13/13,
   `InstructionServiceTest` 18/18, `InstructionControllerTest` 3/3, 0 failures /
   errors / skipped.
5. Full canonical gate with coverage: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f
   apps/api/pom.xml -Pno-security verify` → exit 0, `Tests run: 2364, Failures:
   0, Errors: 0, Skipped: 0`, `jacoco:0.8.16:check` → `All coverage checks have
   been met.`, `BUILD SUCCESS`. Log: `/tmp/opencode/verify-aud12-fix1.log`.
   (OWASP and SpotBugs are intentionally skipped via `-Pno-security`; they are
   the integrator's gate.)

## Coverage evidence (JaCoCo, tool-generated, fresh this run)

Read `apps/api/target/site/jacoco/jacoco.xml` per changed production class — all
missed counts are 0 (100% instruction/line/branch/method/class/COMPLEXITY):

| Class | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `InstructionActions` | 73/73 | 25/25 | 11/11 | 3/3 | 1/1 |
| `InstructionService` | 950/950 | 52/52 | 166/166 | 32/32 | 1/1 |
| `InstructionStore$InMemory` | 571/571 | 24/24 | 95/95 | 29/29 | 1/1 |

No production line, branch, method or class was added by this repair attempt, so
no new coverage obligation was introduced; the existing implementation is fully
covered by the tests listed below.

## New/changed production branches and their tests (unchanged from the story)

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
  → patient verified-chart membership + connection filter; staff branch unchanged.
  Tests: same patient tests plus staff regressions
  `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and
  `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections`.
- Action matrix: `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`.

## Defects found

1. **Process defect — root cause (fixed here).** The late commit `9820a0e`
   re-committed the branch note into the shared `.agent/pr-notes.md`, which
   `main` also owns with different content. `merge_local` conflicts, the resolver
   merges main in, and its environment-blocked `scripts/check.sh` yields "merged
   but checks failed". This attempt restored the shared file to `origin/main`, so
   the branch diverges only in the two declared in-scope paths and per-story note
   files (new files cannot conflict). The displaced note text remains in
   `.agent/pr-notes/fix-20261120-120143.md` and this file.
2. **Environment blockers, not product defects.** `check-pack` sees the
   skip-worktree CI `opencode.json`; no shellcheck locally. Neither can be fixed
   from the declared Java scope and neither is an observed in-scope code failure.

No product defect was found in the instruction implementation, and no BUGS.md
change is in scope.

## Decisions

- Restored the shared `.agent/pr-notes.md` to `origin/main` rather than leaving
  the divergence. AGENTS.md forbids committing that shared file; restoring it
  removes the out-of-scope change and makes `merge_local` clean. No note text was
  added to or left in the shared file; all narrative lives in per-story files.
- Made no production or test code change: the implementation already compiles
  and passes the full unit/integration/JaCoCo gate here. Adding code I cannot
  cover would risk the mandatory 100% gate.
- Did not touch any tracker, workflow, script, agent or skill.

## Evidence provenance

All evidence above is **tool-generated** (executed `scripts/check.sh`, policy
scripts, `git merge-tree`, Maven/Surefire/JaCoCo, JaCoCo XML reads and git
diff/status) from this checkout using synthetic fixtures only. Skills
`smartclinic-delivery`, `smartclinic-senior-backend`,
`smartclinic-senior-spring-security`, `smartclinic-software-engineer` and
`smartclinic-test-engineer` were loaded and applied as reviewer guidance, not as
separate reviewer runs. No subagent finding, simulated user walkthrough, human
participant or real patient data contributed to this repair.
