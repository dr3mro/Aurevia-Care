# AUD-20261120-10 — Attachment event history per-file scope — merge-conflict repair

## Requirement IDs and acceptance mapping

- **MSG-07, ARC-07** — audit and bind every attachment event to one conversation/file; a caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) inside the same tenant.
- **Acceptance from the story:** `AttachmentService.events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id, exactly as `view()` does. A test creates two attachments in one tenant and asserts each trail contains only that file's events.
- **In scope (declared):** `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments`, `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new folder or package — names stay business-domain (`messaging.attachments`).
- **Not changed:** `opencode.json`, `.opencode/*`, `.github/workflows/*`, `scripts/*`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`.

## State on arrival

The in-scope fix and its regression test were already committed on this branch:

- `AttachmentService.events()` (`AttachmentService.java:287-297`) authorizes, loads and checks visibility, then captures `String fileId = current.id();` and filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view()` (`:266-277`). Both `store.events()` call sites (`:273`, `:294`) are filtered.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (`:435-455`) creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity.

I did not rewrite this working, fully covered code. I independently re-verified it red → green rather than trusting earlier notes.

## Root cause of the reported merge failure and the fix applied

`git merge-tree --write-tree origin/main HEAD` on arrival reported exactly one conflict:

```
Auto-merging .agent/pr-notes.md
CONFLICT (content): Merge conflict in .agent/pr-notes.md
```

`git diff $(git merge-base origin/main HEAD) origin/main -- <scope paths>` is **empty** — main never touched the Java scope. The conflict was entirely in the shared `.agent/pr-notes.md`: both main and this worker branch changed that file from the merge base. AGENTS.md forbids any worker from creating, editing or committing the shared `.agent/pr-notes.md` (parallel workers overwrite it and every PR then conflicts). Prior repair attempts kept re-introducing a prepend to it.

**Fix:** `git checkout origin/main -- .agent/pr-notes.md` restores the shared file byte-for-byte to main's version, so `git diff origin/main -- .agent/pr-notes.md` is empty and the integrator's merge has no conflict on it. No production Java code was changed. My findings live only in this dedicated file, which is my own and is never touched by parallel workers.

## Test-first verification (red → green) — tool evidence

- **RED** — temporarily removed only the per-file `.filter` from `events()` (byte-for-byte backup/restore of the single tracked file), then:
  `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → `RED_RC=1`; `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (4 facts across two files) was returned instead of the requested file's 2 facts — the exact defect. The file was restored and `git diff --quiet` confirmed it identical to `HEAD`.
- **GREEN** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → `GREEN_RC=0`; surefire: `tests="29" errors="0" skipped="0" failures="0"`.

## Coverage command and result (AGENTS.md Coverage rules)

- `cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → **VERIFY_RC=0**. PostgreSQL accepted connections on `127.0.0.1:5432`. JaCoCo build-failing thresholds (INSTRUCTION/LINE/BRANCH/METHOD/CLASS) passed.
- `cd apps/api && mvn -q -B -Pno-security,fast test` → **FAST_RC=0**; aggregated surefire: 2350 tests, 0 failures, 0 errors, 0 skipped.
- `target/site/jacoco/jacoco.xml`, class `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:

| Counter | missed | covered |
|---|---|---|
| INSTRUCTION | 0 | 963 |
| BRANCH | 0 | 86 |
| LINE | 0 | 168 |
| COMPLEXITY | 0 | 71 |
| METHOD | 0 | 28 |
| CLASS | 0 | 1 |

Bundle totals: INSTRUCTION 0/90675, BRANCH 0/6403, LINE 0/15730, METHOD 0/3814, CLASS 0/785 — **100% on every changed file and the whole bundle.**

## New branches and covering tests

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch; it must simply be executed. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Review evidence

- `smartclinic-senior-backend` and `smartclinic-test-engineer` skills loaded for this Java/backend + test change this session. The Java fix was previously endorsed by a read-only `smartclinic-senior-backend` simulated reviewer (recorded in `.agent/pr-notes/AUD-20261120-10-20261120-101159.md`): smallest correct fix, tenant scoping / visibility / canonical-id preserved, no new JaCoCo branch, no EI_EXPOSE_REP concern (`Stream.toList()` unmodifiable, `AttachmentEvent` immutable), parity assertion adopted. That reviewer edited no files.
- Human/clinical/patient evidence: none — contained backend correctness fix with no HTTP controller entry point yet (tracked separately as AUD-20261119-03).

## Environment artifacts (observed, not caused by this change)

`bash scripts/worker-check.sh` / `bash scripts/check.sh` were not relied on as the gate here: in this cloud worker `opencode.json` is `S` (skip-worktree, the CI profile copied by `agent-run.sh`), so `check-pack.py` reports the documented OPS-CW-003-01 "opencode.json is stale" false positive; in a clean CI checkout the committed local profile is used and that step passes. `shellcheck` is absent locally (CI runs it). PyYAML is absent, so unrelated Python tooling modules cannot be collected locally. None of these import or exercise the changed Java. The authoritative in-scope gate (`mvn verify` with JaCoCo 100%) was run directly and is green.

## Evidence source

All commands above were executed in this checkout this session — **tool evidence**, synthetic fixtures only, no patient data or credentials. No reviewer, simulated user or human participant evidence beyond the simulated reviewer noted above.
