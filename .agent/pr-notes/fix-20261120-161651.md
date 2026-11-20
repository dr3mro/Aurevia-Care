# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, tests or this note.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Opening, viewing and downloading conversations/files is audited; a caller may not obtain another file's event detail through an authorized read of one file | `AttachmentService.events(actor, id)` now filters the tenant event stream to the single visible `attachmentId`, exactly as `view(actor, id)` already did |
| ARC-07 | A conversation/message belongs to one recipient unit and one verified patient connection; store each message/attachment fact with a scoped trail | The per-file filter keeps one file's trail (scanner detail, void/rejection free text) from crossing into another conversation's file in the same tenant |
| OPS-02 | Sensitive reads leave a durable, correctly scoped audit trail | Unchanged: `authorize(...)` and the visibility check still run first; only the returned trail is narrowed |

The defect was a **cross-file / cross-conversation leak within one tenant**, not a cross-tenant leak: `InMemoryAttachmentStore.events(organizationId)` (line 66) is already keyed by organization. `AttachmentEvent.detail` (`AttachmentContract.java:349-351`) carries scanner detail and void/rejection free text, so the leak exposed other files' incident detail to a caller authorized for only one file.

## Change (inside the declared scope)

Only two files, both under the declared scope:

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`
  - `events(Actor, String)`: after `authorize` + `load` + `visible`, capture `String fileId = current.id();` and change
    `return store.events(actor.organizationId()).stream().toList();`
    to
    `return store.events(actor.organizationId()).stream().filter(fact -> fileId.equals(fact.attachmentId())).toList();`
  - This mirrors the sibling `view(...)` implementation (lines 272-275). No authorization, visibility, ordering or signature change. No new method, class, package, branch condition or migration.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`
  - New test `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: uploads two attachments `a` and `b` for the same patient in the same tenant, scans each with a distinct detail string (`detail-of-file-a` / `detail-of-file-b`), and asserts `events(a.id())` has exactly 2 facts, every fact's `attachmentId` is `a.id()`, contains `detail-of-file-a`, contains no `detail-of-file-b`, and equals `view(a.id()).events()`; then the symmetric assertions for `b`.

The pre-existing test `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` only asserted every event carries the caller's `organizationId`, so it could never detect a missing per-file filter. The new test is the missing regression guard.

No migration was added; no coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Tests written first (red -> green)

Tool evidence (commands actually executed, final lines shown):

- Red (production fix temporarily reverted to main's version, new test kept):
  `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant' test`
  → `rc=1`; `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant:443 expected: <2> but was: <4>` (the tenant-wide ledger returned all four facts from both files).
- Green (fix restored):
  same command → `rc=0`.

The fix file was restored byte-for-byte from a pre-revert copy before the green run and before commit (verified with `git diff --stat` against HEAD showing no drift).

## Coverage evidence (AGENTS.md coverage rule 8)

