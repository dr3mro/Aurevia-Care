
# S-G3-06 — PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access

## Story and requirements

- Story: S-G3-06 (swarm fix attempt 1 of 3), branch `swarm/S-G3-06-90`, base SHA `7cd80d6` (contains the verified Doxygen fix). This delivery commit adds the PR-notes artifact.
- Primary requirements: **MSG-03** (attachments, explicit send, recipient shown before send, quarantine scan, clean-verdict access, sender binding, removal/replacement), **ARC-06** (validate size/type/checksum, private quarantine, unique object keys and verified object version, mediated downloads, keep clinical payloads out of logs/URLs).
- Supporting requirements exercised by the implementation: MSG-01 / ARC-07 (connection-scoped binding), IAM-01 (fresh actor snapshot every command), ARC-02 (tenant-scoped store).

## Failure being fixed (defect)

The fast merge gate (`scripts/integrator-gate.sh`) failed on the merge of `main` and this branch. Relevant log tail:

```
apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java:338: error: argument 'organizationId' from the argument list of com.aurevia.smartclinic.messaging.s_g3_06.AttachmentContract.Actor has multiple @param documentation sections
... same for 'subject', 'roles', 'patientId', 'unitId'
```

This is a Doxygen error (`docs/Doxyfile`: `WARN_NO_PARAMDOC = YES`, `WARN_AS_ERROR = FAIL_ON_WARNINGS`, run by `scripts/docs.sh`). Root cause: the `Actor` record repeated its five component `@param` tags on the record declaration **and** on its compact constructor and its two static factory methods. Doxygen folds a record's compact constructor and factory methods into a single canonical-constructor documentation entity, so each component appeared in more than one `@param` documentation section. The same pattern existed on the `View` and `PublishRequest` compact constructors.

## Fix

Document each record component exactly once, on the record declaration, and carry **no** `@param` tags on compact constructors or static factory methods (they keep only `@return`/prose). This satisfies `WARN_NO_PARAMDOC` without producing duplicate sections. The change is present at branch HEAD commit `7cd80d6` in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java` (`Actor`, `View`, `PublishRequest`). No further source change was needed; this attempt verifies and records the fix.

## Red → green evidence (synthetic, tool-generated)

- RED reproduction: a minimal synthetic record `/tmp/doxrepro/src/Repro.java` with `@param` on both the record declaration and its compact constructor/factory, checked with the repository `docs/Doxyfile` (doxygen 1.9.4), reproduced the exact failure:
  `error: argument 'organizationId' from the argument list of repro.Repro.Actor has multiple @param documentation sections` (exit 1).
- GREEN verification: the same minimal record with component `@param` only on the record declaration → doxygen exit 0, no warnings.
- GREEN verification of the actual scope: a Doxyfile with `INPUT = apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06` and `WARN_AS_ERROR = FAIL_ON_WARNINGS` over the current files → exit 0, zero warnings/errors.
- `grep -n "@param organizationId" .../AttachmentContract.java` shows one declaration-level tag per record; no constructor/factory duplicates remain.

## Exact commands run and results

| Command | Result |
|---|---|
| `doxygen docs/Doxyfile` (focused Doxyfile, scope package only) | exit 0, 0 warnings/errors |
| minimal synthetic duplicate-`@param` record + doxygen | exit 1, exact "multiple @param documentation sections" error (reproduction) |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0; 297 tests, 0 failures/errors/skipped; `AttachmentAcceptanceTest` 28 tests OK; BUILD SUCCESS |
| `cd apps/patient-mobile && npm ci --no-audit --no-fund` | exit 0 (1272 packages) |
| `cd apps/patient-mobile && npm run typecheck && npm run lint && npm test -- --ci --runInBand` | exit 0; tsc clean, eslint clean; Jest 1 suite / 22 tests passed |

Pre-existing/environmental, out of scope (not caused by this change, not modified):
- `python3 -I -m unittest discover -s scripts/tests -q` → 162 tests, 6 failures / 4 errors. Failures include `test_repository_pack_is_valid`, `test_committed_opencode_json_is_generated`, `test_check_mode_detects_drift` (`opencode.json is stale`), `test_agent_run.*` (environment-specific git refs) and import errors for `test_fast_tier` / `test_workflow_concurrency`. The stale `opencode.json` is the documented cloud-worker CI-profile/skip-worktree artifact; the rest are unrelated to S-G3-06.
- Full `doxygen docs/Doxyfile` locally reports one error outside this scope: `apps/api/src/main/java/com/aurevia/smartclinic/g1/s_g1_04/TemplateService.java:23: unable to resolve link ... Actor for \link command`. Local doxygen is 1.9.4 (CI installs via apt); this is outside the declared scope and was left untouched.
- No local `main` ref is present, so the exact "merge of main and this branch" could not be reproduced locally; the integrator's failure log above is the authoritative red evidence. The integrator runs the database and integration tests; per worker rules only the fast unit tests were run here.

## Acceptance mapping (requirement → evidence)

- MSG-03: validate type/magic/size/checksum (`AttachmentInputs`, `FileType`), bind upload to a server-side verified connection grant and subject (`AttachmentService.upload`), explicit send of named clean files to one recipient unit (`publish`), quarantine verdict gating (`scan`), clean-verdict-only mediated downloads (`download`), removal/replacement (`remove`), and unit inbox of published patient-supplied material (`inbox`). Evidence: `AttachmentAcceptanceTest` 28 tests (Java fast tier), patient-mobile `attachments.test.tsx` 22 tests.
- ARC-06: private quarantine state machine, unique versioned object key, mediated handle carrying no bytes/file name/URL, and non-sensitive append-only event facts (`AttachmentContract`, `AttachmentService`, `AttachmentStore`). Evidence: same suites.
- Doxygen/doc gate: component `@param` documented once on each record declaration; scope docs build clean (commands above).

## Decisions

- Keep component documentation on the record declaration; compact constructors and static factories must not repeat `@param` (Doxygen folds them into one canonical-constructor entity). This is the minimal fix that satisfies both `WARN_NO_PARAMDOC` and the no-duplicate rule.
- No tracker, workflow, script, migration or out-of-scope source file was edited; only the declared scope was reviewed.

## Evidence provenance

All results are from executed tools (Doxygen 1.9.4, Maven/Surefire, npm/tsc/eslint/Jest, Python unittest) on synthetic fixtures. No real patient data, credentials or raw logs are recorded. No human participant or authorized clinical feedback was used; the only review input was the integrator's automated gate log. Database and integration checks are the integrator's responsibility and were not run here.

---

# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Scope honoured

Only the declared scope was available for edits:
`apps/api/src/main/java/com/aurevia/smartclinic/g0/s_g0_08`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g0_08`, and reserved migration `V11`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) and no file outside that scope was edited. No push, tag or `gh` call
was made. Synthetic fixtures only.

