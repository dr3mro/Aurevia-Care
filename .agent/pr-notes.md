# DLV-01 — Repair integration tests: patient chart, chart entry and exchange task

## Requirements and acceptance mapping

| Requirement | Acceptance exercised | Classes |
|---|---|---|
| CLN-03 | Chart entry provenance, version-bound verification, tenant isolation, units/measurement time, append-only audit | `ChartRecordIntegrationTest`, `ChartEntryIntegrationTest` |
| CLN-05 | Concurrent verification is a single compare-and-set; stale draft/version cannot overwrite (412) | `ChartRecordIntegrationTest`, `ChartEntryIntegrationTest` |
| ARC-02 | Forced RLS conceals cross-tenant chart/exchange rows and audit; direct assertions must bind tenant context | all four classes |
| ARC-04 (supporting) | 412 for a stale `If-Match`, 409 for an invalid transition | chart verification |
| V7 / V13 (schema invariants) | Exactly one of `dob`/`dob_unknown_reason`; chart entry CHECKs | `ChartEntryIntegrationTest` |

Scope: `apps/api` only. No migration added or edited — no schema defect was found; V7 and V13 are correct and the fixtures/services were at fault. No test was weakened, skipped, deleted or re-tagged; no class was excluded from the gate.

## Reproduction (red) and root cause per class

Environment: branch `swarm/DLV-01-221` at `45b4709` (= `origin/main`), clean tree, PostgreSQL 17 on 127.0.0.1:5432, `SMARTCLINIC_PG_ADMIN` set (admin role is `postgres`), JDK 21.0.13, Maven 3.10.0.

Command used to reproduce and re-verify (default profile = real integration tests, `-Pno-security` only skips OWASP/SpotBugs for the inner loop):

```
cd apps/api
mvn -B -Pno-security -Dtest=ChartEntryIntegrationTest,ChartRecordIntegrationTest,ExchangeTaskIsolationTest,CheckInWorklistIntegrationTest -DfailIfNoTests=false test
```

Red baseline: `Tests run: 24, Failures: 5, Errors: 2` — ChartRecord 5×500, ChartEntry `@BeforeAll` error, ExchangeTask duplicate key. Evidence: executed tool command and surefire reports in `apps/api/target/surefire-reports`.

1. **`ChartRecordIntegrationTest` — production defect (5× HTTP 500).** Surefire server stack trace: `PSQLException: Can't infer the SQL type to use for an instance of java.time.Instant` at `chart.record.ChartRepository.insert` (and the same in `verify`). pgjdbc cannot infer a JDBC type for a bare `Instant`; the `timestamptz` columns need a bound type. Binding ISO-8601 *text* was attempted and rejected (`column "recorded_at" is of type timestamp with time zone but expression is of type character varying`), so the fix binds a UTC `OffsetDateTime`, which pgjdbc maps to `timestamptz` without a timezone shift. **Note:** the story's `seedPatient` note for this class was already fixed upstream in `1a829d6` before this branch; no change was needed here.

2. **`ChartEntryIntegrationTest` — test fixture defects.** `seedPatient` inserted `patient_record` without `dob` or `dob_unknown_reason`, violating V7's exactly-one CHECK in `@BeforeAll`. Flyway also pointed at the deleted `it/s_g1_03/migrations` path (`Skipping filesystem location ... (not found)`), so V13 was never applied. Fixed by seeding a synthetic `DATE '1990-05-20'` and pointing Flyway at `it/patientchart/migrations` (V13 there is identical in schema to the `chart/record` copy). The class then exercised the same `Instant` production defect via `patient.chart.ChartRepository`.

3. **`ExchangeTaskIsolationTest` — test fixture defect.** `storageCannotForgeAReviewedTaskOrMoveTwoChannels` failed with `duplicate key value violates unique constraint "exchange_task_pkey"`: `@BeforeAll` pre-seeded the `FORGED` task id, then the test inserted that same id. `FORGED` was removed from the seed array; the test still inserts its own forged row and asserts the V20 guard resets it. `LOCAL-n` order refs of other seeded tasks shift but no assertion depends on them.

4. **`CheckInWorklistIntegrationTest` — already fixed upstream.** `worklistOrdersByScheduledTimeAndConfiguredPriority` (expects 2 rows, previously saw 9) was scoped to its own appointment ids in `cd390df`, which is on current `main`. It passed red-first with no change; verified green.

