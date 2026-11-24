- [-] PR-NOTES-PATH-MIGRATION · Complete the worker-notes and daily-report path migration
  - agent: orca
  - branch: main
  - requirements: OPS-06
  - scope: docs/pr/notes, docs/pr/reports, .agent, .gitignore, .github/workflows, .opencode, scripts, scripts/tests, tasks, docs
  - details: The user moved all 990 `docs/pr/notes/*` files verbatim into `docs/pr/reports/` and deleted `docs/archive/`. Decision: raw worker notes (`<task-id>-<timestamp>.md`, fix variants and hand-off notes) live in `docs/pr/notes/`; reconciled daily reports (`<YYYY-MM-DD>.md`) live in `docs/pr/reports/`. Move the raw notes, then update every reference in notes, instruction files, skills, scripts, tests, workflows and trackers. Repair links to deleted `docs/archive/*` files without weakening checks. Verify with docs, Python tooling, coverage and workflow checks.

## Progress
- [x] New path and file mapping verified (byte-identical moves)
- [ ] Raw notes sorted into docs/pr/notes/
- [ ] References updated
- [ ] Checks green
- [ ] Reviews recorded