## Requirement IDs and acceptance mapping

- **IAM-06** (primary): emergency access is time-limited (1–60 min), scoped to one
  tenant-local facility, reasoned, attributed to a qualified clinician, audited on the
  V3 append-only chain, independently reviewable by privacy auditor / tenant admin
  (grantee cannot review own grant), and revocable early. Platform support access is
  **off by default** and only effective inside an approved, time-bounded (1–240 min)
  incident window; checks return an explicit fail-closed `AccessDecision`.
- **ARC-02 / OPS-02** (supporting): V11 tables carry `(organization_id, id)` keys and
  forced row-level security; purpose, role, facility, actor and timestamp are recorded
  and committed with the audit event.
- Delivered surfaces already present in scope (unchanged by this fix attempt):
  `AccessController` (8 endpoints under the IAM route family), `EmergencyAccessService`,
  `SupportAccessService`, `AccessRepository`, `AccessRules`, `AccessContracts`,
  `V11__emergency_and_support_access.sql`, `AccessUnitTest` (41 tests),
  `AccessIntegrationTest` (8 DB tests, integrator-run).

## Reported failure — investigation and disposition

The integrator's "requested change" reported a `scripts/check.sh` failure whose only
evidence was this stack trace:

```
Error: useTheme must be used inside ThemeProvider
    at useTheme (.../apps/staff-web/src/theme/ThemeProvider.tsx:39:11)
    at Consumer (.../apps/staff-web/tests/theme/ThemeProvider.test.tsx:6:32)
```

Findings:

1. **Out of scope.** The error is in `apps/staff-web`, which is outside this task's
   declared scope (`apps/api/.../s_g0_08` + V11). It cannot be fixed from this branch
   without violating the task rules, and no in-scope file can cause or cure it — the
   staff-web tests use local synthetic fixtures and make no API calls.
