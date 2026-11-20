<!-- Merge resolution: both sides replaced the shared notes file with their own story note. Both are preserved below in full; no content was discarded from either side.
Canonical per-story copies: .agent/pr-notes/AUD-20261120-10-20261120-101159.md and .agent/pr-notes/AUD-20261120-11-20261120-101204.md. -->

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

---

# AUD-20261120-11 — Reserved migration V59 collision (READY-35)

Status: **blocked — fix is outside this worker's scope. No production file changed.**

Worker: swarm-3 · Story mark: `[-]` in `TODO.md` (set by the orchestrator) · Scope: `db/migrations` only · Base: HEAD `92a5120`, clean tracked tree.

## Requirement IDs

`OPS-06` (migration change-safety gate: duplicate Flyway versions must fail), `ARC-01`, `ARC-02`, `OPS-02` (tenant-owned, forced-RLS durable state that READY-35..READY-42 add), `RX-02` (the already-applied V59 transition migration). Story ID `AUD-20261120-11` is a daily-audit tracker/tooling finding, not a new clinical requirement.

## Acceptance mapping

| # | Acceptance from the story | Result | Evidence |
|---|---|---|---|
| A1 | Confirm `V59__rx02_ambiguous_transition.sql` exists, is consumed, and that a second V59 fails the migration gate | **Met** (tool) | `ls db/migrations`, `check-migrations.py` baseline exit 0, reproduction exit 1 with exact message below |
| A2 | Identify the correct owner/location of the fix | **Met** (tool + rules) | Story text names "the tracker owner"; fix targets are `TODO.md` and `docs/decisions/0003`, both outside scope `db/migrations` and `TODO.md` is forbidden to workers |
| A3 | Apply the reassignment (READY-35..READY-42 → V60..V67, update `reserved:` fields, the "V59 to V66" sentence, the `V59__service_profiles.sql` path, and the 0003 mapping) | **Not met — out of scope** | `TODO.md` "Instructions for every agent" rules 3 and 8; `AGENTS.md` rule 8; see "Why this is not finished" |
| A4 | Do not damage an applied migration or invent an in-scope change just to produce a diff | **Met** | No file in `db/migrations` was edited, added, renamed or deleted |

## Finding (verified, not assumed)

