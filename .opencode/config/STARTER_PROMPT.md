# Starting and steering the swarm

The Aurevia V1 delivery loop runs as the agent swarm. Setup is in [docs/SWARM.md](docs/SWARM.md#setup); the earlier `/oc` issue loop has been retired.

## Start

1. Run **Agent image** once and wait for **Swarm slot images** to finish.
2. Run **Agent swarm** with `workers=1` for the first wave, read its logs, then let it run with the default of 10. It keeps starting waves until every ready task in `tasks/` is `[x]`.

## Give it work

- **A task:** add a `[ ]` entry to `tasks/` with a `scope` (and `requirements`, `depends` or `reserved` when they apply) and push it. A push to `tasks/` starts a wave.
- **A problem:** open a GitHub issue, describe the problem and the evidence, and label it `swarm-issue` and `agent-fixable`. Within an hour the issue monitor sends it to a triage worker, which turns it into task `ISS-<n>`; the worker's findings are posted on the issue, and the issue closes when the fix merges.
- **An audit:** run **Daily audit** with a `focus`, for example `the messaging module`.

## Watch it

Open issues are the to-do list for a person: `needs-human` (a decision, a credential or a pull request that will not merge by itself), `needs-review` (a protected-file change or the daily rules pull request), `stalled` and `ci-failure`. Each says why and what was tried. The reconciled notes of each day are in `docs/pr/reports/<YYYY-MM-DD>.md`.

## Stop it

Cancel the running **Agent swarm** runs and disable the workflow, or leave `tasks/` with no ready task. Set the repository variable `SWARM_ISSUES=off` to stop issue writing and the issue monitor, and `SWARM_NOTES=off` to stop the daily notes.
