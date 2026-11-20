# REFACTOR-PACKAGES-02 — rename remaining story-ID Java packages to business domains

Owner decision: 2026-11-20 (delegated to orchestrator). Mapping and option A (mechanical
update of every reference, including `contracts/`) are approved. No behavior change.
Branch `swarm/REFACTOR-PACKAGES-02-260`, base `2b184f6`, clean tree at start.

## Requirement and acceptance mapping

| Item | Acceptance | Evidence |
|---|---|---|
| CODING_STYLE.md package/folder naming | No `x_0` package or folder remains under `apps/api` or `contracts`; no reference to an old path remains in scope | Final searches: no matches (below) |
| No behavior change | Only `package`/`import` lines, contract path text and `DocumentInputs` -> `ArtifactInputs` change; no logic, assertion or test removed or weakened | `git diff` review: 239 insertions / 239 deletions, all mechanical; 2096 tests pass including integration |
| Move history preserved | `git mv` used for every moved file | Staged rename summary: 33 renames, all 100% similarity |

Package mapping (main and mirrored tests):

| Old | New |
|---|---|
| `com.aurevia.smartclinic.api.x_01` | `com.aurevia.smartclinic.api.contract` |
| `com.aurevia.smartclinic.api.x_02` | `com.aurevia.smartclinic.events` |
| `com.aurevia.smartclinic.api.x_03` | `com.aurevia.smartclinic.documents` (merge; `DocumentInputs` -> `ArtifactInputs`) |

Contract folder mapping: `contracts/x_01` -> `contracts/contract`, `contracts/x_02` ->
`contracts/events`, `contracts/x_03` -> `contracts/documents`.

Collision check (before any move): `documents/DocumentInputs.java` already exists and owns
CLN-07 chart-document commands; the moved ARC-06 class was therefore renamed `ArtifactInputs`.
No other simple-name collision exists between the moved classes and `documents/`.
`api/x_04` no longer exists and is not part of this task.

## Changes made

- 33 `git mv` renames: 23 main Java, 5 test Java, 5 contract files. All recorded by Git as
  100%-similarity renames.
- FQN replacement in 29 Java files (the 28 moved files plus
  `api/authorization/CommandClientGenerator.java`, which imports `RequestDigest`).
- Simple-name `DocumentInputs` -> `ArtifactInputs` in the 5 moved ARC-06 files that use it.
- Contract prose/path updates: `contracts/contract/README.md`, `contracts/events/README.md`,
  `contracts/documents/document-artifacts.md`, `contracts/openapi/README.md` (X-01 link and
  the note about the remaining story-ID folders).
- No generated artifact was modified (`git diff --name-only -- contracts/openapi/generated`
  is empty). The generator FQN was already `com.aurevia.smartclinic.api.authorization.CommandClientGenerator`.

## Entry point to test trace

- `X-Api-Version` / `Idempotency-Key` / `cursor` / replay -> `api.contract.ApiVersion`,
  `IdempotencyKey`, `Cursor`, `IdempotencyLedger` ->
  `api.contract.ApiContractPrimitivesTest`, `api.contract.IdempotencyLedgerTest` (all green
  under the new package).
- `POST /api/v1/delivery/**` -> `events.DeliveryController` -> `events.DurableEventService` ->
  `events.DeliveryStore` -> `events.DeliveryDomainTest`, `events.DurableEventServiceTest`.
- Upload/scan/quarantine and PDF binding -> `documents.QuarantineService`,
  `documents.PdfBindingService`, `documents.ArtifactInputs` -> `documents.DocumentArtifactTest`.

