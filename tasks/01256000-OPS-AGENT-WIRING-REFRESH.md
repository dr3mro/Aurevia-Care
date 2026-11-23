- [ ] OPS-AGENT-WIRING-REFRESH · Require agent wiring checks and removal-only baseline refresh after Java or migration changes.
  - requirements: OPS-06
  - scope: AGENTS.md, MEMORY.md
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Start from the declared scope and establish the real entry point before planning edits.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.

    Use the shared agent instructions. Interactive maintainers refresh the baseline after checking for new violations. Swarm workers report stale entries for trusted integration.
  - evidence: Shared AGENTS.md directive reviewed by the delivery policy specialist. OpenCode local and CI configuration generation, pack validation (38 agents and 38 skills), docs, secrets, wiring and whitespace checks passed locally.
  - pending: Repository publication and hosted evidence. The sandbox denied writes to .git/FETCH_HEAD.
