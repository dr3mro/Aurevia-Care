- [-] AUDIT-FIX-FRONTEND-TIMEOUTS · Staff web spec tests time out under full-suite load
  - agent: orca
  - requirements: OPS-06
  - scope: apps/staff-web, tasks/01270800-AUDIT-FIX-FRONTEND-TIMEOUTS.md
  - details:
    The canonical staff-web check failed with three TestTimeoutError (5000ms)
    failures in tests/spec/platform/verify-state-component-test.test.ts and a
    booking-draft test when the full 278-file suite ran. Give the slow async
    component tests explicit per-test timeouts, keep 100% coverage, and prove
    with a full bash apps/staff-web/check.sh run. Do not lower a threshold or
    skip a test.
