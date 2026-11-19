# Workspace resume card

| Field | Current value |
|---|---|
| Working tree and remote | Branch `main`, HEAD `928d886`, `0/0` with `origin/main` (`github.com/dr3mro/Aurevia-Care.git`). `delivery/g0` retired; all scoped work commits to `main`. Remote swarm workers and auto-merges are active — always `git fetch origin main` and reconcile before editing trackers or pushing. Keep `TODO.md` in sync at least every 10 minutes. |
| Active story | None claimed. Standing directive: watch **every** non-successful Actions run (failure, cancelled, skipped, stuck), fetch logs, fix, commit, push until green; pull remote updates and resolve conflicts; keep local exactly 1:1 with `origin/main`. |
| Approved delivery branch | `main` |
| Stable commands | `scripts/check.sh`, `scripts/quick-check.sh`, `scripts/docs.sh`, `scripts/frontend-check.sh`, `python3 -I -m unittest discover -s scripts/tests -q`, `python3 -I scripts/gen-opencode.py --check`, `bash apps/api/check.sh`, `bash db/check.sh` |
| Local environment | Java 27 locally (CI JDK 21, `--release 21`), Maven 3.10, Node 26, Python 3.15, shellcheck, Doxygen, PostgreSQL 18.6 on 5432; Docker MISSING locally — image smoke runs in GitHub Actions only |
| CI and release | `delivery.yml`: policy → java/database + docs → per-arch image smoke → release. Reusable actions in `.github/actions`; Maven + OWASP NVD caches. All workflow concurrency groups queue (`cancel-in-progress: false`, `queue: max`). |
| Swarm | `swarm.yml` hourly (`cron: '7 * * * *'`), 10 workers/wave, 175-min agent / 185-min job budget, model `opencode-go/hy4-flash-free`. `swarm-integrator.yml` runs full `scripts/check.sh` per merge (PostgreSQL service). `main-repair.yml` is PR-event only for fix PRs. `heavy-check.yml` daily. Tracker commits carry `[skip ci]` so they do not spawn duplicate waves. |
| Watch checkpoint | 2026-11-19T11:03Z: HEAD `928d886` fully green (all 5 Delivery checks success). Active wave `44509359962` (SHA `f8c0397`, 10 workers in progress, holds `agent-swarm` group); queued wave `44510302099` (SHA `928d886`). No failures since. Earlier fixes this session: multi-hyphen task IDs in branches, worker publish ref reuse, REST labels (no `read:org`), `[skip ci]` wave amplification, root frontend typecheck, flaky publish test, workflow-scoped `PUSH_TOKEN`. |
| Next action | Continue watch passes: check `gh run list` for any non-success, fetch job logs, fix + push, keep 1:1. Note: a literal 24-hour continuous watch is not possible in one session — the durable automation (hourly swarm, daily heavy check, PR repair, integrator) carries the loop between sessions. |

Keep this card short and current. TODO.md owns task state and evidence; docs/archive/PROGRESS.md holds the earlier chronological log. MEMORY.md owns durable decisions.