2. **Not an actual test failure.** The identical stack trace was reproduced locally.
   It is the expected stderr output of the intentional negative-path test
   `throws when used outside its provider` (`ThemeProvider.test.tsx:31-35`), which
   asserts `expect(() => render(<Consumer />)).toThrow(/useTheme/)`. React 18 dev mode
   logs the guarded-callback rethrow through jsdom's `reportException`; the test still
   catches it and passes. `npx vitest run tests/theme/ThemeProvider.test.tsx` → 3/3
   passed; the full `npm test` suite → **103/103 passed, exit 0**. The pasted text is a
   benign log line, not a build failure.
3. **Merge not reproducible locally.** The checkout is a grafted single-commit shallow
   clone (`git log --all` shows only `bb355f1`; `.git/shallow` present; no local `main`
   ref), so "the merge of main and this branch" cannot be reconstructed here. This is
   an evidence gap, not a code defect.

No in-scope defect was found; no in-scope change was needed for a fix attempt.

## Exact commands run and results

| Command | Result |
|---|---|
| `git status --short` / `git branch --show-current` / `git log -1` | clean tree; branch `swarm/S-G0-08-67`; HEAD `bb355f1` |
| `git show --stat HEAD` / `find` scope dirs / `ls db/migrations` | full S-G0-08 implementation in scope; V11 present |
| `bash scripts/quick-check.sh` | exit 1 **only** at `check-pack: opencode.json is stale` — environment artifact (see below); all preceding policy checks passed |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree); worktree file is CI profile (`wt == generated CI == True`, `committed == generated LOCAL == True`) — documented cloud-worker CI-profile trap, not a branch defect |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; 231 tests, 0 failures, 0 errors; `AccessUnitTest` 41/41 |
| `cd apps/staff-web && npm ci && npx vitest run tests/theme/ThemeProvider.test.tsx` | **exit 0**; 3/3 passed (error text reproduced as benign stderr) |
| `cd apps/staff-web && npm test` | **exit 0**; 23 files, 103/103 passed |

Environment note: `check-pack.py` in this worker reads the skip-worktree CI-profile
`opencode.json`, so it reports it stale; the committed `opencode.json` matches the
generated local profile and is correct. Java/Database integration tests are left to
the integrator as directed.

## Defects found

- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.

## Decision / stop notice

Per the task rule "if a decision or evidence is missing, stop and say so in
`.agent/pr-notes.md`": the merge-gate failure cannot be reproduced from this shallow
clone, the only supplied evidence is a benign log line from a passing out-of-scope
test, and there is no in-scope defect to fix. I therefore made **no code change** and
stop, committing only these notes. If the integrator still observes a non-zero
`scripts/check.sh` on the merge, the failing step and its exit code must be supplied;
if it is a genuine staff-web regression it belongs to a staff-web-scoped worker, not
this IAM-06 backend slice.

---

# S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering

## Requirement IDs and acceptance mapping

Primary: **XCH-02**. Supporting: **ARC-01, ARC-02, ARC-03, ARC-04, IAM-01, PRD-06, OPS-02**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-02, §2 IAM-01, §7 ARC-01..04, §8 OPS-02. Gate: G2.

