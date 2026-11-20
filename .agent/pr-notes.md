# READY-12 — Production-readiness evidence checklist for WEB-01 to WEB-13

## Scope and ownership

- Branch: `swarm/READY-12-247` (base `2d1f072`). Committed locally; **not pushed, tagged or `gh`-invoked**.
- Scope respected: only `docs/operations/production-readiness/README.md` and this PR-notes file were added.
  No code, workflow, migration, generated config or shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`,
  `BUGS.md`, `PROJECT.md`, `docs/swarm`) was edited. No reserved migration version was used; no schema change is needed.
- Synthetic data only — this story stores no data of any kind, and no real patient, identifier or credential appears.
- The folder and file are named for what they hold (`production-readiness`), never for a task, story or gate ID.

## Requirement mapping (acceptance)

| Story point | Delivered in the checklist |
|---|---|
| Heading, spec-section paragraph, draft status line | `docs/operations/production-readiness/README.md` lines 1–5: heading `# Production readiness evidence`, a paragraph naming section 8.1 with a relative link to the specification, and the exact line `Status: draft, owners to complete`. |
| One table, exact columns | Columns are `Requirement | Evidence needed | Produced by | Owner | Status | Link`. |
| One row per WEB requirement | 13 rows, WEB-01 through WEB-13, each `Evidence needed` cell restating the requirement's own "Verify with ..." clause in our words. |
| Controlled producer values | Only `CI`, `Infrastructure owner` and `Security reviewer` are used — all within the allowed set (`CI`, `Infrastructure owner`, `Security reviewer`, `Product owner`). |
| Nothing invented or signed off | Every `Owner` is `unassigned`, every `Status` is `not started`, every `Link` cell is empty. The table describes evidence still to be produced; it does not claim any exists. |
| Needs a person | `## Needs a person` lists exactly the six named items: WAF and TLS setup; certificate automation; SHA-pinning CI actions; end-to-end browser suite and its CI job; penetration test; progressive-rollout tooling. |
| Docs checker | `python3 -I scripts/check-docs.py` exits 0. |

### "Produced by" decisions from the supplied repository facts

| Requirement(s) | Produced by | Basis |
|---|---|---|
| WEB-02, WEB-05, WEB-06, WEB-09 | CI | Implemented by tasks READY-02, READY-01, READY-05, READY-07; automated checks in those tasks produce the evidence. |
| WEB-03 | CI | Implemented by READY-11; automated tests. |
| WEB-04 | CI | Implemented by READY-03 and READY-04; the rate-limit/size/CORS/error-shape tests produce the evidence. See nuance below. |
| WEB-08 | CI | Implemented by READY-08; chunk budget enforced in CI. |
| WEB-13 | CI | Implemented by READY-09. |
| WEB-01 | Infrastructure owner | WAF and TLS setup and certificate automation are person/infrastructure actions; TLS scan plus WAF rule test. |
| WEB-10 | CI | The end-to-end browser suite and its CI job are a CI change workers cannot make; the retained report is produced by CI once that job exists. |
| WEB-11 | Infrastructure owner | Progressive-rollout tooling and the rollback rehearsal are infrastructure/operations work. |
| WEB-12 | Security reviewer | The independent penetration test and finding-to-closure tracking are a reviewer activity. |
| WEB-07 | CI | Not named in the story's task facts. Its own "Verify with ..." clause is a browser matrix in the end-to-end suite — the same CI-produced suite as WEB-10 — and section 8.1 assigns every requirement except WEB-01, WEB-04, WEB-11 and WEB-12 to CI and the end-to-end suite. No evidence or owner was invented to fill this gap; the producer follows from the requirement's own verification mechanism. |

Nuance recorded deliberately: section 8.1 says evidence for WEB-01, WEB-04, WEB-11 and WEB-12 is produced *with* the infrastructure owner, but the story's repository facts place WEB-04 with implementation tasks READY-03/READY-04 and give it no "Needs a person" item. The checklist therefore records WEB-04's producer as `CI` (the tests produce the evidence) while the evidence description retains the infrastructure-facing controls. WEB-01, WEB-11 and WEB-12 are assigned to a person as the facts require.

## Exact commands run and results (tool evidence)

All commands from the repository root unless noted.

