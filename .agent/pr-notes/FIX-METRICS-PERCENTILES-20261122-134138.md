# FIX-METRICS-PERCENTILES — Expose latency distributions for OPS-03 percentile alerts

## Summary

`RequestMetrics` published only a count and a latency sum, which can report an average but not a tail. Added a bounded, cumulative latency histogram family `smartclinic_http_request_duration_seconds` with the existing `method`/`route`/`status` labels plus an `le` bound label, exposing buckets at the OPS-03 p95 targets (0.5 s reads, 0.9 s commands) and the mandatory `+Inf` bucket. The `+Inf` bucket always increments, so its value equals the histogram `_count`, which is rendered from the existing request counter — buckets and count cannot drift. The two legacy counter families keep their names and values, so dashboards stay compatible. Documented the scrape and `histogram_quantile` alert query in the production readiness guide.

## Affected requirements

- OPS-03: p95 ≤ 500 ms routine API reads and ≤ 900 ms routine commands, measured server-side. The 0.5 s and 0.9 s buckets sit on those thresholds and the documented alert computes p95 from the bucket rates.
- WEB-06: publish per-route duration; never emit raw identifiers; access-gated scrape. Histogram series reuse the matched Spring route pattern and remain behind the existing `SMARTCLINIC_METRICS_ALLOW` address gate.

## Risk class

Routine (contained metrics aggregation in the `web` package plus an operations document). No clinical logic, tenant boundary, state machine or persistent data changed. Defensive access-control tests were kept and extended.

## Review owner

smartclinic-senior-backend (Java/Spring), smartclinic-senior-spring-security (scrape access control), smartclinic-test-engineer (coverage). No physician or real-user review needed: no patient data, no clinical decision.

## Data changes

None. No migration, table, backfill or lock/volume impact. Metrics remain instance-local aggregate counters, as before this change; there is no new record of truth and nothing to persist across a restart. Public Prometheus text output gained one histogram family; the two existing families are unchanged.

## Authorization boundaries and audit events

`GET /health/metrics` is unchanged: no login, but answered only from loopback or a CIDR in `SMARTCLINIC_METRICS_ALLOW`, otherwise a bare 404. The histogram adds only an `le` label bound to a fixed numeric list; `method`, `route` and `status` still come from the HTTP method, the matched Spring route pattern and the status class. No raw path, id, token, tenant or patient field can reach a label. No audit events added or changed.

## Migration and rollout considerations

None. Backward compatible: existing counter names and samples are untouched, so a rolling deploy mixing old and new replicas yields a superset of series. Scrape size grows by 14 bucket samples plus sum and count per observed series; bounded by the fixed bucket set and the number of route/status series.

## Test evidence

All commands run from the repository root. Source of evidence: executed tool commands and JaCoCo XML; no reviewer, simulated user or human participant evidence.

