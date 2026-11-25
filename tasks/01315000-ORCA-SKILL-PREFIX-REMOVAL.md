- [-] ORCA-SKILL-PREFIX-REMOVAL · Atomic removal of the `aurevia-` skill prefix
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-081, AG-31
  - scope: .opencode/skills, .opencode/agents, .opencode/setup/AGENTS.md, .opencode/config/MEMORY.md, .opencode/config/README-SETUP.md, tasks/_head.md, docs/orca-refactor/01-inventory.md, docs/orca-refactor/05-instruction-skill-design.md, docs/SWARM.md, docs/WORKFLOW.md, README.md, orca-refactor.md, scripts/agent-run.sh, scripts/check-pack.py, scripts/check-verdicts.py, scripts/route-task.py, scripts/gen-opencode.py, scripts/tests, tasks/01315000-ORCA-SKILL-PREFIX-REMOVAL.md, .opencode/config/WORKSPACE.md
  - details: Owner decision 2026-11-25 (answer "2"): remove the `aurevia-` prefix from all 39 project skills. Atomic change: rename directories, rewrite frontmatter names and cross-references, update agent load lines, instructions, routing/checkers/tests and regenerate both configs. Keep `run-workflow` and all stable technical identifiers (agents, repository, image, bundle IDs) unchanged. Historical notes/reports and `apps/` are immutable. No Git operation in this session.

## Loaded skills
- aurevia-delivery (mandatory), skill-creator (skill modification), aurevia-senior-python (checker and test changes); loaded before the rename because the advertised IDs change with the directories.

## Plan
1. Red tests first: change checker/routing/verdict tests to expect bare skill IDs, observe failures, then perform the atomic rename.
2. Rename 39 skill directories from `aurevia-<name>` to `<name>`; rewrite frontmatter `name:` and every project-owned reference to a skill ID in `.opencode/`, `scripts/`, active docs, `_head.md` and trackers. Do not touch project identity strings (`aurevia-care`, image names, `aurevia.sh`), historical notes/reports or `apps/`.
3. Keep dual lookup for compatibility: `route-task.py` already accepts both forms; `check-verdicts.py` will accept optional `aurevia-` in verdict lines so historical evidence stays valid; `check-pack.py` will accept bare names and require the exact specialist-plus-delivery inventory.
4. Regenerate `.opencode/opencode.json` and `.opencode/opencode.ci.json`; validate with `gen-opencode.py --check`, `--check-runtime`, `check-pack.py`, `check-docs.py`, `check-constraints.py`, `check-names.py`, `check-ownership.py`, full Python suite and `python-coverage.sh`. Fresh-session skill activation cannot be proved in this session and will be recorded as an integrator hand-off.
5. Four reviewer verdicts GO; record all commands and final lines in the task evidence; no commit or PR.

## Evidence log
