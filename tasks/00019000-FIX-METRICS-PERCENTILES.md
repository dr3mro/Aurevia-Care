- [x] FIX-METRICS-PERCENTILES · Expose latency distributions for OPS-03 percentile alerts
  - pr: #1215
  - attempts: 2
  - requirements: OPS-03, WEB-06
  - scope: apps/api/src/main/java/com/aurevia/smartclinic/web, apps/api/src/test/java/com/aurevia/smartclinic/web, docs/operations/production-readiness/README.md
  - depends: READY-44
  - details:
    RequestMetrics exposes a count and sum, which can report averages but cannot support the p95 alerts specified in the readiness guide.
    Add bounded latency histogram buckets with the existing route-template labels and document the scrape/percentile query. Keep the current counters compatible.
    Acceptance: timed synthetic requests increment cumulative buckets and count consistently; labels never contain raw identifiers; the documented percentile query has usable buckets at the read and command targets. Test scrape output and access control.
