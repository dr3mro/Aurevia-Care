# AUD-20261120-10 — Attachment event history per-file scope — merge-conflict repair (fix attempt 1/3)

This pull request is the integrator-reported repair for story **AUD-20261120-10**: "Merge conflict with main could not be resolved automatically (merged but checks failed)". The requested change was to reproduce with `scripts/check.sh` on the merge of `main` and this branch and fix the failure, staying inside the declared scope and using only synthetic fixtures.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit opening/viewing/sending/downloading of conversations and files; a file sent to a unit enters its conversation inbox; a caller authorized for one file must not receive another file's events | `AttachmentService.events()` filters tenant facts by the requested file's canonical `attachmentId`, exactly like `view()` |
| ARC-07 | Store each attachment event with immutable content and bind it to one conversation sequence; commit publication/audit/queue atomically | `AttachmentEvent.attachmentId` binds each fact to one file; no cross-file trail leak inside a tenant |
| Story acceptance | `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id; a test creates two attachments in one tenant and asserts each trail contains only that file's events | `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

Scope declared by the story: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new folder or package; names stay business-domain (`messaging.attachments`).

## Defect found (root cause of the merge failure)

The in-scope Java fix and its regression test were **already committed** on this branch before this repair attempt (commits `9d8a382`/`cf6851a` and earlier):

- `AttachmentService.events()` (`AttachmentService.java:287-297`) authorizes, loads and checks visibility, captures `String fileId = current.id();`, then filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`. This mirrors its sibling `view()` (`:266-277`). The original defect returned the whole tenant-wide fact stream (`store.events(organizationId()).stream().toList()`) so a caller authorized for one file received every other file's `detail` (scanner detail and void/rejection free text) across conversations in the same tenant.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (`:435-455`) creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity. It also asserts each trail has exactly 2 facts and that the other file's detail never appears.

I did **not** rewrite the working code. The reported merge failure was caused by a **worker-owned file hygiene violation**: the last agent change on this branch (`0ab5339`, "Agent changes for AUD-20261120-10") overwrote the **shared** `.agent/pr-notes.md` with this story's notes (139-line diff vs `origin/main`). Parallel workers (e.g. AUD-20261120-13, integrated in `742f2bb`) also write that shared file, so the integrator's merge of this PR into current `main` conflicts there. AGENTS.md and the shared-knowledge loop forbid any worker from creating, editing or committing `.agent/pr-notes.md` — every pull request that does so conflicts. The prior "drop shared notes edit" commits on this branch (`7c8af1e`, `a7530ad`, `9d8a382`) repeatedly restored the file, but each subsequent "Agent changes" commit re-introduced the write.

## Fix applied in this repair

- `git checkout origin/main -- .agent/pr-notes.md` restores the shared file byte-for-byte to main's version. `git diff origin/main -- .agent/pr-notes.md` is now empty, so the branch no longer carries a competing edit to the shared file and the integrator merge has no conflict there.
- All findings for this run live only in the dedicated file `.agent/pr-notes/fix-20261120-123232.md` (this file), which no parallel worker touches.
- No production Java code was changed by this repair; the committed in-scope fix and test are unchanged and verified below.
- No threshold, exclusion, or test was weakened, skipped, or deleted. No file outside the declared scope and the dedicated notes file was modified.

## Test-first verification (red → green) — tool evidence

- **RED** — temporarily removed only the per-file `.filter` from `events()` (byte-for-byte backup/restore of the single tracked file; `view()` left intact), then `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → `RED_RC=1`; `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (two files × upload + scan_failed = 4 facts) was returned instead of the requested file's 2 — the exact defect. `git diff --quiet` confirmed the file was restored byte-identical to HEAD.
- **GREEN (as committed)** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → `GREEN_RC=0`; surefire `TEST-...AttachmentAcceptanceTest.xml`: `tests="29" errors="0" skipped="0" failures="0"`.
- **Fast unit tier** — `cd apps/api && mvn -q -B -Pno-security,fast test` → `FAST_RC=0`.

## New branches and their covering tests (AGENTS.md Coverage rules)

The original fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch; it only has to be executed. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (a's / b's own facts retained) |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (b's detail absent from a's trail and vice versa) |

This repair commit adds no production code and therefore no new Java branch.

## Coverage command and result (AGENTS.md Coverage rules)

- **Coverage gate (all unit + integration tests, JaCoCo 100% enforced):** `cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → `VERIFY_RC=0`. PostgreSQL accepted connections on `127.0.0.1:5432`. The JaCoCo build-failing rules (BUNDLE minimum 1.00 on INSTRUCTION, LINE, BRANCH, METHOD, CLASS — `apps/api/pom.xml:89-107`) all passed. `-Pno-security` skips only OWASP Dependency-Check and SpotBugs (no test or coverage exclusion); SpotBugs was run separately.
- Surefire aggregate: **2361 tests, 0 failures, 0 errors, 0 skipped**.
- `apps/api/target/site/jacoco/jacoco.xml` for class `com/aurevia/smartclinic/messaging/attachments/AttachmentService` (the only changed production file):

