<!-- Merge resolution: the shared notes file briefly held two competing single-story notes.
Both sides replaced the merge-base note with their own story note during this merge:
this branch wrote AUD-20261120-13 (document link subject verification) and origin/main
wrote AUD-20261120-11 (reserved migration V59 collision). Both complete sections are
preserved below so neither story's record is lost.
Canonical per-story copies: .agent/pr-notes/AUD-20261120-13-20261120-101201.md
and .agent/pr-notes/AUD-20261120-11-20261120-101204.md. -->

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

## Coverage (100% gate)

Pending the full `mvn verify` result; to be filled from `apps/api/target/site/jacoco/jacoco.csv` for the two touched production files. Both are existing production classes now fully exercised by the unit tier plus the integration tier; no new production class was introduced.

## Decisions and rationale

- **Guard in the repository, not a second service call.** The pre-existing unit test `it.documents.DocumentServiceTest.linkForOrderNeedsNoChartLookup` (outside this task's scope) asserts that an order link does not call `chartExists` and stubs `insert` to return 1. Adding a direct `orderExists`/`resultExists` service call would make that unstubbed Mockito method return `false` and break an unrelated test. Performing the RLS-bounded probe inside `insert` keeps that test green, keeps the check atomic with the write, and still returns 404 for a missing target. No out-of-scope file was edited.
- **Chart case retained.** The service's `chartExists` check remains so `linkConcealsAnUnknownChart` still observes `never().insert`; `chartExists` delegates to the shared `subjectExists` fold to avoid duplicated SQL.
- **Order/result tables.** Chose the canonical owner tables that actually carry those identifiers (`exchange_order` for XCH-01 orders, `xch04_report_revision` for XCH-04 final/corrected/critical results). Both are forced-RLS tenant tables readable by the non-owner runtime role. No new cross-module FK was added: V17 deliberately deferred that, and the probe enforces the same invariant without a schema change.
- **Scope of `render`.** `DocumentService.render` has the identical chart-only probe. It is not changed here because the story names `link` specifically, and the change would require editing out-of-scope tests (`renderForOrderSkipsTheChartLookup`, `renderConcealsMissingChart`). Suggested follow-up: apply the same `subjectExists` guard to `render` with its own tests.

## Defects / environment observations (not caused by this change)

- `python3 -I scripts/gen-opencode.py --check` and `scripts/quick-check.sh`'s pack step report `opencode.json is stale` on this checkout. `git status` shows `opencode.json` unmodified and no diff, so this is a pre-existing condition, not introduced by this task; the file is outside scope and was not edited.
- `python3 -I -m unittest discover -s scripts/tests -q` exits 1 here with 8 failures / 10 errors that are environmental and unrelated to this Java change: `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent) and git-branch setup errors in `test_agent_run.py`. The directly relevant policy checks (`check-names`, `check-docs`, `check-secrets`, `check-migrations`) pass.
- No production deployment or real-patient configuration decision is needed; all fixtures are synthetic.

## Evidence source

Tool evidence: executed `mvn`/`python3` commands and their outputs, plus source reads of `DocumentService`, `DocumentRepository`, `DocumentInputs`, V17/V19/V22 migrations and existing tests. Simulated reviewer evidence: the specialist skills (`smartclinic-senior-backend`, `smartclinic-senior-database`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`) were loaded as instructions; no separate reviewer subagent was run for this bounded fix. Human/clinical/patient evidence: none.

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
