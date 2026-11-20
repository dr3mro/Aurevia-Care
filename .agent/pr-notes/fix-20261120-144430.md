# AUD-20261120-10 (repair run `fix-20261120-144430`) — Attachment event history leaks every event in the tenant

Branch: `swarm/AUD-20261120-10-289` (current HEAD before this run's commit: `685dae9`). This is a **merge-conflict repair** (story attempt 1 of 3): the integrator reported that merging this branch into `main` succeeded but `scripts/check.sh` failed. I did **not** push, tag or call `gh`. Synthetic fixtures only — no patient data, credentials or real identifiers appear in the note, tests or code.

## Root cause of the merge failure and repair

`origin/main` is exactly the merge base of this branch (`742f2bb`), so the merge result equals this branch's tree. The real code fix (commit `9dee672`) and its test were already correct; the ~30 subsequent repair commits were churn that repeatedly **overwrote the forbidden shared `.agent/pr-notes.md`** (it holds story `AUD-20261120-13`'s notes on `main`) with this story's notes. That shared file is edited by every parallel swarm worker, so it was the merge-conflict source. Repair performed: restored `.agent/pr-notes.md` byte-for-byte to `origin/main`'s version (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`; `cmp` exit 0). No other tracker or shared file was touched. This run's notes go only to this file, per AGENTS.md.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Every attachment event is bound to one file/conversation; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events` now filters the tenant-wide fact stream by `attachmentId`, matching its sibling `view()` |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay in that file's trail | same filter; tenant scoping (`organizationId`), visibility and canonical-id gates are preserved |
| OPS-02 | Facts carry actor/purpose/outcome; denied reads stay concealed | unchanged `authorize`, `visible`, `notFound` and `event` helpers |

Acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical stored id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in one tenant, scans each with distinct detail text, and asserts each trail holds exactly that file's 2 events (4 in the tenant ledger), that the two `detail` strings never cross trails, and that `events()` equals `view().events()`.

## Defect and minimal fix

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide ledger — after authorizing and visibility-checking only the named file. `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is keyed per organization, so this is a cross-file/cross-conversation leak **within one tenant**, not a cross-tenant leak. `AttachmentEvent.detail` carries scanner detail and void/rejection free text. The pre-existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only asserted `organizationId`, so it never caught the missing per-file filter while line coverage stayed 100%. The service has no HTTP controller yet (latent entry point tracked as AUD-20261119-03).

Fix in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`:

```java
String fileId = current.id();
return store.events(actor.organizationId()).stream()
        .filter(fact -> fileId.equals(fact.attachmentId()))
        .toList();
```

It reuses the canonical stored id (not the raw request parameter), the same predicate as `view()`, and changes no interface, store, schema or coverage threshold. Test added in `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`; it imports `assertNotEquals`, already present in the file.

## Entry-point-to-test trace

No HTTP controller exists yet (AUD-20261119-03), so the public entry point is the domain service: `AttachmentService.events(Actor, String)` → `AttachmentService.load`/`visible` → `store.events(org).stream().filter(attachmentId)` → `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant`. Sibling path `AttachmentService.view(Actor, String)` shares the same per-file predicate and is asserted equal to `events()` in that test.

## Tests written first (red → green)

Red command (buggy `events()` body temporarily restored, only the new test):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test
```
Red result: `Tests run: 1, Failures: 1` — `expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443` (the whole 4-event tenant ledger was returned instead of the file's 2). Exit 1. Fix was then restored (source verified byte-for-byte against the pre-red state).

Green command (full fast unit suite):
```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast test
```
Green result: exit 0, no failures or errors.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

| New branch | Test |
|---|---|
| `events()` filter predicate `true` (fact belongs to the requested file) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (a's own 2 events) and `view()`-based tests |
| `events()` filter predicate `false` (fact belongs to a different file in the same tenant) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (b's 2 events are excluded from `events(a.id())` and vice versa) |

No new class, method, enum, `if/else`, ternary, `catch` or store method was added. No migration was added. No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Coverage command and result (100% confirmed)

```
SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify
```
Result: **exit 0**. The build fails on any missed counter (`jacoco:check` minimum 1.00 for INSTRUCTION, LINE, BRANCH, METHOD, CLASS), so a green `verify` proves 100% across the module. I also read the generated `apps/api/target/site/jacoco/jacoco.xml` for the changed class:

```
CLASS: com/aurevia/smartclinic/messaging/attachments/AttachmentService
  INSTRUCTION: covered=963/963 missed=0 ratio=1.000
  BRANCH:       covered=86/86   missed=0 ratio=1.000
  LINE:         covered=168/168 missed=0 ratio=1.000
  COMPLEXITY:   covered=71/71   missed=0 ratio=1.000
  METHOD:       covered=28/28   missed=0 ratio=1.000
  CLASS:        covered=1/1     missed=0 ratio=1.000
```

PostgreSQL 18.6 was available locally, so this run included the real-PostgreSQL integration tier; `-Pno-security` skipped only OWASP/SpotBugs (which the integrator/Delivery run).

## Out-of-scope check failures observed in this worker environment (not caused by this change)

Running the full `bash scripts/check.sh` locally stops earlier in the Python policy suite for environment reasons unrelated to this story (all in `scripts/`, outside this task's scope):
- `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_workflow_concurrency` and several `test_swarm` cases — PyYAML is not installed in this local image.
- `test_agent_run` cases fail on mocked `gh`/`git push` (`permission denied`, `fetch first`) — local harness artifacts.
- `scripts/check-pack.py` initially reported `opencode.json is stale`; this is the documented OPS-CW-003-01 worker artifact — the worktree file is flag `S` (skip-worktree) and equals `.github/opencode.ci.json`, not the committed local profile. With the committed `opencode.json` in place, `check-pack.py` and `gen-opencode.py --check` both exit 0. I restored the CI profile after the check and did not commit any config change.

These reproduce independent of the attachments change and are left to the integrator/CI, which has the required tooling. The Java/DB gates for the changed code are green.

## Evidence source

Tool-generated evidence only (Maven, JUnit, JaCoCo, git comparisons) with synthetic fixtures. No reviewer, simulated user or human participant evidence was used for this containment fix; no clinical decision is involved (no real-patient approval gate).
