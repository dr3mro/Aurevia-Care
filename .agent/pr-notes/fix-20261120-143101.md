# AUD-20261120-10 (repair run `fix-20261120-143101`) — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. This is a **merge-conflict repair** (story attempt 1 of 3): the integrator merged this branch into `main`, the merge succeeded but `scripts/check.sh` failed. Root cause was **not** the code fix — it was prior attempts on this branch overwriting the forbidden shared `.agent/pr-notes.md` and adding two misnamed note files. No push, tag or `gh` was used. Synthetic fixtures only; no patient data or credentials appear in this note, the tests or the code.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Every attachment event is bound to one file/conversation; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events` now filters the tenant-wide fact stream by `attachmentId`, matching its sibling `view()` |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay in that file's trail | same filter; tenant scoping (`organizationId`), visibility and canonical-id gates are preserved |
| OPS-02 | Facts carry actor/purpose/outcome; a denied read stays concealed | unchanged `authorize`, `visible`, `notFound` and `event` helpers |

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

It reuses the canonical stored id (not the raw request parameter), the same predicate as `view()`, and no interface, store, schema or coverage-threshold change. Test added in `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`.

## Merge-conflict repair performed (tool evidence)

Prior attempts on this branch had:
1. overwritten `.agent/pr-notes.md` (which holds `AUD-20261120-13` notes on `main`) with this story's notes — the actual merge-conflict source, because every parallel swarm worker touches that one shared file; and
2. added `.agent/pr-notes/fix-20261120-135546.md` and `.agent/pr-notes/fix-20261120-140846.md` — note files named for other runs, in violation of the one-file-per-run rule.

Repair (no push, tag or `gh`):
- `git restore --source=origin/main --staged --worktree -- .agent/pr-notes.md` — shared file restored byte-identically to `main`/merge base, so a three-way merge takes `main`'s version cleanly.
- `git rm -f .agent/pr-notes/fix-20261120-135546.md .agent/pr-notes/fix-20261120-140846.md` — stray artifacts of this same story's earlier attempts removed.
- This file, `.agent/pr-notes/fix-20261120-143101.md`, is the sole new note file for this run.

`git merge-base HEAD origin/main` = `742f2bb`; branch is ahead of `main` with no new `main` commits, so the shared-file edit was the only conflict source. Final diff vs `origin/main` is exactly: `M AttachmentService.java`, `M AttachmentAcceptanceTest.java`, `A .agent/pr-notes/fix-20261120-143101.md`.

## Tests written first — red then green (executed tool evidence)

- **RED** — temporarily installed the buggy `origin/main` `events()` body, then from `apps/api`:
  `mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → **exit 1**, `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant:443 expected: <2> but was: <4>` (tenant ledger returned 4 facts across two files instead of the requested file's 2). Fixed file restored byte-identically from a backup afterwards.
- **GREEN (targeted)** — fixed file in place:
  `mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` → **exit 0** (all 29 acceptance tests pass).
- **GREEN (fast unit tier)** — `mvn -q -B -Pno-security,fast test` → **exit 0**; 169 surefire reports, **1915 tests, 0 failures, 0 errors, 0 skipped**.

## Coverage — 100% gate (executed tool evidence)

Command (local PostgreSQL 17.12 accepting connections on 127.0.0.1:5432, `SMARTCLINIC_PG_ADMIN=postgres`, Java 21.0.13, Maven 3.10.0):

```
cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify
```

→ **exit 0**. `verify` runs unit + PostgreSQL integration tiers and the build-failing JaCoCo `check` (BUNDLE, 1.00 minimum on INSTRUCTION/LINE/BRANCH/METHOD/CLASS), so the project-wide 100% gate passed. `target/site/jacoco/jacoco.csv` row for the one changed production class:

| Class | Instr missed/covered | Branch missed/covered | Line missed/covered | Method missed/covered | Class missed |
|---|---|---|---|---|---|
| `messaging.attachments.AttachmentService` | 0 / 963 | 0 / 86 | 0 / 168 | 0 / 28 | 0 |

The integrator runs the database/integration tier in CI; I also ran it locally to prove the coverage gate. No threshold, exclusion, `@Disabled`, `.skip` or `xit` was added; no existing test was weakened, deleted or skipped.

### New/changed branch → test (AGENTS.md coverage rule 6)

The fix introduces **no new Java conditional branch**: it replaces a terminal `.toList()` with a `Stream.filter` whose lambda body is a single `String.equals` call. JaCoCo branch count is unchanged at 86, all covered. The lambda predicate is exercised on both sides by the new test: true for file `a`'s own facts and false for file `b`'s facts (and symmetrically for `b`), asserted via `allMatch`, `anyMatch` and `noneMatch` on `detail`. The denial/visibility paths (`authorize`, `visible`, `notFound`) are unchanged and remain covered by existing tests (`quarantineIsPrivateToUploaderAndViewsAreTenantConcealed`, `twoTenantsWithIdenticalLocalIdentifiersStayIsolated`).

## Static analysis and policy checks (executed tool evidence)

- `cd apps/api && mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → exit 0.
- `python3 -I scripts/check-names.py` → exit 0 (no ID-style Java package or folder introduced; reused `messaging.attachments`).
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (no migration added; no reserved version used).

## Unrelated environment failures (not caused by this change)

`python3 -I -m unittest discover -s scripts/tests -q` → exit 1, **271 tests, 9 failures, 10 errors**. All are in `test_agent_run`, `test_swarm`, `test_fast_tier`, `test_workflow_concurrency` and `test_check_pack`; this change touches no file under `scripts/` or any Python source. Causes are environmental: `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent) for the workflow/swarm import errors, and the documented OPS-CW-003-01 artifact for `check-pack.py` (`opencode.json is stale`: the worktree holds the CI profile under `skip-worktree`; committed HEAD matches `origin/main`). `test_agent_run` failures require the GitHub CLI/network, which this worker is forbidden to call. These are recorded here for transparency and left untouched as out of scope.

## Decisions and evidence sources

- Decision: keep the already-correct code fix and regression test; the integrator failure was purely shared-file hygiene, fixed by restoring `.agent/pr-notes.md` to `main` and removing the two stray note files. No production behavior beyond the one-line filter is changed.
- Decision: no migration, no new package, no interface change, no controller (AUD-20261119-03 remains the owner of the entry point).
- Evidence source: **tool evidence** (all commands above executed locally by this worker). **Simulated review only**: the `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-spring-security`, `smartclinic-senior-java-build`, `smartclinic-test-engineer` and `smartclinic-qa-reviewer` skills were loaded and their guidance applied; no independent subagent review, simulated user or human/clinical participant was used. No human pilot feedback.
- No decision or evidence is missing for the code fix; the only missing evidence is PyYAML/network-dependent tooling, which is unrelated to the touched scope and noted above.
