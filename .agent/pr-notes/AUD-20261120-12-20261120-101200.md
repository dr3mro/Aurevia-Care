# AUD-20261120-12 — Patients cannot read instruction content

## Story and requirement mapping

The only patient-reachable instruction read paths were denied to the `patient` role:
`InstructionActions.READ` granted only staff sender roles, `tenant_admin` and
`privacy_auditor`. Fix adds a patient-authorized read branch that binds the
caller to a verified chart link and an active connection, while preserving the
staff branch.

| Requirement | How this change serves it |
|---|---|
| MSG-04 | Patients receive typed instruction title/body through `get` and `listForPatient`. |
| MSG-05 | Patient read is a separate fact from acknowledgment; no acknowledgment is implied. |
| PAT-02 | Patient sees only instruction content for a verified, linked, active chart. |
| IAM-05 | Caller is bound to patient charts linked to the authenticated account subject; several charts may be linked. |
| MSG-01 / MSG-07 | A revoked patient-unit connection ends future content access on the next read. |
| ARC-04 | Another patient's chart is concealed with 404 (existence not revealed). |
| ARC-02 / OPS-02 | All checks stay inside the existing guarded `execute` path; tenant scope is from `TenantContext`, not the request. |

## Acceptance mapping

- Positive patient self-read: `GET /api/v1/patient-instructions/{threadId}` and
  `GET /api/v1/patient-instructions?patientId=...` return instruction title/body
  for a patient account linked to the chart with an active connection.
- Negative wrong-patient denial: a thread on an unlinked chart, or a
  `patientId` not linked to the caller, returns 404 (concealment).
- Negative revoked connection: returns 403 for `get` and an empty list for
  `listForPatient`, with no content disclosed.
- Staff branch unchanged: unit assignment + active connection still required;
  every pre-existing test still passes.

## Changes (scope: messaging/instructions main + test only)

- `InstructionActions.allows`: `READ` now also permits `patient`.
- `InstructionStore`: new `verifiedPatientCharts(subject)` port returning the
  patient charts linked to the authenticated patient account (IAM-05), with a
  tenant-keyed in-memory implementation and a `linkPatientChart` synthetic seed.
  This mirrors the established `InstructionFactStore.verifiedPatientCharts`
  pattern already used by `InstructionFactService.requirePatientAccess`.
- `InstructionService`:
  - `get` — patient callers are bound via `requirePatientChartAccess`; staff
    callers keep the `staffAssignedToUnit` guard.
  - `listForPatient` — patient callers may only request a chart from their own
    verified link set, then see current revisions with an active connection;
    the staff filter is unchanged.
  - New private helpers `isPatient()` and `requirePatientChartAccess(...)`.
- Tests: action-matrix assertion, three service tests (positive self-read,
  wrong-patient, revoked connection) and one HTTP-level positive test.

No migration, contract, tracker, build file, agent or skill was changed. No new
Java package or task-ID-named folder was introduced.

## Entry-point-to-test trace

- `GET /api/v1/patient-instructions/{threadId}` →
  `InstructionController.get` → `InstructionService.get` →
  `execute(InstructionActions.READ, ...)` → `isPatient()` →
  `requirePatientChartAccess` → `verifiedPatientCharts` / `connectionActive`.
  Tested by `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes`
  and `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`.
- `GET /api/v1/patient-instructions?patientId=...` →
  `InstructionController.listForPatient` → `InstructionService.listForPatient`
  → patient branch verified-chart membership + connection filter.
  Tested by the same two tests; wrong-patient by
  `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`.

## New/changed branches and their tests (100% coverage)

| Production branch | Test that covers both sides |
|---|---|
| `InstructionActions.READ` `|| "patient".equals(role)` — true / false | `InstructionContractTest.actionMatrixSeparatesAdministrationClinicalAndRead` (new `patient` assertion / existing `finance` denial); service tests exercise it through `execute`. |
| `InstructionStore.InMemory.verifiedPatientCharts` and `linkPatientChart` | All three patient service tests + the controller patient test (present set); pre-existing staff tests run the port without links. |
| `InstructionService.get`: `if (isPatient())` true / false | New patient `get` tests (true); `replacementSupersedesPriorRevisionAndNotifiesPatient` (false, staff). |
| `InstructionService.get`: `else if (!staffAssignedToUnit(...))` true / false | `unitAssignmentAndConnectionAreValidatedOnEveryCommand` (true, stranger 403); staff `get` tests (false). |
| `requirePatientChartAccess`: chart-not-linked `if` true / false | `patientCannotReadAnotherPatientsInstructionContent` (true, 404); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `requirePatientChartAccess`: connection-inactive `if` true / false | `patientReadRequiresAnActiveVerifiedConnection` (true, 403); `patientReadsOwnInstructionContentAndOwnThread` (false). |
| `InstructionService.listForPatient`: `if (isPatient())` true / false | New patient list tests (true); `sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts` and `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` (false, staff). |
| `listForPatient` patient branch: `verifiedPatientCharts.contains(patient)` false / true | `patientCannotReadAnotherPatientsInstructionContent` (false, 404); `patientReadsOwnInstructionContentAndOwnThread` (true). |
| `listForPatient` patient branch: connection filter false / true | `patientReadRequiresAnActiveVerifiedConnection` (false → empty); `patientReadsOwnInstructionContentAndOwnThread` (true → one revision). |

