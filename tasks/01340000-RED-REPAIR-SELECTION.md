- [-] RED-REPAIR-SELECTION · PO and SM select investigated red-state repairs before backlog work
  - agent: orca
  - scope: .github/workflows/swarm.yml, scripts/tests/test_swarm.py, docs/SWARM.md, tasks/01340000-RED-REPAIR-SELECTION.md
  - requirements: OPS-06
  - details:
    The swarm already detects a red `main`, creates `FIX-<run>` tasks from bounded failure logs, attaches PR failure evidence and gives repairs priority in the trusted planner (`scripts/swarm.py add-repair-task`, `plan_workers`, `fix-pr-gate.yml`). The PO and SM agents that choose the wave's candidates never see that evidence, so a red repair can be omitted from `feature=`/`tasks=` and ordinary backlog work competes with it. Wire the existing evidence into the two prompts: PO reads open repair tasks and their failure evidence and lists an eligible repair first; SM allocates repair candidates first within `WORKER_COUNT` without claiming them or relaxing exclusions. The trusted repair step, watchdog and planner keep ownership of creating and claiming tasks; the agent jobs stay read-only.
    Acceptance scenarios:
    1. Given a red `main` with an open, eligible `FIX-<run>` repair whose task file carries failure evidence, when PO selects, then that repair id is the first id in `tasks=` and the PO prompt says not to edit or claim trackers.
    2. Given a red PR with attached head SHA, run link, tool and bounded identifiers, when PO selects, then a matching repair candidate is preferred over unrelated backlog work.
    3. Given no open repair and a green `main`, when PO selects, then normal feature/task selection continues and no repair is invented.
    4. Given repair ids in `CANDIDATES`, when SM allocates, then eligible repairs come first inside the `WORKER_COUNT` cap and `[-]`, `[!]`, unmet dependencies and overlapping scopes are still excluded.
    5. Given any candidate touches protected files or gates, when the wave is dispatched, then the existing guard and repair gate still apply with no new write token, bypass or threshold change.
    Exclusions: no new script or CLI, no agent write token, no change to `main_is_red`, repair creation, planner priority, gates or concurrency, no live wave dispatch verification in this story.
    Related open tracker entries: 01248000-OPS-RED-MAIN-FIRST, 01249000-OPS-PR-REPAIR-WAVES, 01250000-OPS-RED-MAIN-DETECTION, 01251000-OPS-SWARM-PRIORITY-CAPACITY. They are not hard dependencies because the underlying implementation is already on `main`; this story only changes the PO/SM decision input.
