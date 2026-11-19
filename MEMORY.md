# Durable memory

Current checkpoint: PROGRESS.md, G0-01 implementation. Next action: OWASP/static-analysis gate, documentation and CI wiring, then independent review. Keep a short pointer to the last completed stage and next action in this file after each stage.

| Decision | Rationale and evidence |
|---|---|
| One delivery story in progress | Keeps changes reviewable and resume state small |
| Version from immutable Git tag | Published Docker image and GitHub release use the same tag |
| Synthetic data in engineering and simulated UAT | Site approval and genuine clinical acceptance are tracked separately |
| User directive (2026-11-18): persist every user instruction/choice and every task outcome to the owning repo Markdown tracker | Shared knowledge loop added to AGENTS.md: directives to MEMORY.md, stages to PROGRESS.md, environment to WORKSPACE.md, implementation decisions to PROJECT.md, ideas to TODO.md, defects to BUGS.md; preserves context across agents and resets |
| User directive (2026-11-18): "always allow, don't ask me again" | Run scoped edits, tests, checks, subagents and Git operations without pausing for confirmation. Hard denies (force-push, --no-verify, .env reads, hard reset, clean), PHI/credential rules, release tags and production deployment stay separately gated by AGENTS.md and the release policy |
| User feedback (2026-11-18): keep executing; do not stop for status-only turns while work remains | Specialists are advisory and run in the background; proceed with implementation from an available plan instead of waiting on them. Report progress only at a real blocker or completed stage |
| User directive (2026-11-18): maximize self-improvement of agents and skills; static OWASP testing; 100% testable, readable, clean SOLID code | Engineering quality bar added to AGENTS.md; devsecops/java-build skills and CI will be updated in the same loop so each run inherits the rule |

Add durable decisions only. Reference exact IDs, files and evidence paths. Avoid copying logs, specifications or stage histories.
