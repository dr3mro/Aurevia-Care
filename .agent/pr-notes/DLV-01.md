# DLV-01 — Repair integration tests: patient chart, chart entry and exchange task

This file preserves both workers' notes for the same story. The merge resolution keeps
main's UTC-`OffsetDateTime` (`instantValue`) binding together with this branch's explicit
`CAST(... AS timestamptz)` in the chart-record and patient-chart SQL.

## Variant A — branch `swarm/DLV-01-222` (text binding + explicit CAST)

Status: ready for integrator. Branch `swarm/DLV-01-222`, base `feb59df`. Six files changed, all inside `apps/api`; no migration, `.github/`, `scripts/` or shared tracker touched. All four named classes are green in the full `mvn verify` run. The remaining full-gate failures belong to the separate DLV-02/DLV-03 stories.

## Requirements and acceptance mapping

| Requirement | Acceptance repaired |
|---|---|
| CLN-03 | Chart records/entries can be captured with provenance, verification and tenant isolation against real PostgreSQL; audit and verification are observable (ChartRecordIntegrationTest, ChartEntryIntegrationTest). |
| CLN-05 | Version-bound verification refuses a stale/concurrent write with 412; the winning revision is the only one applied. |
| ARC-02 | Forced RLS and tenant context are respected by test SQL assertions (audit reads must bind the active organization); two tenants with identical local ids stay concealed. |
| ARC-03 / ARC-04 (supporting) | `If-Match` version precondition is validated before the lifecycle state, so a stale version yields 412 while a current-version already-verified entry yields 409. |
| XCH-02 (supporting) | `ExchangeTaskIsolationTest` proves the INSERT trigger normalises a forged review state; the fixture must leave the row free for the test to insert. |

Classification (`docs/WORKFLOW.md`): cross-boundary (production repository + service + integration fixtures), with a safety-critical verification precondition. Synthetic data only. No human or clinical participant; evidence is tool output plus one bounded read-only senior-backend review (simulated).

## Root cause per class (all reproduced RED first)

