# AUD-20261120-10 — Attachment event history per-file scope — integrator merge-conflict repair (fix 1/3)

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit opening/viewing/sending/downloading of conversations and files; a caller authorized for one file must not receive another file's events or free-text detail | `AttachmentService.events(actor, id)` filters the tenant fact stream by the requested file's canonical `attachmentId`, exactly like its sibling `view()` |
| ARC-07 | Store each attachment event bound to one conversation/sequence; commit publication, audit and queue atomically; no cross-file trail leak inside a tenant | `AttachmentEvent.attachmentId` binds each fact to one file; `events()` no longer exposes other files' `detail` |
| Story acceptance | `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id; a test creates two attachments in one tenant and asserts each trail contains only that file's events | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

Scope declared by the story: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and
`apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new
folder or package; names stay business-domain (`messaging.attachments`). Only synthetic fixtures.

## Root cause of the integrator failure (defect found)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks
failed)". The in-scope Java fix and its regression test were already correct on this branch before this
repair attempt (first introduced in commit `9dee672`):

- `AttachmentService.events()` authorizes, loads, checks visibility, captures `String fileId = current.id();`
  then filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`.
  The original defect returned the whole tenant-wide stream (`store.events(organizationId()).stream().toList()`),
  so a caller authorized for one file received every other file's `detail` (scanner detail and void/rejection
  free text) across conversations in the same tenant.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in
  one tenant with distinct scanner details and asserts per-file trails, absence of the other file's detail, and
  `events() == view().events()` parity.

The real cause of the merge failure was a **worker-owned file hygiene violation**, not the Java change: the
prior agent repeatedly wrote this story's notes into the **shared** `.agent/pr-notes.md` (commits `2f261c4`,
`50b1a6c`, `49b84fd`, `cf6851a`, `0ab5339`, and finally `c4187d1`). Parallel workers (e.g. AUD-20261120-13,
integrated in `742f2bb`) also edit that shared file, so the integrator's merge conflicted there. AGENTS.md and
the shared-knowledge loop forbid any worker from creating, editing or committing `.agent/pr-notes.md`; notes
must go to `.agent/pr-notes/<task-id>.md`. The prior "drop/restore shared notes edit" commits oscillated, and
each "Agent changes" step re-introduced the shared-file write.

## Fix applied in this repair

1. Restored `.agent/pr-notes.md` to the exact content from `origin/main` (`git restore --source=origin/main`).
   The branch no longer modifies the shared file, so the integrator merge is conflict-free.
2. Retained the correct, already-committed Java fix and regression test unchanged.
3. Wrote this repair's evidence to its own allowed file, `.agent/pr-notes/fix-20261120-124105.md`.

No production logic was rewritten, no migration added, no coverage threshold/exclusion changed, and no existing
test weakened, deleted or skipped.

## Reproduce and verify (exact commands and results)

- Reproduce on this branch: `bash scripts/check.sh` -> exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Verified as the known **worker-only
  measurement artifact**: `git ls-files -v opencode.json` reports flag `S` (skip-worktree). `agent-run.sh`
  copies the CI profile into the worktree under skip-worktree, so `check-pack.py`/`gen-opencode.py --check`
  judge the CI profile, not committed HEAD. `git diff HEAD -- opencode.json` is empty and the worktree file is
  the CI profile; the committed HEAD file is the correct local profile. The integrator checkout has no
  skip-worktree artifact. NOT fixed here (out of scope; `opencode.json` and `.opencode/*` are not in this story's scope).
- Merge shape: `git merge-tree --write-tree origin/main HEAD` -> exit 0, single tree hash, no conflict (after
  this fix) — the branch merges cleanly with main.
- Other cheap gates on the worktree: `python3 -I scripts/check-docs.py` exit 0; `python3 -I scripts/check-secrets.py`
  exit 0; `python3 -I scripts/check-names.py` exit 0.

### Red/green regression proof (tool evidence, synthetic data)

- RED: reverted `events()` to the original tenant-wide return, then
  `mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  -> exit 1, `expected: <2> but was: <4>` (the file-a trail contained all four tenant facts).
- GREEN: restored the fix, same command -> exit 0 (`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`).
- Targeted class: `mvn -q -B -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` -> exit 0
  (`Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`), including the new regression test.
- Full API gate (unit + real-PostgreSQL integration + JaCoCo 100%): `bash apps/api/check.sh -Pno-security`
  -> exit 0.

### Coverage proof (AGENTS.md coverage rules)

- `apps/api/target/site/jacoco/jacoco.csv` for the only changed production file:
  `AttachmentService,0,963,0,86,0,168,0,71,0,28` — 0 missed instructions (963 covered), 0 missed branches
  (86 covered), 0 missed lines (168 covered), 0 missed complexities, 0 missed methods (28 covered). 100% on
  every counter for every file changed. The test file is not production code and is not in the coverage scope.
- Note: `mvn -q -B -Pno-security,fast verify` fails the bundle JaCoCo check only because the `fast` profile
  excludes `@Tag("integration")` classes (e.g. TrendService, ConformanceLedger) that are covered in the full
  gate; this is unrelated to this change. The full gate above is green at 100%.

### New/changed branch and its covering test (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `events()` per-file filter `.filter(fact -> fileId.equals(fact.attachmentId()))` true (fact belongs to requested file) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (aTrail/bTrail contain only their own file's facts and detail) |
| filter false (fact belongs to another file in the same tenant) | same test: `noneMatch` assertions reject the other file's detail; RED run showed 4 facts leak when the filter is absent |
| `view()`/`events()` parity guard | same test: `assertEquals(service.view(patientA, a.id()).events(), aTrail)` |

The fix adds one `.filter(...)` lambda (no `if/else`, ternary, `switch` or `catch`), and its true and false sides
are both asserted by the single regression test above.

## Entry point -> test trace

`AttachmentService.events(actor, id)` (no HTTP controller yet — tracked separately as AUD-20261119-03) ->
`authorize(actor, AttachmentActions.READ, id)` -> `load(actor, id)` -> `visible(actor, current)` ->
`store.events(organizationId)` -> per-file `.filter(fact -> fileId.equals(fact.attachmentId()))`. The red/green
test enters through the public `service.events(...)` method and asserts the returned trail.

## Evidence sources and decisions

- Evidence source: executed tool commands (`git`, `mvn`, `bash scripts/*.sh`) — tool evidence only. No reviewer,
  simulated user or human/clinical participant evidence; no real patient data.
- Decision: the integrator failure is a shared-file hygiene problem, so the minimal correct repair is to stop
  modifying `.agent/pr-notes.md` and keep the already-correct Java change. Regenerating `opencode.json` locally
  would commit the CI profile and is out of scope, so the skip-worktree `check-pack` result is documented, not
  "fixed".

## Not verified here

- The integrator re-runs the full `scripts/integrator-gate.sh` (check.sh + docs.sh) on the merge result; this
  worker is instructed to run the fast unit tests only, which it did (all green). The full API gate was also run
  locally and is green.
- No HTTP-level test for `events()` exists because the service has no controller (AUD-20261119-03, out of scope).
