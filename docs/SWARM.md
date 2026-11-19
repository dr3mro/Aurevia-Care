# Agent swarm

Up to 10 OpenCode workers run in parallel on GitHub-hosted runners. An orchestrator splits tasks from [TODO.md](../TODO.md), each worker commits to its own branch, and an integrator merges only what passes the full check. A watchdog repairs failures on `main`. Everything is event driven, so nothing runs while there is no work.

## Flow

| Step | Workflow | What happens |
|---|---|---|
| 1. Split | `swarm.yml` (plan) | Reads TODO.md. Picks `[ ]` tasks that have a `scope`, whose `depends` are all `[x]` and whose scopes do not overlap each other, `[-]` tasks or open swarm PRs. Marks each picked task `[-]` with `agent: swarm-<slot>` in one commit to `main`, and frees stale claims first (a `[-]` swarm task with no open PR goes back to `[ ]`, or to `[x]` if a person merged its PR). Repairs and fix requests go first. One task per slot. |
| 2. Work | `swarm.yml` (worker) | Starts the slot's prepared image, checks out the repository, runs the agent test first on branch `swarm/<ID>-<run>`, runs `scripts/check.sh`, packs the commits and exits. The container is discarded. |
| 3. Publish | `swarm.yml` (publish) | A separate trusted job pushes the branch and opens the pull request. The worker never holds a GitHub token. |
| 4. Integrate | `swarm-integrator.yml` | One job for the whole ready batch: merges each PR into local `main` one at a time, runs the fast gate `scripts/quick-check.sh` after each, then commits and pushes once. No model is used. |
| 5. Heavy checks | `heavy-check.yml` | Runs the expensive checks (PostgreSQL integration, JaCoCo coverage, OWASP with cached NVD data, SpotBugs) once per batch on `main`, scheduled, dispatched, or after a successful integrator run. A failure wakes the watchdog. |
| 6. Return | `swarm-integrator.yml` | On a conflict or failing fast gate the PR gets `needs-fix` with the log tail and goes back to the pool; its merge is rolled back so the rest of the batch proceeds. After 3 attempts it gets `needs-human`. |
| 7. Repair | `main-watchdog.yml` | When Delivery, Heavy verification or an image build fails on `main`, starts one repair worker with the failing log. The repair PR (`FIX-`, label `autofix`) is merged by the integrator after the fast gate, and Delivery re-runs on `main`, so a fix that did not work starts the next repair. While `main` is red only repair PRs merge. After 3 repairs in 24 hours it opens an issue and stops. |
| 8. Continue | `swarm.yml` (next), `swarm-integrator.yml` | After a wave with no PR waiting, and after every merge, the next wave starts if `TODO.md` still has ready `[ ]` tasks. A 3-hourly schedule restarts the loop if it stalls. It stops when nothing is left to do. |

**Continuous loop.** Start it once (run **Agent swarm**, or push a change to `TODO.md`). It then repeats wave, publish, integrate, next wave until every task is `[x]`. A task a worker has taken 3 times without finishing keeps `attempts: 3` in `TODO.md` and waits for a person, and the monthly minute cap in `budget-gate` still applies, so the loop cannot run away.

**Two-tier checks.** The worker, integrator and PR gate run only `scripts/quick-check.sh`: policy, compile and unit tests. PostgreSQL integration, coverage, OWASP and SpotBugs run once per batch on `main` in `heavy-check.yml` with a cached NVD database, not once per PR. This removes the expensive checks from every merge and worker.

**Batching.** The integrator sets up Java/Maven once, merges every ready PR in one job, pushes once, and triggers Heavy verification once. Publish is one job for all slots rather than a matrix; the worker keeps no GitHub token and only the trusted publish job holds one.

## Prepared images

`agent-image.yml` builds a base image once (OpenCode V2 CLI, Git, gh, JDK 21, Maven, the project's Maven dependencies). `swarm-images.yml` adds one small layer per slot, `ghcr.io/<owner>/<repo>-agent-<n>`, that bakes that slot's OpenCode model and config from `.github/swarm/slots.json`. Each slot uses its own free account through the secret `OPENCODE_API_KEY_<n>`. Credentials are never baked into an image. Edit `slots.json` to give a slot a different model or extra OpenCode settings, and the image rebuilds.

**Reusable build, never per-task.** Images are built only when their own inputs change (the agent Dockerfile, `slots.json` or the image workflows), on the monthly refresh, or by manual dispatch. `swarm.yml` contains no build step: a worker only pulls its slot's `:latest` image, so running N tasks does not rebuild anything. Because a slot image layers on the base `:latest` tag, `swarm.yml` checks image health before planning a wave: while `agent-image` or `swarm-images` is running, or the last slot build is not green, it starts no workers (avoiding a stale-image race such as a V1 CLI after the base moved to V2). A successful `Swarm slot images` run re-triggers `swarm.yml`, so a wave skipped during a rebuild still picks up its task. To verify a CLI change reached workers, rebuild the base image, wait for slot images, then start a wave.

## Setup

1. Push the branch, merge it, then run **Agent image** once. **Swarm slot images** follows automatically.
2. For each slot you use, save the repository secret `OPENCODE_API_KEY_<n>` from that slot's own OpenCode account. If a slot-specific secret is absent, the worker falls back to `OPENCODE_API_KEY`; if neither secret exists, the task goes back to `[ ]`.
3. Settings, Actions, General: allow Actions to create pull requests.
4. `main` must allow the orchestrator and the integrator to push (the orchestrator pushes the `[-]` marks in TODO.md). Either exempt the integrator from the branch rule, or save a fine-grained token with contents, pull requests and actions write as the secret `SWARM_TOKEN`. A `SWARM_TOKEN` also lets pull requests and merges trigger the Delivery workflow and the next wave.
5. Edit TODO.md and push to `main`, or run **Agent swarm** manually.

## Rules the swarm enforces

- Workers follow AGENTS.md, CODING_STYLE.md, the specification and the `.opencode` specialists, stay inside their `Scope`, and use only their `Reserved` migration version.
- Workers do not edit shared trackers. The orchestrator marks a task `[-]` when it starts a worker, and the integrator marks it `[x]` with `pr:` and `evidence:` and writes `docs/swarm/<ID>.md` after each merge, one at a time, so TODO.md never conflicts. A worker that publishes nothing releases its task back to `[ ]`.
- Repair pull requests (`FIX-`, label `autofix`) are merged by the integrator like other swarm work, including changes to `.github/`, `.opencode/`, `scripts/` and the other protected files, as long as the fast gate passes. Ordinary story pull requests touching those files still wait for a person.
- A story pull request that changes `.github/`, `.opencode/`, `scripts/`, `opencode.json`, AGENTS.md, Dockerfile, compose or any `check.sh` is never merged automatically. It is labeled `needs-human`. Watchdog repair pull requests (`FIX-`, label `autofix`) are the exception: the integrator merges them after the fast gate even when they change those files, because repairing the pipeline itself must not need owner action. All merges still require the fast gate, and shared trackers stay integrator-only.
- The integrator merges exactly the commit it tested and refuses if the branch moved.
- A rate limit, timeout or failure ends that worker. There are no retries inside a run.
- Use synthetic data only. Free models are not suitable for real patient data. Check each provider's terms before running several free accounts.

## Not verified yet

Written and unit tested (scripts/tests) but never run on GitHub: the image builds, the container jobs, per-slot secrets, OpenCode reading the baked slot config and auth file, pushing to protected `main`, and the integrator check timing. Start with `workers=1` and one task.
