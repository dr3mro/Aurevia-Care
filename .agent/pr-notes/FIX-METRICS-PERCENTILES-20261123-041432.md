# FIX-METRICS-PERCENTILES — Expose latency distributions for OPS-03 percentile alerts

## Summary

Extended the existing instance-local request metrics registry (`RequestMetrics`) with a bounded, cumulative latency histogram on the existing `GET /health/metrics` scrape. A fixed set of 13 nanosecond bounds (5 ms through 10 s, including exactly 0.5 s and 0.9 s) is recorded per existing `method|route|statusClass` key with `LongAdder`s. The scrape now also emits `# TYPE smartclinic_http_request_duration_seconds histogram` with ascending `_bucket{method,route,status,le}` samples, `le="+Inf"`, `_sum` and `_count`. `+Inf` and `_count` are derived from the existing request counter and `_sum` from the existing latency sum, so the histogram cannot disagree with the counters. The two existing counter families are untouched and byte-compatible. No dependency, migration, new route or OpenAPI change. The readiness guide documents the families, bounds, percentile query, the read/command route allowlist and the 0.5/0.9 thresholds.

## Affected requirements

- OPS-03 — p95 ≤ 500 ms for routine API reads and ≤ 900 ms for routine commands; the 0.5 s and 0.9 s buckets bracket both targets so the documented `histogram_quantile` expression has usable samples.
- WEB-06 — per-route request rate, error rate and duration published on the scrape; the structured JSON log evidence from READY-44 was left unchanged.

## Risk class

Cross-boundary — an existing unauthenticated, address-gated HTTP endpoint gains three new metric families. No clinical, tenant or patient data is read or emitted; only aggregate values and fixed `le` bounds enter labels. Per docs/WORKFLOW.md the full chain links (migration/RLS, `Jdbc*`/`*Store`, injected service, controller, OpenAPI) are N/A: no new route, table or store is added, and the controller already injects the registry.

## Review owner

Senior Java and Spring Boot (registry and Prometheus rendering); Senior Spring Security and OIDC (access gate unchanged); Test Engineer (acceptance matrix).

## Data changes

None. No Flyway migration, backfill, lock or volume impact. No rollback or forward-fix needed.

## Authorization boundaries and audit events

`GET /health/metrics` remains permitted in both production filter chains and gated inside `MetricsController` by `MetricsAccessPolicy` on the servlet remote address (loopback default, CIDR list from `SMARTCLINIC_METRICS_ALLOW`, malformed CIDR fails construction). No route, role or `SecurityConfiguration` matcher changed. No tenant context, role or patient link is involved and no audit event is added — the body still contains only aggregates; `le` is a fixed numeric bound. No patient data, credential or raw log was introduced.

## Migration and rollout considerations

Configuration-free rollout: the additional families appear on the next scrape after deployment. Existing dashboards reading `smartclinic_http_requests_total` or `smartclinic_http_request_seconds_sum` see byte-identical lines. Scrapers should be on loopback or added to `SMARTCLINIC_METRICS_ALLOW`; disallowed sources still receive a bare 404. The histogram is instance-local and non-durable by design (same as the existing counters).

## Test evidence

Evidence came from executed tools (Maven, shell, Python checks) and read-only specialist reviewers; no human or clinical participant was involved. All fixtures are synthetic.

