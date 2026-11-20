<!-- Merge resolution: this shared notes file previously held a single story note.
Both sides replaced the merge-base note; both sections are preserved below.
Canonical per-story copies: .agent/pr-notes/AUD-20261120-12-20261120-101200.md and
.agent/pr-notes/AUD-20261120-11-20261120-101204.md. -->

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

---

# AUD-20261120-11 — Reserved migration V59 collision (READY-35)

Status: **blocked — fix is outside this worker's scope. No production file changed.**

Worker: swarm-3 · Story mark: `[-]` in `TODO.md` (set by the orchestrator) · Scope: `db/migrations` only · Base: HEAD `92a5120`, clean tracked tree.

## Requirement IDs

`OPS-06` (migration change-safety gate: duplicate Flyway versions must fail), `ARC-01`, `ARC-02`, `OPS-02` (tenant-owned, forced-RLS durable state that READY-35..READY-42 add), `RX-02` (the already-applied V59 transition migration). Story ID `AUD-20261120-11` is a daily-audit tracker/tooling finding, not a new clinical requirement.

## Acceptance mapping

| # | Acceptance from the story | Result | Evidence |
|---|---|---|---|
| A1 | Confirm `V59__rx02_ambiguous_transition.sql` exists, is consumed, and that a second V59 fails the migration gate | **Met** (tool) | `ls db/migrations`, `check-migrations.py` baseline exit 0, reproduction exit 1 with exact message below |
| A2 | Identify the correct owner/location of the fix | **Met** (tool + rules) | Story text names "the tracker owner"; fix targets are `TODO.md` and `docs/decisions/0003`, both outside scope `db/migrations` and `TODO.md` is forbidden to workers |
| A3 | Apply the reassignment (READY-35..READY-42 → V60..V67, update `reserved:` fields, the "V59 to V66" sentence, the `V59__service_profiles.sql` path, and the 0003 mapping) | **Not met — out of scope** | `TODO.md` "Instructions for every agent" rules 3 and 8; `AGENTS.md` rule 8; see "Why this is not finished" |
| A4 | Do not damage an applied migration or invent an in-scope change just to produce a diff | **Met** | No file in `db/migrations` was edited, added, renamed or deleted |

## Finding (verified, not assumed)