| Class | Observed RED | Root cause | Fix |
|---|---|---|---|
| `ChartRecordIntegrationTest` | 5 × HTTP 500 on `POST /api/v1/chart/records/entries`. Server trace: `PSQLException: Can't infer the SQL type to use for an instance of java.time.Instant` at `chart.record.ChartRepository.insert:106`. | **Production defect.** The repository bound `Instant` values directly; PG JDBC 42.7.14 cannot infer a type for `java.time.Instant`. The failure happens at parameter binding, before SQL parsing. | `chart/record/ChartRepository` now binds ISO-8601 text via `instantText(Instant)` and casts it with `CAST(:param AS timestamptz)` in both `insert` and `verify` — the established pattern in `AppointmentRepository`, `WorklistRepository`, `ExchangeTaskRepository`. Same latent defect fixed in `patient/chart/ChartRepository` (the sibling slice would have 500'd once its fixture was repaired). |
| `ChartEntryIntegrationTest` | `prepare -> seedPatient`: `new row for relation "patient_record" violates check constraint "patient_record_check"`. Also Flyway logged `Skipping filesystem location: .../it/s_g1_03/migrations (not found)`. | **Two fixture defects.** (1) `seedPatient` inserted neither `dob` nor `dob_unknown_reason`, violating V7 `CHECK ((dob IS NOT NULL) <> (dob_unknown_reason IS NOT NULL))`. (2) The package refactor moved the test migration directory to `it/patientchart/migrations` but left the stale `s_g1_03` Flyway location, so `chart_entry` was never created. | Seed synthetic `dob = DATE '1990-05-20'` (matches the already-fixed `ChartRecordIntegrationTest`); point Flyway at `it/patientchart/migrations`. No schema change: V7's invariant is correct. |
| `ExchangeTaskIsolationTest` | `storageCannotForgeAReviewedTaskOrMoveTwoChannels`: `duplicate key value violates unique constraint "exchange_task_pkey"`. | **Fixture defect.** `@BeforeAll` seeded `FORGED` among the initial tasks, so the test's own `INSERT` of that id collided before the V20 `xch02_initialize_task_channels` trigger could be exercised. | Remove `FORGED` from the seeded id array; the test now inserts a fresh row and asserts the trigger resets workflow/transmission/clinical-review state. The production guard is correct and unchanged. |
| `CheckInWorklistIntegrationTest` | Story reported 2 rows expected, 9 observed. | Already repaired on current `main` (commit `cd390df`): the assertion is scoped by appointment id and the ordering list is filtered. No change needed here. | Verified green (9/9) in the RED run and in every subsequent run; no edit made. |
| `ChartRecordIntegrationTest` / `ChartEntryIntegrationTest` (second RED layer, after the 500s were fixed) | Audit count `expected: <4> but was: <0>`; race `expected: <1> but was: <0>` for a 412. | **Two more fixture/test defects plus one precondition-order defect.** (a) The audit-count SQL connection was opened as `smartclinic_migrate` without setting `app.current_org`; `audit_event` has `FORCE RLS`, so the owner saw zero rows (the same connection also calls `audit_verify_chain`, which requires the active tenant). (b) The concurrent loser returned 409 when its read followed the winner's commit because `ChartService.verify` checked `ALREADY_VERIFIED` before the `If-Match` version. | (a) Set `SET app.current_org = ORG_A` on that connection before both queries. (b) Validate `existing.version() != expected` **before** the verified-state check in both `ChartService.verify` methods, so a stale precondition always returns 412 and a current-version verified entry still returns 409. The original strict 412 assertions were kept — no test was weakened, skipped, deleted or re-tagged. |

## Decisions

- No new migration: V7's patient invariant and V20's channel trigger are correct; every observed defect was in test fixtures or Java bindings. Already-applied migrations were not edited.
- Kept the two-line `instantText` helper local to each repository (five existing copies in the codebase) instead of adding a shared utility; a new shared class would widen a repair story and need its own coverage. Confirmed by the bounded senior-backend review.
- Both halves of the Instant fix are required: wrapping to text fixes the driver binding; `CAST(... AS timestamptz)` makes the text unambiguous. Dropping either reintroduces the 500.
- `encounterId` is a `text` column in both test-scoped V13 migrations and is bound as a `String`; no UUID cast was added.
- The senior-backend reviewer noted a non-blocking parity gap (`patient.chart.ChartService` does not normalise a blank `unknownReason` like the record slice). It yields a translated 409, not a 500, is not asserted by the patient test, and is outside DLV-01; left for a follow-up story.

## Exact commands and results

Environment: Java 21.0.13 Temurin, Maven 3.10.0, PostgreSQL 17.12 via `SMARTCLINIC_PG_ADMIN`; tools executed by the primary agent, not delegated.

| Command | Result |
|---|---|
| `cd apps/api && mvn -B test -Pno-security -Dtest=ChartRecordIntegrationTest,ChartEntryIntegrationTest,ExchangeTaskIsolationTest,CheckInWorklistIntegrationTest` (before fixes) | exit 1 — Tests run 24, Failures 5, Errors 2 (5 × 500, duplicate key, V7 CHECK) |
| Same command after binding/fixture fixes | exit 1 — Tests run 27, Failures 4, Errors 0 (audit count 0, race 412 count 0 in both chart classes) |
| Temporary instrumentation of race outcomes (removed afterwards) | combined run `[409, 200]` / `[200, 409]`; isolated run `[412, 200]`. Established the timing-dependent precondition-order root cause. |
| Same command after audit-context and precondition-order fixes, run A | exit 0 — Tests run 27, Failures 0, Errors 0 (5 + 9 + 4 + 9) |
| Same command, run B (repeat for flake check) | exit 0 — Tests run 27, Failures 0, Errors 0 |
| `mvn -B test -Pfast` | exit 0 — Tests run 1844, Failures 0, Errors 0 |
| `mvn -B spotbugs:check -DskipTests` | exit 0 — `BugInstance size is 0` |
| `bash apps/api/check.sh` (full `mvn -B verify`, default security profile) after final code | exit 1 overall — Tests run 2087, Failures 63, Errors 4. Surefire reports for all four DLV-01 classes: 5/5, 9/9, 4/4, 9/9 green with zero failures/errors. |
| `git diff --check` | exit 0 |

Evidence provenance: all command results above are **tool-generated** by this worker; the bounded senior-backend review is **simulated reviewer** evidence (file reads only, no commands run by that subagent). No simulated-user or human/clinical evidence was available or claimed.

## Out-of-scope remainder (for the integrator / DLV-02 / DLV-03)

The full gate is still red because of 11 classes owned by the other repair stories, none touched here: `IdentityProofingIsolationTest`, `StaleInvitationIntegrationTest`, `ConsolidationIsolationTest`, `ConversationFlowTest`, `ClinicalNoteRevisionIntegrationTest`, `ResultRoutingIntegrationTest`, `AccessIntegrationTest`, `DocumentIsolationTest`, `SpecimenAccessionIsolationTest`, `TrendingTest`, `RxTransmissionFlowTest`. Their reported causes (the `set_link_actor()` trigger, Spring 7 `UNPROCESSABLE_CONTENT` rename, `uuid = character varying`, prescription transmission 500s) match the DLV-02/DLV-03 descriptions. Because surefire fails before the verify-phase plugins, the final full-run JaCoCo 100% gate and OWASP check could not be observed on this checkout; SpotBugs was run separately and is green. The integrator's full `scripts/check.sh` on the merged SHA remains the authoritative gate.
## Variant B — merged to `origin/main` in PR #187 (UTC `OffsetDateTime` binding)

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
