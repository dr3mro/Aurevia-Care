# AUD-20261120-10 (repair run `fix-20261120-135546`) — Attachment event history returns every event in the tenant

Branch: `swarm/AUD-20261120-10-289`. Scope (unchanged): `apps/api/src/main/java/com/aurevia/smartclinic/messaging/attachments` and `apps/api/src/test/java/com/aurevia/smartclinic/messaging/attachments`. No migration, no new Java package/folder, and no file outside the declared scope is changed by the code fix. Synthetic fixtures only; no patient data or credentials.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-07 | Audit and bind every attachment event to one file/conversation; a caller authorized for one file must not receive another file's scanner detail or void/rejection free text | `AttachmentService.events` filters the tenant fact stream by `attachmentId`, matching its sibling `view()` |
| ARC-07 | A fact is reachable only through its own verified file context; per-file facts stay in that file's trail | same filter; tenant scoping and file-visibility gates preserved |
| OPS-02 | Facts carry actor/purpose/outcome; a denied read stays concealed | unchanged `authorize`, `visible` and `notFound` guards |

Acceptance: `events(actor, id)` returns only facts whose `attachmentId` equals the requested file's canonical id. `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` creates two attachments in one tenant and asserts each trail holds only that file's events, that `detail-of-file-a`/`detail-of-file-b` never cross trails, and that `events()` equals `view().events()`.

## Defect and minimal fix (already present on this branch from the original commit)

`AttachmentService.events(actor, id)` returned `store.events(actor.organizationId()).stream().toList()` — the entire tenant-wide ledger — after authorizing and visibility-checking only the named file. `view()` already filtered with `fileId.equals(fact.attachmentId())`. `InMemoryAttachmentStore.events(org)` is per-organization, so this is a cross-file/cross-conversation leak **within one tenant**, not cross-tenant. `AttachmentEvent.detail` carries scanner detail and void/rejection free text. The existing `twoTenantsWithIdenticalLocalIdentifiersStayIsolated` test only asserted `organizationId`, so it never caught the missing per-file filter while line coverage stayed 100%. No HTTP controller exists yet (latent entry point tracked as AUD-20261119-03).

Fix in `events()`: capture `String fileId = current.id();` and add `.filter(fact -> fileId.equals(fact.attachmentId()))` before `.toList()`, mirroring `view()`. Tenant scoping, visibility gating and use of the canonical stored id (not the raw request parameter) are preserved. No interface, store, schema or coverage-threshold change.

## Why the integrator failed and the repair performed (tool evidence)

The code fix was already correct. The integrator failure — "merge conflict with main could not be resolved automatically (merged but checks failed)" — was caused by prior attempts **overwriting the forbidden shared file `.agent/pr-notes.md`**: this branch replaced `main`'s `AUD-20261120-13` notes with this story's notes. Parallel swarm workers all edit that one shared file, so every merge conflicted. AGENTS.md and this task require notes only in a uniquely named file.

Repair performed on the current branch (no push, tag or `gh`):
1. `git restore --source=origin/main --staged --worktree -- .agent/pr-notes.md` — restores the shared file byte-identically to the merge base. Worktree first line is again `# AUD-20261120-13 ...`. Because this branch is now unchanged from the base on that path, a three-way merge takes `main`'s version cleanly regardless of concurrent edits.
2. `git rm -f .agent/pr-notes/fix-20261120-134632.md` — removes a stale prior-attempt note file (added on this branch, absent from `origin/main`), so this run contributes exactly one uniquely named notes file and the PR stays at 3 files.
3. Created this file, `.agent/pr-notes/fix-20261120-135546.md`.

`git fetch origin main` → `origin/main` = `742f2bb` = `git merge-base HEAD origin/main`; `git rev-list --left-right --count HEAD...origin/main` = `29 0` (branch ahead, main unchanged), so replaying the merge yields HEAD and the only conflict source was the shared-file edit.

## Commands actually run and results (executed tool evidence)