- RED (before implementation): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequestMetricsHistogramTest,MetricsHttpTest test` → `Tests run: 6, Failures: 6, Errors: 0, Skipped: 0`, all new assertions failed because no histogram family existed.
- GREEN (final code): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequestMetricsTest,RequestMetricsHistogramTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsHttpTest test` → `MVN_EXIT=0`; Surefire: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (RequestMetricsHistogramTest), `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (MetricsHttpTest), plus RequestMetricsTest 3/3, RequestMetricsFilterTest 3/3, MetricsControllerTest 2/2.
- Static analysis: `mvn -q -B -f apps/api/pom.xml -Pno-security -Djacoco.skip=true -DskipTests compile spotbugs:check` → `SPOTBUGS_EXIT=0`, zero findings (classes compiled first).
- Policy: `python3 -I scripts/check-names.py` → 0; `python3 -I scripts/check-wiring.py` → 0 (only pre-existing informational baseline notes); `python3 -I scripts/check-docs.py` → 0.
- Java conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` → `Ran 2 tests ... OK`. The full tooling suite `python3 -I -m unittest discover -s scripts/tests` → `Ran 1138 tests ... FAILED (failures=6)`; all six failures are `test_check_pack.*`, the documented worker-image `skip-worktree` artifact (`git ls-files -v opencode.json` → `S`). Confirmed once, not re-diagnosed.
- `python3 -I scripts/gen-opencode.py --check` → `opencode.json is out of date: run scripts/gen-opencode.py` (same artifact). `bash scripts/check.sh` → `check-pack: opencode.json is stale: run scripts/gen-opencode.py`, exit 1 (same artifact, confirmed once, not re-diagnosed; AGENTS.md says the integrator owns this gate).
- Entry point to test trace: `GET /health/metrics` → `MetricsController.scrape` → `RequestMetrics.scrape` → `appendHistogram`; `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` drives two synthetic `GET /api/v1/patients/patient-777|888` requests through `RequestMetricsFilter` → `RequestMetrics.record`, then scrapes over HTTP and asserts `smartclinic_http_request_duration_seconds_count{...} 2`, `le="+Inf"} 2`, and absence of the raw ids. Access control: `MetricsHttpTest.productionChainsServeMetricsOnlyFromAllowedAddresses` asserts a loopback scrape is 200 with histogram metadata, an external source (`198.51.100.10`) gets 404, and a protected `/api/v1` route is still redirected under the production chains; `MetricsControllerTest` asserts the same 200/404 split at the handler. No CI run is linked because this worker does not push or call gh.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security -Djacoco.skip=false -Dtest=RequestMetricsTest,RequestMetricsHistogramTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsHttpTest test jacoco:report`, then read `apps/api/target/site/jacoco/jacoco.xml`.

Result for the only changed production class, `com/aurevia/smartclinic/web/RequestMetrics`: INSTRUCTION missed=0 covered=466; BRANCH missed=0 covered=10; LINE missed=0 covered=57; METHOD missed=0 covered=12; CLASS missed=0 covered=1 — 100% on every JaCoCo counter.

New and changed branches and the test that takes each side:
- Histogram loop `i < BUCKET_UPPER_BOUNDS_NANOS.length` (enter/exit): every `record` test, e.g. `RequestMetricsHistogramTest.exposesBucketsAtThePercentileTargets`.
- `elapsedNanos <= BUCKET_UPPER_BOUNDS_NANOS[i]` true side: `RequestMetricsHistogramTest.emitsEveryBucketForEachSeriesIncludingZeroCounts` (5 ms/1 ns observations fall inside buckets) and `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` (real fast requests).
- Same condition false side: `RequestMetricsHistogramTest.bucketsAreCumulativeAndCountMatchesObservations` (1.5 s exceeds the 0.1/0.5/0.9/1 s bounds) and `emitsEveryBucketForEachSeriesIncludingZeroCounts` (20 s exceeds every finite bound).
- `bucket == null ? 0L : bucket.sum()` null side (a bound never crossed for a series): `emitsEveryBucketForEachSeriesIncludingZeroCounts` asserts `le="0.005"} 0` and `le="10"} 0`.
- Same ternary non-null side: `bucketsAreCumulativeAndCountMatchesObservations` asserts `le="0.1"} 1`, `le="2.5"} 2`, `le="+Inf"} 2`.
- Access-control 200/404 branches were already covered by `MetricsControllerTest` and `MetricsHttpTest`, both kept and extended.

## Repair pass — coverage check failure (attempt 1 of 2)

The delivery coverage gate (`scripts/coverage-check.sh` → `mvn -q -B -f apps/api/pom.xml -Pno-security verify`) failed at `testCompile`, before JaCoCo measured any coverage. Root cause is the pre-existing defect already recorded under `## Findings`: `MessagingAuditContract.ExportRecord` gained a 7th `ExportProvenance` component in BUILD-16 (`905c23d0`), but four call sites in `OffboardingCommandTest.java` (lines 43, 92, 99, 106) still passed only 6 arguments. Java test compilation is all-or-nothing, so no test ran and the gate could not produce coverage data.

