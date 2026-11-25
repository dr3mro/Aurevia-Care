---
name: aurevia-github-workflow
description: "Reviews GitHub Actions, branch gates, tags and image publication. Use when a change touches .github/workflows, a gate script, token permissions, concurrency, a schedule or a release."
---

# Github Workflow

Verify required checks for the exact SHA, version derivation from tag, least-privilege jobs, build and smoke before publication, release provenance, retries and testability.

Swarm workflow invariants (each one broke or nearly broke a run):
- An agent step never holds a write token. Untrusted output (bundles, JSON, notes, a pull request's own code) is acted on by a second job on a fresh runner that checks out `main`'s scripts; never run untrusted code in the job or step that holds the token.
- A reusable workflow's jobs may only request permissions the calling job grants; a missing scope fails the whole run at startup (`test_reusable_workflow_jobs_ask_only_for_what_the_caller_grants`).
- Concurrency groups that must not drop a waiting run use `cancel-in-progress: false` and `queue: max`. GitHub rejects the combination of `queue: max` with cancellation of a running run at workflow validation time, before any job starts, so cancellation is never an option on a queueing gate. Delivery groups pull-request runs by `${{ github.event.pull_request.head.sha }}` and every other trigger by `${{ github.ref }}`, so each PR head gets its own queue and every exact SHA receives its own `Delivery gate` verdict; main, tag, dispatch and merge-group runs queue per ref.
- Job outputs that may contain anything masked as a secret are dropped by GitHub; pass ids, not task text, through matrices.
- Commits to `main` from automation carry `[skip ci]` and push only the paths they own; reporting (issues, comments) is best effort and must never fail the job that called it.
- Cron is UTC: a local-time schedule needs one cron per daylight-saving season, each guarded to run only in its season. Scheduled runs can start late.
- Text an agent writes to a tracked Markdown file must pass `check-docs.py` and `check-secrets.py`, which Delivery runs. Every push and pull request runs Delivery (no path filter), so the required check is always reported; check Markdown before pushing.
- Before a workflow change: `actionlint`, `shellcheck` on every script, and `python3 -I -m unittest discover -s scripts/tests`. Fast-tier jobs without PostgreSQL must exclude database suites by `@Tag("integration")` and `-Pfast`, never by a class-name pattern such as `-Dtest='!*IsolationTest'`; the guard is `scripts/tests/test_fast_tier.py`.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| The agent step can hold the token, it is our own code. | Never. Untrusted output is acted on by a second job on a fresh runner that uses main's scripts. |
| I will test the workflow after merging. | Run `actionlint`, `shellcheck` and the unit tests first; workflow changes are protected files. |
| Cancel the older run, it is stale. | No queueing gate may cancel a running run: GitHub rejects `queue: max` with `cancel-in-progress: true` before any job starts. Keep one per-branch queue so every head, including a re-pushed PR, receives its own verdict. |

## Red flags

- `cancel-in-progress: true` (or an expression that can evaluate to true) on a queueing gate, or any gate without `queue: max`.
- A reusable workflow asking for permissions its caller does not grant.
- Task text or secrets passed through a matrix output.

## Verification

- [ ] `actionlint` and `shellcheck` pass and `python3 -I -m unittest discover -s scripts/tests` passes.
- [ ] Agent steps hold no write token.
- [ ] Concurrency, schedule time zone and permissions were checked.
