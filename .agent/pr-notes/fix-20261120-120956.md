# AUD-20261120-10 — Attachment event history per-file scope — merge-conflict repair

## Requirement IDs and acceptance mapping

- **MSG-07, ARC-07** — audit and bind every attachment event to one conversation/file. `AttachmentEvent.detail` carries scanner detail and void/rejection free text; a caller authorized for file A must not receive file B's event details inside the same tenant.
- **Acceptance (story):** `AttachmentService.events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id, exactly as its sibling `view()` does. A test creates two attachments in one tenant and asserts each trail contains only that file's events.
- **Scope (declared):** `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new folder or package — names stay business-domain (`messaging.attachments`).
- **Not changed:** `opencode.json`, `.opencode/*`, `.github/workflows/*`, `scripts/*`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`, and the shared `.agent/pr-notes.md`.

## Root cause of the reported merge failure — and the fix applied

The in-scope Java fix and its regression test were already committed on this branch before this repair attempt:

- `AttachmentService.events()` (`AttachmentService.java:287-297`) authorizes, loads and checks visibility, captures `String fileId = current.id();`, then filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`. This mirrors `view()` (`:266-277`). Both `store.events()` call sites are now filtered.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (`:435-455`) creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity.

I did not rewrite the working code. The merge failure was caused by a **worker-owned file hygiene violation**: the last agent change on this branch (`cf6851a`, "Agent changes for AUD-20261120-10") wrote its notes into the **shared** `.agent/pr-notes.md`. Parallel workers (including AUD-20261120-13, integrated in `742f2bb`) also change that shared file, so the integrator's merge of this PR into current main conflicts there. AGENTS.md and the shared-knowledge loop forbid any worker from creating, editing or committing `.agent/pr-notes.md` — every pull request then conflicts.

**Fix applied in this repair:** `git checkout origin/main -- .agent/pr-notes.md` restores the shared file byte-for-byte to main's version, so `git diff origin/main -- .agent/pr-notes.md` is empty. All findings for this run live only in this dedicated file (`.agent/pr-notes/fix-20261120-120956.md`), which no parallel worker touches. No production Java code was changed by this repair. Evidence: `git diff --stat origin/main -- .agent/pr-notes.md` is empty after the restore; `git diff --stat <merge-base> origin/main -- <scope paths>` is empty (main never touched the Java scope).

## Test-first verification (red → green) — tool evidence

- **RED** — temporarily removed only the per-file `.filter` from `events()` (byte-for-byte backup/restore of the single tracked file; `view()` left intact), then `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → `RED_RC=1`; `org.opentest4j.AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (two files x upload + scan_failed = 4 facts) was returned instead of the requested file's 2 — the exact defect. The file was restored and `git diff --quiet` confirmed it byte-identical to HEAD.
- **GREEN** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → `GREEN_RC=0`; surefire `TEST-...AttachmentAcceptanceTest.xml`: `tests="29" errors="0" skipped="0" failures="0"`.

## New branches and covering tests (AGENTS.md Coverage rules)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch; it only has to be executed. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (a's / b's own facts retained) |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (b's detail absent from a's trail and vice versa) |

No coverage threshold, exclusion or skip was changed; no existing test was weakened, deleted or skipped.

## Coverage command and result (AGENTS.md Coverage rules)

- **Coverage gate (all unit + integration tests, JaCoCo 100% enforced):** `cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → **VERIFY_NOSEC_RC=0**. PostgreSQL accepted connections on `127.0.0.1:5432`. JaCoCo build-failing limits on INSTRUCTION / LINE / BRANCH / METHOD / CLASS all passed. The `-Pno-security` profile skips only OWASP Dependency-Check and SpotBugs (no test or coverage exclusion); SpotBugs was run separately below. The canonical `bash apps/api/check.sh` (= `mvn -q -B verify`) was also started but stalled on the OWASP NVD data update phase after all 210 test classes had completed at 12:15; it was terminated and replaced by the no-security gate plus the standalone SpotBugs run.
- **Fast unit tier:** `cd apps/api && mvn -q -B -Pno-security,fast test` → `FAST_RC=0`; aggregated surefire 2361 tests, 0 failures, 0 errors, 0 skipped.
- **SpotBugs:** `cd apps/api && mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → `SPOTBUGS_RC=0`.
- `target/site/jacoco/jacoco.xml`, class `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:

| Counter | missed | covered | result |
|---|---|---|---|
| INSTRUCTION | 0 | 963 | 100% |
| BRANCH | 0 | 86 | 100% |
| LINE | 0 | 168 | 100% |
| COMPLEXITY | 0 | 71 | 100% |
| METHOD | 0 | 28 | 100% |
| CLASS | 0 | 1 | 100% |

- Whole bundle: INSTRUCTION 0/90701, BRANCH 0/6407, LINE 0/15736, COMPLEXITY 0/7070, METHOD 0/3815, CLASS 0/785 — **100% on every changed file and the whole bundle.**

## `scripts/check.sh` reproduction (story request)

`timeout 180 bash scripts/check.sh` → `FULL_CHECK_RC=1`, failing at the **first** policy step: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented OPS-CW-003-01 worker false positive, not a defect of this change: in this cloud worker the worktree `opencode.json` is the CI profile copied under `skip-worktree` by `agent-run.sh` (verified: worktree md5 `4dbdb979…` equals `.github/opencode.ci.json`), while the committed `HEAD:opencode.json` (md5 `c054c4c2…`) equals the generator's local profile (verified by regenerating and comparing byte-for-byte, then restoring the worktree CI profile). A clean integrator checkout uses the committed local profile, so this step passes there; the integrator gate `scripts/integrator-gate.sh` = `scripts/check.sh` + `scripts/docs.sh` is authoritative. The authoritative in-scope gate (`mvn verify` with JaCoCo 100%) was run directly and is green above.

## Additional checks and environment notes

- `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` → all exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK (no unqualified Javadoc links; no ID-style names).
- `opencode.json` is `S` (skip-worktree) in this cloud worker — the CI profile copied by `agent-run.sh`. `check-pack.py`/`scripts/check.sh` therefore judge the CI profile here and can report the documented OPS-CW-003-01 false positive; in a clean integrator checkout the committed local profile is used. PyYAML and shellcheck and Doxygen are absent locally (CI provides them). None of these import or exercise the changed Java. The authoritative in-scope gate is the JaCoCo-enforced `mvn verify`, run directly and recorded above.

## Review evidence

- Local skills loaded for this Java/backend + test change: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. The Java fix was previously endorsed by a read-only `smartclinic-senior-backend` simulated reviewer (recorded in `.agent/pr-notes/AUD-20261120-10-20261120-101159.md`): smallest correct fix; tenant scoping, visibility gating and canonical-id choice preserved; no new JaCoCo branch; no EI_EXPOSE_REP concern (`Stream.toList()` unmodifiable, `AttachmentEvent` immutable); parity assertion adopted. That reviewer edited no files.
- Human/clinical/patient evidence: none — contained backend correctness fix with no HTTP controller entry point yet (tracked separately as AUD-20261119-03).

## Evidence source

All commands above were executed in this checkout this session — **tool evidence**, synthetic fixtures only, no patient data or credentials. No reviewer, simulated user or human participant evidence beyond the simulated reviewer noted above.
