# AUD-20261120-10 — Attachment event history per-file scope — merge-fix attempt

## Requirement IDs and acceptance mapping

- **MSG-07, ARC-07** — every attachment event is bound to one conversation/file; a caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) inside the same tenant.
- **Acceptance from the story:** `AttachmentService.events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id, exactly as `view()` does; a test creates two attachments in one tenant and asserts each trail contains only its own file's events.
- **In scope:** `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments`, `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration version used. No new folder or package; names remain business-domain (`messaging.attachments`).
- **Not changed:** `opencode.json`, `.opencode/*`, `.github/workflows/*`, `scripts/*`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`.

## State on arrival

The in-scope fix and its regression test were already present at `HEAD` (`b374732`, the merge of `origin/main` `b495e96f` into the worker branch):

- `AttachmentService.events()` (`AttachmentService.java:287-297`) authorizes, loads and checks visibility, then captures `String fileId = current.id();` and filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view()` at `:266-277`. Both `store.events()` call sites are filtered.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity.

I independently re-verified the defect and the fix (red → green) rather than assuming the earlier notes were correct.

## Reproducing the reported `scripts/check.sh` failure

`bash scripts/check.sh` on the merged tree → **RC=1**, stopping at `scripts/check.sh:16`:

```
shellcheck not installed: skipping shell lint (CI runs it)
check-pack: opencode.json is stale: run scripts/gen-opencode.py
```

This is the documented **OPS-CW-003-01** environment artifact, not a code regression: `git ls-files -v opencode.json` is `S` (skip-worktree) and the worktree file is byte-identical to `.github/opencode.ci.json` (sha256 `b40d443d…`), while `git show HEAD:opencode.json` is the generator's local profile (`7ceb8b30…`). `agent-run.sh`'s `activate_ci_config` copies the CI profile into the worktree. In a clean CI checkout the committed local profile is used and this step passes.

Other environment-only blockers in this worker (all recorded, none changed — outside scope): PyYAML is not installed (`ModuleNotFoundError: yaml`), so `python3 -I -m unittest discover -s scripts/tests` fails in tooling modules unrelated to `messaging/attachments`; OWASP Dependency-Check's NVD fetch stalls without network/`NVD_API_KEY`. None of them imports or exercises the changed Java.

## Root cause of the merge failure and the fix applied

The real merge problem was **outside the declared Java scope**: parallel workers were all prepending their own story note to the shared `.agent/pr-notes.md`. This branch carried such a prepend (a hand-resolved merge that concatenated the AUD-10 and AUD-11 notes), and the AUD-20261120-12 worker branch did the same. AGENTS.md explicitly forbids editing or committing the shared `.agent/pr-notes.md`, and two branches changing the same lines is the merge conflict the integrator reported.

**Fix:** restored `.agent/pr-notes.md` to the `origin/main` version (`git checkout origin/main -- .agent/pr-notes.md`). The diff of that file against `origin/main` is now empty, so the integrator's merge has no conflict on it, and the AGENTS.md note rule is honored. The story note content is preserved in the allowed per-story files. **No production Java code was rewritten** — re-writing already-correct, fully covered code would only add risk. This is the only change made in this attempt.

## Test-first verification on the merged tree (tool evidence)

- **RED** — temporarily removed only the per-file `.filter` from `events()`, then `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → `RC=1`, `AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (4 facts across two files) was returned instead of the requested file's 2 facts — the exact defect. The file was restored from a byte-for-byte backup and `git diff --quiet` confirmed it identical to `HEAD`.
- **GREEN (fast unit tests)** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → `RC=0`; surefire report `tests="29" errors="0" failures="0" skipped="0"`.

Entry point → test trace: `AttachmentService.events(actor, id)` has no HTTP controller yet (tracked separately as AUD-20261119-03), so the failing path is exercised directly at the service boundary by `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant`. Synthetic fixtures only; no patient data or credentials used.

## Coverage command and result (AGENTS.md Coverage rules)

`cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → **RC=0**. PostgreSQL accepted connections on 127.0.0.1:5432. JaCoCo build-failing thresholds (instruction/line/branch/method/class) passed; surefire totals across all reports: **2350 tests, 0 failures, 0 errors, 0 skipped**.

`target/site/jacoco/jacoco.xml`, class `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:

| Counter | missed | covered |
|---|---|---|
| INSTRUCTION | 0 | 963 |
| BRANCH | 0 | 86 |
| LINE | 0 | 168 |
| COMPLEXITY | 0 | 71 |
| METHOD | 0 | 28 |
| CLASS | 0 | 1 |

Bundle counters are also 0 missed on every type (INSTRUCTION 90675, BRANCH 6403, LINE 15730, METHOD 3814, CLASS 785). → **100% on every production file touched by this story.**

The fix adds no `if`, `?:`, `&&`, `||`, `switch` or `catch`. The single `.filter` lambda has no internal JaCoCo branch; the regression test executes its predicate for a matching fact (own `detail-of-file-a`) and a non-matching fact (sibling `detail-of-file-b`). All pre-existing guards (`authorize`, `visible`, context load) remain covered by the existing suite. No threshold, exclusion or `@Disabled` was introduced and no existing test was weakened.

## Other checks run

- `python3 -I scripts/check-docs.py` → RC=0
- `python3 -I scripts/check-secrets.py` → RC=0
- `python3 -I scripts/check-names.py` → RC=0
- `python3 -I scripts/check-migrations.py --require-baseline` → RC=0
- `bash scripts/check.sh` on the worktree → RC=1 at `check-pack.py` only, for the OPS-CW-003-01 skip-worktree reason above; all earlier policy steps passed.

## Defects found and decisions

- **Defect in scope:** the original cross-file/cross-conversation event leak is fixed and covered. No new in-scope defect found.
- **Process defect (fixed here):** the branch committed an edit to the forbidden shared `.agent/pr-notes.md`, which is the likely merge-conflict source with parallel workers. Reverted to `origin/main`; no content lost because the note is preserved in the allowed per-story files.
- **Out-of-scope blockers (recorded, not changed):** missing PyYAML, stale temp git state in `test_agent_run.py`, OWASP NVD fetch without network/key, and the skip-worktree CI-profile check-pack artifact. These live in `scripts/`, the Python environment and `opencode.json`, all outside this task's scope.
- **Decision:** keep the existing minimal Java fix; change only the out-of-scope shared-note file so the branch merges cleanly. No coverage threshold, exclusion or test was altered.

## Review evidence

- Prior read-only simulated reviews (`smartclinic-senior-backend`, `smartclinic-qa-reviewer`) in `.agent/pr-notes/fix-20261120-105854.md` endorsed the filter as the smallest correct fix and found no in-scope defect. I did not need a new specialist review for the note-file revert (documentation-only, no behavior change).
- Human/clinical/patient evidence: none — contained backend correctness fix with no HTTP entry point yet.

## Missing evidence / why full closure is not claimed

Green `scripts/check.sh` and `scripts/integrator-gate.sh` results on an exact SHA from CI are not available to this worker: I must not call `gh` or push, and local Python tooling tests fail for the environmental reasons above. In-scope Java evidence is complete (independent red → green plus 100% JaCoCo on the merged tree). The integrator should re-run its gate on the resulting commit; with the shared-note conflict removed, the merge should be clean, and any remaining red gate should be reported with its CI log tail so the out-of-scope cause can be attributed.

All commands above were executed by this worker in this checkout with synthetic fixtures only. Evidence provenance: **executed tool commands (tool evidence)**; no patient data, credentials or raw logs stored here.
