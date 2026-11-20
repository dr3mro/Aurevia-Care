# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Repair dispatch `fix-20261120-130309` (integrator merge-conflict fix, attempt 1 of 3), branch
`swarm/AUD-20261120-10-289`, inherited base HEAD `99a2cf3`. Synthetic fixtures only; no patient data,
credentials or secrets. No push, tag or `gh` call was made.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit opening/viewing/sending/downloading of conversations and files; a caller authorized for one file must not receive another file's events or free-text detail | `AttachmentService.events(actor, id)` filters the tenant-wide fact stream by the requested file's canonical `attachmentId`, exactly as its sibling `view()` does |
| ARC-07 | Bind each message/attachment event to one conversation and file; no cross-file trail leak inside a tenant | `AttachmentEvent.attachmentId` binds each fact to one file; `events()` no longer exposes another file's `detail` (scanner detail, void/rejection text) |
| Story acceptance | `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id; one tenant, two attachments, each trail contains only its own file's events | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

Scope declared by the story: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments`
and `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used.
No new folder or package; names stay business-domain (`messaging.attachments`). The service still has
no HTTP controller (tracked separately as AUD-20261119-03), so the fix is a latent correctness fix.

## Defect and root cause of the integrator failure

The in-scope Java fix and regression test were **already correct** in the inherited branch:

- `AttachmentService.events()` (lines 287-297) authorizes, loads, checks `visible()`, captures
  `String fileId = current.id();` and filters `store.events(actor.organizationId())` with
  `.filter(fact -> fileId.equals(fact.attachmentId()))`. The original defect returned the whole
  tenant-wide stream (`store.events(organizationId()).stream().toList()`), leaking other files'
  `detail` across conversations in the same tenant. This is a within-tenant, cross-file /
  cross-conversation leak, not cross-tenant: `InMemoryAttachmentStore.events(org)` is per-organization.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two
  attachments in one tenant with distinct scanner details and asserts per-file trails, absence of
  the other file's detail, and `events() == view().events()` parity.

The repeated "Merge conflict with main could not be resolved automatically (merged but checks failed)"
was **worker file hygiene**, not the Java change: prior attempts repeatedly wrote this story's notes
into the **shared** `.agent/pr-notes.md` (a file parallel workers such as AUD-20261120-13 also edit).
The branch history flip-flopped between "restore shared pr-notes" and "remove shared pr-notes edit"
commits, leaving the shared file modified at the inherited HEAD. AGENTS.md and the shared-knowledge
loop forbid editing that file; worker notes belong only in `.agent/pr-notes/<task-id>-<timestamp>.md`.

## Fix applied in this repair

1. Restored the shared `.agent/pr-notes.md` to the exact content of `origin/main`
   (`git restore --source=origin/main -- .agent/pr-notes.md`). `git diff origin/main -- .agent/pr-notes.md`
   is now empty, so the integrator merge is conflict-free. This undoes a previous worker's forbidden
   edit; it does not author new content in that shared file.
2. Retained the correct, already-committed Java fix and regression test **unchanged**.
3. Wrote this repair's evidence to its own allowed file, `.agent/pr-notes/fix-20261120-130309.md`.

No production logic was rewritten, no migration added, no coverage threshold/exclusion changed and
no existing test weakened, deleted or skipped. Extra `fix-*.md` files from earlier attempts on this
branch are left untouched: they have unique names, so they neither conflict on merge nor affect any
check (all policy checks pass with them present).

## New branches and covering tests (AGENTS.md Coverage rules)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda,
which JaCoCo instruments as a synthetic method with no internal branch; it must simply be executed.
The new test executes it for a matching fact (the file's own events) and a non-matching fact (a sibling
file's event), covering both outcomes of the predicate. `String fileId = current.id();` is straight-line.
All pre-existing branches (`authorize`, `visible`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test`
  → `MVN_EXIT=0`; surefire: `tests="29" errors="0" skipped="0" failures="0"`.
- **RED proof:** temporarily reverted `events()` to the original tenant-wide return, ran
  `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → `RED_EXIT=1`, `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant:443 expected: <2> but was: <4>`.
  Restored the fix with `git checkout HEAD -- .../AttachmentService.java`; re-ran the class → `GREEN_EXIT=0`.
- **Full coverage gate:** `mvn -q -B -f apps/api/pom.xml -Pno-security verify` → `VERIFY_EXIT=0`
  (all unit + real-PostgreSQL integration tests, JaCoCo BUNDLE 100% rule passed). Local JDK 21,
  Maven 3.10.0; PostgreSQL accepting connections on 127.0.0.1:5432.
- **JaCoCo report** `apps/api/target/site/jacoco/jacoco.xml` for
  `com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`:
  INSTRUCTION missed=0 covered=963; BRANCH missed=0 covered=86; LINE missed=0 covered=168;
  COMPLEXITY missed=0 covered=71; METHOD missed=0 covered=28; CLASS missed=0 covered=1.
  → **100% on every changed production file**.
- Policy checks: `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`,
  `check-migrations.py --require-baseline` → all exit 0. Java conventions:
  `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.
- Merge cleanliness: `git merge-tree --write-tree origin/main HEAD` → exit 0 with a single tree hash
  (`c3de34baec751d3287f6529e27b721a0a114aa03`), no conflict markers.

All evidence came from **executed tool commands** in this checkout with synthetic fixtures only.

## Review and evidence source

No specialist reviewer was needed: this is a contained backend correctness fix whose Java change was
already present and verified, plus a one-line shared-file restoration to unblock the integrator merge.
Evidence source: executed tool commands (tool evidence). No reviewer, simulated user or human
participant evidence was required or claimed.

## Entry-point-to-test trace

The service has no HTTP controller yet (AUD-20261119-03), so there is no live request entry point.
The direct public entry point is `AttachmentService.events(Actor, String)`;
`AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` calls it with two
attachments in one tenant and asserts the returned trail is bound to the requested `attachmentId`,
contrasting with the previously tenant-wide return. The sibling `AttachmentService.view()` uses the
same per-file filter and is asserted equal to `events()`.
