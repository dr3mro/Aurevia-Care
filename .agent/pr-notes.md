# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Repair dispatch: `fix-20261120-125255` (integrator merge-conflict fix, attempt 1 of 3), branch
`swarm/AUD-20261120-10-289`, base HEAD `a4ca29b`. Synthetic fixtures only; no patient data, credentials or
secrets. No push, tag or `gh` call.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit opening/viewing/sending/downloading of conversations and files; a caller authorized for one file must not receive another file's events or free-text detail | `AttachmentService.events(actor, id)` filters the tenant-wide fact stream by the requested file's canonical `attachmentId`, exactly as its sibling `view()` does |
| ARC-07 | Bind each message/attachment event to one conversation and file; no cross-file trail leak inside a tenant | `AttachmentEvent.attachmentId` binds each fact to one file; `events()` no longer exposes another file's `detail` (scanner detail, void/rejection text) |
| Story acceptance | `events(actor, id)` returns only the facts whose `attachmentId` equals the requested file's id; one tenant, two attachments, each trail contains only its own file's events | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

Scope declared by the story: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and
`apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new
folder or package; names stay business-domain (`messaging.attachments`).

## Defect found (root cause of the integrator failure)

The in-scope Java fix and regression test were **already correct** on the inherited branch before this repair:

- `AttachmentService.events()` (lines 287-297) now authorizes, loads, checks `visible()`, captures
  `String fileId = current.id();` and filters `store.events(actor.organizationId())` with
  `.filter(fact -> fileId.equals(fact.attachmentId()))`. The original defect returned the whole tenant-wide
  stream (`store.events(organizationId()).stream().toList()`), leaking other files' `detail` across
  conversations in the same tenant (a within-tenant, cross-file/cross-conversation leak, not cross-tenant —
  `InMemoryAttachmentStore.events(org)` is per-organization).
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in
  one tenant with distinct scanner details and asserts per-file trails, absence of the other file's detail, and
  `events() == view().events()` parity.

The real cause of the repeated "Merge conflict with main could not be resolved automatically (merged but checks
failed)" was **worker file hygiene**, not the Java change: prior attempts repeatedly wrote this story's notes
into the **shared** `.agent/pr-notes.md` (a file parallel workers such as AUD-20261120-13 also edit), so the
integrator's merge conflicted there. AGENTS.md and the shared-knowledge loop forbid editing that shared file;
worker notes belong only in `.agent/pr-notes/<task-id>-<timestamp>.md`.

## Fix applied in this repair

1. Restored `.agent/pr-notes.md` to the exact content of `origin/main`
   (`git restore --source=origin/main -- .agent/pr-notes.md`). `git diff origin/main -- .agent/pr-notes.md` is
   now empty — the branch no longer modifies the shared file, so the integrator merge is conflict-free.
2. Retained the correct, already-committed Java fix and regression test **unchanged**.
3. Wrote this repair's evidence to its own allowed file, `.agent/pr-notes/fix-20261120-125255.md`.

No production logic was rewritten, no migration added, no coverage threshold/exclusion changed, and no existing
test weakened, deleted or skipped. Final read-only merge check:
`git merge-tree --write-tree origin/main HEAD` → exit 0 with a single tree hash
(`eafee8cb15a9dc4d6f865b558df034ef0308e4c7`), no conflict markers — the branch merges cleanly with main.

## Red/green regression proof (tool evidence, synthetic data)

- **RED:** temporarily reverted `events()` to the original tenant-wide return, then ran
  `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → exit 1, `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at line 443 (the file-a trail
  contained all four tenant facts). This proves the regression test detects the missing per-file filter.
- **GREEN:** restored the fix from HEAD (`git checkout HEAD -- .../AttachmentService.java`) and re-ran the same
  command → exit 0 (1 test, 0 failures/errors/skipped).
- Targeted class: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` →
  exit 0, 29 tests / 0 failures / 0 errors / 0 skipped (surefire report confirmed), including the new test.
