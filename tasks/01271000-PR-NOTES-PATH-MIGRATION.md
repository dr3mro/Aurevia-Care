- [-] PR-NOTES-PATH-MIGRATION · Complete the worker-notes and daily-report path migration
  - agent: orca
  - branch: main
  - requirements: OPS-06
  - scope: .agents/notes, .agents/reports, .agent, .gitignore, .github/workflows, .opencode, scripts, scripts/tests, tasks, docs
  - details: The user moved all 990 `.agents/notes/*` files verbatim into `.agents/reports/` and deleted `docs/archive/`. Decision: raw worker notes (`<task-id>-<timestamp>.md`, fix variants and hand-off notes) live in `.agents/notes/`; reconciled daily reports (`<YYYY-MM-DD>.md`) live in `.agents/reports/`. Move the raw notes, then update every reference in notes, instruction files, skills, scripts, tests, workflows and trackers. Repair links to deleted `docs/archive/*` files without weakening checks. Verify with docs, Python tooling, coverage and workflow checks.

## Progress
- [x] New path and file mapping verified (byte-identical moves)
- [ ] Raw notes sorted into .agents/notes/
- [ ] References updated
- [ ] Checks green
- [ ] Reviews recorded

## `.agent/pr-notes` fix evidence (orca, interactive, 2026-11-24)

Scoped fix: the last worker note still tracked under the old scratch path was moved
byte-identically to `.agents/notes/START-18-20261124-041536.md` (`cmp` against
`git show HEAD:<old>` → identical); `.gitignore` gained an explicit
`/.agent/pr-notes/` rule and a comment; `scripts/agent-run.sh` no longer tells
workers to create the old folder; `scripts/tests/test_notes_files.py` gained two
regression tests. Core fix landed in commit e63cad04 alongside unrelated concurrent
`scripts/swarm.py` work; this entry tightens one assertion and records evidence.
No push or `gh` call was made by this session.

Tool evidence (exact commands and final lines):
- `git ls-files .agent/pr-notes` → empty (before the move it listed only `START-18-20261124-041536.md`).
- `git check-ignore -v .agent/pr-notes/probe.md` → `.gitignore:14:/.agent/pr-notes/	.agent/pr-notes/probe.md`.
- `bash -n scripts/agent-run.sh` → OK; `shellcheck scripts/agent-run.sh` → OK.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_notes_files.py' -q` → `Ran 5 tests ... OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_agent_run.py' -q` → `Ran 93 tests in 137.920s ... OK`.
- `python3 -I scripts/check-docs.py` → exit 0; `check-names.py` → exit 0; `check-wiring.py` → exit 0; `check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`.
- `bash scripts/python-coverage.sh` → exit 1 before the coverage assertion, from concurrent unrelated work (`test_swarm*`, `test_check_migrations*`, V99 migration: 9 failures, 8 errors of 1574 tests); no failure named a file in this scope. No production Python line or branch was added here, so there is no new production line to cover.

Review verdicts (reviewer evidence, bounded to this diff):
- aurevia-senior-shell: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

The code reviewer and coverage auditor asked for one Optional tightening: the
`assertIn` for the new path was also satisfied by a pre-existing comment, so it now
asserts the exact prompt fragment `(under .agents/notes/; the file is named after
your story`. No threshold, exclusion, skip or suppression was added. Evidence
sources: executed tool commands and reviewer reports; no simulated user or human
participant.
