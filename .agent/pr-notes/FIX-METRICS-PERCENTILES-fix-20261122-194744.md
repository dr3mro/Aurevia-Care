# FIX-METRICS-PERCENTILES — Expose latency distributions for OPS-03 percentile alerts (fix pass)

## Summary

The earlier pass added a bounded cumulative histogram `smartclinic_http_request_duration_seconds` to `RequestMetrics`, documented the scrape and `histogram_quantile` query, and proved 100% coverage. The fix PR gate still failed twice. The garbled debugger diagnosis in the issue pointed at ESLint/tsc and a `NewService.java` fixture; that string is only an example in the repository's own docs. The **actual** cause, recovered from the local `.agent/merge-1.log` left by an interrupted earlier run and reproduced here, was **SpotBugs `EI_EXPOSE_REP2`** on `RequestMetricsFilter.java:41`: the new instance method `appendHistogram` used a stream lambda that captured `this`, so SpotBugs began treating the injected `RequestMetrics` as externally mutable. The central `apps/api/spotbugs-exclude.xml` exempts role suffixes `Service|Repository|Controller|Store|Interceptor|Guard|Appender|Configuration`, not `Filter`.

This pass makes `appendHistogram` static with the registry passed explicitly (no `this` capture) and, at the code reviewer's request, replaces the three sequentially published maps with one `ConcurrentHashMap<String, Series>` updated inside a single atomic `compute`. That also removes a real race in which a scrape between the old map updates could hit `Objects.requireNonNull(...)` and return 500. The Prometheus text output is unchanged, so the earlier tests, documentation and dashboards remain valid.

No task-advisor `<<<BRIEF` block was present in this worker's prompt (the repo has since adopted one). I followed the story, AGENTS.md and the named specialists instead; this is noted for the integrator.

## Affected requirements

- OPS-03: p95 ≤ 500 ms for routine API reads and ≤ 900 ms for routine commands, measured server-side. The 0.5 s and 0.9 s buckets sit on those thresholds and the documented query computes p95 from bucket rates.
- WEB-06: publish request rate, error rate and duration per route; never emit raw identifiers; access-gated scrape. The histogram reuses the matched Spring route pattern and stays behind the existing address gate.

## Risk class

Routine (contained aggregation in the `web` package plus an operations document). No clinical logic, tenant boundary, state machine or persistent data changed. The concurrency fix strengthens an existing boundary rather than adding one.

## Review owner

smartclinic-senior-backend (Java/Spring), smartclinic-senior-spring-security (scrape access control), smartclinic-test-engineer (coverage). Read-only reviewers for the finished change: smartclinic-code-reviewer, smartclinic-coverage-auditor, smartclinic-devsecops. No physician or real-user review: no patient data or clinical decision.

## Data changes

None. No migration, table, backfill, lock or volume impact. Metrics remain instance-local aggregate counters; there is no new record of truth and nothing to persist across a restart. Scrape output gained one histogram family; the two legacy counter families keep their names and values.

## Authorization boundaries and audit events

`GET /health/metrics` is unchanged: no login, but answered only from loopback or a CIDR in `SMARTCLINIC_METRICS_ALLOW`, otherwise a bare 404. Labels are `method`, `route` (matched Spring pattern), `status` class and `le` from a fixed numeric list; no raw path, id, token, tenant or patient field can reach a label. No audit events added or changed.

## Migration and rollout considerations

None. Backward compatible: existing counter names and samples are untouched, so a rolling deploy mixing old and new replicas yields a superset of series. Scrape size grows by 14 bucket samples plus sum and count per observed series, bounded by the fixed bucket set and the number of route/status series.

## Test evidence

All commands run from the repository root. Sources of evidence: executed tool commands, JaCoCo XML, and three read-only reviewer reports. No simulated user or human participant evidence.

