# fix-20261120-141438 — AUD-20261120-12 merge/check repair

## Task and provenance

Repair for the integrator outcome on `AUD-20261120-12` ("Patients cannot read
instruction content: the only instruction reads deny the patient role"):
`Merge conflict with main could not be resolved automatically (merged but checks
failed)`, fix attempt 1 of 3. Branch `swarm/AUD-20261120-12-289`, working tree
clean at start. No push, tag or `gh` call was made. Skills loaded:
`smartclinic-delivery`, `smartclinic-senior-backend`,
`smartclinic-senior-spring-security`, `smartclinic-test-engineer`,
`smartclinic-senior-java-build`, `smartclinic-debugger` (as reviewer guidance,
not separate reviewer runs).

Scope: `apps/api/src/{main,test}/java/com/aurevia/smartclinic/messaging/instructions`.

## Requirement IDs and acceptance mapping (existing change)

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04, MSG-05 | A verified patient receives typed instruction title/body via `get` and `listForPatient`; reading is distinct from sending and implies no acknowledgment | patient branch of `InstructionService.get` / `listForPatient` |
| PAT-02 | Patient sees content only for a verified, linked chart with an active connection | `requirePatientChartAccess`, verified-chart membership + connection filter |
| IAM-05 | Caller bound to charts linked to the authenticated subject; query-string `patientId` validated, never trusted | `InstructionStore.verifiedPatientCharts(subject)` |
| MSG-01, MSG-07 | Revoked connection ends content access on next read (403 for `get`, empty list for list) | `store.connectionActive(...)` on every patient read |
| ARC-04 | Another patient's chart/thread concealed with 404 | `notFound()` |
| ARC-02, OPS-02 | Checks stay inside guarded `execute`; tenant scope from `TenantContext` | unchanged `execute` + tenant-keyed `InMemory` store |

Acceptance cases (patient self-read, wrong-patient 404, revoked connection
403/empty, staff branch unchanged) are implemented and tested.

## Root cause of the merge/check failure (defect found — process, not code)

The branch's **shared** `.agent/pr-notes.md` held the AUD-20261120-12 story note,
while `origin/main` (`742f2bb`) holds AUD-20261120-13's note in that same tracked
path. Both sides changed the same lines, so the integrator's three-way merge
conflicts there; the automatic resolver then re-writes the branch note into the
shared file, and its `scripts/check.sh` on the merged result reports
`merged but checks failed`. `git log --oneline --stat -- .agent/pr-notes.md` on
this branch shows a repeating ping-pong: each "Agent changes for
AUD-20261120-12" commit re-pollutes the shared file and each `fix-...: restore
shared pr-notes` commit reverts it. Other stories (AUD-13, AUD-11, READY-49,
READY-15, READY-14, READY-10) have likewise modified that shared file on main,
so it is a systemic worker-note placement defect, not a defect in the Java code.

AGENTS.md and `scripts/agent-run.sh:28-42` require notes only in a per-story file
under `.agent/pr-notes/`; the shared `.agent/pr-notes.md` must never be written
by a worker. Story narrative already survives in
`.agent/pr-notes/AUD-20261120-12-20261120-101200.md`.

**Fix applied:** restored `.agent/pr-notes.md` byte-for-byte to `origin/main`
(`git checkout origin/main -- .agent/pr-notes.md`). The shared file no longer
diverges, so `merge-tree` of `origin/main` and HEAD is clean (rc 0) and the
integrator can merge without invoking the re-polluting resolver. This run's
evidence is recorded only in this per-story file. No production/test code,
tracker, workflow, script, agent or skill was otherwise changed.

## Source-tree survey (before any edit)

Read in full: `InstructionActions.java`, `InstructionService.java`,
`InstructionStore.java`, `InstructionContract.java`, `InstructionInputs.java`,
`InstructionController.java` and the three test classes. Confirmed the only
`InstructionStore` implementation is `InstructionStore.InMemory`, so the added
`verifiedPatientCharts` port cannot break another adapter. The pattern mirrors
the sibling `messaging/acknowledgment/InstructionFactService.requirePatientAccess`
(unlinked chart → 404, inactive connection → 403). No new Java package or
task-ID-named folder is introduced by the existing change. No migration,
contract, build file, agent or skill is involved.

## Reproduction and verification — exact commands and results (tool evidence)

Environment: Java 21.0.13, Maven 3.10.0, Python 3.11.2 (no `yaml` module),
PostgreSQL 17 on 5432, `opencode.json` under `skip-worktree` (git flag `S`).

- `bash scripts/check.sh` → exit 1 at first policy step:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed
  environmental (OPS-CW-003-01): `git ls-files -v opencode.json` = `S`; verified
  with `gen-opencode.render()` that HEAD's committed `opencode.json ==
  render(local)` (True), HEAD's `.github/opencode.ci.json == render(ci)` (True)
  and the worktree `opencode.json == render(ci)` (True). The committed profile
  the integrator measures is correct; no config change was made.
- Individual policy stages on this tree: `check-docs.py` (0), `check-secrets.py`
  (0), `check-names.py` (0), `check-migrations.py --require-baseline` (0),
  `project-name.py displayName/repositorySlug` (0).
- `python3 -I -m unittest discover -s scripts/tests -q` → fails with
  `ModuleNotFoundError: No module named 'yaml'` (no PyYAML in this worker);
  environmental, outside the Java instructions scope.
- Fast unit tests (task-required): `mvn -q -B -f apps/api/pom.xml
  -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  → exit 0. Surefire: 13 + 18 + 3 tests, 0 failures / 0 errors / 0 skipped.
- Full canonical API gate: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f
  apps/api/pom.xml -Pno-security verify` → exit 0;
  `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check → All coverage checks have been met.`; BUILD SUCCESS.
  Log: `/tmp/opencode/verify-aud12-fix4.log`.
- `(cd db && bash check.sh)` → exit 0: "migrations, forced RLS, role separation,
  isolation, audit, append-only staff history and versioned config verified".

## 100% coverage (JaCoCo, tool-generated, read from apps/api/target/site/jacoco/jacoco.xml)

| Class | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `InstructionActions` | 73/73 | 25/25 | 11/11 | 3/3 | 1/1 |
| `InstructionService` | 950/950 | 52/52 | 166/166 | 32/32 | 1/1 |
| `InstructionStore$InMemory` | 571/571 | 24/24 | 95/95 | 29/29 | 1/1 |

All missed counts are 0 on every changed production class. This fix attempt
added no new production line, branch, method or class, so it introduces no new
coverage obligation; the 100% figures above are from a coverage command I ran
in this attempt, not carried from a prior note.

## New/changed production branches and their tests (existing implementation)

| Production branch | Test covering both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true/false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (patient true, `finance` false) |
| `InstructionService.get` `if (isPatient())` true/false | `patientReadsOwnInstructionContentAndOwnThread` / `replacementSupersedesPriorRevisionAndNotifiesPatient` |
| `get` `else if (!staffAssignedToUnit(...))` true/false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` / staff `get` tests |
| `requirePatientChartAccess` chart-not-linked true/false | `patientCannotReadAnotherPatientsInstructionContent` (404) / `patientReadsOwnInstructionContentAndOwnThread` |
| `requirePatientChartAccess` connection-inactive true/false | `patientReadRequiresAnActiveVerifiedConnection` (403) / self-read test |
| `listForPatient` `if (isPatient())` true/false | new patient tests / staff list tests |
| patient list verified-chart membership false/true | wrong-patient test / self-read test |
| patient list connection filter false/true | revoked-connection test / self-read test |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | patient tests (present set) and staff tests (empty set) |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised. No threshold, exclusion or skip was changed and no
existing test was weakened.

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
  unchanged. Tests: the same patient tests plus the staff regressions
  `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and
  `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections`.
- Action matrix: `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead`.

## Defects found and decisions

1. Process defect (corrected here): the shared `.agent/pr-notes.md` divergence
   caused the merge conflict and the resolver's check failure. Restored it to
   `origin/main` so the branch diverges only in the two declared in-scope paths
   and per-story note files. This is the same corrective action earlier fix
   attempts converged on; it is repeated here because the resolver re-polluted
   the file after each prior restore.
2. Environmental blockers, not code failures: check-pack's skip-worktree CI
   profile and missing PyYAML locally. Neither can be fixed from the Java
   scope, and neither is present in the integrator's prepared image.
3. No new product defect was found in the instruction implementation; no
   BUGS.md change is in scope.

- Restored the shared notes file rather than leaving the divergence; no note
  text was added to or left in it and no narrative was lost.
- Made no production/test code change: the implementation compiles and passes
  the full unit/integration/JaCoCo gate here (verified above). Adding code I
  cannot newly cover would risk the mandatory 100% gate.
- Did not touch any tracker, workflow, script, agent or skill.

## Evidence provenance

All evidence above is tool-generated (executed git, Maven/Surefire/JaCoCo,
db/check.sh, policy scripts and per-class JaCoCo XML reads) from this checkout
using synthetic fixtures only. No subagent finding, simulated user walkthrough,
human participant or real patient data contributed to this attempt.