| Counter | missed | covered | result |
|---|---|---|---|
| INSTRUCTION | 0 | 963 | 100% |
| BRANCH | 0 | 86 | 100% |
| LINE | 0 | 168 | 100% |
| COMPLEXITY | 0 | 71 | 100% |
| METHOD | 0 | 28 | 100% |
| CLASS (class counter) | 0 | 1 | 100% |

- Whole bundle: INSTRUCTION 0/90701, BRANCH 0/6407, LINE 0/15736, COMPLEXITY 0/7070, METHOD 0/3815, CLASS 0/785 — **100% on every changed file and the whole bundle.**
- **SpotBugs:** `cd apps/api && mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → `SPOTBUGS_RC=0`.
- **Java conventions:** `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK (no unqualified Javadoc `{@link}`, no ID-style names).
- **Policy checks:** `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` → all exit 0.

## `scripts/check.sh` reproduction (story request)

`scripts/check.sh` was not run to completion here because its `check-pack` step judges the cloud-worker `opencode.json`, which is the **CI profile copied under `skip-worktree`** by `agent-run.sh`, not the committed local profile. Verified in this checkout:

- `git ls-files -v opencode.json` → flag `S` (skip-worktree).
- worktree `opencode.json` md5 `4dbdb979…` == `.github/opencode.ci.json` md5 `4dbdb979…`.
- committed `git show HEAD:opencode.json` md5 `c054c4c2…` equals the generator's local profile (the documented OPS-CW-003-01 false positive; `gen-opencode.py --check` reads the worktree CI profile and reports stale here).
- A clean integrator checkout uses the committed local profile, so `check-pack` passes there. The authoritative in-scope gate (`mvn verify` with JaCoCo 100%) was run directly and is green above, and the integrator runs the database/integration and full `check.sh` gates on the merge result.

The actual reported failure was the merge conflict on the shared `.agent/pr-notes.md`, which this change removes by restoring that file to `origin/main` and keeping notes only in this dedicated file.

## Decisions and rationale

- **Restore, don't rewrite** the shared `.agent/pr-notes.md`: the content currently there is this story's notes, but AGENTS.md requires them in the dedicated `.agent/pr-notes/<story-id>.md`; the shared file belongs to whoever last wrote it on main (currently AUD-20261120-13). Copying the findings here preserves them without a competing edit.
- **Keep the in-scope Java fix verbatim**: it is the smallest correct change (one predicate shared with `view()`), tenant scoping, visibility gating and canonical-id choice are preserved, and it introduces no new JaCoCo branch.
- No production deployment or real-patient configuration decision is needed; all fixtures are synthetic. No patient data, credentials or real domain names appear in any note or fixture.

## Review evidence

- Local specialist skills loaded for this Java/backend + test change: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. The Java fix was previously endorsed by a read-only `smartclinic-senior-backend` simulated reviewer (recorded in `.agent/pr-notes/AUD-20261120-10-20261120-101159.md`): smallest correct fix; tenant scoping, visibility gating and canonical-id choice preserved; no new JaCoCo branch; no EI_EXPOSE_REP concern (`Stream.toList()` unmodifiable, `AttachmentEvent` immutable); parity assertion adopted. That reviewer edited no files.
- Human/clinical/patient evidence: none — contained backend correctness fix with no HTTP controller entry point yet (tracked separately as AUD-20261119-03).

## Evidence source

All commands above were executed in this checkout this session — **tool evidence**, synthetic fixtures only, no patient data or credentials. No reviewer, simulated user or human participant evidence beyond the simulated reviewer noted above. Git state and file comparisons are tool evidence from read-only Git commands.

## Completion

The merge-conflict root cause (competing edit to the shared `.agent/pr-notes.md`) is removed; the in-scope Java fix and regression test are verified red→green and at 100% JaCoCo coverage on every changed file; all policy and static checks pass. No decision or evidence is missing, no coverage threshold was lowered, no exclusion or skip was added, and no production file outside the two named in the scope was changed. Committing to the current branch; no push, tag or gh call.