- Full fast unit suite: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0;
  **1915 tests / 0 failures / 0 errors / 0 skipped** (surefire aggregate).
- Full canonical gate (unit + real-PostgreSQL integration + JaCoCo):
  `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh -Pno-security` → **exit 0**;
  **2361 tests / 0 failures / 0 errors / 0 skipped** (surefire + failsafe aggregate). PostgreSQL was accepting
  connections on 127.0.0.1:5432.

## Coverage proof (AGENTS.md coverage rules)

- `apps/api/target/site/jacoco/jacoco.csv` and `jacoco.xml` for the only changed production file
  `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:
  INSTRUCTION missed=0 covered=963; BRANCH missed=0 covered=86; LINE missed=0 covered=168;
  COMPLEXITY missed=0 covered=71; METHOD missed=0 covered=28; CLASS missed=0 covered=1.
  → **100% on every counter for the changed file**; zero missed lines/branches/methods/classes.
- The full JaCoCo `check` (BUNDLE rule, 1.00 minimum for INSTRUCTION/LINE/BRANCH/METHOD/CLASS) passed as part of
  the exit-0 canonical gate. The test file is not production code and is not in the coverage scope.

### New/changed branch and its covering test (AGENTS.md coverage rule 6)

| Production branch | Test |
|---|---|
| `events()` per-file `.filter(fact -> fileId.equals(fact.attachmentId()))` true (fact belongs to requested file) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`: aTrail/bTrail each contain only their own file's facts and detail |
| filter false (fact belongs to a sibling file in the same tenant) | same test: `noneMatch` assertions reject the other file's `detail`; RED run showed all 4 tenant facts leak when the filter is absent |
| `view()`/`events()` parity | same test: `assertEquals(service.view(patientA, a.id()).events(), aTrail)` |

The fix adds one `.filter(...)` lambda (no `if/else`, ternary, `&&`, `||`, `switch` or `catch`), whose true and
false outcomes are both asserted by the single regression test. All pre-existing branches in `events()`
(`authorize`, `visible`, `requireVersion`) remain covered by the existing suite, as confirmed by the 100%
JaCoCo report.

## Entry point -> test trace

`AttachmentService.events(actor, id)` (no HTTP controller yet — tracked separately as AUD-20261119-03) →
`authorize(actor, AttachmentActions.READ, id)` → `load(actor, id)` → `visible(actor, current)` →
`store.events(organizationId)` → per-file `.filter(fact -> fileId.equals(fact.attachmentId()))`. The red/green
test enters through the public `service.events(...)` method and asserts the returned trail.

## Additional checks run (tool evidence)

- `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK (no
  unqualified Javadoc `{@link}`, no ID-style names).

## Decisions and evidence sources

- Decision: the integrator failure is a shared-file hygiene problem, so the minimal correct repair is to stop
  modifying `.agent/pr-notes.md` and keep the already-correct Java change. No Java logic needed rewriting.
- Evidence source: executed tool commands (`git`, `mvn`, `bash scripts/*.sh`, `python3`) — **tool evidence only**.
  No reviewer, simulated user or human/clinical participant evidence was required for this contained backend
  correctness fix; all fixtures are synthetic.
- Not verified here (integrator's job): the full `scripts/integrator-gate.sh` / `scripts/check.sh` on the merge
  result. The worker instruction was to run the fast unit tests (done, all green); the full API gate was also
  run locally and is green. The worktree `opencode.json` is a known CI-profile-under-skip-worktree measurement
  artifact (OPS-CW-003-01) and is out of scope; it was not edited.

Story complete on this branch: the shared-file modification that caused the merge conflict is removed, the
regression test is proven to detect the defect (red/green), coverage is 100% on the changed file, and all fast
unit tests pass. No decision or evidence is missing. Committing to the current branch; no push, tag or gh call.