Fix (test source only; no production line or branch changed, no threshold/exclusion/suppression added): imported `MessagingAuditContract.ExportProvenance`, added a shared synthetic constant `PROVENANCE = new ExportProvenance("export-1", "rev-1", "record_access")`, and supplied it as the 7th argument at all four `ExportRecord` constructions. `OffboardingCommand.begin` gates only on `organizationId`, `id` and `expiresAt`; provenance is immaterial to that decision, so a synthetic value keeps the fixtures deterministic. No production code, migration, contract or threshold was touched.

Source of evidence for this section: executed tool commands only (no reviewer, simulated user or human participant).

Repair gate evidence:
- Targeted tests (executed tool): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=OffboardingCommandTest,RequestMetricsTest,RequestMetricsHistogramTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsHttpTest test` → `MVN_EXIT=0`; Surefire `OffboardingCommandTest` `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` (previously could not compile), plus `RequestMetricsTest` 3/3, `RequestMetricsHistogramTest` 4/4, `RequestMetricsFilterTest` 3/3, `MetricsControllerTest` 2/2, `MetricsHttpTest` 2/2.
- Full gate (executed tool): `bash scripts/coverage-check.sh origin/main` → `coverage check: java (JaCoCo 100%)`, `coverage check: passed`, `COVERAGE_EXIT=0`. (The run logged one benign Shutdown-hook warning, `Surefire is going to kill self fork JVM ... after System.exit(0)`, which did not fail the build.)
- JaCoCo XML `apps/api/target/site/jacoco/jacoco.xml` read with an XML parser: changed production class `com/aurevia/smartclinic/web/RequestMetrics` INSTRUCTION missed=0/covered=466, BRANCH missed=0/covered=10, LINE missed=0/covered=57, METHOD missed=0/covered=12, CLASS missed=0/covered=1. Project totals: INSTRUCTION missed=0/covered=141481, BRANCH missed=0/covered=9050, LINE missed=0/covered=24594, METHOD missed=0/covered=5999, CLASS missed=0/covered=1253 — 100% on every counter.
- Java conventions (executed tool): `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` → `Ran 2 tests ... OK`.

### Branch-to-test list (complete for this repair)

No new production branch exists in this repair — the change is confined to test fixtures, so the previously un-runnable 13-case `OffboardingCommandTest` now compiles and runs. The full branch-to-test list for the changed production class in this pull request (`com/aurevia/smartclinic/web/RequestMetrics`) is unchanged and reproduced here, plus the newly unblocked offboarding paths:

- Histogram loop `i < BUCKET_UPPER_BOUNDS_NANOS.length` (enter/exit): every `record` test, e.g. `RequestMetricsHistogramTest.exposesBucketsAtThePercentileTargets`.
- `elapsedNanos <= BUCKET_UPPER_BOUNDS_NANOS[i]` true side: `RequestMetricsHistogramTest.emitsEveryBucketForEachSeriesIncludingZeroCounts` and `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds`.
- Same condition false side: `RequestMetricsHistogramTest.bucketsAreCumulativeAndCountMatchesObservations` and `emitsEveryBucketForEachSeriesIncludingZeroCounts`.
- `bucket == null ? 0L : bucket.sum()` null side: `emitsEveryBucketForEachSeriesIncludingZeroCounts` (asserts zero-count lines such as `le="0.005"} 0`).
- Same ternary non-null side: `bucketsAreCumulativeAndCountMatchesObservations` (asserts `le="0.1"} 1`, `le="+Inf"} 2`).
- Access-control 200/404 branches: `MetricsControllerTest` and `MetricsHttpTest` (kept and extended).
- Newly unblocked `OffboardingCommand` branches (no production change, previously blocked from running): `begin` blank-field rejections (`OffboardingCommandTest.blankOrganizationIsRefused`, `blankAuthorizerIsRefused`, `blankAuditReferenceIsRefused`, `blankExportReferenceIsRefused`), non-positive review period (`nonPositiveReviewPeriodIsRefused`), missing/foreign/mismatched/expired export (`offboardingWithoutAnExportIsRefused`, `exportFromAnotherTenantIsRefused`, `exportWithDifferentIdentifierIsRefused`, `expiredExportIsRefused`), success path (`beginRecordsGovernedStepsAndReviewWindow`), and `markForDeletion` already-scheduled/review-active/success (`markForDeletionTwiceIsRefused`, `markForDeletionBeforeReviewElapsesIsRefused`, `markForDeletionAtReviewBoundaryMarksTheTenant`).

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing, critical result or persistent boundary. The metric is an aggregate with no PHI and no state transition.

## Findings

- Pre-existing broken test compilation on `origin/main` at `5af5e1ae`: `OffboardingCommandTest.java` constructed `MessagingAuditContract.ExportRecord` with 6 arguments at lines 43, 92, 99 and 106, but the record has a 7th `ExportProvenance` component since commit `905c23d0` (BUILD-16). `mvn ... test` failed at testCompile before any test ran. In the original pass I temporarily moved that one file aside and restored it byte-for-byte via trap. **Resolved in the repair pass** (`## Repair pass — coverage check failure`): the four call sites now supply a synthetic `ExportProvenance`, so the whole Java test suite compiles and the coverage gate can run. The task-request below is retained for the integrator's record.
- Pre-existing committed conflict markers (outside scope, not modified): `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds literal markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`; they are present in `git show HEAD`, i.e. already committed to main from BUILD-37. No merge in this run introduced them. A task-request is recorded below.
- Environment artifact (not a change defect): worktree `opencode.json` is the CI profile under `skip-worktree`, so `gen-opencode.py --check`, `check-pack.py` and the six `test_check_pack` cases fail here. `git show HEAD:opencode.json` is the correct committed local profile. Per AGENTS.md this was confirmed once and left for the integrator.
- No skipped, disabled or weakened tests; no threshold, exclusion or suppression was added.

