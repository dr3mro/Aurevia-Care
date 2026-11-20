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
