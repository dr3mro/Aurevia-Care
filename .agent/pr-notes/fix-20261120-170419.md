# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, tests or this note.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Opening, viewing and downloading conversations/files is audited; a caller authorized for one file may not obtain another file's event detail | `AttachmentService.events(actor, id)` now filters the tenant event stream to the single visible `attachmentId`, exactly as its sibling `view(actor, id)` already did |
| ARC-07 | A conversation/message belongs to one recipient unit and one verified patient connection; each message/attachment carries a scoped fact trail | The per-file filter keeps one file's trail (scanner detail, void/rejection free text) from crossing into another conversation's file in the same tenant |
| OPS-02 | Sensitive reads leave a correctly scoped, durable audit trail | Unchanged: `authorize(...)` and the visibility check still run first; only the returned trail is narrowed |

The defect was a **cross-file / cross-conversation leak within one tenant**, not a cross-tenant leak: `InMemoryAttachmentStore.events(organizationId)` is already keyed by organization. `AttachmentEvent.detail` (`AttachmentContract.java`) carries scanner detail and void/rejection free text, so the leak exposed other files' incident detail to a caller authorized for only one file.

## Change (inside the declared scope)

Only two production/test files, both under the declared scope, plus this note:

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`
  - `events(Actor, String)`: after `authorize` + `load` + `visible`, capture `String fileId = current.id();` and change the unfiltered
    `return store.events(actor.organizationId()).stream().toList();`
    to
    `return store.events(actor.organizationId()).stream().filter(fact -> fileId.equals(fact.attachmentId())).toList();`
  - Mirrors `view(...)`. No authorization, visibility, ordering or signature change. No new method, class, package, branch condition or migration.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`
  - New test `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: uploads two attachments `a` and `b` for the same patient in the same tenant, scans each with a distinct detail string, and asserts `events(a.id())` has exactly 2 facts, every fact's `attachmentId` is `a.id()`, contains `detail-of-file-a`, contains no `detail-of-file-b`, and equals `view(a.id()).events()`; then the symmetric assertions for `b`.

The pre-existing test `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` only asserted every event carries the caller's `organizationId`, so it could never detect a missing per-file filter. The new test is the missing regression guard. No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Tests written first (red -> green) — tool evidence

Commands actually executed in this checkout (final lines shown):

- RED (production fix temporarily replaced with `origin/main`'s unfiltered version, new test kept):
  `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant' test`
  → `rc=1`; `Tests run: 1, Failures: 1`; `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` (the tenant-wide ledger returned all four facts from both files).
- GREEN (fix restored byte-for-byte, verified `git diff` against HEAD clean):
  same command → `rc=0`.

## Coverage evidence (AGENTS.md coverage rule 8)

