# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Repair dispatch `fix-20261120-131411` (integrator merge-conflict fix, attempt 1 of 3) on branch
`swarm/AUD-20261120-10-289`. Synthetic fixtures only; no patient data, credentials or secrets.
No push, tag or `gh` call was made. This file is the run-owned notes file and becomes the PR description.

## Merge-conflict root cause and fix (the reason for this repair pass)

`scripts/swarm.py::merge_local` merges the PR with `git merge --no-ff`. This branch had **overwritten the
forbidden shared file `.agent/pr-notes.md`** (it held AUD-20261120-10 notes while `main` held the
AUD-20261120-13 notes). AGENTS.md and `scripts/agent-run.sh` state that workers must never write that
path — notes go only to a run-owned file under `.agent/pr-notes/`. Prior repair commits on this branch
(`7c8af1e`, `13332b3`, `4ff4ce8`, `b7a9ade`, `14041f9`) flip-flopped the shared file, so the conflict kept
returning. The durable fix is to make this PR **not modify `.agent/pr-notes.md` at all**:

- `git restore --source=origin/main -- .agent/pr-notes.md`

After this, the branch's copy equals `main` and `git diff origin/main -- .agent/pr-notes.md` is empty, so
the integrator's merge has no conflict on that path regardless of what other workers do to the shared file.

The product fix and its regression test (`9dee672`) were already correct and are preserved unchanged.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit opening/viewing/sending/downloading of conversations and files; a caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) | `AttachmentService.events(actor, id)` now filters the tenant-wide fact stream by the requested file's canonical `attachmentId`, exactly as sibling `view()` does |
| ARC-07 | Bind each attachment event to one conversation/file; no cross-file/cross-conversation trail leak inside a tenant | `AttachmentEvent.attachmentId` binds each fact to one file; `events()` no longer exposes another file's `detail` |
| Story acceptance | `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id; one tenant, two attachments, each trail contains only its own file's events | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

## Defect and fix

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()`
— the entire tenant-wide fact ledger — after authorizing and checking visibility for only the single named
file. Its sibling `view()` (line 273) already filtered with `fileId.equals(fact.attachmentId())`.
`InMemoryAttachmentStore.events(org)` is per-organization, so the leak is cross-file / cross-conversation
**within one tenant**, not cross-tenant. The service has no HTTP controller yet (latent; entry point tracked
separately as AUD-20261119-03).

Fix: in `events()`, capture the canonical `String fileId = current.id();` and filter the tenant fact stream
with `.filter(fact -> fileId.equals(fact.attachmentId()))` before `.toList()`, mirroring `view()` exactly.
Tenant scoping (`store.events(organizationId())`), visibility gating (`visible()`), and use of the stored
canonical id (not the raw request parameter) are all preserved. No interface, store, controller or schema
change; no migration version used; no new folder or package (naming stays business-domain `messaging.attachments`).

## New/changed branches and covering tests (AGENTS.md Coverage rules 1–6)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which
JaCoCo instruments as a synthetic method with no internal branch; it must simply be executed. The new test
executes it for a matching fact (a's own events) and a non-matching fact (b's events), covering both
outcomes of the predicate. `String fileId = current.id();` is straight-line.

| New code / branch | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

The test creates two attachments in one tenant, scans each with a distinct detail (`detail-of-file-a` /
`detail-of-file-b`), and asserts each trail has exactly 2 facts, every fact carries that file's
`attachmentId`, the sibling's detail does not cross trails, and `events()` equals sibling `view().events()`.
No threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Exact commands run and results (tool evidence, executed in this checkout)

- Fast unit tests: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` → **EXIT 0**.
- Fresh red phase (fix temporarily reverted in the working tree, then restored with `git checkout --`):
  `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → **EXIT 1**, `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` (tenant-wide ledger returned
  4 facts across two files instead of the requested file's 2). Confirms the regression test detects the defect.
- **Full coverage gate (every unit + integration test + enforced JaCoCo 100%):**
  `cd apps/api && mvn -q -B -Pno-security verify` → **EXIT 0**; 2361 tests in `target/surefire-reports`.
- **JaCoCo report for the changed production file** (`apps/api/target/site/jacoco/jacoco.xml`,
  `com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`):
  INSTRUCTION missed=0 covered=963 (100%); LINE missed=0 covered=168 (100%); BRANCH missed=0 covered=86
  (100%); METHOD missed=0 covered=28 (100%); COMPLEXITY missed=0 covered=71 (100%). → **100% on every
  counter of the changed file**, so the build-failing BUNDLE rule passes.
- Policy checks, all **EXIT 0**: `python3 -I scripts/check-names.py`, `python3 -I scripts/check-secrets.py`,
  `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-migrations.py --require-baseline`.
- Java conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` →
  2 tests OK (no unqualified Javadoc `{@link}`, no ID-style names).
- Environment: Java 21.0.13, Maven 3.10.0; PostgreSQL 17.12 accepting connections on 5432 with
  `SMARTCLINIC_PG_ADMIN=postgres`.

All evidence above is from **executed tool commands** in this checkout with synthetic fixtures only.
No reviewer, simulated user, human or clinical evidence is required for this contained backend fix.

## Pre-existing environment limitation (not caused by this change)

`python3 -I scripts/check-pack.py` (and therefore `scripts/quick-check.sh` / `scripts/check.sh`) fails with
`check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Verified cause: the worktree `opencode.json`
is the CI profile under `skip-worktree` (`git ls-files -v` flag `S`, byte-equal to `.github/opencode.ci.json`),
while `HEAD:opencode.json` differs from it (HEAD holds the correct local profile). This is the documented
OPS-CW-003-01 measurement artifact of `agent-run.sh::activate_ci_config`; `opencode.json` is a protected file
outside this task's scope, so it was not modified. The integrator runs on a clean checkout where HEAD truth
applies. The full `mvn verify` coverage gate and every in-scope policy check pass.

## Scope compliance and decisions

- Changed files after this repair: `AttachmentService.java`, `AttachmentAcceptanceTest.java` (both inside
  the declared `messaging/attachments` scope), the shared `.agent/pr-notes.md` **restored to main's version**
  (net no change vs main), and this run-owned notes file. No migration, controller, tracker or `.github/`,
  `.opencode/`, `scripts/`, `opencode.json` edit.
- Decision: resolve the repeated integrator conflict by removing this PR's change to the shared file rather
  than flipping its content again; keep the verified product fix and test intact.
