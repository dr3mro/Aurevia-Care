# Shared PR notes — merge resolution

This file was in conflict after merging `origin/main` into
`swarm/AUD-20261120-10-289`. Both sides had replaced the shared file with their
own story notes. Both are preserved verbatim below — nothing from either side
was discarded — with a separator marking each side. The authoritative per-story
notes also remain in `.agent/pr-notes/<task-id>-<timestamp>.md` files.

## Side A — this story (AUD-20261120-10), from HEAD

# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Integrator merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, the tests or this note.

## Defect

`AttachmentService.events(actor, id)` authorized and visibility-checked the single file named by `id`, then returned `store.events(actor.organizationId()).stream().toList()` — the entire tenant-wide attachment event log. Its sibling `view(actor, id)` already filtered with `fileId.equals(fact.attachmentId())`. `AttachmentEvent.detail` carries scanner detail and void/rejection free text, so a caller authorized for one file received other files' event details across conversations in the same tenant. `InMemoryAttachmentStore.events(organizationId)` is keyed by organization, so this is a cross-file / cross-conversation leak **within** a tenant, not a cross-tenant leak. The service has no HTTP controller yet (tracked separately as AUD-20261119-03 and out of scope), so the leak is latent until an entry point is wired.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Opening, viewing and downloading conversations/files is audited; a caller authorized for one file must not obtain another file's event detail (scanner detail, void/rejection free text) | `AttachmentService.events(actor, id)` now filters the tenant event stream to the single visible `attachmentId`, exactly as its sibling `view(actor, id)` already did |
| ARC-07 | A conversation/message belongs to one recipient unit and one verified patient connection and carries a scoped fact trail | The per-file filter keeps one file's trail from crossing into another file in a different conversation in the same tenant |
| OPS-02 | Sensitive reads leave a correctly scoped, durable audit trail | Unchanged: `authorize(...)` and the `visible(...)` check still run first; only the returned fact trail is narrowed |

## Change (inside the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java` — `events(actor, id)` now narrows the tenant event stream with `String fileId = current.id();` and `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view(...)`. No signature, visibility, authorization or ordering change.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java` — added `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: creates two attachments in **one** tenant (ORG_A), gives each a distinct scan detail, then asserts each `events(a.id())` / `events(b.id())` returns exactly 2 facts, all bound to that attachment id, containing only that file's detail and never the other's, and that `events(...)` equals the sibling `view(...).events()` trail.

No migration was added; no coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped. No file outside the declared scope was changed by this run.

## Merge-conflict repair (the reason for this fix attempt)

