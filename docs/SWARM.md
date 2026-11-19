# Agent swarm

Up to 10 OpenCode workers run in parallel on GitHub-hosted runners. An orchestrator splits tasks from [CLOUDWORKERS.md](../CLOUDWORKERS.md), each worker commits to its own branch, and an integrator merges only what passes the full check. A watchdog repairs failures on `main`. Everything is event driven, so nothing runs while there is no work.

## Flow

| Step | Workflow | What happens |
|---|---|---|
| 1. Split | `swarm.yml` (plan) | Reads CLOUDWORKERS.md. Picks `todo` tasks whose dependencies are `done` and whose scopes do not overlap each other or open swarm PRs. Repairs and fix requests go first. One task per slot. |
| 2. Work | `swarm.yml` (worker) | Starts the slot's prepared image, checks out the repository, runs the agent test first on branch `swarm/<ID>-<run>`, runs `scripts/check.sh`, packs the commits and exits. The container is discarded. |
| 3. Publish | `swarm.yml` (publish) | A separate trusted job pushes the branch and opens the pull request. The worker never holds a GitHub token. |
| 4. Integrate | `swarm-integrator.yml` | One PR at a time: merges it into current `main` locally, runs `scripts/check.sh` (unit, PostgreSQL integration, coverage, OWASP, SpotBugs, policy checks), then commits the merge to `main` and records it. No model is used. |
| 5. Return | `swarm-integrator.yml` | On a conflict or failing check the PR gets `needs-fix` with the log tail and goes back to the pool for a worker. After 3 attempts it gets `needs-human`. |
| 6. Repair | `main-watchdog.yml` | When Delivery fails on `main`, starts one repair worker with the failing log. While `main` is red only repair PRs merge. After 3 repairs in 24 hours it opens an issue and stops. |

## Prepared images

`agent-image.yml` builds a base image once (OpenCode, Git, gh, JDK 21, Maven, the project's Maven dependencies). `swarm-images.yml` adds one small layer per slot, `ghcr.io/<owner>/<repo>-agent-<n>`, that bakes that slot's OpenCode model and config from `.github/swarm/slots.json`. Each slot uses its own free account through the secret `OPENCODE_API_KEY_<n>`. Credentials are never baked into an image. Edit `slots.json` to give a slot a different model or extra OpenCode settings, and the image rebuilds.

## Setup

1. Push the branch, merge it, then run **Agent image** once. **Swarm slot images** follows automatically.
2. For each slot you use, save the repository secret `OPENCODE_API_KEY_<n>` from that slot's own OpenCode account. If a slot-specific secret is absent, the worker falls back to `OPENCODE_API_KEY`; if neither secret exists, the task stays `todo`.
3. Settings, Actions, General: allow Actions to create pull requests.
4. `main` must allow the integrator to push. Either exempt the integrator from the branch rule, or save a fine-grained token with contents, pull requests and actions write as the secret `SWARM_TOKEN`. A `SWARM_TOKEN` also lets pull requests and merges trigger the Delivery workflow and the next wave.
5. Edit CLOUDWORKERS.md and push to `main`, or run **Agent swarm** manually.

## Rules the swarm enforces

- Workers follow AGENTS.md, CODING_STYLE.md, the specification and the `.opencode` specialists, stay inside their `Scope`, and use only their `Reserved` migration version.
- Workers do not edit shared trackers. The integrator writes the CLOUDWORKERS.md status, a PROGRESS.md row and `docs/swarm/<ID>.md` after each merge, one at a time, so trackers never conflict.
- A pull request that changes `.github/`, `.opencode/`, `scripts/`, `opencode.json`, AGENTS.md, Dockerfile, compose or any `check.sh` is never merged automatically. It is labeled `needs-human`.
- The integrator merges exactly the commit it tested and refuses if the branch moved.
- A rate limit, timeout or failure ends that worker. There are no retries inside a run.
- Use synthetic data only. Free models are not suitable for real patient data. Check each provider's terms before running several free accounts.

## Not verified yet

Written and unit tested (scripts/tests) but never run on GitHub: the image builds, the container jobs, per-slot secrets, OpenCode reading the baked slot config and auth file, pushing to protected `main`, and the integrator check timing. Start with `workers=1` and one task.