- **Red** — temporarily installed the buggy `origin/main` `AttachmentService.java`, then from `apps/api`: `mvn -q -B -f pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest#eventsAreScopedToTheRequestedAttachmentWithinOneTenant test` → **exit 1**, `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant:443 expected: <2> but was: <4>` (tenant ledger returned 4 facts across two files instead of the requested file's 2). Confirms the test detects the missing filter. Fixed file restored byte-identically from a backup.
- **Green** — fixed file restored; `mvn -q -B -f pom.xml -Pno-security,fast -Dtest=AttachmentAcceptanceTest test` → **exit 0**; surefire `TEST-...AttachmentAcceptanceTest.xml` = `tests="29" errors="0" skipped="0" failures="0"`.
- **Full coverage gate** — `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` → **exit 0**; PostgreSQL accepting connections on 127.0.0.1:5432; Java 21.0.13, Maven 3.10.0. `-Pno-security` skips only the network-dependent OWASP NVD download; unit tests, integration tests and the 100% JaCoCo check all run.
- Surefire aggregate over `apps/api/target/surefire-reports/TEST-*.xml`: **210 report files, 2361 tests, 0 failures, 0 errors, 0 skipped**.
- **Static analysis** — `mvn -q -B -f apps/api/pom.xml -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → **exit 0**.
- **Policy checks** — `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` → all **exit 0**.
- `bash scripts/frontend-check.sh` → **exit 0**; `(cd db && bash check.sh)` → **exit 0** (migrations, forced RLS, role separation, isolation, audit, append-only history and versioned config verified).

### Reproduction of `scripts/check.sh` (as the task requested)

`bash scripts/check.sh` → **exit 1**, stops at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Diagnosis: `git ls-files -v opencode.json` = `S` (skip-worktree); the worktree file is the CI profile installed by `agent-run.sh`, which differs from `git show HEAD:opencode.json`. `git show HEAD:opencode.json` is byte-identical to `git show origin/main:opencode.json` — this branch does not touch it. This is the documented OPS-CW-003-01 environment artifact, not a branch defect; the integrator image restores the committed local profile before checks. Running the remaining steps individually: policy checks, frontend and db checks all pass (above); `python3 -I -m unittest discover -s scripts/tests -q` → exit 1 (Ran 271, 9 failures, 10 errors) from `ModuleNotFoundError: No module named 'yaml'`, the skip-worktree `test_check_pack` drift failures, and `git branch -D` errors in throwaway temp repos. All are container-environment issues; none touches the two scoped Java files, and the integrator image supplies PyYAML and the committed profile.

## Coverage (100% gate)

`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` → exit 0. Read directly from `apps/api/target/site/jacoco/jacoco.csv` and `jacoco.xml`:

| Scope | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `messaging/attachments/AttachmentService.java` (changed file) | 0/963 | 0/86 | 0/168 | 0/28 | 0/1 |
| Bundle (all production) | 0/90701 | 0/6407 | 0/15736 | 0/3815 | 0/785 |

Zero missed on every counter, including the changed file and its class. JaCoCo's build-failing 1.00 rules for INSTRUCTION/LINE/BRANCH/METHOD/CLASS therefore passed. No threshold, exclusion or generated file was changed.

## New or changed branches and their covering tests (AGENTS.md coverage rule 6)

The fix adds **no** `if`, `?:`, `&&`, `||`, `switch` or `catch`. It adds one `.filter(...)` lambda, which JaCoCo instruments as a synthetic method with no internal branch, plus one straight-line assignment. All pre-existing branches (`authorize`, `visible`, `requireVersion`) remain covered by the existing suite.

| New code | Covering test |
|---|---|
| `events()` per-file `.filter` predicate — true outcome (matching `attachmentId`) | `eventsAreScopedToTheRequestedAttachmentWithinOneTenant` (both trails contain only their own file's events) |
| `events()` per-file `.filter` predicate — false outcome (sibling file's fact rejected) | same test (`detail-of-file-b` absent from a's trail and vice versa) |

No existing test was weakened, deleted or skipped.

## Defects found and decisions

- Primary defect: per-file event-history leak (fixed by the single filter + regression test).
- Repair defect: shared `.agent/pr-notes.md` overwrite causing the integrator merge conflict (fixed by restoring it to the merge base); a stale prior-attempt note file was removed to keep the PR small.
- Decision: keep the smallest code fix and the `events() == view().events()` parity assertion so the divergence cannot regress. Do not add an HTTP controller (AUD-20261119-03), a durable store (persistence stories) or touch the similar `QuarantineService.events` pattern in the documents domain (out of scope). No clinical decision or production deployment is required; all fixtures are synthetic.

## Entry-point-to-test trace

`AttachmentService.events(Actor,String)` (no HTTP controller yet) → `store.events(org)` → per-file `.filter` on `attachmentId` → `AttachmentAcceptanceTest.eventsAreScopedToTheRequestedAttachmentWithinOneTenant` asserts the returned trail, absence of the sibling file's detail, and parity with `view().events()`.

## Evidence sources

All commands above are **executed tool evidence** from this checkout with synthetic fixtures only. Skills loaded as instructions: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. No specialist subagent, simulated user or human participant was used for this bounded backend correctness fix and merge-conflict repair. Human/clinical/patient evidence: none.

No decision or evidence is missing; code, red/green tests, per-branch test mapping and 100% coverage are proven. Committing to the current branch; no push, tag or `gh` call.
