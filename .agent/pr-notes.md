# AUD-20261120-10 — fix attempt 1 of 3 — Attachment event history per-file scope

## Requirements and acceptance mapping

- **MSG-07, ARC-07** — Audit and bound every attachment event to one conversation/file. A caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) within the same tenant.
- **Acceptance:** `AttachmentService.events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id, exactly as `view()` does. A test creates two attachments in one tenant and asserts each trail contains only that file's events.
- **In scope:** `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments`, `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration, no new package/folder; naming stays business-domain (`messaging.attachments`).
- **Not changed:** `opencode.json`, `.opencode/*`, `.github/workflows/*`, `scripts/*`, `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*` — all outside the declared scope or owned by the orchestrator/integrator.

## State on arrival and decision

`HEAD` is the already-merged `bef602f` (parents `2f261c4` = the original fix, `7fd16c9` = main). The fix and its regression test are **already present** in the merged tree:

- `AttachmentService.events()` (`AttachmentService.java:287-297`) captures `String fileId = current.id();` and filters `store.events(actor.organizationId())` with `.filter(fact -> fileId.equals(fact.attachmentId()))`, mirroring `view()` (`:266-277`). The only two `store.events()` call sites (`:273`, `:294`) are both filtered.
- `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (`AttachmentAcceptanceTest.java:434-455`) creates two attachments in one tenant with distinct scanner details and asserts per-file trails plus `events() == view().events()` parity.

`git diff $(git merge-base 2f261c4 7fd16c9) 7fd16c9 -- <scope paths>` is **empty**: main changed nothing in the scope. Both read-only reviewers independently concluded the in-scope fix is correct and complete and that no further in-scope change is needed. **Decision: no production code change.** Re-writing working, fully covered code would only add risk; the smallest correct action is to re-verify the merged state and record the evidence.

## Test-first verification (red → green) on the merged tree

- **RED** — temporarily removed only the per-file filter from `events()` (backup/restore of the single tracked file), then:
  `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test`
  → `MVN_RC=1`; `AssertionFailedError: expected: <2> but was: <4>` at `AttachmentAcceptanceTest.java:443`. The tenant-wide ledger (4 facts across two files) was returned instead of the requested file's 2 facts — the exact defect.
  The file was restored; `git diff --quiet -- <file>` confirmed it is identical to `HEAD`.
- **GREEN** — `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test`
  → `RC=0`; surefire report: **Tests run: 29, Failures: 0, Errors: 0, Skipped: 0**.
- Working tree after the red/green check: clean (no accidental source edits).

## Coverage command and result (AGENTS.md Coverage rules)

`cd apps/api && SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -Pno-security verify` → **exit 0**.
PostgreSQL accepted connections on `127.0.0.1:5432`; JaCoCo build-failing thresholds (instruction/line/branch/method/class) passed. Surefire totals across all reports: **2350 tests, 0 failures, 0 errors, 0 skipped**.

`target/site/jacoco/jacoco.xml`, class `com/aurevia/smartclinic/messaging/attachments/AttachmentService`:

| Counter | missed | covered |
|---|---|---|
| INSTRUCTION | 0 | 963 |
| BRANCH | 0 | 86 |
| LINE | 0 | 168 |
| COMPLEXITY | 0 | 71 |
| METHOD | 0 | 28 |
| CLASS | 0 | 1 |

Bundle totals are also 0 missed on every counter. → **100% on every production file touched by this story.** The fix adds no `if`/`?:`/`&&`/`||`/`switch`/`catch`; the single `.filter` lambda has no internal branch and is executed for both a matching fact (own `detail-of-file-a`) and a non-matching fact (sibling `detail-of-file-b`) by `eventsAreScopedToTheRequestedAttachmentWithinOneTenant`. All pre-existing guards (`authorize`, `visible`, `requireVersion`) remain covered by the existing suite.

`bash apps/api/check.sh` (plain `mvn -q -B verify`, i.e. with OWASP/SpotBugs) was attempted first: all unit and integration tests plus JaCoCo completed, then the process stalled for >10 minutes inside OWASP Dependency-Check's NVD fetch (thread names `BfDependencyCollect`, `httpclient-dispatcher`, `MVStore background`; no `NVD_API_KEY` in this environment and no network progress). It was stopped (rc 143). The equivalent gate with the documented `-Pno-security` profile ran green end to end. This is an environment/network limitation, not a code failure; SpotBugs was green in the prior recorded run of this story (`.agent/pr-notes/AUD-20261120-10-20261120-101159.md:35`).

## Reproduction of the reported `scripts/check.sh` failure

`bash scripts/check.sh` on the merged tree fails **before** any module check, at `scripts/check.sh:21`:

- `python3 -I -m unittest discover -s scripts/tests -q` → **Ran 271 tests, FAILED (failures=6, errors=10)**, all confined to out-of-scope tooling tests:
  - `test_agent_run.py` (6 FAIL + 2 ERROR): stale temp-repository git state, e.g. `git branch -D opencode/issue-5-9` returns non-zero; `test_resolve_*` expects `merged` but the helper writes `merged but checks failed` after its own check step fails in this environment.
  - `test_swarm.py` Plan/Budget tests (8 ERROR) plus `test_fast_tier` / `test_workflow_concurrency` loader errors: `ModuleNotFoundError: No module named 'yaml'` (PyYAML is not installed here).
- Earlier in the same run, `python3 -I scripts/check-pack.py` reported `opencode.json is stale`. This is the documented **OPS-CW-003-01 false positive**: `git ls-files -v` shows flag `S` (skip-worktree) and the worktree file is byte-identical to `.github/opencode.ci.json`, copied there by `agent-run.sh`'s `activate_ci_config`. With `git show HEAD:opencode.json` restored in the worktree, `python3 -I scripts/gen-opencode.py --check` → 0 and `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 33 agents, 33 skills` (0). The worktree CI profile was restored afterwards; final `git status` is clean.
- Other policy steps all exit 0 locally: shell syntax/shellcheck (shellcheck not installed; `check.sh` skips it), `project-name.py`, `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`.

None of these failures imports or exercises `messaging/attachments`; they are environment/tooling artifacts, not a regression from this story. The integrator's log tail was not included in the story text and I cannot call `gh` to read the PR comment, so I cannot conclusively attribute the original CI failure to one of them.

## Defects found and decisions

- **Defect in scope:** none. The original cross-file leak is fixed and covered on the merged tree.
- **Out-of-scope blockers observed:** missing PyYAML, stale temp git state in `test_agent_run`, OWASP NVD fetch without network/API key, and the skip-worktree CI-profile check-pack artifact. These live in `scripts/`, the Python environment and `opencode.json`, all explicitly outside this task's scope; they were recorded, not changed.
- **Decision:** keep the existing minimal fix; commit the verification evidence only. No threshold, exclusion or test was weakened; no test was skipped.

## Review evidence

- **`smartclinic-senior-backend`** (simulated, read-only): fix mirrors `view()` exactly, uses the canonical id, preserves tenant/visibility guards, adds no JaCoCo branch, no additional in-scope change required. No files edited.
- **`smartclinic-qa-reviewer`** (simulated, read-only): reproduced failures are environment artifacts outside the scope; no in-scope defect or missing test; missing evidence is a green SHA-linked CI/integrator result, which needs `gh`/CI access. No files edited.
- Human/clinical/patient evidence: none (contained backend correctness fix; no HTTP controller yet — that entry point is tracked separately as AUD-20261119-03).

## Missing evidence / why full closure is not claimed

Green `scripts/check.sh` and `scripts/integrator-gate.sh` results on exact SHA `bef602f` (or the new commit) from CI are not available to me: I must not call `gh`, and Python tooling tests fail locally for the environmental reasons above. The in-scope Java evidence is complete (red/green plus 100% JaCoCo on the merged tree). The integrator should re-run its gate on this commit; if it still fails, the CI log tail is required to identify the out-of-scope cause.

All commands above were executed by this worker in this checkout with synthetic fixtures only; no patient data, credentials or raw logs are stored here. Evidence provenance: executed tool commands (tool evidence) and two read-only simulated reviewers.
