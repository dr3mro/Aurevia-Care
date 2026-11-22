# Production readiness evidence

This checklist turns section 8.1 ("Production platform and web delivery") of the [SmartClinic Cloud V1 specification](../../../SMARTCLINIC_V1_SPEC.md) into evidence that a named owner can produce, record and sign before G5. Each row states, in our own words, the verification the specification asks for; a row is complete only when its evidence link is supplied and its owner signs it. Nothing here is asserted as done.

Status: draft, owners to complete

| Requirement | Evidence needed | Produced by | Owner | Status | Link |
|---|---|---|---|---|---|
| WEB-01 | An independent external TLS scan (TLS 1.2 or newer, HSTS with a one-year max-age and includeSubDomains, HTTP-to-HTTPS redirect) and a managed-WAF rule test recorded before pilot, with automatic certificate renewal and 21-day expiry alerting. | Infrastructure owner | unassigned | not started |  |
| WEB-02 | A CI header test backed by an external header scan: CSP without unsafe-inline or unsafe-eval (nonces or hashes), frame-ancestors 'none', nosniff, no-referrer, a restrictive Permissions-Policy, HttpOnly/Secure/SameSite cookies, Cache-Control: no-store on authenticated responses, and no patient data in URLs, client storage, analytics or logs. | CI | unassigned | not started |  |
| WEB-03 | Automated tests proving idle expiry (15 minutes, never above a configured 30) and 12-hour absolute expiry, server-side logout and session revocation, renewed authentication for sensitive actions, CSRF rejection on state changes and lockout of authentication endpoints. | CI | unassigned | not started |  |
| WEB-04 | Tests for per-subject and per-organization rate limits that answer 429 with Retry-After, caps on headers, body, page size and request time, an exact-origin CORS allowlist that is empty by default, and machine-readable errors that reveal no stack trace, SQL, other tenant or internal class name. | CI | unassigned | not started |  |
| WEB-05 | Tests for unauthenticated, tenant-free /health/live and /health/ready as the only routes outside /api/v1, at least 30 seconds of graceful drain, fail-closed startup on missing configuration and migrations run as a separate step, together with a rolling-deploy rehearsal. | CI | unassigned | not started |  |
| WEB-06 | A test proving structured JSON logs carry correlation ID, route, status and latency and redact request bodies, tokens and patient fields; per-route rate, error rate and duration plus saturation and queue age published; and alerts on the availability, critical-result, error-budget and external-probe targets with policy-set retention. | CI | unassigned | not started |  |
| WEB-07 | A browser matrix covering the latest two majors of Chrome, Edge, Firefox and Safari at desktop 1280 x 720 and compact 768 px widths, plus the latest two iOS and Android versions, showing that unsupported browsers receive a plain message and no data. | CI | unassigned | not started |  |
| WEB-08 | A CI-enforced check that every route chunk is at most 250 KB gzip, with the LCP 2.5 s, INP 200 ms and CLS 0.1 web-vitals thresholds met at the 75th percentile, code split per feature, self-hosted Arabic-coverage fonts, immutable hashed assets and no third-party scripts or trackers. | CI | unassigned | not started |  |
| WEB-09 | A component test for each screen state: loading, empty, error, timeout/offline, forbidden, stale-version conflict that preserves typed work and allows compare/reload/copy, and session expired; plus the unsaved-changes prompt and a global error boundary that shows the correlation ID without patient data. | CI | unassigned | not started |  |
| WEB-10 | Real-browser end-to-end journeys for each supported role against staging with synthetic data in English and Arabic RTL, an accessibility scan with zero serious or critical violations, light and dark visual checks, run on every release candidate with the report retained as evidence. | CI | unassigned | not started |  |
| WEB-11 | Evidence that one signed artifact with an SBOM is promoted unchanged through environments, rollout proceeds progressively at 5%, 25% and 100% with automatic rollback on error-rate or latency breach, migrations expand and contract, feature flags default off, a rollback has been rehearsed and a release checklist names its approvers. | Infrastructure owner | unassigned | not started |  |
| WEB-12 | Dependency, image and secret scan results on every change with critical findings blocking merges and remediation within 7 days (high within 30), CI actions pinned by commit SHA and base images by digest, and an independent penetration-test report before pilot and after major changes with every finding tracked to closure. | Security reviewer | unassigned | not started |  |
| WEB-13 | Evidence that status/maintenance and support pages are hosted separately with no patient data and carry the privacy notice, accessibility statement, terms and support contact, and that authenticated web apps are marked noindex with crawling disallowed in robots.txt. | CI | unassigned | not started |  |

## Request metrics endpoint (WEB-06)

`GET /health/metrics` publishes per-route `smartclinic_http_requests_total{method,route,status}` and `smartclinic_http_request_seconds_sum{method,route,status}` in Prometheus text format. It requires no login but is answered only from loopback or a network in the comma-separated CIDR list `SMARTCLINIC_METRICS_ALLOW` (loopback by default); any other source receives a bare 404. Route labels are the matched Spring route pattern, never a raw path that could carry an id. Alerting on these series must cover the OPS-03 targets — 99.9% monthly core availability and p95 ≤ 500 ms for routine API reads and ≤ 900 ms for routine commands — together with the OPS-05 error-rate, saturation and queue-age signals, error-budget burn and failed external uptime probes from outside the network; logs and metrics are retained for the period the approved policy pack sets.

### Latency distribution for percentile alerts (OPS-03)

An average cannot show a tail, so the same scrape also publishes a cumulative histogram family, `smartclinic_http_request_duration_seconds`, carrying the same `method`, `route` and `status` labels plus an `le` (upper bound) label. The finite bounds are `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 1, 2.5, 5, 10` seconds, followed by the mandatory `+Inf` bucket. An observation increments every bucket whose bound is at least its duration; bucket values are therefore cumulative and the `+Inf` bucket always equals `smartclinic_http_request_duration_seconds_count` and `smartclinic_http_requests_total` for the same labels. The two original counter families keep their names, so existing dashboards remain valid.

The `0.5` s and `0.9` s buckets sit exactly on the OPS-03 p95 targets for routine reads and commands, so an alert threshold at the target does not have to be interpolated across a wide bucket gap. Compute p95 from the bucket rates (scrape every 30 s, evaluate over 5 m):

```promql
# p95 server-side duration for routine reads
histogram_quantile(0.95, sum by (le) (
  rate(smartclinic_http_request_duration_seconds_bucket{method="GET", route=~"/api/v1/.+"}[5m])
)) > 0.5

# p95 server-side duration for routine commands
histogram_quantile(0.95, sum by (le) (
  rate(smartclinic_http_request_duration_seconds_bucket{method=~"POST|PUT|PATCH|DELETE", route=~"/api/v1/.+"}[5m])
)) > 0.9
```

Add `route` (or `method, route, status`) to the `sum by (...)` clause to alert per route rather than globally. Always include the `+Inf` bucket in the aggregation; dropping it makes `histogram_quantile` overstate latency for observations slower than the largest finite bound. Every label comes from a Spring route pattern or a bounded status class, so no bucket or other label can carry a raw identifier. Keep the alert scoped to routine `/api/v1` read and command routes: document transfer/rendering and external calls are excluded from the OPS-03 measurement.

## Needs a person

- WAF and TLS setup
- certificate automation
- SHA-pinning CI actions
- end-to-end browser suite and its CI job
- penetration test
- progressive-rollout tooling
