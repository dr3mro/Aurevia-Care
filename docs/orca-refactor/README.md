# ORCA infrastructure refactor — deliverable index

This directory holds the evidence-backed artifacts of the staged refactor defined in `orca-refactor.md`. Nothing in this index is a completion claim: each entry records its current status and the evidence source kind (tool, reviewer, simulated user, or human participant).

## Evidence source legend

- **tool** — a command or file read executed in this checkout.
- **reviewer** — a read-only specialist finding; its file and line citations are recorded in `tasks/01300000-ORCA-INFRASTRUCTURE-REFACTOR.md`.
- **simulated user** — a synthetic walkthrough; none has been run for the infrastructure refactor.
- **human** — owner or clinical feedback; only the directives recorded in `.opencode/config/MEMORY.md` exist so far.

## Deliverable map

| # | Deliverable | Status | Evidence |
|---:|---|---|---|
| 1 | Agent infrastructure inventory | Available | `01-inventory.md`; tool + reviewer |
| 2 | Skill inventory | Available | `01-inventory.md`; tool + reviewer |
| 3 | Markdown instruction inventory | Available | `01-inventory.md`; tool + reviewer |
| 4 | Script ownership inventory | Available | `01-inventory.md`; tool + reviewer |
| 5 | Current architecture diagram | Available | `01-inventory.md`; tool |
| 6 | Target architecture diagram | Draft, blocked on owner decisions | `01-inventory.md`; human decisions pending |
| 7 | Canonical instruction hierarchy | Pending Stage E | no evidence |
| 8 | Markdown loading policy | Partial: four global instructions verified | tool (generated configs) |
| 9 | Validated skill registry | Pending Stage E | no evidence |
| 10 | Skill dependency graph | Pending Stage E | no evidence |
| 11 | Task-to-skill routing matrix | Partial: `scripts/route-task.py` exists | tool (file read) |
| 12 | Standardised skill definitions | Pending Stage E | no evidence |
| 13 | Agent capability contracts | Pending Stage E | no evidence |
| 14 | Task execution contract schema | Pending Stage F | no evidence |
| 15 | Task readiness assessment | Pending Stage F | no evidence |
| 16 | Repository knowledge index | Pending Stage F | no evidence |
| 17 | Impact analysis mechanism | Pending Stage F | no evidence |
| 18 | Context compiler | Pending Stage F | no evidence |
| 19 | Context manifest schema | Pending Stage F | no evidence |
| 20 | Preflight validation mechanism | Pending Stage G | no evidence |
| 21 | Canonical agent configuration | Available: generated from one generator | tool (`gen-opencode.py --check`, exit 0) |
| 22 | Execution profiles | Pending Stage G; conflicts with owner model decision | blocked |
| 23 | Model routing policy | Conflicts with owner decision | blocked, `MEMORY.md:33` |
| 24 | Model capability profiles | Conflicts with owner decision | blocked |
| 25 | Provider circuit breaker | Conflicts with owner decision | blocked |
| 26 | Scoped memory retrieval | Pending Stage H | no evidence |
| 27 | Durable checkpointing | Available: task files plus daily notes | tool (`tasks/`, `.agents/`) |
| 28 | Task recovery mechanism | Partial: leases and stale-worker logic in `scripts/swarm.py` | reviewer (WF-14) |
| 29 | Independent review protocol | Partial; defect AG-22 open | reviewer (AG-22) |
| 30 | Feature completion matrix | Partial: `docs/gates/`, `docs/security/` | tool |
| 31 | Healthcare safety verification policy | Available: `docs/launch/`, `docs/gates/` | tool |
| 32 | Architecture enforcement mechanism | Partial: `scripts/check-wiring.py` | tool + reviewer |
| 33 | Contract synchronization checks | Partial: OpenAPI generation checks | tool |
| 34 | Script ownership migration table | Pending Stage D | no evidence |
| 35 | Updated repository directory structure | Pending Stage D | no evidence |
| 36 | Subsystem dependency diagram | Pending Stage D | no evidence |
| 37 | Canonical script entry point documentation | Pending Stage D | no evidence |
| 38 | Script ownership and path validator | Pending Stage D | no evidence |
| 39 | Updated test discovery configuration | Pending Stage D | no evidence |
| 40 | Complete regression test suite | Partial: ~75 Python modules, targeted run green | tool |
| 41 | Baseline benchmark results | Partial: byte/line proxy only; token metrics unavailable | tool |
| 42 | Post-refactoring benchmark results | Pending Stage N | no evidence |
| 43 | Skill effectiveness results | Pending Stage N | no evidence |
| 44 | Instruction adherence results | Pending Stage N | no evidence |
| 45 | Updated workflow integration | Pending Stage L | no evidence |
| 46 | Agent canary validation | Pending Stage N | no evidence |
| 47 | Migration and rollback documentation | Pending Stage M | no evidence |
| 48 | Updated project documentation | In progress: this directory | tool |
| 49 | Technical debt and remaining risks report | Pending Stage N | no evidence |
| 50 | Final independent audit report | Pending Stage N | no evidence |

## Blocker summary

Three durable decisions conflict with `orca-refactor.md` and are recorded in `.opencode/config/MEMORY.md`: the removed model router and force flag, the requirement to work on `main` on this PC rather than in worktrees, and this session's prohibition on Git and Git-backed operations. Deliverables 22 to 25 and all live GitHub/merge evidence remain blocked until the owner rules on them.