- Branch reconciliation (tool): `git merge --no-edit origin/main` initially refused because the worktree `opencode.json` is the CI profile under `skip-worktree`. Saved it, cleared the bit, restored the committed profile, merged, resolved the one `OffboardingCommandTest.java` conflict by keeping both sides' intent (the branch's explanatory comment plus main's authoritative synthetic `ExportProvenance("synthetic-source", "revision-1", "offboarding")`), committed merge `fc73a387`, and restored the CI-profile artifact. `git merge --no-edit origin/main` at the end: `Already up to date.` `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- ':!*.md'` shows only the three pre-existing committed `exchange/hl7` markers (out of scope, see Findings).
- Real gate failure reproduced (tool): `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `SPOTBUGS_EXIT=1`, `BugInstance size is 1`, `Total bugs: 1`, `Medium: ... RequestMetricsFilter.metrics ... EI_EXPOSE_REP2 ... RequestMetricsFilter.java:[line 41]`, `BUILD FAILURE`. The same command against the pre-change `RequestMetrics` from `origin/main` → `OLD_SPOTBUGS_EXIT=0`, confirming the histogram change introduced it.
- Same command after the fix → `SPOTBUGS_EXIT=0`, no bug instances. The earlier worker's bisect (`story-minus-histogram-and-loop: PASS`, `story-loop-rewrite: FAIL`) is consistent with the `this` capture being the trigger rather than the loop form.
- RED (earlier pass, recorded for the record): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequestMetricsHistogramTest,MetricsHttpTest test` → `Tests run: 6, Failures: 6`, all new assertions failed because no histogram family existed.
- GREEN after the final refactor (tool): `mvn -q -B -f apps/api/pom.xml -Pno-security -Djacoco.skip=false -Dtest=RequestMetricsTest,RequestMetricsHistogramTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsHttpTest test jacoco:report` → `JAVA_EXIT=0`; Surefire `RequestMetricsTest` 3/3, `RequestMetricsHistogramTest` 4/4, `RequestMetricsFilterTest` 3/3, `MetricsControllerTest` 2/2, `MetricsHttpTest` 2/2, all `Failures: 0, Errors: 0, Skipped: 0`. Re-run after the Javadoc accuracy edit: `JAVA_EXIT=0` with the same counts.
- Static analysis (tool): `python3 -I scripts/check-names.py` → 0; `python3 -I scripts/check-wiring.py` → 0 (only pre-existing informational baseline notes); `python3 -I scripts/check-docs.py` → 0; `python3 -I scripts/check-secrets.py` → 0; `python3 -I scripts/check-constraints.py` → 0; `python3 -I scripts/check-migrations.py --require-baseline` → 0. After regenerating the local profile with `python3 -I scripts/gen-opencode.py`, `python3 -I scripts/check-pack.py` → 0 and `python3 -I scripts/gen-opencode.py --check` → 0 (the worktree copy had been the stale pre-merge CI profile; the committed `HEAD:opencode.json` already contained the four new agents).
- Java conventions (tool): `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` → `Ran 2 tests ... OK`, both before and after the final Javadoc edit.
- Repository gates (tool): `bash scripts/python-coverage.sh` → `EXIT=0`; `bash scripts/frontend-check.sh` → `EXIT=0` (`Test Files 1 passed`, `frontend standards: policy and tool commands passed`).
- Frontend definition of done (tool, explicitly required): `bash apps/patient-mobile/check.sh` → `PATIENT_CHECK_EXIT=0`. Typecheck final lines: `> patient-mobile@0.1.0 typecheck` / `> tsc --noEmit`, no `error TS…`. Lint clean. Jest: `Test Suites: 82 passed, 82 total`, `Tests: 1157 passed, 1157 total`, `Time: 16.632 s`. No patient-mobile source was changed; this is evidence that the touched tree still passes the module gate.
- Entry point to test trace: `GET /health/metrics` → `MetricsController.scrape` → `RequestMetrics.scrape` → static `appendHistogram`. `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` drives two synthetic `GET /api/v1/patients/patient-777|888` requests through `RequestMetricsFilter` → `RequestMetrics.record`, then scrapes over HTTP and asserts `smartclinic_http_request_duration_seconds_count{method="GET",route="/api/v1/patients/{id}",status="2xx"} 2`, `le="+Inf"} 2`, and absence of the raw ids. `MetricsHttpTest.productionChainsServeMetricsOnlyFromAllowedAddresses` asserts loopback 200 with `# TYPE smartclinic_http_request_duration_seconds histogram`, `198.51.100.10` → 404, and a protected `/api/v1` route still redirected under the production chains; `MetricsControllerTest` asserts the same 200/404 split at the handler.
- No CI run is linked: this worker did not push or call `gh`.