- `db/migrations/V59__rx02_ambiguous_transition.sql` is tracked, was added by commit `1b1fe29` (merged PR #198, DLV-03), has header `Change: expand`, and is recorded as consumed in `.agent/pr-notes/DLV-03.md` line 24 and 42. It widens a single legal RX-02 transition with `CREATE OR REPLACE FUNCTION`; it is additive and has already been applied by Flyway in CI.
- `TODO.md` line 817 still says persistence tasks reserve "V59 to V66". READY-35 (line 821) reserves `V59` and its `scope` names `db/migrations/V59__service_profiles.sql` (line 824, 830). READY-36..READY-42 reserve V60..V66; `docs/decisions/0003` maps V59..V64 to the in-memory stores.
- `scripts/check-migrations.py` has explicit duplicate-version detection (lines 220-228): a repeated `V<n>` produces `duplicate migration version V<n>` and a non-zero exit. Flyway likewise cannot distinguish two migrations with the same version.

So the audit is correct: when READY-35 is dispatched as written, the first persistence story cannot go green.

## Reproduction (failing check first, synthetic temporary file, removed immediately)

Baseline — one V59 present:

```
$ python3 -I scripts/check-migrations.py --require-baseline
baseline_exit=0
```

Reproduction — a temporary `db/migrations/V59__service_profiles.sql` (the file READY-35 would create), created and removed in the same command with a shell `trap`, never committed:

```
$ printf '%s\n' '-- Change: expand' 'SELECT 1;' > db/migrations/V59__service_profiles.sql
$ python3 -I scripts/check-migrations.py --require-baseline
check-migrations: duplicate migration version V59
reproduction_exit=1
```

After cleanup, `git status --short` is empty. No tracked migration was changed.

## Exact commands run and results (all executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `ls -1 db/migrations/ \| sort -V` | V1..V59 present, exactly one `V59__rx02_ambiguous_transition.sql`; no `V59__service_profiles.sql` |
| `git status --short` / `git rev-parse --short HEAD` | clean tree; HEAD `92a5120` |
| `git ls-files db/migrations/ \| grep -c 'V59__'` | `1` |
| `git log -1 --format='%h %s' -- db/migrations/V59__rx02_ambiguous_transition.sql` | `1b1fe29 DLV-03: Repair integration tests: prescription transmission (#198)` |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (current tree has a unique V59) |
| same command with a temporary second V59 (see above) | exit 1, `check-migrations: duplicate migration version V59` |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1, `Ran 273 tests`, `FAILED (failures=8, errors=10)` — all pre-existing and unrelated (see Defects); this worker changed no Python file |
| `python3 -I scripts/gen-opencode.py --check` | exit 1 — known CI-profile/skip-worktree artifact (OPS-CW-003-01), not a HEAD defect (see Defects) |
| `python3 -I scripts/gen-opencode.py` into the worktree, compared with `git show HEAD:opencode.json` | generated file **identical** to HEAD; the pre-existing worktree file (CI profile, `S` skip-worktree flag) differs. Original bytes restored exactly. |
| `git diff --check` | exit 0 |

No database/integration test was run by this worker; the integrator owns those. Nothing in `db/migrations` was changed, so there is no new production line or branch to cover and no Java/SQL coverage command applies to this outcome.

## Defects and decisions

- **Root cause is tracker state, not migration content.** The DLV-03 V59 migration is correct, applied, immutable, additive and legitimately consumed by RX-02. The stale data is in `TODO.md` (lines 817, 821, 824, 826, 828, 830, 1719 and the reserved fields of READY-36..42) and in `docs/decisions/0003-persistence-of-in-memory-stores.md` (lines 36, 45, 63: the proposed V59 reservation and V59..V64 mapping). A full `grep -n V59` over Markdown confirmed every location; `DLV-03.md`, `DLV-04.md` and `AUD-20261120-07.md` references are historical records of the applied repair and must not be rewritten.
- **Rejected in-scope alternative:** renumbering DLV-03's applied V59 to a free version (for example V67) to free V59 for READY-35. That would rewrite an already-applied Flyway migration, break checksum/history validation, invalidate DLV-03's notes (`V59 is consumed`) and contradict the audit's own expected fix, which reassigns the *READY tasks*, not the applied repair. It also does not address the stale tracker text, and V67 is the version READY-42 already intends to use, so renaming into it would recreate the collision one story later. No such edit was made.
- **No fabricated diff:** producing a cosmetic DB change only to have something to commit would be wrong; the honest in-scope result is no production change plus this handoff note.
- Pre-existing, unrelated fast-test failures (not caused by, and not fixable inside, this story's scope): `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier` and `test_workflow_concurrency`; `gen-opencode.py --check` / `test_check_pack.*` failing because the worker worktree `opencode.json` is the CI profile under `skip-worktree` while HEAD holds the correct generated local profile (MEMORY.md: OPS-CW-003-01 closed as false positive); `AgentRunTests` / `test_swarm` failures depending on local git branch state. These belong to tooling/worker-environment owners, not `db/migrations`.

## Why this is not finished

The fix the story requires cannot be applied within the declared scope `db/migrations`:

1. It requires editing `TODO.md` (the "V59 to V66" sentence, READY-35's `scope`, `V59__service_profiles.sql` path and `reserved: V59`, and the `reserved:` fields of READY-36..READY-42). `AGENTS.md` rule 8 and the "Instructions for every agent" rules 3 and 8 at the top of `TODO.md` explicitly forbid a worker from editing `TODO.md`; the orchestrator/integrator own it.
2. It requires editing `docs/decisions/0003-persistence-of-in-memory-stores.md` (the proposed V59..V64 → V60..V65 mapping). That path is also outside `db/migrations`.
3. `TODO.md` rule 3 says: if the task needs a file outside its scope, stop, write the reason here, and finish without changing that file. `TODO.md` rule 9 says not to guess when the task and rules disagree.

I therefore stopped before making any production change.

## Exact next step (tracker owner / orchestrator)

Before dispatching READY-35, edit the two out-of-scope trackers:

1. `TODO.md` line 817: change "V59 to V66" to "V60 to V67".
2. `TODO.md` READY-35 (lines 821-830): `reserved: V59` → `V60`; `scope` path `db/migrations/V59__service_profiles.sql` → `db/migrations/V60__service_profiles.sql`; the sentence "This store owns migration V59 only" → V60; step 2 "Create `db/migrations/V59__service_profiles.sql`" → V60.
3. `TODO.md` READY-36..READY-42: shift each `reserved:` and migration `scope` path up by one — V60→V61, V61→V62, V62→V63, V63→V64, V64→V65, V65→V66, V66→V67 — and update the in-details "owns migration V<n> only" / "Create `db/migrations/V<n>__*.sql`" references to match.
4. `docs/decisions/0003-persistence-of-in-memory-stores.md`: update the "proposed V59" reservation and the V59..V64 mapping to V60..V65 (V59 stays with the applied RX-02 repair).
5. Re-run `python3 -I scripts/check-migrations.py --require-baseline` and `bash scripts/quick-check.sh` after the tracker edits, then dispatch READY-35. Leave the applied `V59__rx02_ambiguous_transition.sql` untouched.

## Entry-point-to-test trace

`db/migrations/V*.sql` → `scripts/check-migrations.py` (filename parse → version set → duplicate-version check at lines 220-228) → `check-migrations: duplicate migration version V59` + exit 1. The same duplicate is what Flyway would reject when two `V59__*.sql` files are present; the reproduction above exercises that gate with a synthetic temporary file. This task ends at the gate result because the corrective edits live in the trackers, not in `db/migrations`.

## Evidence provenance

- **Tool evidence (executed by this worker):** `ls`, `git status`, `git log`, `git ls-files`, `git show`, `cmp`, `grep`, `python3 -I scripts/check-migrations.py --require-baseline` (baseline and reproduction), `python3 -I -m unittest discover -s scripts/tests -q`, `python3 -I scripts/gen-opencode.py --check`, `git diff --check`, with results recorded above.
- **Reviewer evidence (simulated reviewer, read-only, no commands run by the reviewer):** the `smartclinic-senior-database` specialist independently confirmed both decisions. It added that an applied migration's identity (version + description + checksum in `flyway_schema_history`) is immutable; with default `validateOnMigrate` (test suites call `Flyway.configure().load().migrate()` with no override), a database that already applied V59 fails validation with "applied migration not resolved locally: 59" if the file is renamed. It also confirmed that `check-migrations.py:220-228` deliberately allows version gaps (so HEAD already passes) and that renaming V59 to V67 would consume READY-42's intended version and recreate the collision. It agreed the correct outcome is no `db/migrations` change plus this handoff, with tracker-state edits owned by the orchestrator/integrator. Its finding is incorporated in the Decisions and Next-step sections above; no reviewer command output is claimed.
- **Simulated-user / human / clinical evidence:** none. No patient data, credential, token or real domain name appears in this work; all fixtures and the temporary reproduction file are synthetic.
