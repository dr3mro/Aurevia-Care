# Production readiness evidence

This checklist turns section 8.1 ("Production platform and web delivery") of the [SmartClinic Cloud V1 specification](../../../SMARTCLINIC_V1_SPEC.md) into evidence that a named owner can produce, record and sign before G5. Each row states, in our own words, the verification the specification asks for; a row is complete only when its evidence link is supplied and its owner signs it. Nothing here is asserted as done.

The product-level companion for PRD-01 to PRD-06 is
[requirement traceability](requirement-traceability.md); this register covers WEB-01
to WEB-13 only.

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

`GET /health/metrics` publishes three per-route families in Prometheus text format, all labelled `method`, `route` and `status` (status as a class such as `2xx`/`5xx`):

- `smartclinic_http_requests_total{method,route,status}` — completed request counter.
- `smartclinic_http_request_seconds_sum{method,route,status}` — accumulated latency in seconds.
- `smartclinic_http_request_duration_seconds_bucket{method,route,status,le}`, `..._sum` and `..._count` — a latency histogram. Buckets are cumulative and their `le` bounds, in seconds, are `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0, 2.5, 5.0, 10.0`, followed by `+Inf`. The `0.5` and `0.9` bounds straddle the OPS-03 read and command targets so the p95 estimate has a sample on each side of both thresholds. `..._count` equals the request counter for the same labels and `le="+Inf"` equals `..._count`; families are regenerated from the same key and the existing escaping rules, so no raw identifier can enter a bucket label. The first two counter families are unchanged and byte-compatible with earlier scrapes.

The endpoint requires no login but is answered only from loopback or a network in the comma-separated CIDR list `SMARTCLINIC_METRICS_ALLOW` (loopback by default); any other source receives a bare 404. Route labels are the matched Spring route pattern, never a raw path that could carry an id.

Server-side p95 for a class of routes is estimated from the histogram with:

```
histogram_quantile(0.95, sum by (le, method, route, status) (rate(smartclinic_http_request_duration_seconds_bucket[5m])))
```

Alert on `... > 0.5` for routine reads and `... > 0.9` for routine commands (OPS-03). Restrict each expression to an explicit allowlist of successful (`status="2xx"`) route patterns belonging to that class: `read` and `command` classification is declared in [the SLO target-to-signal map](../slo.md) and [the capacity decision D3](../capacity/README.md), which also require at least one route of each class. The histogram supplies the distribution only; runtime p95 aggregation and route-class classification remain the separate follow-up named in the SLO map. Alerting must also cover 99.9% monthly core availability (OPS-03), the OPS-05 error-rate, saturation and queue-age signals, error-budget burn and failed external uptime probes from outside the network; logs and metrics are retained for the period the approved policy pack sets.

## Needs a person

- WAF and TLS setup
- certificate automation
- SHA-pinning CI actions
- end-to-end browser suite and its CI job
- penetration test
- progressive-rollout tooling
