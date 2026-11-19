---
name: smartclinic-github-workflow
description: "Review GitHub Actions, branch gates, tags and image publication."
---

# Github Workflow

Verify required checks for the exact SHA, version derivation from tag, least-privilege jobs, build and smoke before publication, release provenance, retries and testability. Fast-tier jobs without PostgreSQL must exclude database suites by `@Tag("integration")` and `-Pfast`, never by a class-name pattern such as `-Dtest='!*IsolationTest'`; the guard is `scripts/tests/test_fast_tier.py`.