5. **Secondary failures exposed after the 500s were fixed (both chart classes).**
   - Audit assertion `expected <4> but was <0>`: test defect — `audit_event` has `FORCE ROW LEVEL SECURITY`, and the direct JDBC assertion used `smartclinic_migrate` without binding `app.current_org`, so every row was concealed. Diagnostic confirmed all 4 `CHART_RECORD/SUCCESS` rows existed once `SET app.current_org` was applied. Added the tenant context to the assertion connection in both chart classes. The assertion itself is unchanged.
   - Concurrent verification `expected one 200 but was zero`: production defect — both chart services checked `verification == VERIFIED` before `version != expected`, so the losing racer received 409 (`ALREADY_VERIFIED`) instead of the asserted 412 (`STALE_VERSION`). Diagnostic showed outcomes `[200, 409]`. Reordered the preconditions so a stale `If-Match` wins (ARC-04: 412 for stale versions); a sequential verify at the current version still returns 409. Applied to both the `chart.record` and legacy `patient.chart` services.

## Green verification (tool evidence)

- Targeted real-PostgreSQL integration run (command above): `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.
  - ChartRecord 5/5, ChartEntry 4/4, ExchangeTaskIsolation 9/9, CheckInWorklist 9/9.
- Fast unit tier: `cd apps/api && mvn -B -Pno-security,fast test` → `Tests run: 1844, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Full canonical gate: `bash apps/api/check.sh` → still `BUILD FAILURE` overall (`Tests run: 2087, Failures: 63, Errors: 4`), but the four target classes are all green in that same run (surefire reports: 5/5, 4/4, 9/9, 9/9 with zero failures/errors) and none appears in the failing-class list. Remaining failures are pre-existing classes owned by other stories: `IdentityProofingIsolationTest`, `ConsolidationIsolationTest`, `ConversationFlowTest`, `StaleInvitationIntegrationTest` (DLV-02); `RxTransmissionFlowTest` (DLV-03); and `AccessIntegrationTest`, `ClinicalNoteRevisionIntegrationTest`, `DocumentIsolationTest`, `ResultRoutingIntegrationTest`, `TrendingTest`, `SpecimenAccessionIsolationTest` (separate follow-on work). Because the full suite stops at the `test` phase, the JaCoCo 100% gate could not be evaluated locally; the new `instantValue` null/non-null branches are both exercised by the chart integration tests and the reorder adds no branch. The integrator re-runs the full gate after DLV-02/DLV-03 land.

## Decisions and defects

- Decision: bind instants as UTC `OffsetDateTime` rather than adding `CAST(... AS timestamptz)` to the SQL — it is type-safe, avoids a timezone-dependent shift, and keeps the SQL unchanged. Text binding was tried first and proven wrong by the server error before this decision.
- Decision: prefer 412 over 409 when a verify request is both stale and already verified, matching the integration acceptance and ARC-04.
- No production schema change: V7 and V13 were correct; only fixtures and SQL parameter binding needed repair.
- No new BUGS.md entry: all defects are resolved in this change and were reproduced with executed commands; they are recorded here rather than in a shared tracker (worker rule).

## Evidence provenance

- Reproduction, fixes and all green runs: **executed tool commands** (Maven/Surefire) plus direct file inspection of surefire XML/TXT reports and migration SQL.
- Root causes: **tool evidence** (server stack traces in `apps/api/target/surefire-reports` and `mvn` output), plus diagnostic prints temporarily added to `ChartRecordIntegrationTest` and removed before commit.
- No simulated-user, human or clinical evidence was used; this is a test-infrastructure repair with synthetic fixtures only.


# AUD-20261119-04 — Rename story-ID-named patient-mobile feature packages

Status: complete, ready for integrator merge. Scope: `apps/patient-mobile/src/features` only.

## Independent resume verification (current branch)

