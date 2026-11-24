- [ ] CLEAN-PATH-REFERENCES · Repair stale path strings and broken relative links left by the config and operations-folder moves
  - requirements: OPS-06
  - scope: README.md, docs/operations/slo.md, docs/reference/synthetic-data.md, docs/security/data-inventory.md, docs/reference/testing-guide.md, docs/TEAM-ACTIONS.md, docs/SWARM.md, docs/operations/workflow-changes/quality-verification-checklist.md, docs/gates/g0-isolation-surfaces.md, .opencode/setup/AGENTS.md, .opencode/setup/CONSTRAINTS.md, .opencode/config/README-SETUP.md, .opencode/config/STARTER_PROMPT.md, .opencode/config/MEMORY.md, .opencode/config/BUGS.md, .opencode/config/WORKSPACE.md, .opencode/agents/orca.md, .opencode/skills/aurevia-delivery/SKILL.md
  - attempts: 0
  - details:
    The 2026-11-23 naming audit found path strings that still use the removed
    `infra/s_g5_*` folders and the pre-move config locations (`opencode.json`,
    `.github/opencode.ci.json`, `.github/swarm/slots.json`,
    `.github/swarm/model-pricing.json`). The real files are under `.opencode/`.
    Fix every active reference, repair the broken relative Markdown links, and
    reconcile the stale tracker claims. Historical records under docs/archive/,
    docs/swarm/, tasks/ and .agent/pr-notes/ are deliberately left untouched.
    Regenerate the OpenCode config after editing instruction files and commit
    any resulting diff. Run `python3 -I scripts/check-docs.py` and the pack
    check as evidence.
