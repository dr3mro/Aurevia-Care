# AUD-20261120-10 — Attachment event history per-file scope — merge-conflict repair

## Requirement IDs and acceptance mapping

- **MSG-07, ARC-07** — audit and bind every attachment event to one conversation/file; a caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) inside the same tenant.
- **Acceptance from the story:** `AttachmentService.events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id, exactly as `view()` does. A test creates two attachments in one tenant and asserts each trail contains only that file's events.
- **In scope (declared):** `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments`, `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new folder or package — names stay business-domain (`messaging.attachments`).
- **Not changed:** `opencode.json`, `.opencode/*`, `.github/workflows/*`, `scripts/*`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`.

## State on arrival

The in-scope fix and its regression test were already committed on this branch:

- `AttachmentService.events()` (`AttachmentService.java:287-297`) authorizes, loads and checks visibility, then captures `String fileId = current.id();` and filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view()` (`:266-277`). Both `store.events()` call sites (`:273`, `:294`) are filtered.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (`:435-455`) creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity.

I did not rewrite this working, fully covered code. I independently re-verified it red → green rather than trusting earlier notes.

## Root cause of the reported merge failure and the fix applied

`git merge-tree --write-tree origin/main HEAD` on arrival reported exactly one conflict:

```
Auto-merging .agent/pr-notes.md
CONFLICT (content): Merge conflict in .agent/pr-notes.md
```

`git diff $(git merge-base origin/main HEAD) origin/main -- <scope paths>` is **empty** — main never touched the Java scope. The conflict was entirely in the shared `.agent/pr-notes.md`: both main and this worker branch changed that file from the merge base. AGENTS.md forbids any worker from creating, editing or committing the shared `.agent/pr-notes.md` (parallel workers overwrite it and every PR then conflicts). Prior repair attempts kept re-introducing a prepend to it.

**Fix:** `git checkout origin/main -- .agent/pr-notes.md` restores the shared file byte-for-byte to main's version, so `git diff origin/main -- .agent/pr-notes.md` is empty and the integrator's merge has no conflict on it. No production Java code was changed. My findings live only in this dedicated file, which is my own and is never touched by parallel workers.

## Test-first verification (red → green) — tool evidence

- **RED** — temporarily removed only the per-file `.filter` from `events()` (byte-for-byte backup/restore of the single tracked file), then:
  `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → `RED_RC=1`; `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (4 facts across two files) was returned instead of the requested file's 2 facts — the exact defect. The file was restored and `git diff --quiet` confirmed it identical to `HEAD`.
- **GREEN** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → `GREEN_RC=0`; surefire: `tests="29" errors="0" skipped="0" failures="0"`.

## Coverage command and result (AGENTS.md Coverage rules)

- `cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → **VERIFY_RC=0**. PostgreSQL accepted connections on `127.0.0.1:5432`. JaCoCo build-failing thresholds (INSTRUCTION/LINE/BRANCH/METHOD/CLASS) passed.
- `cd apps/api && mvn -q -B -Pno-security,fast test` → **FAST_RC=0**; aggregated surefire: 2350 tests, 0 failures, 0 errors, 0 skipped.
- `target/site/jacoco/jacoco.xml`, class `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:

| Counter | missed | covered |
|---|---|---|
| INSTRUCTION | 0 | 963 |
| BRANCH | 0 | 86 |
| LINE | 0 | 168 |
| COMPLEXITY | 0 | 71 |
| METHOD | 0 | 28 |
| CLASS | 0 | 1 |

Bundle totals: INSTRUCTION 0/90675, BRANCH 0/6403, LINE 0/15730, METHOD 0/3814, CLASS 0/785 — **100% on every changed file and the whole bundle.**

## New branches and covering tests

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch; it must simply be executed. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Review evidence

- `smartclinic-senior-backend` and `smartclinic-test-engineer` skills loaded for this Java/backend + test change this session. The Java fix was previously endorsed by a read-only `smartclinic-senior-backend` simulated reviewer (recorded in `.agent/pr-notes/AUD-20261120-10-20261120-101159.md`): smallest correct fix, tenant scoping / visibility / canonical-id preserved, no new JaCoCo branch, no EI_EXPOSE_REP concern (`Stream.toList()` unmodifiable, `AttachmentEvent` immutable), parity assertion adopted. That reviewer edited no files.
- Human/clinical/patient evidence: none — contained backend correctness fix with no HTTP controller entry point yet (tracked separately as AUD-20261119-03).

## Environment artifacts (observed, not caused by this change)

`bash scripts/worker-check.sh` / `bash scripts/check.sh` were not relied on as the gate here: in this cloud worker `opencode.json` is `S` (skip-worktree, the CI profile copied by `agent-run.sh`), so `check-pack.py` reports the documented OPS-CW-003-01 "opencode.json is stale" false positive; in a clean CI checkout the committed local profile is used and that step passes. `shellcheck` is absent locally (CI runs it). PyYAML is absent, so unrelated Python tooling modules cannot be collected locally. None of these import or exercise the changed Java. The authoritative in-scope gate (`mvn verify` with JaCoCo 100%) was run directly and is green.

## Evidence source

All commands above were executed in this checkout this session — **tool evidence**, synthetic fixtures only, no patient data or credentials. No reviewer, simulated user or human participant evidence beyond the simulated reviewer noted above.


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
