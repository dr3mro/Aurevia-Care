# SmartClinic working rules

**Read the relevant docs before writing code.** List the repository's Markdown files and read the ones that govern your task (working rules, README/setup, CODING_STYLE.md, docs/WORKFLOW.md, architecture decisions, the maintainer guide and API conventions). Follow the code style and testing guidelines there; `opencode.json` and `.github/opencode.ci.json` also load these files as `instructions` for every agent, including swarm workers.

Read WORKSPACE.md and TODO.md first. TODO.md is the single task file: `[ ]` open, `[-]` taken by an agent, `[x]` done. Read MEMORY.md, BUGS.md and SMARTCLINIC_V1_SPEC.md by relevant IDs. Current code and executed checks are implementation evidence. Preserve unrelated changes. Never include patient data or credentials in agent context, tests, logs or trackers.

Use the smartclinic-delivery skill. The primary agent owns edits and history; specialists return bounded findings and patches in their reports. Route each task to its domain specialist. Give specialists only story IDs, paths, acceptance criteria and relevant evidence. Prefer one bounded review per stage; combine independent reviews when useful. Rehydrate from tracking files after context resets. Report unavailable delegation accurately.

Run one story at a time. Take it in TODO.md before you start (change `[ ]` to `[-]` and add `- agent: <name>`), record each stage's evidence on its entry, and mark it `[x]` with `evidence:` and `pr:` only when every gate passes. Put it back to `[ ]` if you stop. Swarm workers never edit TODO.md: the orchestrator marks their task `[-]` and the integrator marks it `[x]`. Update MEMORY.md with any durable decision. Update WORKSPACE.md when branch, commands or environment changes. Record defects in BUGS.md. Keep unrelated notes out of the active checkpoint.

Start with failing acceptance/regression tests, implement the smallest change, then refactor and verify. Apply CODING_STYLE.md. No uncovered production code is permitted by default. Require all applicable gates and observed evidence before calling a commit releasable. Use synthetic fixtures and defensive tests. Follow real-patient approval gates in the specification.

When the active execution request authorizes GitHub delivery, commit scoped passing changes and push to the agreed delivery branch. Wait for the required checks on the exact SHA and repair failures through a new tested commit. Do not force-push or bypass protections. Tag/publish releases only under the release policy and with release authority. Keep production deployment separately authorized.

Read project.json for the current display name and suggested repository slug. Use stable technical identifiers for agent routing and the GitHub remote for publication.

Use docs/WORKFLOW.md to route routine, cross-boundary and safety-critical changes. Document changed public contracts, build scripts/docs.sh, and include a short entry-point-to-test trace in review evidence.

## Shared knowledge loop

Every user instruction or choice is durable input. Route it to the owning tracker in the same turn. After any completed task, write the outcome to the owning tracker so the next agent resumes without re-asking.

| Input or outcome                                         | Owner file                              |
| -------------------------------------------------------- | --------------------------------------- |
| Durable directive, policy decision or approved choice    | MEMORY.md (decision, rationale, date)   |
| Task state, executed commands, reviewer or test evidence | TODO.md (marks and `evidence:` on the task entry) |
| Branch, delivery branch, commands or environment change  | WORKSPACE.md                            |
| Implementation decision with requirement IDs             | PROJECT.md                              |
| New story, acceptance change or improvement idea         | TODO.md                                 |
| Observed, reproducible defect                            | BUGS.md                                 |

Keep notes short and cite exact requirement IDs, paths and evidence. Record whether evidence came from a tool, reviewer, simulated user or human participant. Never store credentials, patient data or raw logs in any note.

## Engineering quality bar

Every change must meet all of the following before it is called done:

- **100% coverage** of production lines, branches, methods and classes from the first production file, with build-failing gates and no fabricated or pending-as-covered results.
- **Static and dependency security testing** on every change, including OWASP dependency analysis and language-appropriate static analysis. Record tool, version, command, finding count and disposition in the checkpoint.
- **Clean code and SOLID**: one responsibility per unit, small explicit interfaces, dependency inversion at boundaries, no duplicated rules across layers, readable names and guard clauses, and a traceable entry point to a test for each behavior.
- **Self-improvement**: when a defect, review finding or tool gap is observed, update the owning skill or agent instruction in the same change so the next run avoids it. Regenerate `opencode.json` after any agent or skill edit and commit the generated file.

## Frontend standards

- TypeScript only: .ts/.tsx, never .js/.jsx. tsconfig must have "strict": true, "noUncheckedIndexedAccess": true; no `any`, no `@ts-ignore`.
- React: function components and hooks only, typed props, small single-purpose components, no business logic in JSX, data fetching via one typed API client.
- Before finishing any task run `tsc --noEmit`, `eslint . --max-warnings=0`, `prettier --check .` and the tests, and fix every failure. Never disable a rule to pass.
- Convert any existing .js/.jsx file you touch to TypeScript.

Apply these rules in every specialist routing brief and in acceptance evidence.