## Review verdicts

- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

The code reviewer's first pass was NO-GO on a real concurrency defect (a scrape between the three old map updates could NPE or see `+Inf` disagree with `_count`); the single-holder atomic `compute` resolved it and the follow-up review returned GO. Its two remaining notes were Optional (index-coupled bucket arrays; transient lock-free snapshot wording) — the Javadoc wording was corrected in this pass. The coverage auditor and DevSecOps returned GO without Required findings. DevSecOps evidence was static inspection only (its subagent session had no shell); the Java policy, SpotBugs and coverage commands above were run by this worker with tools.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security -Djacoco.skip=false -Dtest=RequestMetricsTest,RequestMetricsHistogramTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsHttpTest test jacoco:report`, then read `apps/api/target/site/jacoco/jacoco.xml` with an XML parser.

Result — every counter missed=0 on every touched production class:
- `com/aurevia/smartclinic/web/RequestMetrics`: INSTRUCTION 0/439, BRANCH 0/12, LINE 0/55, METHOD 0/10, CLASS 0/1.
- `com/aurevia/smartclinic/web/RequestMetrics$Series`: INSTRUCTION 0/34, BRANCH 0/2, LINE 0/7, METHOD 0/1, CLASS 0/1.
- `com/aurevia/smartclinic/web/RequestMetricsFilter`: INSTRUCTION 0/39, BRANCH 0/2, LINE 0/10, METHOD 0/3, CLASS 0/1.

New and changed production branches, each with its test and assertion:
- `Series()` constructor loop `i < buckets.length` (enter/exit): `RequestMetricsHistogramTest.exposesBucketsAtThePercentileTargets` creates two new series; `bucketsAreCumulativeAndCountMatchesObservations` asserts bucket samples for a new series.
- `record()` `existing == null ? new Series() : existing` null side (first observation): `exposesBucketsAtThePercentileTargets` (one GET series, one POST series) and the first call in `bucketsAreCumulativeAndCountMatchesObservations`.
- Same ternary non-null side (existing series): the second `record` in `bucketsAreCumulativeAndCountMatchesObservations`, asserted by `le="2.5"} 2` and `le="+Inf"} 2`.
- `record()` loop `i < BUCKET_UPPER_BOUNDS_NANOS.length` (enter/exit): every `record` test.
- `if (elapsedNanos <= bound)` true side: `bucketsAreCumulativeAndCountMatchesObservations` (100 ms, asserts `le="0.1"} 1`) and `exposesBucketsAtThePercentileTargets` (1 ns, asserts `le="0.5"` and `le="0.9"` lines).
- Same condition false side: `bucketsAreCumulativeAndCountMatchesObservations` (1.5 s exceeds the 0.1/0.5/0.9/1 s bounds) and `emitsEveryBucketForEachSeriesIncludingZeroCounts` (20 s exceeds every finite bound, asserts `le="10"} 0`).
- `appendSeries` `seconds ? latencyNanos.sum() : requests.sum()` and `seconds ? Double.toString : Long.toString`, both sides: `RequestMetricsTest.recordsCountLatencyAndStatusClassPerSeries` asserts `smartclinic_http_requests_total{...} 1` (false) and `smartclinic_http_request_seconds_sum{...} 1.5` / `0.5` (true); `MetricsHttpTest` asserts `status="2xx"} 2`.
- `appendHistogram` bucket loop `i < BUCKET_LABELS.length` (enter/exit): any scrape with a recorded series, e.g. `bucketsAreCumulativeAndCountMatchesObservations`.
- Empty registry (both stream lambdas skipped): `emptyRegistryEmitsHistogramMetadataWithoutSamples` asserts HELP/TYPE present and no `_bucket{`.
- `RequestMetricsFilter` `instanceof String` ternary (pre-existing, kept covered): `RequestMetricsFilterTest` and `MetricsHttpTest` cover matched-pattern and `UNMATCHED` sides.
- HTTP entry point: `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` and `productionChainsServeMetricsOnlyFromAllowedAddresses` (see trace above).

