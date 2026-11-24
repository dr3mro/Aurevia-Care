- [x] FIX-METRICS-PERCENTILES · Expose latency distributions for OPS-03 percentile alerts
  - evidence: PR #1215 merged to main (reconciled 2026-11-23)
  - pr: #1215
  - attempts: 2
  - requirements: OPS-03, WEB-06
  - scope: apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web, docs/operations/production-readiness/README.md
  - depends: READY-44
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.

    RequestMetrics exposes a count and sum, which can report averages but cannot support the p95 alerts specified in the readiness guide.
    Add bounded latency histogram buckets with the existing route-template labels and document the scrape/percentile query. Keep the current counters compatible.
    Acceptance: timed synthetic requests increment cumulative buckets and count consistently; labels never contain raw identifiers; the documented percentile query has usable buckets at the read and command targets. Test scrape output and access control.
