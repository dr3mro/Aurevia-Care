# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

Fix attempt `fix-20261120-132507` (branch `swarm/AUD-20261120-10-289`). Scope: `messaging/attachments` (main + test). No migration used; no file outside the declared scope was changed for the fix.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Auditing of attachment events is bound to one conversation/file; an authorized viewer of one file does not receive another file's scanner/void/free-text `detail` | `AttachmentService.events` now filters tenant facts by `attachmentId` |
| ARC-07 | A conversation/file is accessed only through its own verified context; per-file facts stay in that file's trail | same filter; tenant scoping and file visibility gates preserved |
| OPS-02 | Facts carry actor/purpose/scope and denial stays concealed | unchanged `authorize` / `visible` / `notFound` guards |

Story acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id. Test `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in one tenant, asserts each trail contains only its own file's events, that `detail-of-file-a`/`detail-of-file-b` do not cross trails, and that `events()` equals sibling `view().events()`.

## Defect

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide fact ledger — after authorizing/visibility-checking only the single named file. `InMemoryAttachmentStore.events(org)` is per-organization, so this is a **cross-file / cross-conversation leak within one tenant**, not cross-tenant. `AttachmentEvent.detail` carries scanner detail and void/rejection free text (MSG-07, ARC-07). The service has no HTTP controller yet (tracked separately as AUD-20261119-03), so the leak is latent. Synthetic fixtures only; no patient data or credentials.

## Fix

In `events()`, after the existing `authorize`/`load`/`visible` guards, capture the canonical `String fileId = current.id();` and filter the tenant fact stream with `.filter(fact -> fileId.equals(fact.attachmentId()))` before `.toList()`, mirroring `view()` exactly. Tenant scoping, visibility gating and use of the stored canonical id (not the raw request parameter) are preserved. No interface, store, controller or schema change.

## New branches and covering tests (AGENTS.md Coverage rules)

The fix adds no `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda (a synthetic JaCoCo method with no internal branch) that must be executed; the new test exercises both predicate outcomes.

| New code | Covering test |
|---|---|
| `events()` per-file filter predicate — true (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file filter predicate — false (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

No threshold, exclusion or skip was changed. No existing test was weakened, deleted or skipped. Naming is business-domain (`messaging.attachments`); no new folder or package.

## Commands run and results (tool evidence)

Environment: JDK 21.0.13, Maven 3.10.0, PostgreSQL accepting on 127.0.0.1:5432, `SMARTCLINIC_PG_ADMIN=postgres`.

- **Red phase** — reverted only the production fix in the working tree, ran `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → **exit 1**, `AssertionFailedError: expected: <2> but was: <4>` (tenant-wide ledger returned 4 facts across two files). Fix restored immediately afterwards. Confirms the failing test detects the defect.
- **Green phase (target class)** — `mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → **exit 0**; surefire `tests="29" errors="0" skipped="0" failures="0"`.
- **Full gate** — `cd apps/api && mvn -q -B -Pno-security verify` → **exit 0** (all unit + PostgreSQL integration tests, build-failing JaCoCo 100% rule on INSTRUCTION/LINE/BRANCH/METHOD/CLASS).
- **Totals** — surefire: `Tests run: 2361, Failures: 0, Errors: 0, Skipped: 0`. JaCoCo bundle: INSTRUCTION 90701/90701, BRANCH 6407/6407, LINE 15736/15736, METHOD 3815/3815, CLASS 785/785 — **100.00% on all five counters**.
- **Changed file** — `target/site/jacoco/jacoco.xml`, package `com/aurevia/smartclinic/messaging/attachments`, `AttachmentService.java`: INSTRUCTION missed=0 covered=963; BRANCH missed=0 covered=86; LINE missed=0 covered=168; COMPLEXITY missed=0 covered=71; METHOD missed=0 covered=28; CLASS missed=0 covered=1 → **100% on every counter**.
- **Static analysis** — `mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → exit 0.
- **Policy** — `python3 -I scripts/check-names.py` → 0; `python3 -I scripts/check-docs.py` → 0; `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK (no unqualified Javadoc links, no ID-style names).

## Defects found / repair performed in this attempt

The code fix was already present on the branch from an earlier attempt; the repeated integrator merge failure was **not** in the fix. A prior attempt had overwritten the forbidden shared file `.agent/pr-notes.md` (replacing the AUD-20261120-13 notes committed on `main` with this story's notes), which conflicts with parallel workers editing that shared file. Per AGENTS.md, notes belong only in this uniquely named file. I restored `.agent/pr-notes.md` to the exact content of `origin/main` in the working tree and committed that restoration, so the branch's net diff against `main` is now only the two scoped Java files plus this notes file (the timestamped `.agent/pr-notes/*.md` additions from earlier runs are unique-named and cannot cause a merge conflict). No shared tracker or workflow file was edited; no push, tag or `gh` call was made.

## Evidence sources

All commands above are **executed tool evidence** from this checkout, with synthetic fixtures only. Reviews loaded as instructions: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. No reviewer, simulated user or human participant evidence was required for this contained backend correctness fix. Human/clinical/patient evidence: none.

## Decision

Keep the smallest possible fix (single `.filter`) and the parity assertion `events() == view().events()` so the divergence cannot regress. Do not add a controller or durable store — both are out of scope (controller tracked as AUD-20261119-03; persistence as the READY-* persistence stories). Entry-point-to-test trace: `AttachmentService.events(Actor,String)` → `store.events(org)` → per-file `.filter` → `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (entry point is the service method; no HTTP controller exists yet).
