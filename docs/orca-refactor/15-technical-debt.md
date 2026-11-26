# Technical debt and remaining risks (ORCA refactor)

Deliverable 49 of `orca-refactor.md` section 20. Evidence source labels follow
`docs/orca-refactor/README.md`: tool (command/file read in this checkout),
reviewer (read-only specialist), human (owner directive in `MEMORY.md`). No live
Git/GitHub evidence exists in this session, so every live item stays disclosed.

## Owner decisions that narrow the directive (human, `MEMORY.md` 2026-11-24/25)

- Direct `SWARM_MODEL` only; no router, force flag, pricing registry, ranking or
  failover. ORCA-032/033/069/141 and WF-22/23 are NOT APPLICABLE, not "pending".
- Work stays on `main` on this PC; no worktree-isolation requirement.
- A single offline ORCA analysis module is the agreed vehicle for the remaining
  passive items; it never launches agents, writes swarm state or replaces
  `scripts/swarm.py`, `scripts/route-task.py` or `scripts/agent-run.sh`.
- Git/GitHub execution was granted by the owner but this session's runtime denies
  `git`, `gh` and GitHub API calls. Publication and exact-SHA verification are a
  trusted-integrator hand-off, not a local defect.

## Debt and risks by area

| Area | Item | Evidence source | Risk / next bounded action |
|---|---|---|---|
| Reviewer provenance | AG-22 (`scripts/check-verdicts.py:3-5,39-41,74-82`) accepts verdict lines authored by the worker | reviewer | A worker can manufacture its own GO. Needs a trusted runner to bind verdicts to the reviewer identity; task 01301200 stays blocked. |
| Permission breadth | `scripts/gen-opencode.py:81` grants `orca` wildcard shell; literal deny globs at `:51-60` miss equivalent spellings (AG-06/07) | reviewer | Replace with an explicit allow list plus canonicalisation test; owner policy decision on the allow set. |
| Runtime config swap | `scripts/agent-run.sh:289-310,324-328` changes tracked config with `skip-worktree` (AG-16/29, OPS-CW-003-01) | reviewer | Confirmed false-positive class; correction belongs to owner policy, not a local code change. |
| Script ownership | `.github/script/` and `.opencode/scripts/` absent; 24 Python + 17 Bash flat in `scripts/` (ORCA-051..063) | tool | Three-root migration changes many callers, generated config and workflows; needs owner layout ruling and one atomic PR. Current structure/entry points recorded in `14-script-ownership-structure.md`. |
| Test environment | macOS bash 3.2 rejects `${var^^}` in hermetic fixtures (AG-17); full Python gate red on an unpatched base | tool/targeted | Hermetic fixtures need bash-3.2-compatible syntax; full-suite green can only be claimed with the recorded Python 3.15 + clean environment. |
| Skill naming | ORCA-081/082 require removing the `aurevia-` prefix; advertised routing in this session uses those IDs | reviewer | Atomic rename needs regenerated config and every reference updated; staged, not started, pending owner ruling. |
| Benchmarks | Tokens, duration, invocation counts, skill activation, first-attempt success, conflicts and CPU/memory are unavailable (no tokeniser, no live runs) | tool/reviewer | `scripts/orca_benchmark.py` returns `"unavailable"` and labels percentages as targets. Only byte/line/test-count proxies exist; never present a proxy as a measurement. |
| Feature completeness | ORCA-101/112 satisfied; ORCA-103/104 need a Git base for `check-doc-sync` and OpenAPI generation checks | tool/reviewer | Local source checks exist; live contract-sync and gate enforcement remain blocked without Git/apps authority. |
| Concurrency runtime | Lease TTL and stale-worker fencing absent (`scripts/swarm.py:2976-3006`, `:3049`); critical-path, capability match, cancellation, starvation and replay are design-only (ORCA-108/117/129-140) | reviewer | Passive helpers can be added to the offline core; runtime scheduling needs canary and live merge evidence on a trusted runner. |
| False annotations | Untrusted `##[`/`::` lines were neutralised in `clean()` and story prompts (01302100/01302200) | tool/reviewer | Prevention is in place; the existing annotations on run 45499386305 cannot be cleared without a trusted GitHub job. |

## What is explicitly NOT debt to fix here

- Deliverables 22–25 (execution profiles, model routing, capability profiles,
  provider circuit breaker) conflict with the owner's direct-`SWARM_MODEL`
  decision. They are NOT APPLICABLE, not outstanding work.
- Worktree isolation clauses conflict with the owner's `main`-only decision.
- Any `apps/*` change, `tasks/*` tracker edit or workflow permission change is
  outside this refactor slice by the active execution request.

## Retest status

Each row's current evidence is recorded in `tasks/01300000` (read only for this
slice) and in the numbered documents under `docs/orca-refactor/`. Rows marked
"needs trusted runner" have no retest in this session and must not be closed.