| XCH-02 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Three independent state channels: workflow `requested→accepted→processing→completed` (+ `rejected`/`cancelled`), transmission `pending→sent→delivered\|failed`, clinical review `unreviewed→acknowledged→action_pending→resolved` | `WorkflowState`, `TransmissionState`, `ClinicalReviewState` matrices; `ExchangeDimension`; `ExchangeStateMachine.next` writes exactly one channel; V20 `CHECK` constraints + `xch02_guard_task_update` mirror the matrices in SQL | `WorkflowStateTest` (3), `TransmissionStateTest` (2), `ClinicalReviewStateTest` (2), `ExchangeStateMachineTest` (6); `ExchangeTaskIsolationTest.workflowPathRejectsReasonsAndIllegalTransitions`, `transmissionAcknowledgmentNeverClosesAClinicalTask`, `reviewChainRequiresAcknowledgmentAndIsAppendOnly` (integrator) |
| Acknowledgment by an authorized human with timestamp and revision | Clinician-only `EXCHANGE_REVIEW` in `ExchangeActionPolicy`; `Clock`-derived timestamp; `reviewedRevision = current.version()` after `If-Match` CAS; actor derived by V20 `xch02_actor_id()` from transaction-local `app.actor`, never from a request DTO | `ExchangeTaskServiceTest.acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor`, `acknowledgeDeniesNonClinicianAndInactiveMembership`; `ExchangeTaskIsolationTest.humanAcknowledgmentBindsContextActorClockAndRevision` (integrator) |
| A transmission acknowledgment cannot close a clinical task | `DELIVER`/`FAIL`/`SEND` map to `TRANSMISSION`; the state machine copies the clinical channel and its evidence unchanged; V20 restores clinical evidence columns on non-clinical writes and enforces the one-channel rule | `ExchangeStateMachineTest.aTransmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskServiceTest.transmissionAcknowledgmentNeverMutatesClinicalReview`; `ExchangeTaskIsolationTest.transmissionAcknowledgmentNeverClosesAClinicalTask` (integrator, incl. direct SQL assertion) |
| Explicit POST commands with version precondition and machine-readable errors (ARC-03/04) | `POST /api/v1/exchange-tasks` and `/{id}/{command}`, `IfMatch.parse` (428 missing / 422 malformed / 412 stale through CAS), allowlisted `ExchangeCommand.from`, uniform `ApiException` mapping | `ExchangeTaskControllerTest` (5); `ExchangeTaskIsolationTest.authorizationPreconditionsAndTenantConcealment` (401/403/404/409/412/422/428) (integrator) |
| Tenant isolation, fail-closed authorization, append-only ledger and audit (ARC-01/02, IAM-01, OPS-02) | `(organization_id,id)` composite keys; forced RLS on both V20 tables with `WITH CHECK`; `FOR UPDATE` + version CAS; immutable `exchange_task_transition`; `appendAudit` SUCCESS/DENIED inside the guarded transaction; membership and roles re-derived per request | `ExchangeTaskIsolationTest` two-tenant `LOCAL-1` concealment, ledger immutability attempt, audit-row assertions (integrator); `ExchangeTaskServiceTest` 403 denial audits |

### Entry-point-to-test trace

`POST /api/v1/exchange-tasks` → `ExchangeTaskController.create` → `ExchangeTaskService.create` → guarded `TransactionTemplate` (`TenantGuardTransactionManager` binds `app.current_org`/`app.actor`) → `ExchangeActionPolicy.allowsCreate` → `ExchangeTaskRepository.insert` (forced RLS) → `appendEvent(CREATE)` + `appendAudit` → 201 with three initial states. Verified by `ExchangeTaskControllerTest.createReturns201WithLocationAndInitialStates` and `ExchangeTaskIsolationTest.createOpensTheThreeInitialChannelsWithOneHistoryEvent`.

`POST /api/v1/exchange-tasks/{id}/{command}` + `If-Match` → `ExchangeTaskController.execute` → `IfMatch.parse` → `ExchangeCommand.from` → `ExchangeTaskService.execute` → authorize → `repository.loadForUpdate` (`FOR UPDATE`) → `requireVersion` → `ExchangeStateMachine.next` (one channel) → `repository.updateState` (version CAS, V20 guard) → `appendEvent` + `appendAudit` → next state. Verified by `ExchangeTaskControllerTest`, `ExchangeTaskServiceTest`, `ExchangeStateMachineTest` and the integration scenarios.

`GET /api/v1/exchange-tasks/{id}` → `get` → read policy → `repository.find` + `history` → read audit → state + append-only history.

## Commands run and results

Evidence source for this section: **executed tool commands** (not reviewer claims).

- `java -version` → `openjdk version "21.0.13" 2026-10-13 LTS` (Temurin). `mvn -version` → `Apache Maven 3.10.0`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. This is the exact DB-less fast command from `scripts/quick-check.sh`. Surefire XML aggregation: **1463 tests, 0 failures, 0 errors, 0 skipped across 134 suites**. The 8 in-scope S-G2-02 suites contribute **38 tests, all green**: `ClinicalReviewStateTest` 2, `ExchangeActionPolicyTest` 4, `ExchangeCommandTest` 3, `ExchangeStateMachineTest` 6, `ExchangeTaskControllerTest` 5, `ExchangeTaskServiceTest` 13, `TransmissionStateTest` 2, `WorkflowStateTest` 3. (The exact command was re-run a second time with `>/tmp/sg2_02_fasttest.log` to capture the exit code: `FAST_TEST_EXIT=0`.)
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0**. Reserved V20 is present as `db/migrations/V20__exchange_task_states.sql` with a valid `V<n>__name.sql` name and `Change: expand`; no other migration version was touched.
- `git check-ignore -v .agent/pr-notes.md` → not ignored (tracked file); `git ls-files --error-unmatch .agent/pr-notes.md` → tracked. `git status --short` before commit → clean.
- Cross-checks: `IfMatch.parse` (428/422/412 semantics), JaCoCo `check` execution bound at 1.00 for INSTRUCTION/LINE/BRANCH/METHOD/CLASS and the `fast` profile's `excludedGroups=integration` were read from `apps/api/pom.xml` to confirm the gate boundary.

