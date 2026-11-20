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

---

# AUD-20261120-13 — Document link accepts order or result subjects without verifying they exist

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| CLN-07 | An original PDF/image binds only to a chart, order or result that exists in the active tenant; an orphan association must not be created | `DocumentService.link` now maps a zero-row insert to 404; `DocumentRepository.insert` re-checks the subject inside the RLS boundary |
| ARC-01 | Tenant-owned subject keys are real, not arbitrary opaque ids | `DocumentRepository.subjectExists` uses forced-RLS relations only (`current_patients()`, `exchange_order`, `xch04_report_revision`) |
| ARC-02 | Existence probe runs as the non-owner role with transaction-local tenant context | Reuses `public.current_patients()` fold and the forced-RLS policies already on the canonical tables; no `SECURITY DEFINER`, no explicit tenant switch |
| XCH-01 / XCH-04 | Canonical owner tables for order and result identifiers | Order probe → `public.exchange_order`; result probe → `public.xch04_report_revision` |
| OPS-02 | Denials are auditable | The 404 path leaves no success audit row and no history event, matching the existing chart-concealment behavior |

## Change (inside the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/documents/DocumentRepository.java`
  - Added `subjectExists(String subjectType, String id)`: one RLS-bounded `SELECT CASE ... EXISTS` probe covering chart (`current_patients()`), order (`exchange_order`) and result (`xch04_report_revision`); unknown type yields `false`; a null JDBC result yields `false`.
  - `chartExists(String)` now delegates to `subjectExists("chart", id)` so there is one ledger of the chart fold and no duplicated SQL.
  - `insert(...)` calls `subjectExists(subjectType, subjectId)` first and returns `0` without writing when the subject is absent (atomic with the caller's transaction; no TOCTOU window across transactions).
- `apps/api/src/main/java/com/aurevia/smartclinic/documents/DocumentService.java`
  - `link(...)` captures the insert row count and returns `404 NOT_FOUND` ("subject not found") when the insert wrote no row. The pre-existing chart check and the `DuplicateKeyException -> 409` path are unchanged, and no history event or success audit is emitted on the 404 path.
- New tests in `apps/api/src/test/java/com/aurevia/smartclinic/documents/`:
  - `DocumentRepositorySubjectTest.java` (5 tests) — probes chart/order/result with RLS-bounded SQL assertions, false and null results, insert skipped when missing, insert writes when present, and chart reuse of the same fold.
  - `DocumentServiceSubjectLinkTest.java` (6 tests) — order-missing 404, result-missing 404, chart insert-nothing 404, order/result success, and unknown chart still concealed before insert.

No migration was added; no coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped. `render(...)` retains its chart-only probe; the story scopes the defect to `link`, and changing `render` would require editing out-of-scope tests. Recorded as a follow-up observation below.

## Tests written first (red -> green)

Red command (before production change):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentRepositorySubjectTest,DocumentServiceSubjectLinkTest test
```
Red result: compilation failure — `cannot find symbol: method subjectExists(String,String)` (the new repository boundary did not exist); plus an initially ambiguous `queryForObject` matcher, fixed in the test by typing the parameter matcher as `Map<String, ?>`.

Green command (same command after implementation): `exit 0`
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- DocumentRepositorySubjectTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- DocumentServiceSubjectLinkTest
```

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `DocumentRepository.subjectExists` result `true` | `DocumentRepositorySubjectTest.subjectExistsChecksChartOrderAndResultWithRlsBoundedQueries`, `insertWritesBytesWhenTheSubjectExists` |
| `DocumentRepository.subjectExists` result `false` and `null` | `DocumentRepositorySubjectTest.subjectExistsIsFalseForAMissingOrNullResult` |
| `DocumentRepository.insert` subject absent -> `0` (guard true) | `DocumentRepositorySubjectTest.insertIsSkippedWhenTheSubjectDoesNotExist` |
| `DocumentRepository.insert` subject present -> JDBC update (guard false) | `DocumentRepositorySubjectTest.insertWritesBytesWhenTheSubjectExists` |
| `DocumentService.link` `written == 0` -> 404 for order | `DocumentServiceSubjectLinkTest.linkRejectsAnOrderWhoseTargetDoesNotExist` |
| `DocumentService.link` `written == 0` -> 404 for result | `DocumentServiceSubjectLinkTest.linkRejectsAResultWhoseTargetDoesNotExist` |
| `DocumentService.link` `written == 0` -> 404 for chart race | `DocumentServiceSubjectLinkTest.linkRejectsAChartWhoseInsertFindsNoEffectiveRecord` |
| `DocumentService.link` `written > 0` success (guard false) | `DocumentServiceSubjectLinkTest.linkStoresAnAssociationWhenAnOrderTargetExists`, `linkStoresAnAssociationWhenAResultTargetExists`, and existing `it.documents.DocumentServiceTest.linkStoresOriginalBytesAndAppendsHistory` |
| `DocumentService.link` `DuplicateKeyException` -> 409 (unchanged) | existing `it.documents.DocumentServiceTest.linkMapsAConcurrentConflictTo409` |
| `DocumentService.link` unknown chart -> 404 before insert (unchanged) | `DocumentServiceSubjectLinkTest.linkStillConcealsAnUnknownChartBeforeInsert` and existing `linkConcealsAnUnknownChart` |

The SQL `CASE` arms are inside a string literal and create no Java branches; each arm's table is asserted by `subjectExistsChecksChartOrderAndResultWithRlsBoundedQueries`.

## Entry point -> test trace

`POST /api/v1/documents/associations` -> `DocumentController.link` -> `DocumentService.link` -> (authorize + chart probe) -> `DocumentRepository.insert` -> `DocumentRepository.subjectExists` (RLS-bounded SELECT EXISTS) -> zero rows -> 404 `NOT_FOUND`. HTTP success/concealment for charts is exercised by `it.documents.DocumentIsolationTest` (integration tier); per-subject rejection is exercised by the two new mock-based unit classes above.

## Commands actually run and results

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentRepositorySubjectTest,DocumentServiceSubjectLinkTest test` — exit 0, 11 tests, 0 failures.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — exit 0; 1914 unit tests, 0 failures, 0 errors, 0 skipped. The pre-existing `it.documents.DocumentServiceTest` (21 tests) still passes unchanged.
- `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` — all exit 0.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml verify` — result recorded in the Coverage section below.
- Segment 2 independently re-ran: `bash apps/api/check.sh` (exit 0, full verify — see Coverage); the targeted two-class `mvn ... -Dtest=... test` (exit 0); and `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` (all exit 0). Java 21.0.13, Maven 3.10.0, PostgreSQL accepting connections on 127.0.0.1:5432.

## Coverage (100% gate)

Full canonical gate re-run on the final branch (segment 2), with PostgreSQL available and `SMARTCLINIC_PG_ADMIN=postgres` in the environment:

```
bash apps/api/check.sh        # canonical: cd apps/api && mvn -q -B verify
```

Result: **exit 0**. Surefire aggregate from `apps/api/target/surefire-reports/TEST-*.xml`: **2360 tests, 0 failures, 0 errors, 0 skipped**. No failing surefire/failsafe XML report exists. JaCoCo `check` (BUNDLE rule, 1.00 minimum for INSTRUCTION, LINE, BRANCH, METHOD and CLASS) therefore passed — project-wide `apps/api/target/site/jacoco/jacoco.xml` reports **0 missed on every counter**: INSTRUCTION 0/90690, BRANCH 0/6407, LINE 0/15733, COMPLEXITY 0/7069, METHOD 0/3814, CLASS 0/785.

Read directly from `apps/api/target/site/jacoco/jacoco.csv` for the two touched production files (columns: instruction missed/covered, branch missed/covered, line missed/covered, complexity missed/covered, method missed/covered):

| File | Instruction | Branch | Line | Complexity | Method | Class (from jacoco.xml) |
|---|---|---|---|---|---|---|
| `documents/DocumentService.java` | 0/964 | 0/68 | 0/196 | 0/52 | 0/18 | 0/1 |
| `documents/DocumentRepository.java` | 0/384 | 0/2 | 0/86 | 0/21 | 0/20 | 0/1 |

Both changed files are at 100% on every counter, including the per-class counter. No new production class was introduced. The report goal is bound to the `verify` phase (pom.xml lines 80-88), so the later targeted `test` run could not overwrite these numbers.

Targeted confirmation of the new tests:
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentRepositorySubjectTest,DocumentServiceSubjectLinkTest test
```
Result: **exit 0** — `DocumentRepositorySubjectTest` 5 tests and `DocumentServiceSubjectLinkTest` 6 tests, 0 failures/errors/skipped each.

## Decisions and rationale

- **Guard in the repository, not a second service call.** The pre-existing unit test `it.documents.DocumentServiceTest.linkForOrderNeedsNoChartLookup` (outside this task's scope) asserts that an order link does not call `chartExists` and stubs `insert` to return 1. Adding a direct `orderExists`/`resultExists` service call would make that unstubbed Mockito method return `false` and break an unrelated test. Performing the RLS-bounded probe inside `insert` keeps that test green, keeps the check atomic with the write, and still returns 404 for a missing target. No out-of-scope file was edited.
- **Chart case retained.** The service's `chartExists` check remains so `linkConcealsAnUnknownChart` still observes `never().insert`; `chartExists` delegates to the shared `subjectExists` fold to avoid duplicated SQL.
- **Order/result tables.** Chose the canonical owner tables that actually carry those identifiers (`exchange_order` for XCH-01 orders, `xch04_report_revision` for XCH-04 final/corrected/critical results). Both are forced-RLS tenant tables readable by the non-owner runtime role. No new cross-module FK was added: V17 deliberately deferred that, and the probe enforces the same invariant without a schema change.
- **Scope of `render`.** `DocumentService.render` has the identical chart-only probe. It is not changed here because the story names `link` specifically, and the change would require editing out-of-scope tests (`renderForOrderSkipsTheChartLookup`, `renderConcealsMissingChart`). Suggested follow-up: apply the same `subjectExists` guard to `render` with its own tests.

## Defects / environment observations (not caused by this change)

- `python3 -I scripts/gen-opencode.py --check` and `scripts/quick-check.sh`'s pack step report `opencode.json is stale` on this checkout. `git status` shows `opencode.json` unmodified and no diff, so this is a pre-existing condition, not introduced by this task; the file is outside scope and was not edited.
- `python3 -I -m unittest discover -s scripts/tests -q` exits 1 here with 8 failures / 10 errors that are environmental and unrelated to this Java change: `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent) and git-branch setup errors in `test_agent_run.py`. The directly relevant policy checks (`check-names`, `check-docs`, `check-secrets`, `check-migrations`) pass.
- The full `bash apps/api/check.sh` run emitted two non-fatal OWASP Dependency-Check NVD data-import errors (`Failed to process CVE-2026-6785` / `CVE-2026-6786`: a reference URL longer than the tool's local H2 `URL VARCHAR(1000)` column). These occur during NVD data update, are logged per-CVE by the dependency-check plugin, and did **not** fail the build — the canonical gate returned exit 0 with 2360 passing tests and a green JaCoCo check. Tool/data environment issue, unrelated to this change; no code or threshold was altered to accommodate it.
- No production deployment or real-patient configuration decision is needed; all fixtures are synthetic.

## Evidence source

Tool evidence: executed `mvn`/`python3` commands and their outputs, plus source reads of `DocumentService`, `DocumentRepository`, `DocumentInputs`, V17/V19/V22 migrations and existing tests. Simulated reviewer evidence: the specialist skills (`smartclinic-senior-backend`, `smartclinic-senior-database`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`) were loaded as instructions; no separate reviewer subagent was run for this bounded fix. Human/clinical/patient evidence: none.

## Completion (segment 2)

Story complete. The code change, red/green tests, per-branch test mapping and 100% coverage evidence are all recorded above. The full canonical gate (`bash apps/api/check.sh`) passed on the final tree with 2360 tests and 0 missed JaCoCo counters project-wide; both touched files are at 100% on every counter. No decision or evidence is missing, no coverage threshold was lowered, no exclusion or skip was added, and no production file outside the two named in the scope was changed. Committing this notes update to the current branch; no push, tag or gh call.
