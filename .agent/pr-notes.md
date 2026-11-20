# fix-20261120-145144 — AUD-20261120-12 merge/check repair

## Task and provenance

Repair attempt 1 of 3 for the swarm integrator report: *"Merge conflict with main
could not be resolved automatically (merged but checks failed)"* on story
**AUD-20261120-12 — Patients cannot read instruction content: the only instruction
reads deny the patient role**.

The story's production fix was already present on the branch when this repair
started (commits through `21ad5c5`) and is retained unchanged. The defect that
blocked the merge was **not** in the Java change: it was in the shared note file
`.agent/pr-notes.md`, which seven earlier fix attempts on this branch had
repeatedly overwritten with their own note text. `origin/main` holds a different
story's text in that same shared path, so the integrator's merge of `main` and
this branch conflicted there. This repair restores the shared file to main's
version and keeps all story evidence in this worker-owned file only.

Evidence source: executed tool commands (tool evidence) from this checkout,
synthetic fixtures only. No reviewer subagent, simulated user, human participant
or real patient data contributed.

## Root cause and repair

| Observation | Evidence |
|---|---|
| `origin/main` is an ancestor of the branch HEAD, so the merge result is the branch tree itself (fast-forward); there is no Java-side conflict. | `git merge-base --is-ancestor origin/main HEAD` → YES; `git merge --no-commit --no-ff origin/main` → "Already up to date" in a scratch worktree. |
| The branch diverged from `main` only in the instructions domain and under `.agent/`. | `git diff --stat origin/main HEAD -- apps/api/src` → 6 files; `git diff --stat origin/main HEAD -- .agent/` → 17 files. |
| `.agent/pr-notes.md` on the branch held `fix-20261120-141438` text; on `main` it holds `AUD-20261120-13` text. | `git show origin/main:.agent/pr-notes.md | head -1` vs worktree file; `git diff` showed a 167+/110- rewrite of that shared file. |
| Sixteen note files under `.agent/pr-notes/` existed only on this branch (the original story note plus fifteen failed-attempt notes). | `git diff --name-only origin/main HEAD -- .agent/pr-notes/`; each checked with `git cat-file -e origin/main:<file>` → "BRANCH ONLY". |

Repair applied in this commit:

- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main` (no branch
  divergence on the shared file, so a future integrator merge cannot conflict
  there). Verified with `git diff --exit-code origin/main -- .agent/pr-notes.md`
  → identical.
- Removed the sixteen branch-only note files; their evidence is consolidated
  here. This keeps the change set to the six in-scope Java files plus this one
  note file (seven files, under the swarm's 15-file `needs-human` threshold).
- Left the `.opencode/`, `scripts/`, workflows, `opencode.json` and all shared
  trackers untouched.
- Retained the story's six Java file changes verbatim.

## Requirement IDs and acceptance mapping

| Requirement | How the retained change serves it |
|---|---|
| MSG-04 | A verified patient receives typed instruction title/body through `get` and `listForPatient`. |
| MSG-05 | Patient read is a distinct fact; reading does not imply acknowledgment. |
| PAT-02 | A patient sees only content for a verified, linked chart with an active connection. |
| IAM-05 | The caller is bound to charts linked to the authenticated account subject; several charts may be linked. |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read. |
| ARC-04 | Another patient's thread or chart is concealed with 404 (existence not revealed). |
| ARC-02 / OPS-02 | All checks remain inside the tenant-guarded `execute` path; tenant scope comes from `TenantContext`, never the request. |

Acceptance cases:

- Positive patient self-read of `GET /api/v1/patient-instructions/{threadId}` and
  `GET /api/v1/patient-instructions?patientId=...` for a linked chart with an
  active connection → content returned.
- Wrong-patient denial: a thread on an unlinked chart, or a `patientId` not in
  the caller's verified chart set → 404.
- Revoked connection → 403 for `get`, empty list for `listForPatient`, no content
  disclosed.
- Staff branch unchanged: unit assignment + active connection still required.

## Changes retained (inside declared scope)

- `InstructionActions.allows`: `READ` also permits `patient`.
- `InstructionStore`: new `verifiedPatientCharts(subject)` port with a
  tenant-keyed in-memory implementation and a `linkPatientChart` synthetic seed,
  mirroring the established `InstructionFactStore.verifiedPatientCharts` pattern
  used by `InstructionFactService.requirePatientAccess`.
- `InstructionService`:
  - `get` — patient callers bound by `requirePatientChartAccess`; staff keep the
    `staffAssignedToUnit` guard.
  - `listForPatient` — patient callers may only request a chart from their own
    verified link set, then see current revisions over an active connection;
    staff filter unchanged.
  - New private helpers `isPatient()` and `requirePatientChartAccess(...)`.
- Tests: action-matrix `patient` assertion, three service tests (positive
  self-read, wrong-patient, revoked connection) and one HTTP-level positive test.

No migration, contract, tracker, build file, agent or skill was changed. No new
Java package or task-ID-named folder was introduced. No coverage threshold,
exclusion or test skip was added; no existing test was weakened.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get` →
  `InstructionService.get` → `execute(READ, ...)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Tests: `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`,
  `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient` →
  patient branch verified-chart membership + connection filter. Wrong-patient:
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.

## New/changed branches and their tests (100% coverage)

| Production branch | Test covering each side |
|---|---|
| `InstructionActions.READ` `|| "patient".equals(role)` — true / false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it via `execute`. |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` | All three patient service tests + the controller patient test (present set); pre-existing staff tests run the port without links. |
| `InstructionService.get`: `if (isPatient())` true / false | New patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff). |
| `InstructionService.get`: `else if (!staffAssignedToUnit(...))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, stranger 403); staff `get` tests (false). |
| `requirePatientChartAccess`: chart-not-linked `if` true / false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `requirePatientChartAccess`: connection-inactive `if` true / false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `InstructionService.listForPatient`: `if (isPatient())` true / false | New patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff). |
| `listForPatient` patient branch: `verifiedPatientCharts.contains(patient)` false / true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true). |
| `listForPatient` patient branch: connection filter false / true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision). |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both outcomes
are exercised by the tests listed above.

## Exact commands run and results (tool evidence)

Reproduction of the reported failure, from this checkout:

- `git merge --no-commit --no-ff origin/main` in a scratch worktree → "Already up
  to date"; `git merge-base --is-ancestor origin/main HEAD` → YES. The merge
  result equals this branch tree; the conflict is the shared note file, not Java.
- `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed as
  the documented OPS-CW-003-01 local artifact: `git ls-files -v opencode.json` →
  flag `S` (skip-worktree), worktree file equals `.github/opencode.ci.json`, and
  differs from `git show HEAD:opencode.json` (the committed local profile). No
  config/agent/skill file was changed by this story. With the committed profile
  in place, `python3 -I scripts/check-pack.py` → exit 0
  ("Delivery tooling validated: 33 agents, 33 skills").
