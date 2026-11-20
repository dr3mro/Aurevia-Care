# AUD-20261120-10 (repair run `fix-20261120-155612`) — Attachment event history was tenant-wide, not per file

Branch: `swarm/AUD-20261120-10-289`. Merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only: no patient data, credentials, tokens or real identifiers appear in code, tests or notes.

## Evidence sources

- **Tool (executed commands):** every command and result below was run in this worker (`java -version`, `mvn -version`, `pg_isready`, `mvn ... test`, `mvn ... verify`, JaCoCo CSV parse, `git show`/`cmp`).
- **Reviewer:** none engaged this run. The scoped Java fix is unchanged from the prior, already-correct attempt; the residual failure was a git/tracker-hygiene issue, not a design change.
- **Simulated user / human participant:** none. `AttachmentService` has no HTTP controller (latent entry point tracked separately as AUD-20261119-03), so no clinician/patient flow could be exercised.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Every attachment fact stays bound to one file/conversation. A caller authorized for one file must not receive another file's scanner detail or void/rejection free text. | `AttachmentService.events(actor, id)` filters the tenant-wide fact stream by `attachmentId`, exactly as its sibling `view()` does. |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay in that file's trail. | Same filter. Tenant scoping (`organizationId`), canonical-id gate (`load`) and visibility gate (`visible`) are unchanged. |
| OPS-02 | Facts carry actor/outcome; denied reads stay concealed. | Unchanged `authorize`, `notFound`, `visible` and `event` helpers. |

Acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical stored id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` uploads two files in one tenant, scans each with distinct detail text, and asserts each trail holds exactly that file's two events, that the two `detail` strings never cross trails, and that `events()` equals `view().events()`.

## Defect and minimal fix (scoped production change)

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide ledger — after authorizing and visibility-checking only the named file. `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is keyed per organization, so this is a cross-file/cross-conversation leak **within one tenant**, not a cross-tenant leak. `AttachmentEvent.detail` carries scanner detail and void/rejection free text. The pre-existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only asserted `organizationId`, so it never caught the missing per-file filter while line coverage stayed at 100%.

Fix, in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`, method `events`:

```java
String fileId = current.id();
return store.events(actor.organizationId()).stream()
        .filter(fact -> fileId.equals(fact.attachmentId()))
        .toList();
```

No migration was used. No coverage threshold, exclusion or skip was changed. No existing test was weakened, deleted or skipped. No new production branch is introduced that is not already covered: the new `.filter` lambda is exercised by both the matching-file and non-matching-file events in the regression test (and the `view()` sibling already covered the identical predicate).

## Failing test first (red → green)

- **Red command:** `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` after temporarily reverting the `events()` filter to the tenant-wide query. Result: `Tests run: 1, Failures: 1` — `expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443` (the leaked trail contained both files' events). Exit 1.
- **Green command:** the same command after restoring the fix. Result: exit 0, 0 failures.
- The production file was restored from a byte-for-byte backup; `git diff` confirmed the working tree returned to the HEAD (fixed) state before the full checks.

## Commands run and final results

- `java -version` → openjdk 21.0.13 LTS; `mvn -version` → Apache Maven 3.10.0; `pg_isready -h 127.0.0.1 -p 5432` → accepting connections (exit 0).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AttachmentAcceptanceTest test` → exit 0. Surefire report: `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`.
- Fast unit suite `mvn -q -B -f apps/api/pom.xml -Pfast,no-security test` → exit 0. Aggregated Surefire reports: **1915 tests, 0 failures, 0 errors, 0 skipped**.
- Coverage command `mvn -q -B -f apps/api/pom.xml -Pno-security verify` → exit 0. This runs all unit + integration tests and the build-failing JaCoCo 100% bundle gate (instruction/line/branch/method/class, minimum 1.00 in `apps/api/pom.xml`). JaCoCo `target/site/jacoco/jacoco.csv` for the touched class: `AttachmentService: INSTRUCTION 0/963 missed, LINE 0/168 missed, BRANCH 0/86 missed, METHOD 0/28 missed` — **100% on every counter for the only production file changed**.
- Static/dependency scans (OWASP, SpotBugs) were deliberately skipped with `-Pno-security`; they are owned by the integrator's full gate, as recorded in `scripts/worker-check.sh` and the task brief. No threshold, exclusion or skip was added.

## Root cause of the repeated integrator merge failure and the repair performed

`origin/main` is exactly this branch's merge base (`742f2bb`), so the code diff is a clean two-file change: `AttachmentService.java` (+ the filter) and `AttachmentAcceptanceTest.java` (+ the regression test). The integrator conflict was **not** in the scoped Java code: a long tail of repair commits repeatedly rewrote the **forbidden shared** `.agent/pr-notes.md`, which on `main` holds story `AUD-20261120-13`'s notes and is touched by every parallel swarm worker. That shared-file churn is the git merge-conflict source.

Repair this run:
- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main`'s version with `git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`; `cmp` exit 0 against `origin/main`. The branch now carries **no modification** to that shared file relative to the merge base, so the integrator merge has nothing to conflict on there.
- Kept the scoped production fix and its regression test unchanged (verified red → green above).
- This run's notes go **only** to this per-run file, per AGENTS.md ("one new file per run"; never create, edit or commit the shared `.agent/pr-notes.md` or any other file directly under `.agent/`).
- No tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`) and no `docs/swarm/` file was touched. No migration version was used.

## Defects found / decisions

- Defect: the `events()` query leaked other files' event detail within a tenant. Fixed by the per-file filter; regression test added.
- Decision: restore the shared `.agent/pr-notes.md` to `origin/main` rather than write this run's notes there — this removes the forbidden modification that repeatedly caused the integrator conflict, while keeping evidence in the dedicated per-run file.
- Observation (out of scope, no change made): `AttachmentService` still has no HTTP controller (AUD-20261119-03); the leak is latent until that entry point is wired. Records were not touched.
- Pre-existing tooling failures (not addressed, out of scope): `python3 -I -m unittest discover -s scripts/tests -q` exited 1 with 9 failures / 10 errors in `test_agent_run`, `test_swarm`, `test_check_pack` and two import-failed loader stubs (`test_fast_tier`, `test_workflow_concurrency`). `git diff --quiet origin/main HEAD -- scripts` exits 0 (scripts byte-identical to `main`), so these failures predate this branch and are environmental (worker git-ref state and the CI `opencode.json` profile under skip-worktree, per MEMORY.md), not caused by this change. `python3 -I scripts/check-docs.py` exited 0.

## Entry-point-to-test trace

`AttachmentService.events(actor, id)` → `authorize(READ)` → `load(org, key(id))` → `visible(actor, attachment)` → `store.events(org).stream().filter(fileId.equals(fact.attachmentId()))` → `List<AttachmentEvent>`. Test `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` drives this path with two uploads and two scans in one tenant and asserts per-file trails.
