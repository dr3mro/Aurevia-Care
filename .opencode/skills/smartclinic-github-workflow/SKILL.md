---
name: smartclinic-github-workflow
description: "Review GitHub Actions, branch gates, tags and image publication."
---

# Github Workflow

Verify required checks for the exact SHA, version derivation from tag, least-privilege jobs, build and smoke before publication, release provenance, retries and testability.

Swarm workflow invariants (each one broke or nearly broke a run):
- An agent step never holds a write token. Untrusted output (bundles, JSON, notes, a pull request's own code) is acted on by a second job on a fresh runner that checks out `main`'s scripts; never run untrusted code in the job or step that holds the token.
- A reusable workflow's jobs may only request permissions the calling job grants; a missing scope fails the whole run at startup (`test_reusable_workflow_jobs_ask_only_for_what_the_caller_grants`).
- Concurrency groups that must not drop a waiting run use `cancel-in-progress: false` and `queue: max`.
- Job outputs that may contain anything masked as a secret are dropped by GitHub; pass ids, not task text, through matrices.
- Commits to `main` from automation carry `[skip ci]` and push only the paths they own; reporting (issues, comments) is best effort and must never fail the job that called it.
- Cron is UTC: a local-time schedule needs one cron per daylight-saving season, each guarded to run only in its season. Scheduled runs can start late.
- Text an agent writes to a tracked Markdown file must pass `check-docs.py` and `check-secrets.py`, which Delivery runs; Markdown-only pull requests skip Delivery, so check them before pushing.
- Before a workflow change: `actionlint`, `shellcheck` on every script, and `python3 -I -m unittest discover -s scripts/tests`. Fast-tier jobs without PostgreSQL must exclude database suites by `@Tag("integration")` and `-Pfast`, never by a class-name pattern such as `-Dtest='!*IsolationTest'`; the guard is `scripts/tests/test_fast_tier.py`.
