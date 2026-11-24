- [-] AUDIT-FIX-DELIVERY-COVERAGE · Required Delivery check skips frontend and Python coverage
  - agent: orca
  - requirements: OPS-06
  - scope: .github/workflows/delivery.yml, scripts/frontend-check.sh, scripts/tests/test_workflow_concurrency.py, tasks/01270100-AUDIT-FIX-DELIVERY-COVERAGE.md
  - details:
    Delivery runs plain npm test and unittest discover, so the 100% frontend
    and changed-script coverage gates never run in the sole required check.
    Wire the canonical module check.sh scripts and scripts/python-coverage.sh
    into Delivery, keep the docs claim truthful, and cover the wiring with a
    regression test. Do not lower any threshold or add an exclusion.

  - agent: orca