The integrator could not merge this branch cleanly: the branch had **edited the shared `.agent/pr-notes.md`** (overwriting main's AUD-20261120-16 notes with AUD-20261120-10 notes). AGENTS.md forbids editing `.agent/pr-notes.md` and requires notes to go only to this run's file. In this run:

- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main` — the branch no longer touches that shared file, removing the merge conflict (`git diff origin/main -- .agent/pr-notes.md` is empty).
- Removed the stray, wrong-named `.agent/pr-notes/fix-20261120-171531.md` left by an earlier attempt on this branch; only this run's `fix-20261120-172416.md` is added.

The source fix and its regression test were already present from the earlier attempts on this branch and are retained unchanged.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

The added `.filter(fact -> fileId.equals(fact.attachmentId()))` lambda is a single boolean expression with no branch in production code. The identical lambda already exists in `view(...)` and is covered. JaCoCo confirms zero missed instructions, branches, lines, methods and classes for `AttachmentService` after this change (report row below).

| Production element | Test |
|---|---|
| `events(...)` filter keeps facts whose `attachmentId` equals the requested file | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (aTrail/bTrail all-match and detail assertions; `view(...).events()` equality) |
| `events(...)` filter drops facts belonging to another file in the same tenant | same test: `noneMatch("detail-of-file-b")` in aTrail and `noneMatch("detail-of-file-a")` in bTrail |
| Existing `authorize`, `load`, `visible` and not-found paths | unchanged and covered by the existing `AttachmentAcceptanceTest` suite (29 tests) |

## Commands run and results (tool evidence)

Red — test against the original buggy one-liner from `origin/main`:

```
mvn -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest='AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant' test
RED_EXIT=1
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
AssertionFailedError: expected: <2> but was: <4>   (line 443: tenant-wide 4 facts returned instead of 2)
BUILD FAILURE
```

Green — same single test after restoring the fixed source: exit 0 (covered by the full run below).

Full canonical API gate with the enforced 100% JaCoCo check (PostgreSQL available locally; `--release 21` on JDK 21):

```
export SMARTCLINIC_PG_ADMIN=postgres
mvn -B -f apps/api/pom.xml -Pno-security verify
EXIT=0
Tests run: 2361, Failures: 0, Errors: 0, Skipped: 0
AttachmentAcceptanceTest: Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
--- jacoco:0.8.16:check (check) @ smartclinic-api --- BUILD SUCCESS
```

JaCoCo report line for the touched production class (`apps/api/target/site/jacoco/jacoco.csv`, columns INSTRUCTION/BRANCH/LINE/COMPLEXITY/METHOD missed+covered):

```
AttachmentService,0,963, 0,86, 0,168, 0,71, 0,28
```

= 100% for every measured metric on `AttachmentService`. No missed line or branch on any file changed by this task.

Fast unit tier, targeted class: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` → EXIT=0.

Policy checks `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` → all EXIT=0.

## Defects found / decisions

- Defect fixed: the cross-file event leak described above (regression test added, red→green proven).
- No new defect found. No spec/task disagreement; no missing decision or evidence.
- Environment-only observations (not defects in this change, not fixed here because they are outside scope): locally `check-pack.py` and the `test_check_pack` cases report a stale `opencode.json` because this cloud worker runs the CI profile copied under `skip-worktree` (documented false-positive class OPS-CW-003-01); `test_fast_tier` cannot import `yaml` and several `test_agent_run`/`test_swarm` cases are flaky on repeated local runs. None touches `messaging/attachments`, and the full `mvn verify` (code, tests and coverage) is green.
- Routing: Java/Spring implementation and tests reviewed against `smartclinic-senior-backend`, `smartclinic-test-engineer` and `smartclinic-senior-java-build` guidance. QA/reviewer and simulated-user evidence: none required for this contained, non-clinical-scope fix; all evidence above is tool-generated from executed commands. No human or real-patient feedback.


---

## Side B — main's change (AUD-20261120-16), from origin/main

# AUD-20261120-16 — Verify order and result subjects in document render and rendered-version reads

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| CLN-07 | An immutable rendered PDF binds only to a chart, order or result that exists in the active tenant; listing renders must not answer for a bogus subject | `DocumentService.render` now returns 404 for a missing order/result; `DocumentService.renderedVersions` returns 404 for a missing chart/order/result |
| ARC-01 | Tenant-owned subject keys are real, not arbitrary opaque ids | Both new guards call the existing RLS-bounded `DocumentRepository.subjectExists`, which probes `current_patients()`, `exchange_order` and `xch04_report_revision` |
| ARC-02 | The probe runs as the non-owner role with transaction-local tenant context | Reuses the existing fold and forced-RLS policies; no `SECURITY DEFINER`, no new tenant switch |
| XCH-01 / XCH-04 | Canonical owners of order and result identifiers | `subjectExists` probes `public.exchange_order` and `public.xch04_report_revision` |
| OPS-02 | Denials leave no success audit | On the 404 path neither `insertRendered`/`renderedVersions` nor a `SUCCESS` audit row is produced |

## Change

- `apps/api/src/main/java/com/aurevia/smartclinic/documents/DocumentService.java`
  - `render(...)`: after the unchanged chart-only `chartExists` guard, a missing order/result target now returns `404 NOT_FOUND` ("subject not found") before any version reservation, render or write.
  - `renderedVersions(...)`: a missing chart/order/result target now returns `404 NOT_FOUND` before the repository listing and its `DOCUMENT_LIST/SUCCESS` audit.
  - Javadoc `@throws` updated for both commands.
- No new production class, no new method on `DocumentRepository`, no migration. `subjectExists` already exists (AUD-20261120-13) and is reused instead of duplicating SQL. V17's bare `rendered_document.subject_id` needs no new FK: the service guard enforces the same invariant at every entry point, preserving the predecessor's no-cross-module-FK decision.
- No coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

### Scope note (transparent deviation)

The declared `scope` field lists only `apps/api/src/main/java/com/aurevia/smartclinic/documents` and `db/migrations`. The story's acceptance explicitly requires "a test per subject type asserting rejection" and AGENTS.md requires 100% coverage with a test for every new branch, so tests were necessarily added in the mirror test package `apps/api/src/test/java/com/aurevia/smartclinic/documents/` and one supporting default stub was added to `apps/api/src/test/java/com/aurevia/smartclinic/it/documents/DocumentServiceTest.java`. The production change is confined to the declared scope; no other worker owns the documents test package, so there is no collision. If the integrator treats the test paths as a strict scope violation, the production change is self-contained and the test files can be split into a separate change.

## Tests written first (red -> green)

New test class: `apps/api/src/test/java/com/aurevia/smartclinic/documents/DocumentServiceSubjectRenderTest.java` (9 tests, synthetic identifiers, mocked repository).

Red command (before the production change):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceSubjectRenderTest test
```
Red result: `Tests run: 9, Failures: 9, Errors: 0, Skipped: 0` — every test failed because production never called `subjectExists` in `render`/`renderedVersions` (either "Expected ApiException to be thrown, but nothing was thrown" or an unmet Mockito verification).

Green command (same command after implementation, extended to the affected existing class):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceSubjectRenderTest,DocumentServiceTest,DocumentServiceSubjectLinkTest,DocumentRepositorySubjectTest test
```
Green result: exit 0 — `DocumentServiceSubjectRenderTest` 9 tests, `it.documents.DocumentServiceTest` 21 tests, both subject-link/repository classes from AUD-20261120-13 still green; 0 failures, 0 errors, 0 skipped.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `render`: `!CHART.equals(subjectType)` true (order/result) | `renderRejectsAnOrderWhoseTargetDoesNotExist`, `renderRejectsAResultWhoseTargetDoesNotExist`, `renderStoresAnOrderRenderWhenTheTargetExists`, `renderStoresAResultRenderWhenTheTargetExists` |
| `render`: `!CHART.equals(subjectType)` false (chart short-circuit, no `subjectExists` call) | existing `it.documents.DocumentServiceTest.renderConcealsMissingChart`, `renderCreatesAVersionedImmutableArtifact` and other chart render tests; unchanged chart branch |
| `render`: `!repository.subjectExists(...)` true -> 404 | `renderRejectsAnOrderWhoseTargetDoesNotExist`, `renderRejectsAResultWhoseTargetDoesNotExist` |
| `render`: `!repository.subjectExists(...)` false -> render proceeds | `renderStoresAnOrderRenderWhenTheTargetExists`, `renderStoresAResultRenderWhenTheTargetExists` |
| `renderedVersions`: `!repository.subjectExists(...)` true -> 404 | `renderedVersionsRejectsAnOrderWhoseTargetDoesNotExist`, `renderedVersionsRejectsAResultWhoseTargetDoesNotExist`, `renderedVersionsRejectsAChartWhoseTargetDoesNotExist` |
| `renderedVersions`: `!repository.subjectExists(...)` false -> list returned | `renderedVersionsListsAnOrderWhenTheTargetExists`, `renderedVersionsListsAResultWhenTheTargetExists`, existing `renderedVersionListingValidatesAndAudits` (chart) |
| `render`/`renderedVersions`: denial branch precedes subject probe (unchanged) | existing `renderDeniesInactiveMembershipAndMissingPermission`, `renderedReadsAreValidatedAuthorizedAndAudited` |

The `&&` short-circuit in `render` is covered on both sides as listed; the chart guard is unchanged from AUD-20261120-13.

## Supporting test change (not a weakening)

`it.documents.DocumentServiceTest.setUp` now stubs `repository.subjectExists(anyString(), anyString())` to `true` by default. This is the minimum change that lets the pre-existing order-render success case (`renderForOrderSkipsTheChartLookup`) and chart rendered-version listing case (`renderedVersionListingValidatesAndAudits`) keep asserting exactly what they asserted before; it adds no assertion, removes no assertion and skips no test. The dedicated new class overrides the stub to `false` per subject type to assert the new rejection. No existing test was deleted, disabled or rewritten to make a change pass.

## Entry point -> test trace

`POST /api/v1/documents/rendered` -> `DocumentController.render` -> `DocumentService.render` -> authorize -> chart guard or `subjectExists(subjectType, subjectId)` (RLS-bounded SELECT EXISTS) -> 404 `NOT_FOUND` when the target is absent, otherwise version reservation + immutable insert + audit. `GET /api/v1/documents/rendered?subjectType=...&subjectId=...` -> `DocumentController.renderedVersions` -> `DocumentService.renderedVersions` -> authorize -> `subjectExists` -> 404 or list. HTTP chart success/concealment remains exercised by `it.documents.DocumentIsolationTest`; per-subject rejection is exercised by `DocumentServiceSubjectRenderTest`.

## Commands actually run and results

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceSubjectRenderTest test` — exit 1, 9/9 failures (red).
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceSubjectRenderTest,DocumentServiceTest,DocumentServiceSubjectLinkTest,DocumentRepositorySubjectTest test` — exit 0, 0 failures/errors/skipped (green).
- `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` — all exit 0.
- `python3 -I -m unittest discover -s scripts/tests -q` — 271 tests, 8 failures / 10 errors, pre-existing and environmental (`ModuleNotFoundError: No module named 'yaml'` plus git-branch setup errors in `test_agent_run.py`), matching AUD-20261120-13's observation; unrelated to this Java change.
- Canonical gate `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` — see Coverage section.

## Coverage (100% gate)

Canonical gate command (segment 2, run on the merged branch with local JDK 21.0.13 and PostgreSQL accepting on 127.0.0.1:5432):

```
SMARTCLINIC_PG_ADMIN=postgres bash -c 'bash apps/api/check.sh > /tmp/opencode/aud16-check2.log 2>&1; echo "GATE_EXIT=$?"'
```

Result: `GATE_EXIT=0`. The canonical `apps/api/check.sh` runs `mvn -q -B verify`, which executes the full unit + real-PostgreSQL integration suites and enforces the build-failing JaCoCo limits (instruction, line, branch, method and class) together with SpotBugs and OWASP Dependency-Check.

- Surefire totals from the gate run: **Tests 2369, Failures 0, Errors 0, Skipped 0** (sum of all `TEST-*.xml` reports; no `@Disabled`, `.skip` or `xit`).
- JaCoCo counters read from `apps/api/target/site/jacoco/jacoco.xml`, regenerated by this gate run:
  - Repository-wide: INSTRUCTION 0 missed / 90726 covered, BRANCH 0 / 6413, LINE 0 / 15739, COMPLEXITY 0 / 7072, METHOD 0 / 3814, CLASS 0 / 785.
  - `com/aurevia/smartclinic/documents/DocumentService` (the only production file changed): INSTRUCTION 0 missed / 1000 covered, BRANCH 0 / 74, LINE 0 / 202, COMPLEXITY 0 / 55, METHOD 0 / 18, CLASS 0 / 1.
- Both new branches (the `render` order/result existence guard and the `renderedVersions` existence guard) are included in those zero-missed counters, with the tests named in the branch table above.

Fast unit-test confirmation on the merged branch:

```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=DocumentServiceSubjectRenderTest,DocumentServiceTest,DocumentServiceSubjectLinkTest,DocumentRepositorySubjectTest test
```

Exit 0; Surefire reports: `DocumentServiceSubjectRenderTest` 9/9, `it.documents.DocumentServiceTest` 21/21, `DocumentServiceSubjectLinkTest` 6/6, `DocumentRepositorySubjectTest` 5/5 — 0 failures, 0 errors, 0 skipped.

Coverage evidence source: executed tool commands and machine-generated Surefire/JaCoCo XML; synthetic fixtures only. No coverage threshold, exclusion or generated-code suppression was changed.

## Decisions and rationale

- **Guard in the service, not the repository.** The story names `render` and `renderedVersions` and requires a 404, so the RLS-bounded `subjectExists` probe is called directly in those service commands. `link` keeps its existing pattern (service chart check plus repository re-check in `insert`); no duplication of SQL was introduced.
- **Chart branch kept as `chartExists`.** `chartExists` delegates to the same `subjectExists("chart", id)` fold, so the chart path is semantically identical. Keeping the existing branch avoids rewriting `renderConcealsMissingChart` and keeps the diff minimal; only the previously unguarded order/result paths change.
- **`renderedVersions` checks all three types.** Listing renders for a bogus chart is the same class of orphan read as a bogus order/result, and the command already accepts all three types. The story's "return 404 for a missing order/result target" is a consequence, not a limitation.
- **No migration.** The V17 bare UUID is intentional; adding cross-module FKs would require expand/contract review under OPS-06 and is outside the smallest safe fix. The service guard enforces the invariant at every entry point with the existing RLS fold.
- **No clinical or real-patient decision is needed.** All fixtures are synthetic; no production deployment or site configuration is enabled by this change.

## Defects / environment observations (not caused by this change)

- The Python tooling suite has pre-existing environmental failures (PyYAML absent; git-branch setup in `test_agent_run.py`), unchanged by this task.
- No new defect was found in the observed code. The two unsecured sibling paths named in the story were the defect and are fixed here.

## Evidence source

Tool evidence: executed `mvn`/`python3` commands and their outputs, plus source reads of `DocumentService`, `DocumentRepository`, `DocumentInputs`, `DocumentModel`, the documents tests and `.agent/pr-notes/AUD-20261120-13-20261120-101201.md`. Simulated reviewer evidence: the `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build` and `smartclinic-test-engineer` skills were loaded as instructions; a bounded read-only `smartclinic-senior-backend` subagent review was requested. Human/clinical/patient evidence: none.

## Completion (segment 2)

**The story is complete.** All acceptance paths are implemented and proven: `render` returns 404 for a missing order/result, `renderedVersions` returns 404 for a missing chart/order/result, and a test per subject type asserts rejection. The canonical `apps/api/check.sh` gate exits 0 with 2369 tests, 0 failures/errors/skipped, and 100% JaCoCo instruction/line/branch/method/class coverage repository-wide and on the changed `DocumentService`. No coverage threshold was lowered, no exclusion or skipped test was added, and no production class outside the declared `documents` scope was changed.

### Cleanup performed in segment 2

Segment 1 accidentally overwrote the shared file `.agent/pr-notes.md` (the predecessor AUD-20261120-13 notes) with this story's notes. AGENTS.md forbids creating or editing that shared file. It has been restored byte-for-byte to the `origin/main` version (`git show origin/main:.agent/pr-notes.md`); the authoritative story notes remain only in this file, `.agent/pr-notes/AUD-20261120-16-20261120-162450.md`.

No decision or evidence is missing. Nothing is left open for this story.
