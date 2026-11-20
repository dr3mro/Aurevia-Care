# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Integrator merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, the tests or this note.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Opening, viewing and downloading conversations/files is audited; a caller authorized for one file must not obtain another file's event detail (scanner detail, void/rejection free text) | `AttachmentService.events(actor, id)` now filters the tenant event stream to the single visible `attachmentId`, exactly as its sibling `view(actor, id)` already did |
| ARC-07 | A conversation/message belongs to one recipient unit and one verified patient connection and carries a scoped fact trail | The per-file filter keeps one file's trail from crossing into another file in a different conversation in the same tenant |
| OPS-02 | Sensitive reads leave a correctly scoped, durable audit trail | Unchanged: `authorize(...)` and the `visible(...)` check still run first; only the returned fact trail is narrowed |

The defect was a **cross-file / cross-conversation leak within one tenant**, not a cross-tenant leak: `InMemoryAttachmentStore.events(organizationId)` is already keyed by organization. `AttachmentEvent.detail` (`AttachmentContract.java`) carries scanner detail and void/rejection free text, so the leak exposed other files' incident detail to a caller authorized for only one file. The service has no HTTP controller yet (tracked separately as AUD-20261119-03 and out of scope), so the leak is latent until an entry point is wired.

## Change (only the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`
  - `events(Actor, String)`: after `authorize` + `load` + `visible`, capture `String fileId = current.id();` and replace the unfiltered
    `return store.events(actor.organizationId()).stream().toList();`
    with
    `return store.events(actor.organizationId()).stream().filter(fact -> fileId.equals(fact.attachmentId())).toList();`
  - Mirrors `view(...)`. No authorization, visibility, ordering or signature change. No new class, method, package, branch condition or migration.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`
  - New regression test `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: uploads two attachments `a` and `b` for the same patient in the same tenant, scans each with a distinct detail string, asserts `events(a.id())` has exactly 2 facts, every fact's `attachmentId` is `a.id()`, contains `detail-of-file-a`, contains no `detail-of-file-b`, and equals `view(a.id()).events()`; then the symmetric assertions for `b`.

The pre-existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` only asserted every event carries the caller's `organizationId`, so it never detected the missing per-file filter even at 100% line coverage. The new test is the missing behavioral guard. No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Merge-conflict repair actions

- A prior attempt wrote its note into the forbidden shared `.agent/pr-notes.md`; that file is the artifact the swarm publish job regenerates from a per-story note, so committing a change to it collides with parallel workers. Restored `.agent/pr-notes.md` byte-for-byte to `origin/main` (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`), so it no longer appears in this branch's diff against main.
- Removed a duplicate prior note `.agent/pr-notes/fix-20261120-170419.md` (same story, wrong filename). This branch's only note is the file named in the task, `.agent/pr-notes/fix-20261120-171531.md`.
- The production fix and regression test were already present in this branch from earlier attempts; I re-verified them rather than duplicating them (RED/GREEN and full coverage below are my own fresh tool runs).

## Tests written first (RED -> GREEN) — tool evidence

All commands run from `apps/api` with Java 21.0.13 / Maven 3.10.0.

- RED: temporarily replaced `events()` with `origin/main`'s unfiltered body (new test kept), then ran
  `mvn -q -B -f pom.xml -Pno-security,fast -Dtest='AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant' test`
  → `rc=1`, `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`, `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest:443` (tenant-wide ledger returned all four facts from both files).
- Restored the fixed file byte-for-byte (`cmp` vs saved copy returned 0).
- GREEN: same command → `rc=0`.

## Coverage evidence (AGENTS.md coverage rule 8)

