# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Integrator merge-conflict repair, fix attempt 1 of 3. No push, tag or `gh` call was made. Synthetic fixtures only — no patient data, credentials, tokens or real identifiers appear in the code, the tests or this note.

## Summary

The production fix and its regression test were already present in this branch from earlier attempts (commit `9dee672`). This run re-verified them from scratch (red → green, full 100% coverage gate), and removed the two artifacts that made the branch unmergeable: the forbidden edit to the shared `.agent/pr-notes.md` and a stale prior-attempt notes file. Only this run's notes file is added.

## Defect

`AttachmentService.events(actor, id)` authorized and visibility-checked the single file named by `id`, then returned `store.events(actor.organizationId()).stream().toList()` — the entire tenant-wide attachment event log. Its sibling `view(actor, id)` already filtered with `fileId.equals(fact.attachmentId())`. `AttachmentEvent.detail` carries scanner detail and void/rejection free text (MSG-07), so a caller authorized for one file received other files' event details across conversations in the same tenant. `InMemoryAttachmentStore.events(organizationId)` is keyed only by organization, so this is a cross-file / cross-conversation leak **within** a tenant, not a cross-tenant leak. The service has no HTTP controller yet (tracked separately as AUD-20261119-03, out of scope), so the leak is latent until an entry point is wired.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where proven |
|---|---|---|
| MSG-07 | Opening, viewing and downloading conversations/files is audited; a caller authorized for one file must not obtain another file's event detail (scanner detail, void/rejection free text) | `AttachmentService.events(actor, id)` narrows the tenant fact stream to the single visible `attachmentId`, exactly as `view(actor, id)` already did; regression test asserts neither file's detail leaks into the other's trail |
| ARC-07 | A conversation/message belongs to one recipient unit and one verified patient connection and carries a scoped fact trail | The per-file filter keeps one file's trail from crossing into another file in a different conversation in the same tenant |
| OPS-02 | Every sensitive read is authorized, visibility-checked and leaves a correctly scoped audit record | Unchanged: `authorize(...)` and `visible(...)` still run first and still append `ACCESS_DENIED` facts on denial; only the returned fact trail is narrowed |
| G3 | Messaging works with wrong-unit denial and safe unit retrieval | Existing `AttachmentAcceptanceTest` suite (29 tests) covers wrong-unit, wrong-patient, quarantine privacy and two-tenant concealment; all green |

## Change (inside the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments/AttachmentService.java` — `events(actor, id)` narrows the tenant event stream with `String fileId = current.id();` and `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view(...)`. No signature, authorization, visibility, ordering or immutability change; no new method or class.
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments/AttachmentAcceptanceTest.java` — added `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: creates two attachments in **one** tenant (ORG_A), gives each a distinct scan detail, then asserts each `events(a.id())` / `events(b.id())` returns exactly 2 facts, all bound to that attachment id, containing only that file's detail and never the other's, and that `events(...)` equals the sibling `view(...).events()` trail.

No migration was added or reserved; no coverage threshold, exclusion, `@Disabled`, `.skip` or `xit` was changed; no existing test was weakened, deleted or rewritten.

## Merge-conflict repair (the reason for this fix attempt)

The integrator could not merge this branch cleanly because it had edited the shared `.agent/pr-notes.md`, overwriting main's AUD-20261120-16 notes with a combined "merge resolution" document. AGENTS.md forbids editing `.agent/pr-notes.md` and requires notes to go only to this run's file. In this run:

- Restored `.agent/pr-notes.md` byte-for-byte from `origin/main` (`git checkout origin/main -- .agent/pr-notes.md`); `git diff origin/main -- .agent/pr-notes.md` is empty.
- Removed the stale `fix-20261120-172416.md` left by an earlier attempt on this branch; only this run's `fix-20261120-180204.md` is added.
- Retained the source fix and regression test unchanged.

After the commit, the branch diff against `origin/main` is exactly three files: the two scoped Java files plus this notes file.

## Tests written first — red → green (tool evidence)

Red — the regression test run against the original buggy one-liner (fix temporarily reverted from `AttachmentService.events`, then restored byte-for-byte from a backup):

```
mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
  -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test
```

Result: `EXIT_MARKER=1`, `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`.
`org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443` — the tenant-wide ledger of 4 facts was returned instead of file `a`'s 2 facts. This proves the test detects the cross-file leak.

Green — the same command against the committed fixed source: exit 0; Surefire `tests="1" errors="0" skipped="0" failures="0"`.

