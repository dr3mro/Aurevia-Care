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
