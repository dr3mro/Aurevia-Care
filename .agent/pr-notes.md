# AUD-20261120-12 — Patients cannot read instruction content (fix attempt, branch `swarm/AUD-20261120-12-289`)

## Task, scope and provenance

Daily-audit story: `InstructionActions.READ` granted only staff sender roles,
`tenant_admin` and `privacy_auditor`, so a verified patient got 403 on both
patient instruction-content routes. The same `listForPatient` also trusted a
caller-supplied `patientId` query parameter. This re-run is the integrator's
"merge conflict with main could not be resolved automatically (merged but checks
failed), fix attempt 1 of 3" request.

Scope kept to the two declared paths under `messaging/instructions` (main + test).
No migration version used, no new Java package, no task/story-ID name, no
coverage threshold/exclusion/skip changed, no test weakened.

**Root cause of the merge conflict (the actual thing this fix corrects).** Prior
workers on this branch repeatedly wrote their notes to the SHARED
`.agent/pr-notes.md` instead of their own per-story file. That artifact path is
explicitly forbidden by AGENTS.md ("Never create, edit or commit the shared
`.agent/pr-notes.md`") and by `scripts/agent-run.sh:197-198`, which copies a
per-story note into an ignored artifact. The branch therefore diverged on a
file that other stories also modify on `main`, causing the integrator's
conflict. This run restores `.agent/pr-notes.md` byte-for-byte to
`origin/main` and records its evidence only in this file. The Java fix and its
tests from the earlier attempt were verified again from scratch and kept.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A patient receives typed instruction title/body through a verified connection | `get` and `listForPatient` patient branch |
| MSG-05 | Reading is a separate fact from acknowledgment; no ack is implied | patient read returns a `View`; no ack is written |
| PAT-02 | A patient sees only content for a verified, linked, active chart | `requirePatientChartAccess` + verified-chart membership filter |
| IAM-05 | Caller is bound to charts linked to the authenticated account; several charts may be linked | `InstructionStore.verifiedPatientCharts(subject)` |
| MSG-01 / MSG-07 | A revoked connection ends future content access on the next read | `store.connectionActive(...)` on every patient read |
| ARC-04 | Another patient's chart is concealed (404), not 403 | `notFound()` on unlinked chart / chart list |
| ARC-02 / OPS-02 | Checks stay inside the guarded `execute` path; tenant scope from `TenantContext`, not the request | unchanged `execute` + tenant-keyed store |

Acceptance outcomes:
- Positive: patient linked to the chart with an active connection reads own
  title/body via `GET /api/v1/patient-instructions/{threadId}` and
  `GET /api/v1/patient-instructions?patientId=...`.
- Negative wrong patient: a thread on an unlinked chart, or a `patientId` not in
  the caller's verified links, returns 404.
- Negative revoked connection: 403 for `get`, empty list for `listForPatient`,
  no content disclosed.
- Staff branch unchanged; all pre-existing staff tests still pass.

## Source survey before changing code

Read the delivery skill, TODO.md "Instructions for every agent", AGENTS.md,
CODING_STYLE.md and the already-in-context spec/decisions. Surveyed the whole
`messaging/instructions` package (actions, service, store, controller, contract,
inputs) and the sibling matrices `ConversationActions`, `ServiceProfileActions`,
`MessagingActions` and `InstructionFactActions`, plus
`InstructionFactService.requirePatientAccess` for the established verified-chart
pattern. Reused that pattern instead of duplicating a new authorization model.

## Changes (already on the branch; re-verified this run)

- `InstructionActions.allows`: `READ` also permits `patient` (staff branch kept).
- `InstructionStore`: new `verifiedPatientCharts(subject)` port with a
  tenant-keyed in-memory implementation and a `linkPatientChart` synthetic seed.
- `InstructionService.get`: patient callers bound via `requirePatientChartAccess`;
  staff callers keep the `staffAssignedToUnit` guard.
- `InstructionService.listForPatient`: a patient may only request a chart from
  its own verified link set, then sees current revisions with an active
  connection; staff filter unchanged. New private helpers `isPatient()` and
  `requirePatientChartAccess(...)`.
- Tests: action-matrix assertion, three service tests (positive self-read,
  wrong-patient, revoked connection) and one HTTP-level positive test.
- This run: `.agent/pr-notes.md` restored to `origin/main`; this notes file added.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` → `InstructionController.get`
  → `InstructionService.get` → `execute(READ, ...)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Covered by `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`
  and `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → patient verified-chart membership +
  connection filter; wrong-patient by
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.
- Revoked connection by `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection`.

## New/changed branches and their tests (100% coverage)

| Production branch | Test that covers both sides |
|---|---|
| `InstructionActions.READ` `\|\| "patient".equals(role)` true / false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new patient assertion / existing `finance` denial); service tests exercise it through `execute` |
| `InstructionStore.InMemory.verifiedPatientCharts` / `linkPatientChart` present / absent | three patient service tests + controller patient test (present); pre-existing staff tests (empty set) |
| `InstructionService.get`: `if (isPatient())` true / false | new patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff) |
| `InstructionService.get`: `else if (!staffAssignedToUnit(...))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, stranger 403); staff `get` tests (false) |
| `requirePatientChartAccess`: chart not linked true / false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `requirePatientChartAccess`: connection inactive true / false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false) |
| `listForPatient`: `if (isPatient())` true / false | new patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff) |
| patient list: verified-chart membership false / true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true) |
| patient list: connection filter false / true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision) |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both outcomes
are exercised by the tests listed above.