## Lessons for workers

- Render the histogram `_count` from the existing request counter and increment the `+Inf` bucket on every observation; that makes bucket consistency structural instead of something a concurrency test must chase.
- A cumulative histogram renders zero-count lines for bounds a series never crossed. The `LongAdder == null ? 0 : sum` path needs its own explicit assertion or JaCoCo reports a missed branch.
- A Javadoc `{@link #PRIVATE_FIELD}` fails `test_java_conventions`; use `{@code PRIVATE_FIELD}` for private members and fully qualified names for real links.
- `histogram_quantile` silently overstates latency if the `+Inf` bucket is dropped from `sum by (le)`; say so next to the query in operator docs.
- Run `git grep` for conflict markers at the start of a swarm story. Markers already committed to main are not yours to resolve when the task scope excludes them — record them for the integrator instead.

## task-requests

```task-requests
[
  {
    "title": "Fix OffboardingCommandTest compilation after ExportRecord provenance field",
    "details": "On origin/main 5af5e1ae, apps/api/src/test/java/com/aurevia/smartclinic/tenant/retention/OffboardingCommandTest.java constructs MessagingAuditContract.ExportRecord with 6 arguments at lines 43, 92, 99 and 106, but the record gained a 7th ExportProvenance component in commit 905c23d0 (BUILD-16). All test compilation fails with 'constructor ExportRecord ... cannot be applied to given types'. Update the four call sites to supply provenance (or use the current factory) so the Java gate compiles, and verify with mvn -f apps/api/pom.xml -Pno-security -Dtest=OffboardingCommandTest test.",
    "scope": ["apps/api/src/test/java/com/aurevia/smartclinic/tenant/retention"],
    "depends": ["BUILD-16"]
  },
  {
    "title": "Resolve committed conflict markers in exchange/hl7 sources",
    "details": "Three production files in apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7 (Hl7ParseException.java, Hl7Parser.java, Hl7Segment.java) contain literal <<<<<<< HEAD / >>>>>>> origin/swarm/BUILD-37-469 markers committed in HEAD at 5af5e1ae. git grep -nE '^(<<<<<<<|>>>>>>>) ' finds them. Resolve by keeping both sides' intent, confirm no markers remain, and recompile. Found by git grep during FIX-METRICS-PERCENTILES; outside that story's scope and left untouched.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7"],
    "depends": ["BUILD-37"]
  }
]
```
