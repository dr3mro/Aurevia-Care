# AUD-20261120-10 — Attachment event history returns every event in the tenant, not the requested file's trail

## Requirements and acceptance mapping

- **MSG-07, ARC-07** — Audit and bound every attachment event to one conversation/file; a caller authorized for one file must not receive another file's event `detail` (scanner detail, void/rejection free text) within the same tenant.
- **Acceptance (from story):** `events(actor, id)` returns only the facts whose `attachmentId` equals the requested file's id; a test creates two attachments in one tenant and asserts each trail contains only its own file's events.
- **In scope / touched:** `AttachmentService.java`, `AttachmentAcceptanceTest.java` (both under `messaging/attachments`). No migration used. No new folder or package; naming is business-domain (`messaging.attachments`).

## Defect

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the entire tenant-wide fact ledger — after authorizing and checking visibility for only the single named file. Its sibling `view()` (line 273) already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is per-organization (line 66), so the leak is cross-file / cross-conversation **within one tenant**, not cross-tenant. The service has no HTTP controller yet (latent; entry point tracked separately as AUD-20261119-03), so this is a correctness fix for an unreachable-yet path. Synthetic fixtures only; no patient data or credentials used.

## Fix

In `events()`, after the existing `authorize` / `load` / `visible` guards, capture the canonical `String fileId = current.id();` and filter the tenant fact stream with `.filter(fact -> fileId.equals(fact.attachmentId()))` before `.toList()`, mirroring `view()` exactly. Tenant scoping (`store.events(organizationId())`), visibility gating (`visible()`), and use of the stored canonical id (not the raw request parameter) are all preserved. No interface, store or schema change.

## New branches and covering tests (AGENTS.md Coverage rules)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch; it must simply be executed. The new test executes it for a matching fact (a's own events) and a non-matching fact (b's events), covering both outcomes of the predicate. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`, `requireVersion`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

New test asserts: each trail has exactly 2 facts (upload + scan_failed); every fact in a trail carries that file's `attachmentId`; distinguishing scanner detail (`detail-of-file-a` / `detail-of-file-b`) does not cross trails; and `events()` equals its sibling `view().events()` — directly locking the divergence that caused the defect.

## Commands run and results (tool evidence)

- Baseline before change: `cd apps/api && mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → 28 tests, 0 failures.
- **Red phase:** `mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → FAILED, `AssertionFailedError: expected: <2> but was: <4>` (tenant-wide ledger returned 4 facts across two files instead of the requested file's 2). Confirms the failing test detects the missing filter.
- **Green phase:** `mvn -q -B -Pno-security -Dtest=AttachmentAcceptanceTest test` → Tests run: 29, Failures: 0, Errors: 0, Skipped: 0.
- **Full coverage gate (includes all unit + integration tests):** `cd apps/api && mvn -q -B -Pno-security verify` → `MVN_EXIT=0` (JaCoCo BUNDLE 100% rule on INSTRUCTION/LINE/BRANCH/METHOD/CLASS passed). Run twice (before and after the parity assertion), both green. PostgreSQL was accepting connections on 127.0.0.1:5432; `SMARTCLINIC_PG_ADMIN=postgres`.
- **JaCoCo report for changed file** (`target/site/jacoco/jacoco.xml`, `com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`): INSTRUCTION missed=0 covered=963; BRANCH missed=0 covered=86; LINE missed=0 covered=168; COMPLEXITY missed=0 covered=71; METHOD missed=0 covered=28; CLASS missed=0 covered=1. → **100% on every changed production file.**
- `mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → exit 0.
- Policy checks: `check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` → all exit 0. Java conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK (no unqualified Javadoc links; no ID-style names).

All above evidence came from **executed tool commands** in this checkout, with synthetic fixtures only. No reviewer, simulated user or human participant evidence was required for this contained correctness fix.

## Review evidence

- **`smartclinic-senior-backend` (simulated reviewer, read-only):** endorsed the filter as the smallest correct fix; confirmed tenant scoping, visibility gating and canonical-id choice are preserved; confirmed no new JaCoCo branch and no EI_EXPOSE_REP/REP2 concern (`Stream.toList()` returns an unmodifiable list; `AttachmentEvent` is an immutable record). Recommended the parity assertion `view().events() == events()`, which was adopted. No files edited by the reviewer.
- Human/clinical/patient evidence: none (contained backend correctness fix, no controller entry point yet).

## Unrelated pre-existing gate failures (environment, not caused by this change)

`bash scripts/worker-check.sh` was attempted. It reaches and passes shell syntax, check-pack, check-docs, check-secrets, check-names and check-migrations (with the worktree `opencode.json` temporarily aligned to the local profile), then stops in `python3 -I -m unittest discover -s scripts/tests` on failures confined to tooling modules that this Java-only change does not touch:

- `test_agent_run` (5 FAIL / several ERROR): stale git branch state, e.g. `git branch -D opencode/issue-5-9` returns non-zero; merge/check expectations depending on shared runner environment.
- `test_check_pack` (3 FAIL): compares the worktree `opencode.json` (the CI profile swapped in by `agent-run.sh` under `skip-worktree`) against the local profile. HEAD's committed `opencode.json` was verified to equal the generator's local profile (`HEAD matches LOCAL profile: True`), and the worktree file equals `.github/opencode.ci.json`. This is the documented OPS-CW-003-01 false positive, not a defect in this change.
- `test_fast_tier`, `test_workflow_concurrency` (import ERROR): `ModuleNotFoundError: yaml` (PyYAML not installed in this environment).

The worktree `opencode.json` was restored to its original CI profile after the check; final `git status` shows only the two intended Java files modified. Because the `set -e` gate stops at the Python unit tests, its final `mvn -Pno-security,fast test` step was not reached there — but the full `mvn verify` above already ran every unit and integration test green, and the fast unit tests were run directly via the `-Dtest=AttachmentAcceptanceTest` commands.

The story itself is complete: the new failing test reproduced the defect, the minimal fix turns it green, and 100% coverage on every changed file is proven by the executed JaCoCo gate and report.