Targeted full class: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` — exit 0, 29 tests, 0 failures/errors/skipped.

## Coverage — 100% gate (tool evidence)

Command actually run locally (PostgreSQL accepting on 127.0.0.1:5432, JDK 21.0.13, `SMARTCLINIC_PG_ADMIN=postgres`):

```
export SMARTCLINIC_PG_ADMIN=postgres
mvn -B -f apps/api/pom.xml -Pno-security verify > /tmp/opencode/aud10-verify.log 2>&1
VERIFY_EXIT=0
```

Result: `VERIFY_EXIT=0`, `--- jacoco:0.8.16:check (check) @ smartclinic-api --- All coverage checks have been met.` and `BUILD SUCCESS`. The JaCoCo check is build-failing at 100% for INSTRUCTION, LINE, BRANCH, METHOD and CLASS (BUNDLE rule in `apps/api/pom.xml`, lines 89-107). No threshold was lowered and no exclusion added.

- Surefire totals from this run (211 `TEST-*.xml` reports): **tests=2370, failures=0, errors=0, skipped=0**.
- `AttachmentAcceptanceTest`: **29 tests, 0 failures, 0 errors, 0 skipped**.
- JaCoCo row for the only production file changed (`apps/api/target/site/jacoco/jacoco.csv`):

```
GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
SmartClinic API,com.aurevia.smartclinic.messaging.attachments,AttachmentService,0,963,0,86,0,168,0,71,0,28
```

Every metric for `AttachmentService` is **0 missed**: instructions 0/963, branches 0/86, lines 0/168, complexity 0/71, methods 0/28, classes 0/1 — 100% on every file changed.

Evidence source: executed tool commands and machine-generated Surefire/JaCoCo XML/CSV. Synthetic fixtures only.

## New/changed branches and their covering tests (AGENTS.md coverage rule 6)

The added `.filter(fact -> fileId.equals(fact.attachmentId()))` is a boolean expression with no `if/else`, ternary, `switch` or `catch`, so it adds **no Java branch**; JaCoCo reports `BRANCH_MISSED=0` for `AttachmentService` with the new test included.

| Production element | Test |
|---|---|
| `events(...)` filter keeps facts whose `attachmentId` equals the requested file | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` — `allMatch` on `a.id()`/`b.id()` for both trails and `view(...).events()` equality |
| `events(...)` filter drops facts belonging to another file in the same tenant | same test — `noneMatch("detail-of-file-b")` in `aTrail` and `noneMatch("detail-of-file-a")` in `bTrail` |
| Existing `authorize`, `load`, `visible` and not-found paths | unchanged, covered by the existing `AttachmentAcceptanceTest` suite (29 tests) |

## Entry point → test trace

No HTTP controller exists yet (AUD-20261119-03), so the entry point is the domain service: `AttachmentService.events(actor, id)` → `authorize(READ, id)` → `load(org, id)` → `visible(actor, current)` → `store.events(org)` filtered by `current.id()`. `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` drives that path with two same-tenant attachments and asserts per-file scoping; `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` still asserts tenant concealment (`organizationId`) and is unchanged.

## Defects found / decisions

- Defect fixed: the cross-file / cross-conversation event leak (regression test added, red→green proven, 100% coverage confirmed).
- No new defect found; no spec/task disagreement; no missing decision or evidence for this story.
- Merge blocker fixed: removed the forbidden shared-file edit and the stale notes file, so the branch diff is clean against `origin/main`.
- Decisions: keep the fix in the service (mirroring `view`) rather than changing the store interface; the store remains per-organization and the per-file narrowing is a read concern owned by the service. No migration needed. No clinical or real-patient decision is required; all fixtures are synthetic.

## Policy checks actually run

- `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` — all exit 0.
- `python3 -I -m unittest discover -s scripts/tests -q` — `Ran 271 tests`, `FAILED (failures=9, errors=10)`. These are pre-existing, environment-only failures in `test_agent_run.py`, `test_swarm.py`, `test_check_pack.py` and import failures for `yaml` (`test_fast_tier`, `test_workflow_concurrency`); none touches `messaging/attachments` or this change. Recorded, not fixed here because they are outside scope.
- `check-pack.py` — exit 1 in this cloud worker: `opencode.json is stale: run scripts/gen-opencode.py`. This is the documented CI-profile-under-`skip-worktree` false positive (OPS-CW-003-01 in MEMORY.md): `agent-run.sh` copies the CI `opencode.json` into the worktree, so local pack checks judge the CI profile, not the committed local profile. No agent, skill or `opencode.json` file was changed by this run.

## Evidence sources

- Tool evidence: executed `mvn`/`python3` commands and their outputs; machine-generated Surefire and JaCoCo reports.
- Reviewer evidence: bounded read-only `smartclinic-senior-backend` subagent review completed — **APPROVE, no blocking findings**. It independently checked source and confirmed: (1) `events()` is now equivalent to `view()` in authorization/visibility/filtering for every READ-capable role, filtering on the authoritative `current.id()` rather than the raw caller string, with no alternate role path; (2) the regression test genuinely detects the leak (two same-tenant attachments, distinct details, `expected 2 but was 4` under the buggy code) and pins `events()` to `view().events()`; (3) no new JaCoCo branch (a `.filter` method call, not `if`/ternary/switch) and the pre-existing visibility guard is already covered both ways; (4) no ordering, authorization, concealment or immutability regression, and `View` still defensively copies. The reviewer explicitly did not re-execute `mvn verify` and relied on the orchestrator's tool evidence. Skills loaded as instructions: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`.
- Simulated-user / human evidence: none. This is a contained backend fix with no UI or clinical decision; no real-patient or human feedback is claimed.

## Non-blocking improvement for the backlog (not changed here)

The reviewer noted that `view()` and `events()` now duplicate the same five-line trail construction and suggested a private `trail(actor, current)` helper to prevent future divergence. This is a **want, not a need** for this story: it would rewrite a second, already-green method and require re-proving 100% coverage for no additional acceptance behavior. The task requires the smallest scoped fix, so it was not taken here. Recorded for the orchestrator/Product Owner to schedule if desired; no TODO.md edit was made by this worker.

Nothing is left open for this story.
