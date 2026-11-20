# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289` (fix attempt 1 of 3 after an integrator merge conflict). No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, tests or this note.

## Summary

The production fix and its regression test were already present on this branch from earlier attempts. This run re-verified them from scratch (red → green, full 100% JaCoCo gate), and repaired the actual merge blocker: the HEAD commit `2e8ef29` ("Agent changes for AUD-20261120-10") had overwritten the shared `.agent/pr-notes.md` (replacing main's AUD-20261120-16 notes). That shared-file edit is the classic cause of the integrator's unresolvable merge conflict and is forbidden by AGENTS.md. It is restored byte-for-byte from `origin/main`. One stale prior-attempt notes file was removed; only this run's notes file is added. The two scoped Java changes are kept unchanged.

## Defect

`AttachmentService.events(actor, id)` authorized and visibility-checked the single file named by `id`, then returned `store.events(actor.organizationId()).stream().toList()` — the entire tenant-wide attachment event log. Its sibling `view(actor, id)` already filtered with `fileId.equals(fact.attachmentId())`. `AttachmentEvent.detail` carries scanner detail and void/rejection free text (MSG-07), so a caller authorized for one file received other files' event details across conversations **within the same tenant**. `InMemoryAttachmentStore.events(organizationId)` is keyed only by organization, so this is a cross-file / cross-conversation leak, not a cross-tenant leak. The service has no HTTP controller yet (tracked separately as AUD-20261119-03, out of scope), so the leak is latent until an entry point is wired.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where proven |
|---|---|---|
| MSG-07 | Opening, viewing, downloading and auditing files must not expose another file's detail to a caller authorized for one file | `events(actor, id)` narrows the tenant fact stream to the single visible `attachmentId`, exactly as `view(actor, id)` already did; regression test asserts neither file's detail leaks into the other's trail |
| ARC-07 | A conversation/message belongs to one verified patient connection and carries a scoped fact trail | The per-file filter stops one file's trail crossing into another file in a different conversation in the same tenant |
| OPS-02 | Sensitive reads are authorized, visibility-checked and leave a correctly scoped audit record | Unchanged: `authorize(...)` and `visible(...)` still run first and still append `ACCESS_DENIED` facts on denial; only the returned fact trail is narrowed |
| G3 | Messaging denial and safe retrieval | Existing `AttachmentAcceptanceTest` suite (29 tests) covers wrong-unit, wrong-patient, quarantine privacy and two-tenant concealment; all green |

## Change (inside the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java` — `events(actor, id)` narrows the tenant event stream with `String fileId = current.id();` and `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view(...)`. No signature, authorization, visibility, ordering or immutability change; no new method or class.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java` — added `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: creates two attachments in **one** tenant (ORG_A), gives each a distinct failed-scan detail, then asserts each `events(a.id())` / `events(b.id())` returns exactly 2 facts, all bound to that attachment id, containing only that file's detail and never the other's, and that `events(...)` equals the sibling `view(...).events()` trail.

No migration added or reserved; no coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` changed; no existing test weakened, deleted or rewritten. The declared `scope` covers the mirror test package alongside the production package, as the story's acceptance explicitly requires a new test.

## Merge-conflict repair

Root cause: an earlier attempt edited the shared `.agent/pr-notes.md`, which parallel workers also write, so the integrator could not auto-merge. In this run:

- Restored `.agent/pr-notes.md` byte-for-byte from `origin/main` (`git checkout origin/main -- .agent/pr-notes.md`); `git diff origin/main -- .agent/pr-notes.md` is empty.
- Removed the stale `fix-20261120-180204.md` left by a prior attempt on this branch; only `fix-20261120-181315.md` is added.
- Confirmed the final diff against `origin/main` contains only the two scoped Java files plus this notes file.

## Tests written first — red → green (tool evidence)

Red — the regression test run against the original buggy one-liner (fix temporarily reverted from `AttachmentService.events` from `origin/main`, then restored byte-for-byte):

```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test
```

Result: `EXIT=1`, `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`.
`org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443` — the tenant-wide ledger of 4 facts was returned instead of file `a`'s 2 facts. This proves the test detects the cross-file leak.

Green — same command after restoring the fix, extended to the whole class:

```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test
```

Result: `EXIT=0`; Surefire `tests="29" errors="0" skipped="0" failures="0"`.

## Coverage — 100% gate (tool evidence)

Command actually run locally (PostgreSQL accepting on 127.0.0.1:5432, JDK 21.0.13, `SMARTCLINIC_PG_ADMIN=postgres`):

```
export SMARTCLINIC_PG_ADMIN=postgres
mvn -B -f apps/api/pom.xml -Pno-security verify > /tmp/opencode/aud10/verify.log 2>&1
VERIFY_EXIT=0
```

Result: `VERIFY_EXIT=0`, `Tests run: 2370, Failures: 0, Errors: 0, Skipped: 0`, `--- jacoco:0.8.16:check --- All coverage checks have been met.`, `BUILD SUCCESS`. JaCoCo is build-failing at 100% for INSTRUCTION, LINE, BRANCH, METHOD and CLASS (BUNDLE rule, `apps/api/pom.xml` lines 89–107). No threshold was lowered, no exclusion added.

JaCoCo row for the only production file changed (`apps/api/target/site/jacoco/jacoco.csv`):

```
GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28
```

Every metric is 0 missed: instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28, classes 0/1 — 100% on the only file changed.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

The added `.filter(fact -> fileId.equals(fact.attachmentId()))` is a boolean lambda with no `if/else`, ternary, `switch` or `catch`, so it adds **no Java branch**; JaCoCo reports `BRANCH_MISSED=0` for `AttachmentService` with the new test included.

| Production element | Test |
|---|---|
| `events(...)` filter keeps facts whose `attachmentId` equals the requested file | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` — `allMatch` on `a.id()`/`b.id()` for both trails and `view(...).events()` equality |
| `events(...)` filter drops facts belonging to another file in the same tenant | same test — `noneMatch("detail-of-file-b")` in `aTrail` and `noneMatch("detail-of-file-a")` in `bTrail` |
| Existing `authorize`, `load`, `visible` and not-found paths | unchanged; covered by the existing `AttachmentAcceptanceTest` suite (29 tests) |

## Entry point → test trace

No HTTP controller exists yet (AUD-20261119-03), so the entry point is the domain service: `AttachmentService.events(actor, id)` → `authorize(READ, id)` → `load(org, id)` → `visible(actor, current)` → `store.events(org)` filtered by the authoritative `current.id()`. `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` drives that path with two same-tenant attachments and asserts per-file scoping; `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` still asserts tenant concealment (`organizationId`) and is unchanged.

## Defects found / decisions

- Defect fixed: cross-file / cross-conversation event leak (regression test added, red→green proven, 100% coverage confirmed).
- Merge blocker fixed: forbidden shared-file edit reverted and stale notes removed, so the branch diff against `origin/main` is clean.
- No new defect found; no spec/task disagreement; no missing decision or evidence for this story.
- Decision: keep the fix in the service (mirroring `view`) rather than changing the store interface; the store remains per-organization and per-file narrowing is a read concern owned by the service. No migration needed. No clinical or real-patient decision is required; all fixtures are synthetic.
- Non-blocking improvement recorded, not taken: `view()` and `events()` now build the same trail in two places; a private `trail(actor, current)` helper could prevent future divergence, but it is outside the smallest scoped fix and is left to the orchestrator/Product Owner.

## Policy checks actually run (tool evidence)

- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 271 tests`, `FAILED (failures=9, errors=10)`. These are pre-existing, environment-only failures in `test_agent_run.py`, `test_swarm.py`, `test_check_pack.py` and import failures for `yaml` in `test_fast_tier` / `test_workflow_concurrency`. None touches `messaging/attachments` or this change; recorded, not fixed here because they are outside scope (and were present before this change).
- `bash scripts/quick-check.sh` → exit 1 at `check-pack: opencode.json is stale`. This is the documented CI-profile-under-`skip-worktree` false positive (OPS-CW-003-01, MEMORY.md): `git ls-files -v opencode.json` returns `S`; `git show HEAD:opencode.json` was verified to equal `gen-opencode.py`'s generated **local** profile, while the worktree file equals the generated **CI** profile. No agent, skill or `opencode.json` file was changed by this run.

## Evidence sources

- Tool evidence: executed `mvn`/`python3` commands and their outputs; machine-generated Surefire and JaCoCo XML/CSV.
- Reviewer evidence: bounded read-only `smartclinic-senior-backend` specialist review (**APPROVE, no blocking findings**). It independently confirmed: (1) `events()` and `view()` are structurally identical and filter on the authoritative `current.id()` with no alternate role path; (2) the regression test fails on vulnerable code (`expected <2> but was <4>`) and asserts both per-file scoping directions plus `view()` parity; (3) the lambda adds no JaCoCo branch and authorization/visibility/ordering/immutability are unchanged; (4) only the two allowed files changed. Simulated reviewer — source reads and greps only, corroborating the tool evidence structurally rather than re-running builds.
- Simulated-user / human evidence: none. This is a contained backend fix with no UI or clinical decision; no real-patient or human feedback is claimed.