- RED (new histogram assertions, production change not yet made): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequestMetricsTest,RequestMetricsFilterTest,MetricsControllerTest,MetricsAccessPolicyTest,MetricsHttpTest test` — final line `[ERROR] Tests run: 14, Failures: 8, Errors: 0, Skipped: 0`, exit 1. Existing counter assertions passed; only the new histogram assertions failed.
- GREEN (same command after implementation) — exit 0. Surefire: `RequestMetricsTest 4/4`, `RequestMetricsFilterTest 3/3`, `MetricsControllerTest 2/2`, `MetricsAccessPolicyTest 3/3`, `MetricsHttpTest 2/2` (14 tests, 0 failures, 0 errors, 0 skipped).
- Full fast unit suite with coverage: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` — exit 0.
- Static analysis: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0, no findings (compiled first so the check is not vacuously green).
- Java conventions: `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py` — `Ran 2 tests in 0.041s ... OK`.
- Policy: `python3 -I scripts/check-docs.py`, `check-names.py`, `check-wiring.py`, `check-secrets.py`, `check-migrations.py` (each with `--require-baseline`) — all exit 0. `check-wiring.py` printed only pre-existing informational "is fixed; delete it from baseline" notes.
- Required worker command: `bash scripts/worker-check.sh` — exit 1, single line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented CI-profile skip-worktree artifact: `git ls-files -v opencode.json` → `S`; worktree hash `9d797518` vs `git show HEAD:opencode.json` hash `69db5d8e`. The committed HEAD profile is the correct local profile; every downstream gate was run individually above and passed. No out-of-scope file was changed.