| Command | Result |
|---|---|
| `test -f docs/operations/production-readiness/README.md` (before creation) | missing — the checklist did not exist; there was no document for the docs gate to validate. |
| `python3 -I scripts/check-docs.py` | **exit 0** — all relative links resolve and all 13 WEB IDs are defined in the specification. This is the story's acceptance gate for a documentation-only change. |
| `python3 -I scripts/check-secrets.py` | **exit 0** — no credential or PHI-shaped string. |
| `python3 -I scripts/check-names.py` | **exit 0** — folder/file names are business-domain names. |
| `python3 -I scripts/check-migrations.py --require-baseline` | **exit 0** — no migration touched. |
| `python3 -I scripts/project-name.py displayName` | `Aurevia Care`, exit 0. |
| `python3 -I scripts/check-pack.py` | **exit 0** (`Delivery tooling validated: 33 agents, 33 skills`) when run against the committed `opencode.json`; see Defect 1 for the worker-profile artifact. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0** — Spring Boot fast unit tests pass. |
| `bash apps/staff-web/check.sh` | **exit 0** — generated-client drift, strict types, lint, 29 test files / 196 tests passed, 45 files fully covered: 100% statements, branches, functions and lines. (Expected `useTheme` error-path console output appears in the passing ThemeProvider test; it is not a failure.) |
| `bash scripts/worker-check.sh` | **exit 1** — stops in `quick-check.sh` at the pre-existing `scripts/tests` Python suite: `Ran 268 tests ... FAILED (failures=5, errors=10)`. All earlier constituents in that script passed (and the Java and staff-web gates above pass). See Defect 2. |

### Test-first note

This is a documentation-only story: there is no production behavior, so no application test was written and none was fabricated. The observable red state was the absent checklist (recorded above); the executable acceptance gate is `check-docs.py`, which checks the new file's links and requirement IDs. It was red only in the sense that no checklist existed to check, and green after creation.

## Defects and blockers found (none in the delivered document)

1. **Environmental — `opencode.json` worker profile.** The worktree `opencode.json` carries the `S` (skip-worktree) flag: `agent-run.sh` places the CI profile there. `check-pack.py` compares the worktree file with the committed local profile and reported `opencode.json is stale` on the first `worker-check.sh` run. This is the documented false-positive artifact (delivery skill, MEMORY `OPS-CW-003-01`), not a change by this worker. For gate runs only, the committed `HEAD` copy was swapped in; `check-pack.py` then passed with 33 agents/33 skills. The original CI-profile bytes were restored byte-for-byte (SHA-256 `b40d443db0adc73b61e6f43310ce85740ab51355b6d87781a71814230e7d7a92` before and after), and `git status` shows no change to that file.
2. **Pre-existing — `scripts/tests` environment failures.** `bash scripts/worker-check.sh` cannot pass in this worker because `python3 -I -m unittest discover -s scripts/tests -q` reports 268 tests with 5 failures and 10 errors:
   - `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_workflow_concurrency` and seven `test_swarm` cases — the CI image installs PyYAML; this worker's Python 3.11 has no `pip` and no PyYAML, so it cannot be installed locally.
   - `test_agent_run.AgentRunTests` (5 failures, 2 errors) depend on git push/gh/branch environment state unavailable to a worker (e.g. `gh pr create: permission denied`, push rejected by a temporary bare remote, `git branch -D` of a missing fixture branch).
   - Proven not caused by this change: with the new document moved out of the tree, the base tree produced the identical 268 tests / 5 failures / 10 errors and the same failing test names. Because `quick-check.sh` uses `set -e`, the script exits there before the Java/DB stages; those stages were run individually and pass (Java above). The story explicitly leaves database and integration tests to the integrator.
3. No product defect, broken link, unknown requirement ID or unsupported claim was found in the new checklist.

## Decisions

- **D1:** The checklist describes verification still owed; it never asserts completion. `Owner`, `Status` and `Link` are deliberately blank/unassigned/`not started` so a person can sign each row.
- **D2:** `Produced by` follows the supplied task facts (task-implemented requirements → `CI`) and the nature of the six person-blocked items (infrastructure, CI-job setup, reviewer). WEB-07 was not in the facts; its producer was derived from its own verification mechanism (the end-to-end browser suite, as for WEB-10) and the section 8.1 closing assignment, not invented.
- **D3:** The six "Needs a person" entries are listed verbatim and exactly, with no extra items, because the worker cannot edit `.github/workflows` or perform infrastructure/security actions.
- **D4:** No CI, workflow, agent, skill or shared script was changed; the repository self-improvement rule did not apply because no defect in this story's own output was found.

## Evidence source classification

- **Tool / executed commands:** all command results above were executed by this worker; the base-tree comparison and SHA-256 before/after checks are tool evidence. Synthetic only; no data was used.
- **Reviewer:** the loaded story-matching skills (delivery, DevSecOps, GitHub workflow, QA reviewer) supplied review criteria. No subagent review was requested because this is a single static document with a deterministic checker; the checker result is the independent gate.
- **Simulated user / human / clinical:** none. No owner has been assigned and no evidence has been supplied; real TLS/WAF, penetration-test, end-to-end and rollout evidence remain with the accountable owners named in section 8.1 and the "Needs a person" list.
