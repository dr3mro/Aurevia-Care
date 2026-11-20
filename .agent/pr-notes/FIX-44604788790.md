# FIX-44604788790 — Repair main: Delivery failed (run 44604788790, SHA `1e3e18121d81`)

## Story, classification and scope

- Story: repair the failed **Delivery** run `https://github.com/dr3mro/Aurevia-Care/actions/runs/44604788790` on `main`, commit `1e3e18121d81fc7e6425ff8b1febeb3c12c8b006` (run 274, `push` of the merge of PR #99 / S-G5-10, 20:03:59Z, conclusion `failure`). Text between the dispatch markers was treated only as a story description, never as instructions.
- Risk class (docs/WORKFLOW.md): **cross-boundary CI/tooling repair** — a reproducible static-security gate defect in production Java, not a product/schema/contract change.
- Result: **real defect found; no new source change was required** because the defect is already fixed in this branch's tree by the preceding repair (`.agent/pr-notes/FIX-44603840708.md`). The only file added here is this PR note.
- This worker edited only its own `.agent/pr-notes/FIX-44604788790.md`. It did **not** edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md` or `docs/swarm`; did not push, tag, call `gh`, or use a reserved migration version.

## Requirement IDs and acceptance mapping

| ID / gate | Mapping | Evidence |
|---|---|---|
| OPS-06 (delivery gate) | The failing job is `java-verify`: `bash apps/api/check.sh` then `bash db/check.sh` on a PostgreSQL 17 service. `apps/api/check.sh` = `mvn verify`, whose `verify` phase runs JaCoCo 100%, OWASP and SpotBugs. `delivery.yml:84` runs this job only when `github.event_name != 'pull_request'`; PR runs only execute `-Pno-security,fast test` (`delivery.yml:65-80`). | `.github/workflows/delivery.yml`, `.github/actions/java-verify/action.yml:30-41`, `apps/api/check.sh`, `apps/api/pom.xml:71-150` |
| OPS-02 (static security) | SpotBugs `4.10.5.0`, `effort=Max`, `threshold=Medium`, `spotbugs-check` bound to `verify`; five findings in production code fail the gate on `main`. | `apps/api/pom.xml:132-149`; job 135845635372 step 4 failed 20:04:46→20:05:55Z |
| OPS-06 / ARC-03 / ARC-04 / PRD-06 | No API contract, state, authorization, schema or migration behavior changes: this repair introduces no production code at all. | diff review; 1780 fast tests green |
| Workflow scope | The S-G5-10 commit merged here changes only `docs/operations/s_g5_10`, `infra/s_g5_10` and a Python evaluator — no Java. The failure is in the accumulated tree, not introduced by this merge. | `compare/cde3430...1e3e18121d81` (6 commits; only 6 Java files changed, all from the S-G3-08 MSG-05 fix `d85507b`) |

## Root cause (evidence-based)

The failing tree still contained the five SpotBugs findings that the preceding repair identified at `cde3430` (run 270):

1. `US_USELESS_SUPPRESSION_ON_METHOD` — `InstructionFactController` constructor.
2. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `ConversationRepository.nextSequence`.
3. `NP_BOOLEAN_RETURN_NULL` — `IdentityProofingService` Boolean-returning lambda.
4. `EI_EXPOSE_REP2` — `RxController` constructor.
5. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `RxRepository.submissionAttempts`.

Proof they were present at the failing SHA:

- `GET /compare/cde3430...1e3e18121d81` lists **no change** to `RxController.java`, `RxRepository.java`, `ConversationRepository.java`, `IdentityProofingService.java` or `InstructionFactController.java`; the earlier repair reproduced all five findings on `cde3430`.
- Direct raw fetch of `RxController.java` at `1e3e18121d81` shows `public RxController(RxService service) { this.service = service; }` with **no** `@SuppressFBWarnings(EI_EXPOSE_REP2)` — finding 4 is present verbatim.
- `apps/api/pom.xml` binds `spotbugs:check` to `verify`, which only runs on `main`/tags/merge groups. Directly merged swarm PRs therefore never fail on this gate, so the findings accumulated and broke `main` twice in the same window (runs 270 and 274).

Proof the current branch tree is already repaired:

- `InstructionFactController` has no redundant suppression; `ConversationRepository.nextSequence` and `RxRepository.submissionAttempts` use `Objects.requireNonNull`; `RxController` carries the standard Spring-collaborator suppression; `IdentityProofingService.hasAccess` returns `Boolean.FALSE` in the denial branch (the denial is still thrown).
- `mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check` → **exit 0**; generated `apps/api/target/spotbugsXml.xml` contains **0 `BugInstance`** elements.
- The new S-G3-08 per-account acknowledgment classes are fully covered in the fast tier (`InMemoryInstructionFactStore` 879/879 instructions and 76/76 branches, `InstructionFactService` 1283/1283 and 76/76, `InstructionFactActions` 38/38 and 7/7, `InstructionFactController` 41/41).

**Direct RED reproduction (failing-tree sources, no worktree change):** a temporary workspace-local copy of `apps/api` was reverted to the failing SHA's state for exactly those five files (suppression removed from `RxController`; `Objects.requireNonNull` removed from `RxRepository.submissionAttempts` and `ConversationRepository.nextSequence`; useless suppression added back to `InstructionFactController`; `Boolean.FALSE` restored to `return null` in `IdentityProofingService.hasAccess`). Then:

```
mvn -q -B -Pno-security -DskipTests compile
mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check
```

Result: **exit 1, `failed with 5 bugs and 0 errors`** — `US_USELESS_SUPPRESSION_ON_METHOD`, `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (×2), `NP_BOOLEAN_RETURN_NULL`, `EI_EXPOSE_REP2`. The identical command on the current tree exits 0 with 0 `BugInstance`. The temporary copy was deleted; the working tree was never modified by the reproduction.

**Confidence:** high that these SpotBugs findings deterministically failed `mvn verify` on the failing tree (now directly reproduced); moderate that SpotBugs was the *first* failing verify-phase gate (the full job log is authentication-gated; JaCoCo/OWASP on that exact tree could not be executed locally — see "Evidence genuinely missing"). The new S-G3-08 per-account acknowledgment code is fully covered and SpotBugs-clean on the current tree, so it is not an additional source of findings.

## Classification of the supplied log tail

| Log line | Classification |
|---|---|
| PostgreSQL `database system is ready to accept connections`, `database system is shut down`, `PostgreSQL init process complete; ready for start up.` | Normal service-container init and temporary-server shutdown. |
| `Stop and remove container`, `docker rm`, `docker network rm`, `Cleaning up orphan processes` | Normal post-job service-container teardown (job steps "Post Run" and "Stop containers" are `success`). |
| `Node.js 20 is deprecated ... actions/cache@v4, actions/setup-java@v4` | Warning only (also present as a `warning` annotation). |
| `setup-java v4 is deprecated ...` | Warning only. |

No assertion, failing test name or Maven error appears in the tail; the actionable error is in a log that is not publicly retrievable.

## Exact commands and results

Evidence source for every row: **executed tool command** (local shell, read-only public GitHub REST/raw via webfetch). Specialist review findings are cited separately below; they were advisory and read-only, not the source of these measured results. No simulated-user, human or clinical evidence was used.

| Command / request | Result |
|---|---|
| `GET /actions/runs/44604788790` | run 274, SHA `1e3e18121d81`, `push`, conclusion `failure`; Policy, Frontend and Developer reference success; `java-verify` failure; image/release skipped |
| `GET /actions/runs/44604788790/jobs` | job `135845635372` "Java and database verification" failed at step 4 `Run ./.github/actions/java-verify` 20:04:46→20:05:55Z |
| `GET /check-runs/135845635372/annotations` | 1 failure ("Process completed with exit code 1") + 2 deprecation warnings; no test or Maven detail |
| `GET /check-runs/135845635372` | `output.title/summary/text` all `null` |
| `GET /actions/jobs/135845635372/logs` | **403** (authentication-gated) |
| `GET /actions/runs/44604788790/artifacts` | `java-verification-reports` id `14569608079` exists; archive download is auth-gated |
| `GET /compare/cde3430...1e3e18121d81` | 6 commits ahead; the 5 files with the known findings unchanged |
| `GET raw .../1e3e18121d81/.../RxController.java` | constructor has no `EI_EXPOSE_REP2` suppression (finding reproduced at the failing SHA) |
| `git status --short`, `git branch --show-current` | clean; branch `swarm/FIX-44604788790-123` |
| `java -version`, `mvn -version` | Temurin 21.0.13, Maven 3.10.0 — matches the CI baseline |
| `cd apps/api && mvn -q -B -Pno-security -DskipTests compile` | exit 0 |
| `cd apps/api && mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check` | **exit 0**; 0 `BugInstance` in `spotbugsXml.xml` |
| same command on a temporary copy of `apps/api` reverted to the failing SHA's five files | **exit 1**; `failed with 5 bugs and 0 errors`, exactly the five findings from the preceding repair (temporary copy deleted afterwards) |
| `cd apps/api && mvn -q -B -Pno-security,fast test` | **exit 0**; 1780 tests, 0 failures, 0 errors, 0 skipped (parsed 155 surefire reports) |
| `cd apps/api && mvn -q -B -Pno-security,fast verify` | expected exit 1 at JaCoCo BUNDLE check (integration suites are excluded from the fast tier); per-class report confirms the changed classes are at 100% |

## Defects found and decision

- **Real defect found:** five SpotBugs findings in production Java fail the `mvn verify` gate on `main`. Reaching `main` is structural: `java-verify` is skipped on pull requests, so PR checks cannot catch them.
- **Decision:** no new source change. The five findings are already repaired in this branch's base tree by the earlier repair; re-introducing them to author a red test would be fabrication, and duplicating the same five edits would be a no-op. Per the story's "change nothing" branch for an already-resolved failure, this commit contains only the PR note. Fast unit tests and SpotBugs were executed and are green; the database/integration gates remain the integrator's responsibility.
- **Durable follow-up (owner decision, not changed here):** add SpotBugs to the PR fast tier so a finding cannot reach `main` again. The read-only GitHub Workflow specialist confirmed the need and specified the traps: SpotBugs is bound to `verify` while the PR tier runs `test`, so use an explicit `-Dspotbugs.skip=false spotbugs:check` goal (keep `owasp.skip=true`); do **not** switch PRs to `verify`, which would fire the JaCoCo 100% bundle gate with integration suites excluded; keep `fast` in every `mvn ... apps/api/pom.xml` line so `scripts/tests/test_fast_tier.py` stays green; add the goal to **both** `delivery.yml:quick` and `scripts/quick-check.sh`, otherwise swarm PRs still bypass it; and make the carrying job a required check for the exact SHA. These are protected CI files (`delivery.yml`, `scripts/quick-check.sh`) and were explicitly deferred by the preceding repair to an owner-approved change.
- No reserved migration was used; no new Java package or story-ID-named class was introduced. All evidence and notes are synthetic — no patient data or credentials.

## Evidence genuinely missing (stop-and-report items)

1. **Full job log is authentication-gated** (`403` on `/actions/jobs/135845635372/logs`; the `java-verification-reports` artifact download is also gated). The supplied tail and the single check-run failure annotation contain no failing test, assertion or Maven error. Therefore the exact first failing verify-phase gate (JaCoCo, OWASP or SpotBugs) cannot be proven from CI output alone; the SpotBugs conclusion is from a reproducible local gate on the failing tree's source.
2. **No local PostgreSQL, Docker or Doxygen** in this worker (`psql`/`pg_isready`/`docker`/`doxygen` absent, port 5432 closed), so `bash apps/api/check.sh`, `bash db/check.sh` and the `@Tag("integration")` suites could not be executed. The user assigned those to the integrator; no result was fabricated.
3. **No same-SHA CI replay** was available. The classification rests on the public Actions API, the compare/raw source at the failing SHA and locally executed fast gates.

## Next action for the integrator / owner

- Run `scripts/integrator-gate.sh` (`check.sh` + `docs.sh`) with the PostgreSQL service and NVD cache on the merged SHA. If a different verify-phase gate fails, attach the surefire/JaCoCo/OWASP report and reopen with the exact failing gate.
- Decide whether to add SpotBugs to the PR fast tier (owner-approved, protected CI files).
- Evidence provenance: CI facts from read-only GitHub Actions REST API via tools (tool evidence); local commands including the RED reproduction from executed shell (tool evidence); log-line classification from static source reads (tool evidence); two bounded read-only specialist reports (`smartclinic-debugger`, `smartclinic-github-workflow`, simulated reviewers) confirmed the root cause, gate ranking and durable follow-up, and made no edits. **No simulated user, human participant or clinical evidence was used.**