Command: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f pom.xml -Pno-security verify`
Result: `rc=0` — full unit, real-PostgreSQL integration and JaCoCo `check` (thresholds `minimum=1.00` for INSTRUCTION, LINE, BRANCH, METHOD and CLASS in `pom.xml`) all passed.

`apps/api/target/site/jacoco/jacoco.csv` row for the only changed production class:

`SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28`

Parsed (missed / covered): instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28 — **100% on every measured counter** for `AttachmentService`. Global `jacoco.xml` counters are also 0 missed: INSTRUCTION 0/90701, BRANCH 0/6407, LINE 0/15736, COMPLEXITY 0/7070, METHOD 0/3815, CLASS 0/785. No touched file is below 100%.

Surefire aggregate (`target/surefire-reports/TEST-*.xml`): **210 suites, 2361 tests, 0 failures, 0 errors, 0 skipped**; `AttachmentAcceptanceTest` ran 29 tests, 0 failures/errors/skipped.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| New `events()` filter predicate `fileId.equals(fact.attachmentId())` → true (fact belongs to the requested file) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` — asserts `detail-of-file-a` in `events(a.id())` and `detail-of-file-b` in `events(b.id())` |
| Same predicate → false (fact belongs to the other file in the same tenant) | Same test — asserts trail size 2 (not 4), no cross-detail, and equality with `view(...).events()` |

No other production branch was added or changed. The existing authorization/visibility branches in `events()` are unchanged and remain covered by their existing tests.

## Entry point -> test trace

`AttachmentService.events(actor, id)` (no HTTP controller yet — AUD-20261119-03) → `authorize(READ)` → `load(...)` → `visible(...)` → `store.events(organizationId)` → per-file `filter(attachmentId)` → ordered `List<AttachmentEvent>`. The regression test enters directly at the service method with two synthetic attachments in one tenant and asserts the returned trail is scoped to the requested `attachmentId`. `view(...)` shares the same filter and is asserted equal in the test.

## Other commands run and results

- `python3 -I scripts/check-names.py` → `rc=0`
- `python3 -I scripts/check-secrets.py` → `rc=0`
- `python3 -I scripts/check-docs.py` → `rc=0`
- `python3 -I scripts/check-migrations.py --require-baseline` → `rc=0`
- `python3 -I scripts/gen-opencode.py --check` and `python3 -I scripts/check-pack.py` → both `rc=0` when run against the committed `opencode.json` (`git show HEAD:opencode.json`); check-pack reported `Delivery tooling validated: 33 agents, 33 skills`.

## Environment artifacts (not caused by this change, out of scope)

- In this cloud worker `opencode.json` carries Git's `S` (skip-worktree) flag and the worktree copy is the CI profile (`cmp` with `.github/opencode.ci.json` returned 0). Running the policy gate against that worktree copy reports `check-pack: opencode.json is stale`; this is the already-closed OPS-CW-003-01 measurement artifact (delivery skill: "in a cloud worker treat worktree config checks as CI-profile results, not HEAD truth"). The committed HEAD profile is correct and both pack checks pass against it. No generator/agent/config file was edited.
- `bash scripts/check.sh` in this worker reaches `python3 -I -m unittest discover -s scripts/tests` and reports 271 tests with 6 failures and 10 errors. All 10 errors are `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent from this worker image); the 6 `test_agent_run.py` failures come from leftover `opencode/issue-5-9` branch/gh-mock state in this reused worker checkout. These are pre-existing environment gaps in Python tooling tests, outside the declared Java scope, and unrelated to the attachment change; they are not reproduced in the integrator's prepared image. No Python tooling file or test was modified.
- The integrator runs the database and integration tier; I additionally ran the full `mvn verify` above (unit + integration + JaCoCo), all green.

## Defects found and decisions

- Root cause confirmed: `events()` omitted the per-attachment filter that `view()` already applied; the new test fails against the unfiltered version with four facts instead of two.
- No new defect discovered; no missing decision or evidence. The fix is within the declared scope, uses only synthetic fixtures, and every gate I can run locally is green.

Evidence source: tool evidence from commands I executed in this checkout (git, Maven/Surefire, JaCoCo, Python policy scripts). Reviewer evidence: none in this run (prior simulated audit finding is the origin of the task). Simulated-user and human/clinical evidence: none.
