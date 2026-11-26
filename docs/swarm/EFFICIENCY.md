# Swarm efficiency baseline

Measured 2026-11-19 from GitHub Actions run JSON via `gh run list` / `gh api .../jobs`.
Seconds are wall-clock per job; GitHub bills each job as whole minutes with a one-minute
minimum, so a 10-second job still costs one runner-minute. Runs observed: swarm
`44457230011`, `44454272186`, `44457655977`; integrator `44458412745`, `44457321990`,
`44454443719`, `44450466105`; agent image `44457064710`, `44450215773`; slot images
`44457295040`, `44450395387`; Delivery `44459043751`, `44458327332`.

## Per-job seconds (observed)

| Workflow | Job | Seconds | Billed min |
|---|---|---:|---:|
| swarm | Plan the wave | 9–10 | 1 |
| swarm | Initialize containers (image pull) | 13–19 | — |
| swarm | checkout in worker | 1–2 | — |
| swarm | Work the task (no-op agent) | 1 | — |
| swarm | Work the task (real CW-003 agent) | 343 | — |
| swarm | Worker job total (no-op) | 21 | 1 |
| swarm | Worker job total (real agent) | 368 | 7 |
| swarm | Publish slot (matrix, per slot) | 5–11 | 1 |
| integrator | Pick the queue | 5–7 | 1 |
| integrator | Integrate PR (skipped in all measured runs) | n/a | n/a |
| agent-image | build (full base image + push) | 86–109 | 2 |
| swarm-images | plan | 6–10 | 1 |
| swarm-images | Slot image build + push (each of 10) | 21–58 | 1 each |
| delivery | Policy and tooling | 7–13 | 1 |
| delivery | Java and database verification | 75–86 | 2 |
| delivery | Developer reference (Doxygen install) | 43–65 | 1–2 |
| delivery | Verify and smoke image (arm64 / amd64) | 17–28 | 1 each |

Delivery's Java-and-database job runs the full `scripts/check.sh` equivalent (unit,
PostgreSQL integration, JaCoCo coverage, OWASP, SpotBugs). The integrator's merge job
runs the same check per PR today, so its per-PR cost is at least the 2 billed minutes
of that job, plus a fresh Java/Maven cache setup and checkout for every PR. No
integrator merge job completed in the 7 runs available, so its exact seconds are
**unmeasured**; the per-PR estimate uses Delivery's job as the proxy.

## Runner minutes per task, before

| Path | Jobs | Billed min |
|---|---|---:|
| One no-op wave (plan + 1 worker + 1 publish) | 3 jobs | 3 |
| One real task end to end (plan + worker + publish) | 3 jobs | ~9 |
| Wave of 3 real tasks (plan + 3 workers + 3 publishes) | 7 jobs | ~25 |
| Integrator, 1 PR (pick + merge + full check) | 2 jobs | ~3 |
| Integrator, 3 PRs (pick + 3 merge jobs) | 4 jobs | ~9 |
| Base image rebuild | 1 job | 2 |
| Slot images rebuild (10 slots) | 11 jobs | 11 |

A wave with per-slot publish and per-PR integration scales linearly with the number
of tasks/PRs; image rebuilds are rare (only on image-input changes or monthly).

## Target after the changes below

| Path | Before | After | Saved |
|---|---:|---:|---:|
| Wave of 3 real tasks | 25 | 11 | 14 |
| Integrator, 3 PRs | 9 | 3 | 6 |
| Delivery per push to main (docs-only) | 7 | 7 | 0 (the exact-SHA gate must be reported) |
| Slot images rebuild | 11 | 1–3 | 8–10 |

Estimates are naive runner-minute arithmetic from the observed job times; they are
**not** measured GitHub billing. Items that cannot be measured locally are marked
unverified in the change log below. A former docs-only push saving is no longer
claimed: there is no path filter on either Delivery trigger, so every push to `main`
and every pull request runs the full chain and reports the exact-SHA check.

## Minutes saved by each change (estimated)

| # | Change | Saving | Basis |
|---|---|---:|---|
| 1 | Two-tier checks | ~2 min per PR | Heavy gate (PostgreSQL, coverage, OWASP, SpotBugs) leaves every merge and worker; the integrator's 2-minute Java job becomes the ~1-minute quick gate, and heavy runs once per batch (Delivery job 75–86s). |
| 2 | Batch the integrator | ~2 min per extra PR | One Java/Maven cache setup and one checkout/push replace one per PR; the per-PR integrate job's setup was the dominant fixed cost. |
| 3 | Fewer jobs | ~1 min per extra slot | Publish is one job for all slots instead of a 10-way matrix; the separate publish job's 5–11s setup billed as a minute per slot. Folding into the worker would save another minute but was rejected to keep the token out of agent-written code. |
| 4 | Right-size pool | up to ~21 min per idle wave | Default cap 10 → 3 ready tasks; empty plan starts no worker jobs. Observed no-op wave billed 3 min; a full 10-slot image build billed 11 min. |
| 5 | Faster launch | ~1 min per base rebuild, unverified | Dropped `curl`/`shellcheck` from the worker image and put the warm Maven repo in one layer; slot layers stay config-only and only slots in the wave are pulled. Image pull cost (13–19s) and size/billing cannot be measured locally (no Docker); the V1→V2 CLI fix is carried over. |
| 6 | Fewer triggers (retired) | 0 (superseded for correctness) | A path filter on `push` once skipped Delivery for docs-only commits, but it also suppressed the required exact-SHA `Delivery gate` check and could block protected branches forever. Both `push` and `pull_request` triggers now have no path filter, so every PR and every push to `main` or a release tag reports the gate; PR runs group by head SHA so a new head is not queued behind a stale one. Waves still run on `tasks/` pushes, the hourly schedule, completed slot-image runs or manual dispatch. |
| 7 | Budget guard | unbounded risk reduction | Refuses a wave above 90% of the 1,500-minute cap and opens an issue; no direct per-wave saving, but limits worst-case monthly spend. |
| 8 | Superseded branch pushes | removes the queue wait after a commit burst | Only Delivery runs for the same branch push group cancel each other; pull request heads, tags, dispatches and merge groups keep exact-SHA verdicts. The saving is the skipped older full-gate runs, not a faster single run. Tag only an already-green main commit, because cancelling its push run removes the exact-SHA success the release job requires. |

**Unverified on GitHub:** integrator merge seconds (no merge job completed in the
7 runs available), new workflow wiring and concurrency, image size/pull savings,
actions/cache effectiveness for `/opt/m2`, and actual billed minutes. All were
verified locally only by unit tests, shellcheck, yamllint and `scripts/check.sh`.
