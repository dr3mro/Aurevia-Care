# AUD-20261120-10 (fix attempt `fix-20261120-133603`) — Attachment event history returns every event in the tenant, not the requested file's trail

Branch: `swarm/AUD-20261120-10-289`. Scope (unchanged): `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration, no new Java package or folder, no file outside scope changed for the fix. Synthetic fixtures only; no patient data or credentials.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit and bind every attachment event to one conversation/file; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events` filters tenant facts by `attachmentId` |
| ARC-07 | A file is accessed only through its own verified context; per-file facts stay in that file's trail | same filter; tenant scoping and file-visibility gates preserved |
| OPS-02 | Facts carry actor/purpose/scope and denial stays concealed | unchanged `authorize` / `visible` / `notFound` guards |

Story acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in one tenant and asserts each trail contains only its own file's events, that `detail-of-file-a` / `detail-of-file-b` do not cross trails, and that `events()` equals its sibling `view().events()`.

## Defect (code, already fixed on this branch before this repair)

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the whole tenant-wide fact ledger — after authorizing and visibility-checking only the single named file. Its sibling `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is per-organization, so this is a cross-file / cross-conversation leak **within one tenant**, not cross-tenant. `AttachmentEvent.detail` carries scanner detail and void/rejection free text (MSG-07, ARC-07). No HTTP controller exists yet (latent entry point tracked as AUD-20261119-03). The existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only checked `organizationId`, so it never caught the missing per-file filter while line coverage stayed 100%.

Fix (minimal, present in the working tree): in `events()`, capture `String fileId = current.id();` and add `.filter(fact -> fileId.equals(fact.attachmentId()))` before `.toList()`, mirroring `view()` exactly. Tenant scoping, visibility gating and use of the canonical stored id (not the raw request parameter) are preserved. No interface, store, schema or threshold change.

## Why the integrator merge failed, and the repair performed

The code fix was already correct. The repeated integrator failure ("merge conflict with main could not be resolved automatically (merged but checks failed)") was caused by this branch **overwriting the forbidden shared file `.agent/pr-notes.md`** — each worker replaced the AUD-20261120-13 notes committed on `main` with this story's notes, which conflicts with parallel workers editing the same shared file. AGENTS.md and this task require notes only in this uniquely named file.

Repair: restored `.agent/pr-notes.md` to the exact content of `origin/main` in the working tree (verified byte-identical), so the branch's net diff against `main` is only the two scoped Java files plus uniquely named `.agent/pr-notes/*.md` additions. No shared tracker, workflow or protected file was edited; no push, tag or `gh` call was made.

## Reproduction of the check.sh failure (tool evidence)

- `bash scripts/check.sh` on the inherited merge state (`origin/main` is an ancestor of `HEAD`, so the merge result equals `HEAD`) → exit 1, first failure `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  - Diagnosis: `git ls-files -v opencode.json` = `S` (skip-worktree); the worktree file was the CI profile under skip-worktree, so `check-pack.py`/`gen-opencode.py --check` judged the CI profile, not HEAD. `git show HEAD:opencode.json`, `git show origin/main:opencode.json` and the generator's local render are byte-identical. This is the documented OPS-CW-003-01 environment artifact (`scripts/agent-run.sh` `activate_ci_config`/`restore_config`), **not** a branch defect. Regenerating the worktree file to the local profile made `gen-opencode.py --check` and `check-pack.py` exit 0; HEAD was never changed.
- With `opencode.json` aligned, `bash scripts/check.sh` reached and passed shell syntax, check-pack, check-docs, check-secrets, check-names and check-migrations, then failed in `python3 -I -m unittest discover -s scripts/tests` on pre-existing **environment** failures (6 FAIL / 10 ERROR) in `test_agent_run` (stale temp-git refs, e.g. `refs/heads/opencode/issue-5-9` absent), `test_swarm` (PyYAML not installed: `ModuleNotFoundError: yaml`), `test_fast_tier` and `test_workflow_concurrency` (same import error). These are in tooling scripts untouched by this Java-only change and are documented as environment failures in the prior attempt's notes; they are not caused by this branch. The integrator image has PyYAML and clean git fixtures, so these do not fail there.

## Commands run and results (executed tool evidence, synthetic fixtures only)

- Fast unit test: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` → **exit 0**; surefire report `tests="29" errors="0" skipped="0" failures="0"`.
- Full coverage gate: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` → **exit 0** (PostgreSQL accepting connections on 127.0.0.1:5432; build-failing JaCoCo 100% rule on INSTRUCTION/LINE/BRANCH/METHOD/CLASS passed). Surefire totals: `tests=2361 errors=0 skipped=0 failures=0`.
- JaCoCo report `apps/api/target/site/jacoco/jacoco.xml`:
  - Changed file `com/aurevia/smartclinic/messaging/attachments/AttachmentService.java`: INSTRUCTION missed=0 covered=963; BRANCH missed=0 covered=86; LINE missed=0 covered=168; COMPLEXITY missed=0 covered=71; METHOD missed=0 covered=28; CLASS missed=0 covered=1 → **100% on every counter**.
  - Bundle totals: INSTRUCTION 90701/90701, BRANCH 6407/6407, LINE 15736/15736, COMPLEXITY 7070/7070, METHOD 3815/3815, CLASS 785/785 → **100.00% on all five enforced counters**.
- Static analysis (prior attempt, unchanged code): `mvn -q -B -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → exit 0.
- Policy: `python3 -I scripts/check-docs.py` → 0; `python3 -I scripts/check-names.py` → 0; `python3 -I scripts/check-secrets.py` → 0; `python3 -I scripts/check-pack.py` → 0; `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK; shared `.agent/pr-notes.md` verified byte-identical to `origin/main`.

## New or changed branches and their covering tests (AGENTS.md coverage rule 6)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda: JaCoCo instruments it as a synthetic method with no internal branch; it must simply execute. `String fileId = current.id();` is straight-line. All pre-existing branches (`authorize`, `visible`, `requireVersion`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` |

No existing test was weakened, deleted or skipped; no threshold, exclusion or generated file was changed.

## Defects found / decisions

- Primary defect: per-file event-history leak (fixed with the single filter + regression test).
- Repair defect: shared `.agent/pr-notes.md` overwrite causing the integrator merge conflict (fixed by restoring it; this dedicated file owns this run's notes).
- Decision: keep the smallest code fix and add the `events() == view().events()` parity assertion so the divergence cannot regress. Do not add an HTTP controller (AUD-20261119-03) or durable store (READY-* persistence stories) — both out of scope.

## Evidence sources

All commands above are **executed tool evidence** from this checkout with synthetic fixtures only. Skills loaded as instructions: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. No reviewer, simulated user or human participant evidence was required for this contained backend correctness fix. Human/clinical/patient evidence: none.

## Entry-point-to-test trace

`AttachmentService.events(Actor,String)` (no HTTP controller yet) → `store.events(org)` → per-file `.filter` on `attachmentId` → `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` asserts the returned trail, absence of the sibling file's detail, and parity with `view().events()`.