Command: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify`
Result: `rc=0` (full unit + real-PostgreSQL integration suite; JaCoCo `check` goal executes with `minimum=1.00` on instruction, line, branch, complexity and method counters).

`apps/api/target/site/jacoco/jacoco.csv` row for the only changed production class:

`SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28`

Parsed (missed / covered): instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28 — **100% on every counter** for `AttachmentService`. The build-failing JaCoCo check passed globally. No touched file is below 100%.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `events()` per-file filter predicate `fileId.equals(fact.attachmentId())` → true (fact belongs to requested file) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (asserts `detail-of-file-a` present in `events(a.id())`, `detail-of-file-b` present in `events(b.id())`) |
| Same predicate → false (fact belongs to the other file in the same tenant) | Same test (asserts no `detail-of-file-b` in `a`'s trail, no `detail-of-file-a` in `b`'s trail; size 2 not 4) |
| Authorization / visibility denial paths in `events()` (unchanged code, still covered) | Existing `AttachmentAcceptanceTest` denial tests |

There is no new `if`/`?:`/`switch`/`catch` in production code: the only new executable element is the stream-filter lambda, whose true and false outcomes are both asserted by the new test. No dead or uncoverable code was added.

## Root cause of the failed integrator check and the repair

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."

Tool evidence: `git merge-tree <merge-base> origin/main HEAD` and `git diff origin/main...HEAD` showed that prior attempts on this branch had:
1. Overwritten the **shared** `.agent/pr-notes.md` (replacing AUD-20261120-13's notes on main with AUD-20261120-10's notes) — this is the merge conflict with `main`, and it violates AGENTS.md ("Notes go only to the file your prompt names ... Never create, edit or commit the shared `.agent/pr-notes.md`").
2. Added five out-of-scope notes files (`fix-20261120-143101.md`, `-144430`, `-150124`, `-151206`, `-155612`) — repeated notes from earlier repair runs of this same story, all outside the declared scope.

Repair applied in this run (net effect of this commit versus `origin/main`):
- restored `.agent/pr-notes.md` to main's exact committed content (no shared-file change remains);
- removed the five out-of-scope notes files;
- kept only the two in-scope Java changes and added this note.

After this commit the net diff against `origin/main` is exactly:
`AttachmentService.java` (the fix), `AttachmentAcceptanceTest.java` (the test), and this notes file. `origin/main` is one commit ahead (`b322476`, daily audit) which only appended AUD-14/15/16 to `TODO.md`; this branch does not touch `TODO.md`, so the integrator merge keeps main's tracker cleanly. I did not edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md` or `docs/swarm`.

## Local environment caveats (observed, not caused by this change)

- `scripts/check.sh` on this worker reports `check-pack: opencode.json is stale`. This is the documented CI-profile artifact: `git ls-files -v opencode.json` returns `S` (skip-worktree) and the worktree holds the CI profile. Measured with HEAD's committed profile substituted, `python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check` both returned `rc=0`; the worktree file was restored afterwards. The integrator's clean checkout judges the correct profile.
- A full `scripts/check.sh` run also reported 16 failures/errors in `scripts/tests/test_agent_run.py` / `test_swarm.py` / `test_fast_tier.py` / `test_workflow_concurrency.py`: most are `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent in this image) and the rest are temp-git-repo setup failures. These are environment issues in tooling tests, unrelated to the two Java files; they reproduce independently of this change and the integrator's CI image installs the dependency. All policy checks relevant to this change passed: `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` each `rc=0`.

## Entry-point-to-test trace

No HTTP controller exists for this service yet (tracked as TODO AUD-20261119-03), so the entry point is the domain API `AttachmentService.events(actor, id)`. Trace:
`events(actor,id)` → `authorize(actor, AttachmentActions.READ, id)` → `load(actor,id)` (`AttachmentStore.attachment(org,id)`) → `visible(actor,current)` → `store.events(organizationId)` → `.filter(fileId.equals(fact.attachmentId()))` → returned trail.
`AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` drives that exact path with two files in one tenant and asserts the returned facts' `attachmentId` and `detail` values, plus equality with `view(...).events()`.

## Evidence sources and reviewers

- Tool evidence: all commands above were executed in this checkout; results are from real command output (`mvn`, `git`, JaCoCo CSV).
- Advisory reviewer: `smartclinic-senior-backend` bounded read-only review (simulated reviewer, not a human/clinical participant). **No blocking issues found.** It confirmed: the filter mirrors `view()` and matches the canonical `current.id()` (so padded ids are handled); authorization/visibility is unchanged and still narrows access; the new test genuinely detects the leak (with the bug `aTrail.size()` is 4); both outcomes of the filter predicate are asserted; naming, imports, Javadoc, immutability and SpotBugs posture are clean. The reviewer had no shell tool and did not re-run builds, so its evidence is source inspection only; the red/green and JaCoCo figures remain this worker's tool evidence.
- Non-blocking out-of-scope observation from the reviewer (not changed here): `AttachmentService` still has no controller/`@Service` entry point (known AUD-20261119-03 / decision 0003), and `QuarantineService` (~line 216) has an analogous `store.events(org).stream()` pattern that may warrant a separate audit story. Both are outside this task's declared scope.
- Human/clinical/patient evidence: none — this is an internal audit defect fix with synthetic fixtures.
- No decision or evidence is missing; the task is complete within scope.
