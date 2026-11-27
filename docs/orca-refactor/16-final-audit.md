# Final audit report (ORCA refactor)

Draft evidence record for deliverable 50 and `orca-refactor.md` section 21. This
is **not** a completion claim: section 22 requires every mandatory requirement to
be resolved before overall success, and several are explicitly blocked. Evidence
source labels are tool, reviewer, simulated user and human per the deliverable
index; no simulated-user or live-human walkthrough exists for this refactor.

## 1. Executive summary

Stage A discovery and 13 of 16 child stories are green locally
(`tasks/01279000-WORKFLOW-AUDIT.md`, read only for this slice): two children are
incomplete on trusted-runner/owner prerequisites and one has not started. Full Python suite
`Ran 1920 tests in 126.800s`, `OK`; changed-line coverage
`python coverage: every changed script line is covered`; policy checks exit 0;
four verdicts GO per story. No commit, push or PR exists — the fixes are local
and the publication hand-off is to the trusted integrator. Owner rulings removed
the model-router requirements as NOT APPLICABLE and fixed `main`-only work; live
Git/GitHub evidence remains blocked by this session's runtime.

## 2. Requirement disposition

- ORCA-001..150: disposition table and per-stage status in `tasks/01279000-WORKFLOW-AUDIT.md`
  (source: tool + reviewer). Marked `[x]`, `[-]`, `[!]` incomplete (including blocked), `[N/A]` or `[ ]` there.
  The live status legend in `orca-refactor.md` is the human-readable index.
- AG-01..35: 35-item table in `tasks/01279000-WORKFLOW-AUDIT.md` sections "Stage A — security and
  permissions"/"Stage A final AG dispositions" (source: read-only devsecops and
  context-optimizer reviewers).
- WF-01..35: 35-item table in `docs/orca-refactor/08-workflow-disposition.md`
  (source: read-only github-workflow reviewer; live SHA verdict UNKNOWN).
- Every disposition cites tool or reviewer evidence. Blocked items stay blocked;
  owner-ruled items are NOT APPLICABLE with justification, not silently dropped.

## 3. Comparative performance

No comparative measurement is possible here. `scripts/orca_benchmark.py`
(`measure`, `record`, `ratio`) reports deterministic counts and returns the text
`"unavailable"` for tokens, duration, agent invocations, tool calls, duplicate
reads, skill activation and live first-attempt success. TARGETS are labelled as
targets, not measurements. The only proxies available are byte/line counts,
test counts and agent/skill counts recorded under `## Evidence log` in
`tasks/01300000`. Any percentage improvement claim would be fabricated.

## 4. Local repository reset investigation

No destructive reset was executed in this session and no user branch, stash or
worktree was removed. Older Gate-1 test artifacts (`test_agent_run.py`) failed
with the documented cross-test pollution signature
(`FAILED (failures=21, errors=3)`); the same suite passed under a clean
environment with the recorded Python 3.15 toolchain
(`Ran 123 tests in 63.605s`, `OK`). That is an environment artifact, not a
repository reset. Live runner-state evidence is unavailable.

## 5. Script migration report

No script was moved. `.github/script/` and `.opencode/scripts/` are absent; the
flat `scripts/` inventory (24 Python, 17 Bash, 2 JSON) and canonical entry points
are recorded in `14-script-ownership-structure.md` (tool evidence: `find`/`rg`
commands). The three-directory migration design is `07-script-ownership-design.md`;
the ownership validator `scripts/check-ownership.py` validates the declared
manifest only. Rollback proof requires a Git base, so it is not claimed.

## 6. Security and workflow report

Source-level findings (reviewer): untrusted `##[`/`::` lines are neutralised in
`clean()` and story prompts (01302100/01302200), preserving annotation integrity;
the `SWARM_REVIEW_GATE=off` bypass is removed (01301100); write tokens stay out of
untrusted-output steps in every workflow path read; queueing gates use
`queue: max` with `cancel-in-progress: false` (asserted by
`test_workflow_concurrency.py`); `delivery.yml` has no path filter. Unresolved:
AG-22 verdict provenance (needs a trusted runner), wildcard shell permission in
`gen-opencode.py:81`, runtime config swap under `skip-worktree`, and the false
annotations on run 45499386305 (prevention in place; clearing needs a trusted
GitHub job). Exact-SHA checks, ruleset state, merge queue and merge records are
UNKNOWN in this session.

## 7. Final implementation references

Local artifacts only: `tasks/01300000` (checkpoint and disposition tables);
`docs/orca-refactor/01`–`16` (inventories, designs, dispositions, structure,
technical debt); `scripts/orca_core.py`, `orca_memory.py`, `orca_benchmark.py`
and their tests; `scripts/check-ownership.py` and `scripts/ownership.json`.
No commit SHA, pull request, tag or container digest exists in this session, so
none is listed. Section 22's "Documentation is accurate" is met for these local
artifacts; "Remaining limitations and blockers are disclosed" is met here.

## Audit verdict

Cannot be GO while mandatory requirements remain unresolved. The blocking set
is: live Git/GitHub verification (exact SHA, merge queue, branch protection,
annotation clearing), AG-22 reviewer provenance, owner rulings for the skill
prefix rename and any three-root script migration, and human sign-off for live
enablement. Everything locally completable is either green or recorded as a
bounded next action in `15-technical-debt.md`.