Command: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify`
Result: `rc=0` (full unit + real-PostgreSQL integration suite; JaCoCo `check` goal executes with `minimum=1.00` on instruction, line, branch, method and class counters and passed globally).

`apps/api/target/site/jacoco/jacoco.csv` row for the only changed production class:

`SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28`

Parsed (missed / covered): instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28 — **100% on every counter** for `AttachmentService`. Global report counters are also 0 missed on every counter (INSTRUCTION 0/90701, BRANCH 0/6407, LINE 0/15736, COMPLEXITY 0/7070, METHOD 0/3815, CLASS 0/785). No touched file is below 100%.

Surefire aggregate: **2361 tests, 0 failures, 0 errors, 0 skipped** across 210 suites; `AttachmentAcceptanceTest` ran 29 tests (the new test included), 0 failures/errors/skipped.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `events()` per-file filter predicate `fileId.equals(fact.attachmentId())` → true (fact belongs to requested file) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (asserts `detail-of-file-a` in `events(a.id())`, `detail-of-file-b` in `events(b.id())`) |
| Same predicate → false (fact belongs to the other file in the same tenant) | Same test (asserts no cross-detail in the other trail; size 2 not 4) |
| Authorization / visibility denial paths in `events()` (unchanged code, still covered) | Existing `AttachmentAcceptanceTest` denial tests |

There is no new `if`/`?:`/`switch`/`catch` in production code: the only new executable element is the stream-filter lambda, whose true and false outcomes are both asserted. No dead or uncoverable code was added.

## Root cause of the failed integrator check and the repair

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."

Tool evidence (`git diff origin/main...HEAD`, `git show`, `git status`): the code fix and regression test were correct, but prior attempts on this branch had:
1. Overwritten the **shared** `.agent/pr-notes.md` (main holds AUD-20261120-13's notes; the branch held AUD-20261120-10's notes) — this is the merge conflict with `main`, and it violates AGENTS.md ("Notes go only to the file your prompt names ... Never create, edit or commit the shared `.agent/pr-notes.md`").
2. Added an out-of-scope notes file `fix-20261120-161651.md` (a repeated note from an earlier repair run of this same story).

Repair applied in this run:
- restored `.agent/pr-notes.md` to `origin/main`'s exact committed content (verified `git diff --quiet origin/main -- .agent/pr-notes.md` → rc 0);
- removed the out-of-scope `fix-20261120-161651.md`;
- wrote this note at the assigned path `.agent/pr-notes/fix-20261120-170419.md`.

After these changes the net diff against `origin/main` is exactly: `AttachmentService.java` (the fix), `AttachmentAcceptanceTest.java` (the test), and this note. The shared tracker files (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`) were not touched. This removes the merge conflict so the integrator can merge cleanly.

## Local environment caveats (observed, not caused by this change)

- Reproducing with `bash scripts/check.sh` on this worker exits 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented CI-profile artifact: `git ls-files -v opencode.json` returns `S` (skip-worktree) and the worktree holds the CI profile. It is pre-existing and unrelated to the two Java files. The integrator's clean checkout judges the correct HEAD profile.
- Python tooling tests cannot run in this image: `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent). The policy checks relevant to this change all passed individually: `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` → each `rc=0`. Java 21.0.13, Maven 3.10.0, PostgreSQL accepting connections on 127.0.0.1:5432.
- No production deployment or real-patient configuration decision is needed; all fixtures are synthetic.

## Entry-point-to-test trace

No HTTP controller exists for this service yet (tracked as TODO AUD-20261119-03), so the entry point is the domain API `AttachmentService.events(actor, id)`. Trace:
`events(actor,id)` → `authorize(actor, AttachmentActions.READ, id)` → `load(actor,id)` (`AttachmentStore.attachment(org,id)`) → `visible(actor,current)` → `store.events(organizationId)` → `.filter(fileId.equals(fact.attachmentId()))` → returned trail.
`AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` drives that exact path with two files in one tenant and asserts the returned facts' `attachmentId` and `detail` values, plus equality with `view(...).events()`.

## Evidence sources and reviewers

- Tool evidence: all commands above were executed in this checkout; results are real command output (`mvn`, `git`, JaCoCo CSV/XML, surefire XML).
- Advisory reviewer: `smartclinic-senior-backend` bounded read-only review (simulated reviewer, not a human/clinical participant). It confirmed the filter mirrors `view()`, matches the canonical `current.id()` (so padded ids are handled), leaves authorization/visibility unchanged, the new test genuinely detects the leak, and both outcomes of the filter predicate are asserted; no blocking issues. Its evidence is source inspection only; red/green and JaCoCo figures are this worker's tool evidence.
- Non-blocking out-of-scope observations (not changed here): `AttachmentService` still has no controller/`@Service` entry point (AUD-20261119-03 / decision 0003), and `QuarantineService` has an analogous `store.events(org).stream()` pattern that may warrant a separate audit story. Both are outside this task's declared scope.
- Human/clinical/patient evidence: none — internal audit defect fix with synthetic fixtures.
- No decision or evidence is missing; the task is complete within scope.