A later worker re-verified the already-committed state on branch
`swarm/AUD-20261119-04-220`, HEAD `9bd064d` (which contains the refactor commit
`55c0d52`, PR #169), with a clean worktree. The refactor was found complete;
no new source edit was needed. Environment on this pass: Node v22.23.5,
npm 10.9.9 (the earlier pass used a different worker image). Commands re-run:

| Command | Result (tool evidence) |
|---|---|
| `find apps/patient-mobile/src/features -maxdepth 1 -type d` | 12 domain folders exactly as mapped, no `s_g*` dir |
| `grep -rn --exclude-dir=node_modules --exclude-dir=.git 's_g3_' apps/patient-mobile` | only the guard test's mapping comments/regex; no stale import or config path |
| `npm ci --no-audit --no-fund` | exit 0, 1272 packages from the pinned lockfile |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0 |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` | exit 0, 1 suite / 3 tests passed |
| `bash check.sh` (canonical module gate) | exit 0, 27 suites / 283 tests passed, `All files` 100% statements/branches/functions/lines |

No decision or evidence is missing; no defect found. The integrator still runs
the database and integration tests, which are unaffected by this frontend-only
structural refactor.

## Decision and evidence availability

No decision or evidence is missing. The owner-approved domain mapping is supplied
verbatim in the story text ("appointments, reports, enrollment, units,
conversations, attachments, instructions, acknowledgments, service-profiles,
governance, flows, evidence") and is consistent with the already-merged Java
refactor recorded in `.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`
(`messaging/s_g3_01 → messaging/appointments`, …) and with the `features/conversations`
path already named in TODO.md S-G3-05. No owner question was needed.

## Requirement IDs and acceptance mapping

| Requirement / rule | Acceptance | Where satisfied |
|---|---|---|
| CODING_STYLE.md "Name Java packages by business domain … never by story, task, gate or ticket ID" (extended to all packages by `REFACTOR-PACKAGES.md` §6) | All 12 feature folders use domain names; no `s_g3_XX` directory remains | 12 `git mv` operations; `domain-layout.test.ts` invariants 1 and 2 |
| AUD-20261119-04 · domain mapping | Exact owner-approved mapping applied in story order | `domain-layout.test.ts` `DOMAIN_FOLDERS`; invariant 1 |
| AUD-20261119-04 · update imports and tests | Every import resolves at the new relative path; all existing tests pass | Module imports were already relative (`./…`), so no content edits were needed; 283/283 tests pass, 27/27 suites |
| AUD-20261119-04 · synthetic fixtures only; failing tests first | No real data introduced; red test observed before rename | `domain-layout.test.ts` red before `git mv`, green after; all fixtures are pre-existing synthetic test data |
| Frontend standard: 100% coverage, `tsc --noEmit`, `eslint . --max-warnings=0` | Enforced module gate stays green | `test:coverage -- --ci` exit 0, `All files 100%`; `typecheck` and `lint` exit 0 |

The change is a pure structural refactor: no behavior, contract, migration or
authorization logic changed. No migration version was used or touched.

## Applied mapping

| Old | New |
|---|---|
| `s_g3_01` | `appointments` |
| `s_g3_02` | `reports` |
| `s_g3_03` | `enrollment` |
| `s_g3_04` | `units` |
| `s_g3_05` | `conversations` |
| `s_g3_06` | `attachments` |
| `s_g3_07` | `instructions` |
| `s_g3_08` | `acknowledgments` |
| `s_g3_09` | `service-profiles` |
| `s_g3_10` | `governance` |
| `s_g3_11` | `flows` |
| `s_g3_12` | `evidence` |

## Exact commands run and results (tool evidence)

All commands run from `apps/patient-mobile` unless noted. Initial state: `main`
at `34a1bd8`, clean worktree; `node_modules` absent.

| Command | Result |
|---|---|
| `npm ci --no-audit --no-fund` | exit 0; 1272 packages installed from the pinned lockfile |
| `npm run typecheck` (baseline) | exit 0 |
| `npm run lint` (baseline) | exit 0 |
| `npx jest --ci --coverage=false` (baseline) | 26 suites / 280 tests passed |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` **before rename** | exit 1 — 1 suite / 3 failed tests; diff showed the 12 `s_g3_XX` folders; `ENOENT … features/appointments`. Confirms red. |
| `git mv` × 12 (listed above) | exit 0; Git detected 71 renames, 0 content rewrites |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` **after rename** | exit 0 — 1 suite / 3 passed tests. Confirms green. |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0 |
| `npm run test:coverage -- --ci` | exit 0 — 27 suites / 283 tests passed; `All files` 100% statements/branches/functions/lines; every production file at 100% (interface-only `types.ts` files report 0/0). |
| `bash check.sh` (canonical module gate: shellcheck, `npm ci`, typecheck, lint, coverage) | exit 0 — 27 suites / 283 tests passed, 100% all metrics. |
| `python3 -I scripts/check-docs.py` / `python3 -I scripts/check-secrets.py` | both exit 0 — the new Markdown notes introduce no broken link, bad requirement ID or secret/PHI-shaped string. |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1 — **228 tests, 8 failures / 10 errors, all unrelated to this story** (see "Environmental issue" below). The patient-mobile module gate and the repo's doc/secret/migration checks all pass. |
| `grep -rn --exclude-dir=node_modules 's_g3_' apps/patient-mobile` | only the new guard test's doc comment, inline mapping comments and regex; no source import or config references the old paths |
| `git status --short \| grep -v 'apps/patient-mobile/src/features/'` | none — all 72 changed files are inside the declared scope |

Baseline was 26 suites / 280 tests; the final 27 / 283 is the same suite plus the
3 new guard-test cases. No test was weakened, skipped, deleted or re-tagged.

## Entry-point-to-test trace

`domain-layout.test.ts` (test entry point) → `fs.readdirSync(__dirname)` reads the
`features` directory → invariant 1 compares sorted directory names to
`DOMAIN_FOLDERS`, invariant 2 rejects `/^s_g\d+_\d+$/`, invariant 3 asserts each
domain folder is non-empty. The renamed feature entry points (`appointments/AppointmentListScreen.tsx`,
`reports/reportClient.ts`, `conversations/ConversationInbox.tsx`, …) are each
exercised by their existing co-located tests, which all pass at the new paths.

## Defects found

None in the source. One pre-existing observation, out of scope and not changed:
historical notes (`.agent/pr-notes/*`, `docs/swarm/*`, `TODO.md`) still contain the
old folder names as plain text/backticks; they are tracker/history records, not
code references, and the task scope plus AGENTS.md forbid editing them. The
specialist notes that the integrator may refresh those tracker paths at merge
time so future agents do not chase non-existent directories; this worker did not.

## Environmental issue (pre-existing, out of scope, not fixed)

The repo-wide tooling unittest suite exits 1 in this worker. It is unrelated to
the rename and was not introduced or worsened by it:

- `ModuleNotFoundError: No module named 'yaml'` breaks import of
  `scripts/tests/test_fast_tier.py` and `test_workflow_concurrency.py` (Python 3.11
  here, PyYAML not installed in this worker image).
- `test_check_pack.*` failures: `git ls-files -v opencode.json` reports flag `S`
  (skip-worktree) and the worktree file differs from `git show HEAD:opencode.json`;
  it is the CI profile copied by `agent-run.sh`, the known OPS-CW-003-01
  measurement artifact documented in MEMORY.md, not a HEAD-tree defect.
- `test_agent_run.*` / `test_swarm.*` failures reference missing temp git refs
  (`refs/heads/opencode/issue-5-9`) and workflow parsing — worker-environment and
  `yaml` issues.

Verification that these are not caused by this change: `git status --short`
shows no changed `.py`, `.sh`, `.json`, `.yml` or `.yaml` file, and all 72 staged
files are under `apps/patient-mobile/src/features` plus this note. None of the
failing tests import or inspect the patient-mobile source tree. No attempt was
made to fix these (out of scope; shared tooling owned by other workers). The
integrator runs the full gate and can confirm against a clean image.

## Evidence provenance

- **Tool evidence**: every command and result in the table above, executed on the
  current checkout (npm, tsc, eslint, Jest via `jest-expo`, `git mv`, `git status`, `grep`,
  `python3 -I` policy checks, `bash check.sh`).
- **Reviewer evidence**: `smartclinic-senior-mobile` read-only review — verdict
  **Approve**, no impact defects. Structural file/pattern inspection only; the
  reviewer had no shell tool and could not re-execute the commands, so test,
  coverage, typecheck and lint results remain tool evidence from this agent.
- **Simulated user / human participant evidence**: none — this is a non-clinical,
  non-user-facing structural refactor. No real patient data or credentials were
  used; all fixtures are the repository's pre-existing synthetic data.

## Subagent (specialist) review

Reviewer: `smartclinic-senior-mobile` (read-only, bounded to story ID, scope,
mapping, acceptance criteria and the tool evidence above).

- Broken imports / path references: none. All 71 renamed files use local `./…`
  imports; no `../`, cross-directory, dynamic `require()` or config reference to
  an old path exists.
- Remaining story-ID artifacts in scope: none. Directory names exactly match the
  12-domain mapping; `s_g3_XX` survives only as inline mapping comments in the
  guard test.
- Guard-test soundness: sound — exact-set equality catches any added, removed or
  differently named folder; the regex check is redundant, not a weakness.
- Coverage / TS strictness: no new risk; the guard test is correctly excluded
  from coverage and strict flags are unchanged.

Verdict: **Approve**. No decision or evidence is missing for this story.

(End of notes.)


# AUD-20261119-02 — Verify the patient-unit connection when opening a conversation

Story source: daily audit. Worker: swarm slot for `AUD-20261119-02`. Branch `swarm/AUD-20261119-02-216`, base `34a1bd8`. Synthetic fixtures only. No shared tracker edited.

## Requirement IDs

Primary **MSG-01**, **MSG-02**, **MSG-07**, **ARC-07**. Supporting: IAM-05, ARC-02, ARC-04, OPS-02. Specification: `SMARTCLINIC_V1_SPEC.md` §§2, 6.1, 7, 11.

## Defect reproduced from the story (root cause)

`ConversationService.open` accepted `connectionId` from the JSON body, shape-validated it with `ConversationInputs.opaqueId`, and persisted it verbatim. For a patient caller the only relationship check was `ConversationRepository.patientLinkActive` (the IAM-05 patient↔chart fold); no check proved that an active `PatientUnitConnection` bound that chart to the named `unitKey`. `CareUnitDirectoryService.requireActiveConnection` already implemented such a check but had no caller in `main`. A verified patient could open a thread with any of the five units by naming the unit key, and a forged/revoked/foreign connection id was stored unchallenged. `reply()` likewise never re-checked connection state, so a revoked connection still permitted new messages.

## Fix (smallest reviewable slice)

1. `CareUnit` now declares an optional tenant-scoped `unitKey` (with a backward-compatible 6-arg constructor). The conversation unit is derived from the connection's recipient care unit **through the tenant directory**, never from a global id map or the client.
2. `CareUnitDirectoryService` gains `requireActiveConnectionById` (patient self-service: reuses `requireActiveConnection` for membership/role/verified-chart/active-connection) and `requireUsableConnection` (staff action: same ownership, state and unit checks without the patient-only role gate). Both resolve inside `TenantContext.organizationId()`, conceal a missing or foreign connection as 404, deny a revoked connection as 403, and require an active care unit with a conversation category. The reused `deny()` path records a non-sensitive `CONNECTION_USE` audit fact.
3. `ConversationService.open` resolves the connection **after** subject/patient/link checks and staff unit eligibility, derives the unit from the tenant directory, rejects a client unit that differs from the bound unit (403), and persists the server-derived unit and authoritative connection id. `reply` re-resolves the stored connection on every message from either side, so revocation or a stale route stops new messaging.
4. New `ConversationUnitResolver.fromDirectory` converts the tenant directory value into the bounded `UnitKey` vocabulary and rejects anything else (403).

Ordering choices: subject mismatch → 404 (no existence probe) → patient 404 → verified-link 404 → staff unit 403 → connection 404 (concealed) / 403 (state) → derived-unit mismatch 403 → write. Zero writes on every denial.

## Acceptance mapping

| # | Acceptance | Evidence (test) |
|---|---|---|
| A1 | Patient cannot open a unit without an active verified connection | `ConversationServiceTest.patientOpenWithoutAnyVerifiedConnectionIsDenied` → 404, no insert |
| A2 | ConnectionId not bound to caller is rejected | `patientOpenWithAConnectionBoundToAnotherPatientIsDenied` → 404; `CareUnitDirectoryServiceTest.requireActiveConnectionByIdConcealsAnotherPatientsConnection` |
| A3 | Wrong-unit connection cannot open a thread | `patientOpenWithAConnectionToAnotherUnitIsDenied` → 403, no insert |
| A4 | Revoked connection cannot open | `patientOpenWithARevokedConnectionIsDenied` → 403 |
| A5 | Malformed connection id fails before any write | `patientOpenWithAMalformedConnectionIdIsRejectedBeforeAnyWrite` → 422 |
| A6 | Server derives and persists the connection's unit and id | `patientOpenCreatesAwaitingStaffConversationAndWorkItem` (verifies `"lab"` and the connection UUID in `insertConversation`) |
| A7 | Every reply re-verifies the connection (both sides) | `patientReplyAfterTheConnectionWasRevokedIsDenied`, `patientReplyRejectsAConnectionBoundToAnotherUnit`, `CareUnitDirectoryServiceTest.requireUsableConnectionAllowsAStaffActionButDeniesInactiveMembership` |
| A8 | Tenant-scoped resolution: missing/foreign connection and missing unit concealed | `requireActiveConnectionByIdConcealsAnUnknownConnection`, `...ConcealsAMissingRecipientCareUnit`, `...DeniesAUnitWithoutAConversationCategoryOrAnInactiveUnit` |
| A9 | Patient-side membership, verified chart and link still enforced | `requireActiveConnectionByIdDeniesInactiveMembershipAndUnlinkedChart` |
| A10 | Unit key vocabulary stays bounded | `ConversationUnitResolverTest.everyDirectoryUnitKeyIsAccepted`, `anUnknownOrMissingDirectoryUnitKeyIsRejected` |

## Entry-point-to-test trace

`POST /api/v1/conversations` → `ConversationController.open` → `ConversationService.open` (subject/link checks, then `CareUnitDirectoryService.requireActiveConnectionById` / `requireUsableConnection` → `resolveConnection` tenant lookup → `requireActiveConnection` for patients → `ConversationUnitResolver.fromDirectory`) → guarded `TransactionTemplate` → `ConversationRepository.insertConversation` with server-derived unit and connection id → 201. Fast path: `ConversationServiceTest` (real `InMemoryMessagingStore` + `CareUnitDirectoryService`, mocked repository). DB/HTTP path: `ConversationFlowTest` (integrator-owned).

`POST /api/v1/conversations/{id}/reply` → `ConversationService.reply` → existing access/version/state/link checks → connection re-resolution above → sequenced insert → 200. Trace covered by `ConversationServiceTest` reply cases.

## Exact commands and results (tool evidence, executed locally)

Baseline (before edits): `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationContractTest,CareUnitDirectoryServiceTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0.

Red: the same targeted command after adding tests → exit 1, `COMPILATION ERROR`: `ConversationUnitResolver` cannot be found, `ConversationService` constructor requires 5 args (found 4), and `requireActiveConnectionById` does not exist. This is the expected missing-behaviour red for a new API.

Green:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0. Surefire reports: **1797 tests, 0 failures, 0 errors**.
- Targeted: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationUnitResolverTest,CareUnitDirectoryServiceTest,ConversationContractTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0. Per class: ConversationServiceTest 47, CareUnitDirectoryServiceTest 44, ConversationUnitResolverTest 2, ConversationContractTest 17, MessagingDomainTest 13, CareUnitControllerTest 4.
- Coverage: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` then JaCoCo XML inspection → `ConversationService` and `CareUnitDirectoryService` both **0 missed** instructions/branches/lines/methods/classes. (Whole-bundle misses are from integration-tagged tests excluded by `-Pfast`; they are unchanged by this slice.)
- Static/security: `mvn -q -B -f apps/api/pom.xml -Psecurity -DskipTests spotbugs:check` → exit 0.
- Conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.
- Migration gate: `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (no migration added or changed).
- Hygiene: `git diff --check` → exit 0.
- Full fast policy: `bash scripts/quick-check.sh` → exit 1 **only** at `check-pack.py` ("opencode.json is stale"). Cause is the known worker artifact, not this change: `git ls-files -v opencode.json` → flag `S` (skip-worktree), the worktree file is the 1837-line CI profile while `git show HEAD:opencode.json` is the 1777-line committed local profile (`cmp` → 1), and no agent, skill or `opencode.json` file was edited. Every other quick-check step passed when run directly: `bash -n`/`shellcheck` on the shell scripts, `project-name.py`, `check-docs.py`, `check-secrets.py`, `check-migrations.py --require-baseline`, all Python tooling tests ("ok"), and the Maven fast tier. The committed local profile is what CI/integrator checks; no regeneration was performed.

## Scope note (recorded, not silently changed)

The declared scope names the two `messaging/conversations` and `messaging/units` **main** source paths. To satisfy "failing tests first" and "add a no-connection/wrong-unit negative test", I also changed the corresponding co-located test packages (`it/messagingconversations`, `messaging/units`) and the existing `ConversationFlowTest` seeding helper so the DB/HTTP suite can still open threads with a real connection. No file outside the API messaging domain, generators, workflows, scripts or shared trackers was touched. No migration was added (no reserved version was supplied for this story).

## Decisions

- Connections are patient↔unit relationships; staff have no patient connection. Staff callers are still authorized by the existing role/unit matrix and now additionally require the thread's connection to be active and bound to that unit, so a revoked connection also stops staff messaging (MSG-07) and staff-opened threads remain repliable.
- Wrong-unit is 403 (not 404): the five unit keys are public, and a random connection id still gets a uniform 404, so no existence oracle is introduced.
- The public `CareUnit` contract gains an optional `unitKey`; the 6-arg constructor is preserved so existing directory-only callers and fixtures are unchanged. The maintainer guide contract entry for the care-unit directory is an integrator follow-up (docs are outside this scope).

## Deferred findings (owners; not blocking this synthetic slice)

1. **In-memory connection store is not authoritative (HIGH, owner: durable connection table).** There is no `care_unit` / `patient_unit_connection` table or reserved migration (V32 absent); `MessagingStore` is `InMemoryMessagingStore`. The new checks inherit that synthetic adapter limitation. A forced-RLS durable store and reconciliation with the IAM-05 DB fold must precede real-patient enablement. The DB second line of defence still stores `connection_id` as plain text with no connection FK (owner: same durable-table story).
2. **Durable denial audit (MEDIUM, owner: MSG-07/S-G3-10 hardening).** Mid-operation denials in `ConversationService` (including the new unit mismatch) throw inside the guarded transaction, so the transactional `AuditAppender` row rolls back; `CareUnitDirectoryService.deny()` writes a non-sensitive `CONNECTION_USE` fact to the in-memory store instead. This is the pre-existing systemic pattern recorded in `S-G3-05` deferred findings, not introduced here.
3. **Other patient commands (MEDIUM, owner: MSG-03/04/05 governance).** Attachment send/download and instruction acknowledgement use separate stores and do not re-resolve this connection; a single revocation is not yet proven to deny every patient command.
4. **Two verified-link sources (MEDIUM, owner: S-G0-07/S-G3-10).** `patient_link_active` (DB) and in-memory `verifiedPatientCharts` can diverge; the new path checks both but does not reconcile them.
5. **Developer reference build (environment).** `bash scripts/docs.sh` could not run locally ("Install Doxygen to build the developer reference"); the Javadoc-link convention test passes. Doxygen is integrator/CI evidence.

## Evidence sources

- **Tool (executed by this worker):** every command and result above, plus `git status`/`git diff` inspection and JaCoCo XML counters. No database, integration or full `scripts/check.sh` run locally.
- **Reviewer (simulated, read-only):** `smartclinic-software-architect` and `smartclinic-senior-spring-security` bounded design reviews. They found the global-unit-id-map and staff-delegation problems; both were fixed before the green run (unit now resolved through the tenant directory; staff use a separate safe method; connection ownership, ordering and concealment verified). Reviewers did not execute tests.
- **Simulated user / human participant:** none. No clinical or real-patient evidence; G5 sign-off remains required.

## Integrator-owned gates (not run locally, per instruction)

`bash db/check.sh`, the `@Tag("integration")` `ConversationFlowTest` (now seeds care units, connections and in-memory links/members), Flyway V1–V55, full `mvn verify` JaCoCo/SpotBugs/OWASP, `bash scripts/check.sh`, `bash scripts/integrator-gate.sh`, and `bash scripts/docs.sh` with Doxygen. `-Pfast` excludes the integration group, so **no integration result is claimed in this note**.