Entry-point-to-test trace: `RequestMetricsFilter.doFilterInternal` (records elapsed nanos in a `finally`) → `RequestMetrics.record` (cumulative buckets) → `/health/metrics` scrape → `MetricsController.scrape` (address gate) → `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` (two timed requests, bucket `+Inf` = `_count` = 2, no raw ids) and `MetricsHttpTest.productionChainsServeMetricsOnlyFromAllowedAddresses` (allowed scrape contains the family, `198.51.100.10` gets 404 with empty body, protected API still redirects). Unit-level semantics are pinned by `RequestMetricsTest.recordsCumulativeHistogramBucketsAtBoundaries`.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report`; result read from `apps/api/target/site/jacoco/jacoco.xml` after the final source edit:

- RequestMetrics — INSTRUCTION 466/466, BRANCH 12/12, LINE 68/68, METHOD 14/14, CLASS 1/1 (no missed counter).

New or changed branches and the test that takes each side:

- `record()` `elapsedNanos <= BOUNDS_NANOS[i]` true side: `RequestMetricsTest.recordsCumulativeHistogramBucketsAtBoundaries` (1 ms below first bound; exact 0.5 s and 0.9 s included by `<=`) and `RequestMetricsTest.escapesBackslashQuoteAndNewlineInLabelValues` (1 ns).
- Same condition false side: `RequestMetricsTest.recordsCumulativeHistogramBucketsAtBoundaries` (11 s above the last 10 s bound; `le="10.0" → 3` and `+Inf → 4` would fail if the above-last sample entered a bucket).
- `record()` bounds loop enter/exit: every `record()` call; the fixed 13-element loop reaches its exit edge at i == 13 (asserted through the emitted `le` lines).
- `newBuckets()` loop enter/exit: first observation of any key (all record-based tests); the 13 adders are what the bucket lines render. Fixed non-empty length, so no zero-iteration path exists.
- `buildLeLabels()` loop enter/exit: class initialization; `LE_LABELS[i]` is consumed by the renderer and its values are asserted by the `le="0.005"`, `le="0.5"`, `le="0.9"`, `le="10.0"` line assertions.
- `appendHistogram` render loop zero iterations (empty map): `RequestMetricsTest.emptyRegistryEmitsMetadataWithoutSamples` asserts histogram HELP/TYPE present but no `method=` and no `le=` sample.
- `appendHistogram` render loop one or more iterations: `RequestMetricsTest.recordsCountLatencyAndStatusClassPerSeries`, `recordsCumulativeHistogramBucketsAtBoundaries`, `RequestMetricsFilterTest.recordsMatchedRouteInsteadOfRawPath`, `MetricsControllerTest.allowedRemoteReceivesPrometheusText`, `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds`.
- Ascending `le` order with `+Inf` last: `recordsCumulativeHistogramBucketsAtBoundaries` asserts `indexOf(0.005) < indexOf(0.5) < indexOf(0.9) < indexOf(10.0) < indexOf(+Inf)`.
- `+Inf` from the existing request counter: `recordsCountLatencyAndStatusClassPerSeries` (=1), `RequestMetricsFilterTest.recordsMatchedRouteInsteadOfRawPath` (=1), `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` (=2).
- `_sum` from the existing latency sum: `recordsCountLatencyAndStatusClassPerSeries` asserts `..._sum{...} 1.5`, equal to the `seconds_sum` for the same series.
- `_count` from the existing request counter: `recordsCountLatencyAndStatusClassPerSeries` (=1), filter test (=1), HTTP test (=2, matches `requests_total`).
- `label()` escaping reused on a bucket line: `RequestMetricsTest.escapesBackslashQuoteAndNewlineInLabelValues` asserts the escaped route inside a `_bucket` line.
- Existing counter families unchanged: all pre-existing `requests_total`/`seconds_sum` assertions still pass unmodified.

No test was skipped, disabled, excluded or given a lowered threshold; no suppression was added.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-test-engineer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical result is involved. The matched route pattern is used in labels, and `MetricsHttpTest.matchedRouteIsRecordedWithoutRawPathIds` asserts ids are absent from the whole scrape including bucket lines. All review evidence is simulated/static; there is no human clinical acceptance in this story.

## Findings

- `bash scripts/worker-check.sh` exits 1 at the pack check because the worktree `opencode.json` is the CI profile under skip-worktree. Pre-existing environment artifact documented in MEMORY.md (OPS-CW-003-01), the delivery skill and the 2026-11-21 daily note; no code change is needed. Integrator: run the gate at HEAD, where the committed local profile is present.
- Pre-existing committed conflict markers remain in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/` (outside this task's scope). They were present before this story and were not touched. `git merge --no-edit origin/main` reported "Already up to date".
- The SLO map (`docs/operations/slo.md`) declares the consumer work "Publish per-route p95 latency and read/command route-class metrics" with no tracker entry. This story supplies the histogram primitive only; a task request is filed below so the consumer is not lost.
- `docs/operations/production-readiness/README.md` now points the route allowlist at the SLO map and capacity decision D3 as the authoritative source instead of hard-coding routes, so it cannot silently drift.

## Lessons for workers

- In a cloud worker, `git ls-files -v opencode.json` showing `S` explains `check-pack.py`/`worker-check.sh` failures: verify against `git show HEAD:opencode.json`, record the command and final line, run the downstream gates individually, and never regenerate or commit that file.
- A Prometheus histogram's `+Inf` bucket and `_count` should be derived from the existing request counter, not from a sentinel bound compared with `<=`: a sentinel makes the comparison always true and leaves an uncoverable false branch. It also guarantees the two values can never disagree.
- The scrape body is rendered before the measuring filter's `finally` block records that same request. The first scrape of a fresh registry contains metadata only; scrape twice (or record an observation first) when asserting bucket samples over HTTP.
- Java conventions require fully qualified Javadoc `{@link}` targets even for a local `{@link com.aurevia.smartclinic.web.RequestMetrics#BOUNDS_NANOS}`; a bare `#field` fails `test_java_conventions.py`.
- Do not run full `mvn verify` or `scripts/check.sh` in a swarm worker: they exceed the time budget. Run targeted `-Dtest=...` plus `jacoco:report`, SpotBugs after compile, the Java-conventions test and the individual policy scripts; the integrator owns the full gate.

```task-requests
[
  {
    "title": "Publish per-route p95 latency and read/command route-class alerts",
    "details": "Consume smartclinic_http_request_duration_seconds_bucket from /health/metrics to emit runtime p95 for reads and commands with an explicit route allowlist and alert on >0.5 s / >0.9 s (OPS-03, WEB-06). This is the consumer follow-up named in docs/operations/slo.md; FIX-METRICS-PERCENTILES only supplies the histogram buckets. Keep at least one read and one command route per capacity decision D3.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/web", "apps/api/src/main/java/com/aurevia/smartclinic/observability", "apps/api/src/test/java/com/aurevia/smartclinic/web", "docs/operations/slo.md"],
    "depends": ["FIX-METRICS-PERCENTILES"]
  }
]
```
