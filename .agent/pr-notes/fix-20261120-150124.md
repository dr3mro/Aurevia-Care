# AUD-20261120-10 (repair run `fix-20261120-150124`) — Attachment event history returned the whole tenant ledger

Branch: `swarm/AUD-20261120-10-289`. This is a **merge-conflict repair (fix attempt 1 of 3)**: the integrator reported that merging this branch into `main` succeeded but checks failed. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials or real identifiers appear in code, tests or notes.

## Root cause of the repeated merge failure and the repair performed

`origin/main` is exactly the merge base of this branch (`742f2bb`), so the merge result equals this branch's tree. The actual code fix and its regression test were already present and correct (commit `3585d30`), but a long tail of repair commits repeatedly **overwrote the forbidden shared `.agent/pr-notes.md`** — which on `main` holds story `AUD-20261120-13`'s notes — with this story's notes. That shared file is edited by every parallel swarm worker and was the git merge-conflict source; the latest commit `537085c` re-broke a prior restoration.

Repair in this run:
- Restored `.agent/pr-notes.md` byte-for-byte to `origin/main`'s version (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`; `cmp` exit 0 against `origin/main`). This removes the branch's illicit change to the shared file so the integrator's merge is clean. No tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`) or `docs/swarm` file was touched.
- This run's notes go **only** to this file, per AGENTS.md (`one file per run`; never the shared `.agent/pr-notes.md`).

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Every attachment fact is bound to one file/conversation; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events(actor, id)` now filters the tenant-wide fact stream by `attachmentId`, exactly as its sibling `view()` does |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay inside that file's trail | same filter; tenant scoping (`organizationId`), canonical-id gate (`load`) and visibility gate (`visible`) are unchanged |
| OPS-02 | Facts carry actor/purpose/outcome; denied reads stay concealed | unchanged `authorize`, `notFound`, `visible` and `event` helpers |

Acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical stored id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` uploads two files in one tenant, scans each with distinct detail text, and asserts each trail holds exactly that file's two events, that the two `detail` strings never cross trails, and that `events()` equals `view().events()`.

## Defect and minimal fix

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide ledger — after authorizing and visibility-checking only the named file. `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is keyed per organization, so this is a cross-file/cross-conversation leak **within one tenant**, not a cross-tenant leak. `AttachmentEvent.detail` carries scanner detail and void/rejection free text. The pre-existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only asserted `organizationId`, so it never caught the missing per-file filter while line coverage stayed at 100%. The service has no HTTP controller yet (latent entry point tracked as `AUD-20261119-03`).

Fix (already in the branch tree, commit `3585d30`, verified unchanged this run) in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`, method `events`:

```java
String fileId = current.id();
return store.events(actor.organizationId()).stream()
        .filter(fact -> fileId.equals(fact.attachmentId()))
        .toList();
```

New test (same commit) in `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java`: `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`.

### New/changed branches and their tests

The added `.filter(fact -> fileId.equals(fact.attachmentId()))` predicate is a single expression — no `if`, ternary, `&&`, `||`, `switch` or `catch` — so it introduces no new conditional branch in the service. Both predicate outcomes (matching file, non-matching file) are asserted by `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: each trail contains 2 matching events and excludes the other file's 2 events and `detail` text. The surrounding `authorize`, `load`/`notFound` and `visible` branches are pre-existing and covered by the other 28 tests in `AttachmentAcceptanceTest`. No coverage threshold, exclusion or skip was changed; no test was disabled, skipped or weakened.

## Entry-point-to-test trace

There is no HTTP controller for `AttachmentService` (tracked as `AUD-20261119-03`), so the entry point is the domain method `AttachmentService.events(actor, id)`. Trace: `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` → `service.upload` (A2 grant binding) → `service.scan` (records per-file facts with distinct detail) → `service.events(patientA, a.id())` → `authorize` → `load` → `visible` → `store.events(org).stream().filter(fileId.equals(fact.attachmentId())).toList()` → assertions on trail size, `attachmentId`, `detail` and equality with `view().events()`.

## Exact commands run and results (all tool evidence, synthetic data)

- `git status --short` → clean tree on `swarm/AUD-20261120-10-289` at start.
- `git merge-base origin/main HEAD` → `742f2bb...`; `git rev-list --left-right --count origin/main...HEAD` → `0 37`; `git diff --name-status <base> HEAD` → modified `.agent/pr-notes.md`, added two older per-run note files, modified `AttachmentService.java` and `AttachmentAcceptanceTest.java`.
- Code diff review (`git diff <base> HEAD -- <two Java files>`) → confirmed the filter fix and the new test were already present and correct.
- `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-pack.py` → exit 1, `opencode.json is stale`. Verified this is the documented cloud-worker CI-profile artifact (OPS-CW-003-01): `git ls-files -v opencode.json` → flag `S` (skip-worktree) and the worktree file equals `.github/opencode.ci.json`, not the committed local profile. `opencode.json` is out of scope and was NOT edited.
- `mvn -q -B -f apps/api/pom.xml -Pfast -Pno-security test` → exit 0; `AttachmentAcceptanceTest` 29 tests, 0 failures/errors/skips; module total 1915 tests, 0 failures/errors/skips (fast tier, integration excluded).
- `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` → exit 0 (full suite, including the real-PostgreSQL integration tier; OWASP/SpotBugs skipped here because the integrator/Delivery runs them). JaCoCo `check` passed with all BUNDLE limits at minimum 1.00.
- JaCoCo report `apps/api/target/site/jacoco/jacoco.xml` read for the changed production file: `com/aurevia/smartclinic/messaging/attachments/AttachmentService` → INSTRUCTION 963/963, BRANCH 86/86, LINE 168/168, COMPLEXITY 71/71, METHOD 28/28, CLASS 1/1, all missed=0, ratio 1.000. Whole bundle: INSTRUCTION 90701/90701, BRANCH 6407/6407, LINE 15736/15736, METHOD 3815/3815, CLASS 785/785, missed=0.
- `git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md` then `cmp -s` against that blob → exit 0 (shared file restored).

## Decisions and residual notes

- The code fix is already correct and fully covered; no production/test code change was needed in this repair run. The only required change is restoring the shared file that keeps causing the merge conflict.
- `check-pack.py`/`gen-opencode.py --check` staleness is a worker-environment measurement artifact, not a defect in this branch; the integrator/CI judges the committed profile. Left untouched per scope rules.
- OWASP and SpotBugs are not run here (`-Pno-security`); the integrator/Delivery runs them on the merge result.
- Evidence source: tool-generated (git, Maven, JUnit, JaCoCo) with synthetic fixtures. No reviewer, simulated user or human participant evidence is involved in this containment fix; no clinical decision or real-patient approval gate applies.
