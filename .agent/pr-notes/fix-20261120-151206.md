# AUD-20261120-10 (repair run `fix-20261120-151206`) — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. This is a **merge-conflict repair (fix attempt 1 of 3)**: the integrator reported that merging this branch into `main` succeeded but checks failed. I did not push, tag or call `gh`. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, tests or notes.

## Evidence sources

- **Tool (executed commands):** all commands and final results below were run by me in this worker.
- **Reviewer:** none engaged this run (change is contained; root cause was a git/tracker hygiene issue, not a design change).
- **Simulated user / human participant:** none (no HTTP controller exists yet; entry point tracked separately as `AUD-20261119-03`).

## Root cause of the repeated merge failure and the repair performed

`origin/main` is exactly the merge base of this branch (`742f2bb`), so the merge result equals this branch's tree. The actual code fix and its regression test were already present and correct (commit `3585d30`, re-verified this run). The integrator conflict was **not** in the scoped Java code: a long tail of repair commits repeatedly rewrote the **forbidden shared** `.agent/pr-notes.md`, which on `main` belongs to story `AUD-20261120-13` and is edited by every parallel swarm worker. That shared-file churn is the git merge-conflict source.

Repair in this run:
- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main`'s version (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`; `cmp` exit 0 against `origin/main`). After this commit that file has **no diff** against `main`, so the integrator merge is clean.
- Kept the scoped production fix and its regression test unchanged (verified red→green below).
- This run's notes go **only** to this file, per AGENTS.md ("one new file per run"; never create, edit or commit the shared `.agent/pr-notes.md` or any other file directly under `.agent/`).
- No tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`) and no `docs/swarm/` file was touched. No migration version was used.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Every attachment fact stays bound to one file/conversation; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events(actor, id)` now filters the tenant-wide fact stream by `attachmentId`, exactly as its sibling `view()` does |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay in that file's trail | same filter; tenant scoping (`organizationId`), canonical-id gate (`load`) and visibility gate (`visible`) are unchanged |
| OPS-02 | Facts carry actor/purpose/outcome; denied reads stay concealed | unchanged `authorize`, `notFound`, `visible` and `event` helpers |

Acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical stored id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` uploads two files in one tenant, scans each with distinct detail text, and asserts each trail holds exactly that file's two events, that the two `detail` strings never cross trails, and that `events()` equals `view().events()`.

## Defect and minimal fix

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide ledger — after authorizing and visibility-checking only the named file. `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is keyed per organization, so this is a cross-file/cross-conversation leak **within one tenant**, not a cross-tenant leak. `AttachmentEvent.detail` carries scanner detail and void/rejection free text. The pre-existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only asserted `organizationId`, so it never caught the missing per-file filter while line coverage stayed at 100%. The service has no HTTP controller yet (latent entry point tracked as `AUD-20261119-03`).

Fix (in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`, method `events`):

```java
String fileId = current.id();
return store.events(actor.organizationId()).stream()
        .filter(fact -> fileId.equals(fact.attachmentId()))
        .toList();
```

Test added in `AttachmentAcceptanceTest.java`: `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (two files, distinct scanner detail, per-trail assertions, `events()==view().events()`).

## New/changed branches and their covering tests (AGENTS.md Coverage rule 6)

| New/changed branch | Test |
|---|---|
| `events()` filter predicate true (`fileId.equals(fact.attachmentId())`) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` — each file's own two facts are present; `view()` sibling assertions |
| `events()` filter predicate false | same test — file a's trail excludes the `detail-of-file-b` fact and vice versa (4 tenant facts, 2 per trail) |
| pre-existing `authorize` / `visible` / `notFound` paths | unchanged; existing `AttachmentAcceptanceTest` cases and full suite |

No threshold, exclusion or `@Disabled`/`.skip` was added; no existing test was weakened, deleted or skipped. No unreachable code was introduced.

## Entry point -> test trace

`AttachmentService.events(actor, id)` (public domain-service method; no HTTP controller yet — see `AUD-20261119-03`) -> `authorize(READ)` -> `load()` (canonical id, concealed 404) -> `visible()` -> `store.events(org)` -> `.filter(fileId.equals(fact.attachmentId()))` -> `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` asserts per-file facts and no cross-trail `detail` leakage.

## Commands actually run and results

Toolchain: Java 21.0.13 (Temurin), Maven 3.10.0, PostgreSQL accepting connections on 127.0.0.1:5432, `SMARTCLINIC_PG_ADMIN=postgres`.

Red (temporarily restored the buggy tenant-wide `events()` from `origin/main`, then ran the new test):
```
cd apps/api && mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test
```
Result: `Tests run: 1, Failures: 1` — `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443` (the whole 4-event tenant ledger was returned). Exit 1. The fix file was then restored from backup.

Green (fix restored):
```
cd apps/api && mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest test
```
Result: exit 0, all `AttachmentAcceptanceTest` tests pass.

Full canonical gate:
```
cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -B verify
```
Result: `BUILD SUCCESS`, exit 0. `Tests run: 2361, Failures: 0, Errors: 0, Skipped: 0`. `jacoco:check` — "All coverage checks have been met." OWASP Dependency-Check "Analysis Complete", SpotBugs check "BugInstance size is 0, Error size is 0, No errors/warnings found".

Coverage (100% gate verified on the touched file):
```
grep "AttachmentService" apps/api/target/site/jacoco/jacoco.csv
SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28
```
Interpretation (JA CoCo CSV columns: instruction missed/covered, branch missed/covered, line missed/covered, complexity missed/covered, method missed/covered): instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28 — **all 100%, zero missed**, including the new filter predicate's two branches.

Shared-file restoration: `git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md` then `cmp ...` exit 0 (byte-identical to `origin/main`).

Note on `scripts/check.sh` in this worker: it fails only on unrelated, pre-existing worker-environment limitations — (a) `ModuleNotFoundError: No module named 'yaml'` in `scripts/tests/test_swarm.py` / `test_fast_tier.py` / `test_workflow_concurrency.py`, (b) `test_agent_run` cases that require `gh`/git push and a passing CI check (denied/unavailable here), and (c) the documented OPS-CW-003-01 artifact where the worktree `opencode.json` is the CI profile under `skip-worktree` so `check-pack.py` reports it stale. With the committed local `opencode.json` profile swapped in temporarily, `check-pack.py` reported "Delivery tooling validated: 33 agents, 33 skills" and the policy checks passed; the remaining failures are environment-only and untouched by this Java-only change. The integrator/CI environment has PyYAML, `gh` and the committed profile, so these do not apply to the merge gate.

## Decisions / follow-ups

- The leak is latent until an HTTP entry point is wired for `AttachmentService`; that remains tracked as `AUD-20261119-03` and was not changed here (out of scope).
- Three stale notes files from prior fix attempts of this same story (`fix-20261120-143101.md`, `fix-20261120-144430.md`, `fix-20261120-150124.md`) remain in the branch as historical records; each is uniquely named, so they do not conflict. The authoritative notes for this run are this file.