## Commands run and results — this run (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  - RED (production files temporarily reverted to `origin/main`): exit 1,
    `cannot find symbol: method linkPatientChart(String,String)` in
    `InstructionServiceTest` and `InstructionControllerTest` — the patient-binding
    API did not exist. Log: `/tmp/opencode/aud12-red.log`.
  - GREEN (implementation restored from HEAD): exit 0; Surefire
    `InstructionContractTest` 13/13, `InstructionServiceTest` 18/18,
    `InstructionControllerTest` 3/3, 0 failures / 0 errors / 0 skipped.
    Log: `/tmp/opencode/aud12-green-targeted.log`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — exit 0;
  1918 tests, 0 errors, 0 failures, 0 skipped across 169 report files.
  Log: `/tmp/opencode/aud12-fast-all.log`.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
  — exit 0; `Tests run: 2364, Failures: 0, Errors: 0, Skipped: 0`;
  `All coverage checks have been met.`; `BUILD SUCCESS` (JaCoCo check green;
  OWASP/SpotBugs intentionally skipped via `-Pno-security` — integrator's gate).
  Log: `/tmp/opencode/aud12-verify.log`.
- JaCoCo `apps/api/target/site/jacoco/jacoco.xml` per changed class (missed/covered):
  `InstructionActions` INSTR 0/73, LINE 0/11, BRANCH 0/25, METHOD 0/3, CLASS 0/1;
  `InstructionService` INSTR 0/950, LINE 0/166, BRANCH 0/52, METHOD 0/32, CLASS 0/1;
  `InstructionStore$InMemory` INSTR 0/571, LINE 0/95, BRANCH 0/24, METHOD 0/29,
  CLASS 0/1. Every missed count is 0 — 100% on every production file touched.
  (`InstructionStore` is an interface and emits no counters; its implementation
  is measured above.)
- `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`,
  `check-migrations.py --require-baseline` — all exit 0.
- Tooling modules individually OK: `test_java_conventions.py` 2/2,
  `test_check_names.py` 6/6, `test_check_migrations.py` 35/35,
  `test_check_docs.py` 6/6, `test_check_secrets.py` 5/5.

## Merge/check reproduction and the two environmental (non-code) reds

The story asked to reproduce with `scripts/check.sh` on the merge of main and
this branch. `origin/main` is the merge base and is an ancestor of HEAD, so HEAD
*is* that merge. `scripts/check.sh` was not run end-to-end here because two of
its steps are red in this sandbox before any change, for environment reasons
already documented in the earlier attempt note:

1. `python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check`
   → exit 1, "opencode.json is stale/out of date". `git ls-files -v opencode.json`
   reports flag `S`: the worktree file is the **CI** profile that `agent-run.sh`
   copied under `skip-worktree`; `git show HEAD:opencode.json` is the committed
   **local** profile. Verified earlier that `git show HEAD:opencode.json ==
   render(local)` and the worktree file == render(ci). This change touched no
   config, agent or skill file — do not regenerate `opencode.json` from a worker
   with the CI profile in place. CI/integrator measures the committed profile.
2. `python3 -I -m unittest discover -s scripts/tests -q` → exit 1 with 19
   failures/errors, all `ModuleNotFoundError: No module named 'yaml'` (Python
   3.11.2 here has no PyYAML) and the `test_check_pack` drift cases from (1).
   All five domain-relevant tooling modules pass individually (listed above).

Neither red is in the Java instructions domain; both would be red on a clean
checkout in this worker. The real, code-level merge blocker — the shared
`.agent/pr-notes.md` divergence — is resolved in this change.

## Decisions

- Wrong-chart / wrong-patient access returns **404 NOT_FOUND** (concealment,
  ARC-04), matching `InstructionFactService.requirePatientAccess`; a revoked
  connection on a linked chart returns 403.
- The `patientId` query parameter is retained for contract compatibility but,
  for a patient caller, is validated against the charts linked to the
  authenticated subject (IAM-05) instead of being trusted. A patient with
  several verified charts can still choose among them.
- The staff branch is byte-for-byte unchanged; patient handling is additive.
- The verified-chart lookup lives in `InstructionStore`, keeping the package
  self-contained and inside scope.
- The shared `.agent/pr-notes.md` is restored to `origin/main` rather than
  deleted or edited; this is the conflict resolution, not a content change.

## Defects found

No new product defect. The audited defect is fixed (patient role denied on every
instruction-content read; `listForPatient` trusted a query-string `patientId`).
Process defect observed and corrected here: prior retries wrote notes to the
shared `.agent/pr-notes.md`, which caused the merge conflict. No BUGS.md change
is in scope for this worker.

## Evidence provenance

All evidence is **tool-generated** (executed Maven, JaCoCo, policy scripts and
unit suites) from this checkout using synthetic fixtures only: no reviewer
subagent finding, simulated user walkthrough, human participant or real patient
data contributed. The action matrix and store pattern were derived by reading
the existing source (tool evidence). Environment-only failures are labelled as
such above and are not claimed as passing.