## Exact commands and results (all executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test-compile` (after `git mv`, before text edits) | exit 1 — RED: `ArtifactInputs.java:[21,14] class DocumentInputs is public, should be declared in a file named DocumentInputs.java` |
| `grep -rInE 'x_0[0-9]' apps/api contracts -I` (excluding `target`) | no matches |
| `grep -rIn 'com.aurevia.smartclinic.api.x_0' apps/api contracts -I` | no matches |
| `git diff --check` | exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 — **Tests run: 1853, Failures: 0, Errors: 0, Skipped: 0**; the five moved test classes ran as `api.contract.*`, `events.*` and `documents.DocumentArtifactTest` (38 tests) |
| `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` | surefire **2096 tests, 0 failures, 0 errors** (unit + all real-PostgreSQL integration); exit 1 only at `jacoco:check` — see Pre-existing blockers |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` then `python3 -I scripts/worker_gate.py $?` | mvn exit 0; `worker_gate.py`: `integration tests passed`, exit 0 |
| JaCoCo CSV audit of moved classes | 42 production classes in `api.contract`, `events` and moved `documents` have **0 missed instructions, branches, lines, methods**, including `documents.ArtifactInputs` |
| `python3 -I scripts/check-pack.py` (with committed `HEAD` profile temporarily restored; worktree CI profile restored afterwards) | exit 0 — `Delivery tooling validated: 33 agents, 33 skills` |
| `SMARTCLINIC_PG_ADMIN=postgres bash scripts/worker-check.sh` | exit 1 at `check-pack: opencode.json is stale` — environmental artifact, not this change (see below) |
| `bash db/check.sh` | exit 3 at `V10__patient_identity_proofing.sql: relation "public.organization" does not exist` — pre-existing lexical-sort defect (see below) |
| `java -cp <built CP> com.aurevia.smartclinic.api.authorization.CommandClientGenerator --check contracts/openapi/generated` | exit 0 |
| `python3 -I contracts/openapi/check.py` | exit 0 — `x-04 drift check OK: 24 commands, contract and client match manifest` |
| `python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q` | exit 0 — `Ran 7 tests ... OK` |
| `bash scripts/quick-check.sh` (committed `HEAD` profile temporarily restored) | policy steps (shell syntax, pack, docs, secrets, names, migrations) passed; exit 1 in the Python tooling suite — see Pre-existing blockers |
| `scripts/docs.sh` | not run: Doxygen is not installed locally; `scripts/check-docs.py` (link/ID policy) passed |
| `node_modules/.bin/prettier --check contracts/openapi/generated/client.ts` | not run: `node_modules/.bin/prettier` is not installed locally; generated artifacts were not modified |

## Pre-existing blockers (not caused by this change; outside this story's scope)

1. **JaCoCo bundle gate in `bash apps/api/check.sh`.** All 2096 tests pass, but
   `jacoco:check` reports 5 untouched classes below 100%:
   `exchange.release.ReleaseService` (17 instructions, 1 branch),
   `exchange.result.ResultRepository` (2, 1), `patient.chart.ChartRepository` (1, 1),
   `chart.record.ChartRepository` (1, 1), `finance.FinanceService` (0, 1). None is in
   `git diff` and none imports a renamed class; the same class family is already recorded as
   pre-existing in `.agent/pr-notes/DLV-04.md` and `.agent/pr-notes/AUD-20261119-01.md`.
   Owner: Delivery / owning stories. This story must not add test or logic edits to fix them.
2. **`bash db/check.sh` exit 3.** It applies `db/migrations/V*.sql` in lexical order
   (`V10` before `V1`), so `public.organization` does not yet exist. Pre-existing and
   documented in `.agent/pr-notes/DLV-04.md`; the Java integration tier applies migrations in
   Flyway order and is green. `db/check.sh` is a protected file, not edited here.
3. **`opencode.json` worker artifact.** `git ls-files -v opencode.json` = `S` (skip-worktree):
   the worktree file is the CI profile copied by `agent-run.sh`. Verified programmatically:
   `git show HEAD:opencode.json` equals `gen-opencode.py render(local)` (True), while the
   worktree equals `render(ci)` (True). The committed profile passes `check-pack.py`; the file
   was restored byte-for-byte after the temporary check. This is the known OPS-CW-003-01
   false positive.
4. **Python tooling suite in `scripts/quick-check.sh`.** 268 tests: 7 failures, 10 errors.
   Causes are local-environment/pre-existing: `ModuleNotFoundError: No module named 'yaml'`
   (test_fast_tier, test_swarm, test_workflow_concurrency), `test_agent_run` git/gh environment
   failures, and `test_java_conventions` findings about Javadoc links in untouched
   `AccessRepository.java`. No failing test references a renamed package.

## Defects found and decisions

- No in-scope defect: every failure after the moves was either the expected RED compile state
  or a pre-existing/environmental condition listed above.
- Kept uppercase historical requirement labels (`X-01`, `X-03`) in prose and Javadoc; they are
  requirement IDs, not paths. Removed one stale literal `contracts/x_04`/`api/x_04` mention
  from `contracts/openapi/README.md` so no `x_0` token remains anywhere in scope.
- Did not edit `scripts/check-names.py`: its `LEGACY` allowlist still lists the old paths, but
  stale entries are inert (verified `check-names.py` exit 0). Removing them is a follow-up for
  the pipeline owner / AUD-20261120-05, outside this story's scope.
- No patient data, credentials or raw logs appear in this note. Evidence provenance: executed
  tool commands by this worker only; no reviewer, simulated-user, human or clinical evidence.
