- [-] STALE-TRACKER-PATHS · Stop swarm agents from searching removed root TODO.md/MEMORY.md
  - agent: orca
  - scope: .github/workflows/swarm.yml, .opencode/skills/aurevia-context-optimizer/SKILL.md, scripts/tests/test_swarm.py, tasks/01331000-STALE-TRACKER-PATHS.md
  - details:
    In the 14:15Z allocate step of run 45837146301 the scrum-master agent searched `/workspace/MEMORY.md` and `/workspace/TODO.md` and got `Error: Search path does not exist`. Both root files were removed when trackers moved to `tasks/` and `.opencode/config/MEMORY.md`; the agent recovered, read the correct paths and produced a valid allocation, so this was non-fatal. Add an explicit guard to the context-optimizer skill and to the PO/SM workflow prompts: use `tasks/` and `.opencode/config/MEMORY.md`, never root-level `TODO.md` or `MEMORY.md`. Add a regression assertion in test_swarm.py.
  - evidence:
    Skills loaded by matched clause: aurevia-delivery (delivery loop), aurevia-debugger (diagnose Actions tool error), aurevia-github-workflow (swarm.yml prompt change).

    Root cause evidence (tool): attached allocate log from run 45837146301, job 139970633661, showed `Grep "AUD-2026112" failed / Error: Search path does not exist: /workspace/MEMORY.md` and later the same for `/workspace/TODO.md`, followed by correct reads of `.opencode/config/MEMORY.md` and `tasks/*`, `opencode rc=0`, `docker rc=0`, and `tasks=AUD-20261122-14,AUD-20261122-19`. No current agent, skill, config or workflow file references those root paths; the model followed stale historical Markdown in context.

    Fix (tool): added the current-path guard to `.opencode/skills/aurevia-context-optimizer/SKILL.md` and to both PO/SM prompts in `.github/workflows/swarm.yml`; added `test_selection_prompts_name_the_current_tracker_paths`.

    Green (tool): `/opt/homebrew/bin/python3.15 -I -m unittest discover -s scripts/tests -p test_swarm.py -q` -> `Ran 371 tests ... OK`; actionlint (queue ignore), check-pack, check-docs, check-secrets, check-constraints --require-base, gen-opencode --check, git diff --check each exit 0.

    Files changed: `.github/workflows/swarm.yml`, `.opencode/skills/aurevia-context-optimizer/SKILL.md`, `scripts/tests/test_swarm.py`, `tasks/01331000-STALE-TRACKER-PATHS.md`.

    Unverified: no live swarm wave has run with the new prompt text yet. Evidence sources were tool commands and the attached Actions log; no simulated-user or human feedback.