- `db/migrations/V59__rx02_ambiguous_transition.sql` is tracked, was added by commit `1b1fe29` (merged PR #198, DLV-03), has header `Change: expand`, and is recorded as consumed in `.agent/pr-notes/DLV-03.md` line 24 and 42. It widens a single legal RX-02 transition with `CREATE OR REPLACE FUNCTION`; it is additive and has already been applied by Flyway in CI.
- `TODO.md` line 817 still says persistence tasks reserve "V59 to V66". READY-35 (line 821) reserves `V59` and its `scope` names `db/migrations/V59__service_profiles.sql` (line 824, 830). READY-36..READY-42 reserve V60..V66; `docs/decisions/0003` maps V59..V64 to the in-memory stores.
- `scripts/check-migrations.py` has explicit duplicate-version detection (lines 220-228): a repeated `V<n>` produces `duplicate migration version V<n>` and a non-zero exit. Flyway likewise cannot distinguish two migrations with the same version.

So the audit is correct: when READY-35 is dispatched as written, the first persistence story cannot go green.

## Reproduction (failing check first, synthetic temporary file, removed immediately)

Baseline — one V59 present:

```
$ python3 -I scripts/check-migrations.py --require-baseline
baseline_exit=0
```

Reproduction — a temporary `db/migrations/V59__service_profiles.sql` (the file READY-35 would create), created and removed in the same command with a shell `trap`, never committed:

```
$ printf '%s\n' '-- Change: expand' 'SELECT 1;' > db/migrations/V59__service_profiles.sql
$ python3 -I scripts/check-migrations.py --require-baseline
check-migrations: duplicate migration version V59
reproduction_exit=1
```

After cleanup, `git status --short` is empty. No tracked migration was changed.

## Exact commands run and results (all executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `ls -1 db/migrations/ \| sort -V` | V1..V59 present, exactly one `V59__rx02_ambiguous_transition.sql`; no `V59__service_profiles.sql` |
| `git status --short` / `git rev-parse --short HEAD` | clean tree; HEAD `92a5120` |
| `git ls-files db/migrations/ \| grep -c 'V59__'` | `1` |
| `git log -1 --format='%h %s' -- db/migrations/V59__rx02_ambiguous_transition.sql` | `1b1fe29 DLV-03: Repair integration tests: prescription transmission (#198)` |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (current tree has a unique V59) |
| same command with a temporary second V59 (see above) | exit 1, `check-migrations: duplicate migration version V59` |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1, `Ran 273 tests`, `FAILED (failures=8, errors=10)` — all pre-existing and unrelated (see Defects); this worker changed no Python file |
| `python3 -I scripts/gen-opencode.py --check` | exit 1 — known CI-profile/skip-worktree artifact (OPS-CW-003-01), not a HEAD defect (see Defects) |
| `python3 -I scripts/gen-opencode.py` into the worktree, compared with `git show HEAD:opencode.json` | generated file **identical** to HEAD; the pre-existing worktree file (CI profile, `S` skip-worktree flag) differs. Original bytes restored exactly. |
| `git diff --check` | exit 0 |

No database/integration test was run by this worker; the integrator owns those. Nothing in `db/migrations` was changed, so there is no new production line or branch to cover and no Java/SQL coverage command applies to this outcome.

## Defects and decisions

- **Root cause is tracker state, not migration content.** The DLV-03 V59 migration is correct, applied, immutable, additive and legitimately consumed by RX-02. The stale data is in `TODO.md` (lines 817, 821, 824, 826, 828, 830, 1719 and the reserved fields of READY-36..42) and in `docs/decisions/0003-persistence-of-in-memory-stores.md` (lines 36, 45, 63: the proposed V59 reservation and V59..V64 mapping). A full `grep -n V59` over Markdown confirmed every location; `DLV-03.md`, `DLV-04.md` and `AUD-20261120-07.md` references are historical records of the applied repair and must not be rewritten.
- **Rejected in-scope alternative:** renumbering DLV-03's applied V59 to a free version (for example V67) to free V59 for READY-35. That would rewrite an already-applied Flyway migration, break checksum/history validation, invalidate DLV-03's notes (`V59 is consumed`) and contradict the audit's own expected fix, which reassigns the *READY tasks*, not the applied repair. It also does not address the stale tracker text, and V67 is the version READY-42 already intends to use, so renaming into it would recreate the collision one story later. No such edit was made.
- **No fabricated diff:** producing a cosmetic DB change only to have something to commit would be wrong; the honest in-scope result is no production change plus this handoff note.
- Pre-existing, unrelated fast-test failures (not caused by, and not fixable inside, this story's scope): `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier` and `test_workflow_concurrency`; `gen-opencode.py --check` / `test_check_pack.*` failing because the worker worktree `opencode.json` is the CI profile under `skip-worktree` while HEAD holds the correct generated local profile (MEMORY.md: OPS-CW-003-01 closed as false positive); `AgentRunTests` / `test_swarm` failures depending on local git branch state. These belong to tooling/worker-environment owners, not `db/migrations`.

## Why this is not finished

The fix the story requires cannot be applied within the declared scope `db/migrations`:

1. It requires editing `TODO.md` (the "V59 to V66" sentence, READY-35's `scope`, `V59__service_profiles.sql` path and `reserved: V59`, and the `reserved:` fields of READY-36..READY-42). `AGENTS.md` rule 8 and the "Instructions for every agent" rules 3 and 8 at the top of `TODO.md` explicitly forbid a worker from editing `TODO.md`; the orchestrator/integrator own it.
2. It requires editing `docs/decisions/0003-persistence-of-in-memory-stores.md` (the proposed V59..V64 → V60..V65 mapping). That path is also outside `db/migrations`.
3. `TODO.md` rule 3 says: if the task needs a file outside its scope, stop, write the reason here, and finish without changing that file. `TODO.md` rule 9 says not to guess when the task and rules disagree.

I therefore stopped before making any production change.

## Exact next step (tracker owner / orchestrator)

Before dispatching READY-35, edit the two out-of-scope trackers:

1. `TODO.md` line 817: change "V59 to V66" to "V60 to V67".
2. `TODO.md` READY-35 (lines 821-830): `reserved: V59` → `V60`; `scope` path `db/migrations/V59__service_profiles.sql` → `db/migrations/V60__service_profiles.sql`; the sentence "This store owns migration V59 only" → V60; step 2 "Create `db/migrations/V59__service_profiles.sql`" → V60.
3. `TODO.md` READY-36..READY-42: shift each `reserved:` and migration `scope` path up by one — V60→V61, V61→V62, V62→V63, V63→V64, V64→V65, V65→V66, V66→V67 — and update the in-details "owns migration V<n> only" / "Create `db/migrations/V<n>__*.sql`" references to match.
4. `docs/decisions/0003-persistence-of-in-memory-stores.md`: update the "proposed V59" reservation and the V59..V64 mapping to V60..V65 (V59 stays with the applied RX-02 repair).
5. Re-run `python3 -I scripts/check-migrations.py --require-baseline` and `bash scripts/quick-check.sh` after the tracker edits, then dispatch READY-35. Leave the applied `V59__rx02_ambiguous_transition.sql` untouched.

## Entry-point-to-test trace

`db/migrations/V*.sql` → `scripts/check-migrations.py` (filename parse → version set → duplicate-version check at lines 220-228) → `check-migrations: duplicate migration version V59` + exit 1. The same duplicate is what Flyway would reject when two `V59__*.sql` files are present; the reproduction above exercises that gate with a synthetic temporary file. This task ends at the gate result because the corrective edits live in the trackers, not in `db/migrations`.

## Evidence provenance

- **Tool evidence (executed by this worker):** `ls`, `git status`, `git log`, `git ls-files`, `git show`, `cmp`, `grep`, `python3 -I scripts/check-migrations.py --require-baseline` (baseline and reproduction), `python3 -I -m unittest discover -s scripts/tests -q`, `python3 -I scripts/gen-opencode.py --check`, `git diff --check`, with results recorded above.
- **Reviewer evidence (simulated reviewer, read-only, no commands run by the reviewer):** the `smartclinic-senior-database` specialist independently confirmed both decisions. It added that an applied migration's identity (version + description + checksum in `flyway_schema_history`) is immutable; with default `validateOnMigrate` (test suites call `Flyway.configure().load().migrate()` with no override), a database that already applied V59 fails validation with "applied migration not resolved locally: 59" if the file is renamed. It also confirmed that `check-migrations.py:220-228` deliberately allows version gaps (so HEAD already passes) and that renaming V59 to V67 would consume READY-42's intended version and recreate the collision. It agreed the correct outcome is no `db/migrations` change plus this handoff, with tracker-state edits owned by the orchestrator/integrator. Its finding is incorporated in the Decisions and Next-step sections above; no reviewer command output is claimed.
- **Simulated-user / human / clinical evidence:** none. No patient data, credential, token or real domain name appears in this work; all fixtures and the temporary reproduction file are synthetic.