`isPatient()` uses `Stream.anyMatch` (no JaCoCo branch in our code); both
outcomes are exercised by the tests listed above.

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=InstructionContractTest,InstructionServiceTest,InstructionControllerTest test`
  — RED before implementation: compilation failed on the missing
  `InMemory.linkPatientChart` API (`cannot find symbol`), i.e. the tested
  behavior did not exist. After implementation: exit 0; Surefire reports
  `InstructionContractTest` 13/13, `InstructionServiceTest` 18/18,
  `InstructionControllerTest` 3/3, 0 failures / 0 errors / 0 skipped.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify`
  — exit 0; `Tests run: 2353, Failures: 0, Errors: 0, Skipped: 0`;
  `jacoco:0.8.16:check` → `All coverage checks have been met.`
  (OWASP and SpotBugs intentionally skipped via `-Pno-security`; they are the
  integrator's gate.) Full log: `/tmp/opencode/verify-aud12.log`.
- JaCoCo `apps/api/target/site/jacoco/jacoco.xml` read per changed class
  (tool evidence):
  - `InstructionActions`: instruction 73/73, line 11/11, branch 25/25, method 3/3, class 1/1.
  - `InstructionService`: instruction 950/950, line 166/166, branch 52/52, method 32/32, class 1/1.
  - `InstructionStore$InMemory`: instruction 571/571, line 95/95, branch 24/24, method 29/29, class 1/1.
  All missed counts are 0 — 100% on every file touched.
- `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`,
  `check-migrations.py --require-baseline` — all exit 0.
- Relevant tooling unit modules (discover with `-p`): `test_java_conventions.py`
  2/2, `test_check_names.py` 6/6, `test_check_migrations.py` 35/35,
  `test_check_docs.py` 6/6, `test_check_secrets.py` 5/5 — all OK.

## Decisions

- Wrong-chart and wrong-patient access return **404 (NOT_FOUND)** rather than
  403, to conceal whether another patient's chart exists (ARC-04). This matches
  `InstructionFactService.requirePatientAccess` in the sibling messaging
  package. A revoked connection on an otherwise linked chart returns 403.
- The `patientId` query parameter is kept for contract compatibility, but for a
  patient caller it is validated against the set of charts linked to the
  authenticated subject (IAM-05) instead of being trusted. This binds the
  caller to its own chart while still allowing a patient with several verified
  charts (IAM-05) to choose among them.
- The staff branch is byte-for-byte unchanged; patient handling is additive, so
  no existing staff authorization behavior is weakened.
- The verified-chart lookup is added to `InstructionStore` rather than reached
  across a module boundary, keeping the instructions package self-contained
  and inside the declared scope.

## Defects found

No new product defect was introduced. The audited defect (patient role denied
on every instruction-content read, plus `listForPatient` trusting a query-string
`patientId`) is fixed. No BUGS.md change is in scope for this worker.

## Evidence provenance

All evidence above is **tool-generated** (executed Maven/JaCoCo, scripts and
unit suites) from this checkout using synthetic fixtures only. No reviewer
subagent finding, simulated user walkthrough, human participant or real patient
data contributed to this change. The action matrix and store pattern were
derived by reading the existing source (tool evidence), not from a human
reviewer.

## Why this is not finished (local environment only; story scope is complete)

The story's own gates are green (full `mvn verify` with JaCoCo 100%, targeted
unit tests, policy scripts). Two *environmental* checks are nevertheless red in
this local worker and would also be red on a clean checkout here, before this
change:

1. `bash scripts/quick-check.sh` →
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (exit 1),
   and `python3 -I scripts/gen-opencode.py --check` → `opencode.json is out of date`.
   - Most likely cause: the documented cloud-worker skip-worktree artifact
     (OPS-CW-003-01). `git ls-files -v opencode.json` reports flag `S`; the
     worktree file is the **CI** profile while the committed file is the
     **local** profile.
   - Tried: verified with a Python import of `gen-opencode.py` that
     `git show HEAD:opencode.json == render(local)` (True),
     `git show HEAD:.github/opencode.ci.json == render(ci)` (True), and the
     worktree file == generated CI profile (True). This change touched no
     config, agent or skill file.
   - Next step: none for this story. The integrator/CI measures the committed
     profile, where the check is green; do not regenerate `opencode.json` from
     a worker with the CI profile in place.
2. `python3 -I -m unittest discover -s scripts/tests -q` → 8 failures / 10
   errors from `ModuleNotFoundError: No module named 'yaml'` (this worker has
   Python 3.11.2 without PyYAML) and cascading `test_check_pack` /
   `test_agent_run` failures from the same config artifact plus sandboxed
   git/gh. All are outside the Java instructions domain.
   - Tried: ran the relevant tooling modules individually — they pass
     (see results above).
   - Next step: run the Python tooling suite in the CI worker image where
     PyYAML is installed and the committed `opencode.json` is in place; no code
     change is warranted here.