No skipped, disabled or weakened test; no exclusion, threshold change or suppression added. The `null ? 0 : sum()` branch of the earlier pass no longer exists — it was deleted with the three-map design that caused the NPE race, not excluded from coverage.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing, critical result or persistent boundary. The metric is an aggregate with no PHI and no state transition.

## Findings

- Root cause of the two integrator failures: SpotBugs `EI_EXPOSE_REP2` on `RequestMetricsFilter.java:41`, introduced when `appendHistogram` began capturing `this`. The issue's debugger diagnosis (ESLint/tsc, five Java "failures", `FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java`) is a corrupted mix of tool labels and an example string from `docs/swarm/*.md`; the real failing command and output are quoted under Test evidence. Fixed in scope by a static appender and a single atomic series holder; SpotBugs now exits 0 with `-Dspotbugs.skip=false`.
- The snippet of patient-mobile Jest output in the issue showed all 82 suites passing; it was the tail of the gate log, not the failing step. Re-running `apps/patient-mobile/check.sh` here also passed (evidence above).
- Pre-existing committed conflict markers (outside scope, untouched): `git grep -nE '^(<<<<<<<|>>>>>>>) '` still finds literal markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`, already committed to main from BUILD-37. A task-request is retained below.
- Environment artifact (not a change defect): the worktree `opencode.json` was the stale pre-merge CI profile under `skip-worktree`, so `check-pack.py`/`gen-opencode.py --check` failed locally until the profile was regenerated from the current agent inventory. The committed `HEAD:opencode.json` already contained the four new agents; this is the documented OPS-CW-003-01 artifact, confirmed once and not re-diagnosed.
- The earlier `OffboardingCommandTest` compile break was fixed independently on main during reconciliation: my branch's comment and main's synthetic provenance value were both kept, so no out-of-scope test change remains in this pull request. The bounded diff is four files: `RequestMetrics.java`, `MetricsHttpTest.java`, `RequestMetricsHistogramTest.java`, `docs/operations/production-readiness/README.md`.

## Lessons for workers

- A stream lambda inside an instance method captures `this`; in this repository that can turn a historically clean SpotBugs baseline into `EI_EXPOSE_REP2` on the class that stores the injected object. Make render/helper methods static and pass the state explicitly, or run `mvn ... -Dspotbugs.skip=false compile spotbugs:check` before finishing (the default profile skips SpotBugs, so a "clean" targeted run can hide it).
- When a debugger diagnosis names a tool that does not match the failing command and quotes a path from `docs/swarm/*.md` fixtures, treat it as corrupted and reproduce the gate locally; `.agent/merge-1.log` from an interrupted run held the real command and result.
- Three parallel maps published in sequence are not a consistent snapshot: a concurrent scrape can observe a key in the first map before the others are written. One holder inserted in a single `ConcurrentHashMap.compute` makes publication atomic and deletes the null branch instead of testing it.
- Keep Javadoc claims within what lock-free accumulators actually guarantee: publication can be atomic while individual samples of one scrape still straddle an in-flight observation.
- Reconcile with `origin/main` before editing: 58 new commits had already fixed an out-of-scope compile break and added a reviewer-verdict quality gate. Resolve conflicts by keeping both sides' intent, then re-run the affected tests.

## task-requests

```task-requests
[
  {
    "title": "Resolve committed conflict markers in exchange/hl7 sources",
    "details": "Three production files in apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7 (Hl7ParseException.java, Hl7Parser.java, Hl7Segment.java) contain literal <<<<<<< HEAD / >>>>>>> origin/swarm/BUILD-37-469 markers committed in HEAD from BUILD-37. git grep -nE '^(<<<<<<<|>>>>>>>) ' finds them. Resolve by keeping both sides' intent, confirm no markers remain, and recompile. Found by git grep during FIX-METRICS-PERCENTILES; outside that story's scope and left untouched.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7"],
    "depends": ["BUILD-37"]
  }
]
```