I did **not** run the database/integration/coverage/static-security gates. Per the worker contract these belong to the integrator: `bash db/check.sh`, `mvn -q -B verify` (runs the `@Tag("integration")` `ExchangeTaskIsolationTest` on real PostgreSQL plus JaCoCo 100%, OWASP and SpotBugs), and `scripts/check.sh`. No result from those gates is claimed here.

## Reviewer findings and dispositions

Three independent simulated specialists reviewed the in-scope files (paths, XCH-02 acceptance and the fast-gate evidence only). All three returned **no blocking defect and no required in-scope source change**:

- **Senior Java & Spring Boot** — transition correctness, transmission/clinical independence, server-side acknowledgment binding, CAS, authorization/error mapping all traceable to tests. One low-severity hardening idea: `ExchangeTaskService.reason()` bounds only presence, not length/characters, though `reason` is persisted to two `text` columns. Disposition: **non-blocking follow-up**; not changed here because it is outside the XCH-02 acceptance and the reviewer explicitly said it should not block acceptance.
- **Software Architect** — Java↔V20 transition matrices match one-for-one; channels independent; revision/time/actor bound to the reviewed version; concurrency/RLS/ledger/audit sound. Flagged (a) no direct SQL negative test of the V20 guard rejection paths, (b) no `Idempotency-Key` (documented project deferral; XCH-02 requires only `If-Match`), (c) "revision" interpretation question, (d) DB guard cannot check role for a hypothetical direct SQL writer. Disposition: **(a) recommended to integrator/QA** as added evidence, not a source change; **(b)** out of scope by documented decision; **(c)** recorded as a decision below; **(d)** defence-in-depth only — authorization is enforced before every write.
- **Senior Database** — V20 evidence `CHECK`, guard trigger, fail-closed `xch02_actor_id`, CAS, immutability triggers, grants and forced RLS all correct; change-safety checker not tripped. One out-of-scope observation: `db/check.sh` static table lists do not include the two new tables, so the shell gate does not assert V20 policy; the Java IT is the runtime proof. Disposition: **separate change**, not touched here.

Reviewer evidence is **simulated review**, not human or clinical acceptance. No human participant was involved.

## Defects found

No reproducible in-scope defect was found. The hardening ideas and verification-gap suggestions above are recorded as follow-ups, not as accepted defects.

## Decisions / notes for the integrator

- The complete S-G2-02 implementation (16 main files), 9 test files and the V20 migration were **already committed at this branch's base** `da4fbb4` (a prior attempt's work, reflected in the recycled `.agent/pr-notes.md`). This worker changed **no production or test source** after verification; its contribution is this PR description and the recorded executable evidence. The prior notes described S-G1-07 and were replaced.
- **"Revision" interpretation**: XCH-02's "timestamp and revision" is implemented as the work-item optimistic version the clinician reviewed (the `If-Match` version at acknowledgment), not the immutable order snapshot revision from XCH-01. This slice is deliberately self-contained (no V19 dependency) and the choice is documented in `ExchangeStateMachine`/V20 comments. Confirm with Product/Architecture before G2 sign-off if a different meaning was intended; it does not block this story.
- **Idempotency keys** are absent on transition routes by documented project deferral; a retry after a lost response gets 412 from the version CAS rather than the original outcome. This diverges from the literal ARC-04 sample contract but is outside XCH-02.
- Verify on the merge result: the `@Tag("integration")` `ExchangeTaskIsolationTest` (real PostgreSQL: V20 guard, forced RLS, actor derivation, two-tenant concealment, ledger immutability), JaCoCo 100% at `verify`, OWASP/SpotBugs and `scripts/check.sh`. These are the integrator's gates, not claims of this worker.
- Scope respected: only `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_02`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_02` and reserved migration V20 were in scope; no shared tracker, doc, workflow, other migration or source file was edited. No push, tag or `gh` call was made.
- Synthetic fixtures only; no patient data, credentials or raw logs were recorded. All gate results above are tool-generated; review findings are simulated; there is no human or clinical evidence.