- A second `bash scripts/check.sh` with the committed `opencode.json` in place
  reached the Python tooling suite and failed with 6 failures / 10 errors across
  271 tests, all in `test_agent_run.py` / `test_swarm.py` (for example
  `ModuleNotFoundError: No module named 'yaml'`, and git/gh-sandbox assertions).
  These are pre-existing environmental failures in the Python 3.11 sandbox and
  are unrelated to the Java instructions domain; the integrator image has
  PyYAML and a real git/gh. Not modified or worked around here.

Verification of the retained change:

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test` → exit 0;
  Surefire: `InstructionContractTest` 13/13, `InstructionServiceTest` 18/18,
  `InstructionControllerTest` 3/3; 0 failures / 0 errors / 0 skipped.
- `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`,
  `python3 -I scripts/check-names.py` → all exit 0 (run before the note-file
  edits; the new note file contains no PHI-shaped or credential strings and no
  broken Markdown links).
- Coverage command and result: see the Coverage section below, filled from the
  executed JaCoCo gate on the final tree.

## Coverage (100% gate)

Coverage command (full canonical API gate, PostgreSQL available, OWASP/SpotBugs
left to the integrator):

```
SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify
```

Result (exit 0):
`Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`,
`jacoco:0.8.16:check → All coverage checks have been met.`, `BUILD SUCCESS`,
`Total time: 01:14 min`. Full log: `/tmp/opencode/aud12-verify.log`.

JaCoCo `apps/api/target/site/jacoco/jacoco.xml`, read per touched class (tool
evidence) — every counter has 0 missed:

| Class | Instruction | Line | Branch | Method | Class |
|---|---|---|---|---|---|
| `InstructionActions` | 0/73 | 0/11 | 0/25 | 0/3 | 0/1 |
| `InstructionService` | 0/950 | 0/166 | 0/52 | 0/32 | 0/1 |
| `InstructionStore$InMemory` | 0/571 | 0/95 | 0/24 | 0/29 | 0/1 |
| `InstructionStore$InMemory$Connection` | 0/12 | 0/1 | n/a | 0/1 | 0/1 |

`InstructionStore` is an interface and emits no executable counters. No file I
touched is below 100% on any counter.

## Decisions

- Wrong-chart and wrong-patient reads return **404 (NOT_FOUND)** rather than 403
  to conceal whether another patient's chart exists (ARC-04), matching
  `InstructionFactService.requirePatientAccess`. A revoked connection on an
  otherwise linked chart returns 403.
- The `patientId` query parameter is kept for contract compatibility, but for a
  patient caller it is validated against charts linked to the authenticated
  subject (IAM-05) instead of being trusted; a patient with several verified
  charts may still choose among them.
- The staff branch is byte-for-byte unchanged; patient handling is additive, so
  no existing staff authorization behavior is weakened.
- The verified-chart lookup lives in `InstructionStore`, keeping the instructions
  package self-contained and inside the declared scope.
- The merge repair restores the shared `.agent/pr-notes.md` to main and removes
  the failed-attempt note files rather than editing the shared file again; all
  evidence for this run is in this worker-owned note file.

## Defects found

No new product defect was introduced. The audited defect (patient role denied on
every instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed by the retained change. The blocking integration defect
was the shared-file divergence described above and is repaired here. No BUGS.md
change is in scope for this worker.
