- [x] ANNOTATION-INJECTION · Stop untrusted findings from creating failure annotations
  - evidence: Red `Ran 362 tests in 10.485s`, `FAILED (failures=2)` → green `Ran 362 tests in 11.466s`, `OK`; full suite `Ran 1741 tests in 101.691s`, `OK`; `bash scripts/python-coverage.sh` → `Ran 1741 tests in 103.397s`, `OK`, `{"violations": []}`, `python coverage: every changed script line is covered`; `check-constraints.py` and `check-docs.py` exit 0. Verdicts: plan GO (after two Required fixes), code GO, coverage GO, devsecops GO. Evidence source: tool commands, read-only reviewers and the owner's supplied run ids; no simulated user or human participant walkthrough.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm.py (or test_swarm_delivery_state.py), tasks/01302100-ANNOTATION-INJECTION.md
  - details: Run 45499386305 is queued with 52 successful jobs and no failures, but check run 138819781979 has false `annotation_level=failure` on `.github` at line 23133. Cause: untrusted `.agent/issue-context.md` text containing literal `##[error]Process completed with exit code 1.` is interpolated into agent prompts at `scripts/agent-run.sh:418,480` and echoed into the Actions log, where GitHub parses it as a workflow command. Neutralise `##[` and `::` line prefixes in the shared `clean()` helper (`scripts/swarm.py:403-406`), used by `issue_context()` and `pr_context()`, and add a content assertion regression test. Do not touch the clean-worktree guard or any gate threshold.

## Plan
1. Red first: add a test near `test_swarm.py:1557` using the existing `history`/`comment` helpers. Each trusted comment body must include `swarm.MARK` so `pr_context` actually selects it. Bodies contain `##[error]Process completed with exit code 1.`, `::error::...` and an indented `  ##[error]...` case. Assert the rendered lines are readable with `> ` in front, that no output line's stripped form begins with `##[` or `::`, and that a token on a quoted command line is still masked.
2. Change `clean()` in this exact order: build `tail` with the existing `[-lines:]` and `[-limit:]`, then prefix each line whose `lstrip()` starts with `##[` or `::` with `> `, then `mask()`. Quoting after truncation prevents a long command line from losing its prefix and reintroducing a column-zero `##[`. Add a boundary test whose retained tail starts with a command prefix. Keep mask and tail semantics unchanged for all other input. Add a doc comment explaining that untrusted issue/PR text is quoted into prompts and echoed into Actions logs, where those prefixes would be parsed as workflow commands.
3. Gates (exact): `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -q` (red then green), `python3 -I -m unittest discover -s scripts/tests -q`, `bash scripts/python-coverage.sh`, `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-docs.py`. No threshold, exclusion, pragma or skip.
4. One shared helper is enough: `clean()` feeds `issue_context()` and `pr_context()`, and both context files are written to `.agent/` (`swarm.py:6540,6548`). No second helper or new file.

## Coverage (branch-to-test map)
- Line with `##[` prefix → new regression test.
- Line with `::` prefix → same regression test.
- Indented command prefix → same regression test.
- Masked token on a quoted command line → same regression test.
- Retained tail beginning with a command prefix → boundary regression test.
- Line without a command prefix → all existing `clean()`/`issue_context()`/`pr_context()` tests.

## Evidence log
- (tool, 2026-11-24) `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -q` → `Ran 360 tests in 10.864s`, `OK` (baseline before the Story 2 change).
- (authorized human, 2026-11-24) Owner supplied the run and check-run ids and the exact root cause; no `gh` call was made in this session to inspect or write them. The false annotations themselves cannot be cleared without a trusted GitHub job.
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -q` → `Ran 362 tests in 10.485s`, `FAILED (failures=2)`: both new tests failed before `clean()` was changed. Green: `Ran 362 tests in 11.466s`, `OK`.
- (tool) During the first green attempt the indented-body assertion was corrected: `pr_context` calls `.strip()` on each comment body before `clean()`, so the rendered line is `> ##[warning]...` rather than `>   ##[warning]...`. Raw indentation is now asserted directly against `clean()`; the context assertion still proves the text is readable and no output line begins with a command marker.
- (tool) Gates: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1741 tests in 101.691s`, `OK`; `bash scripts/python-coverage.sh` → `Ran 1741 tests in 103.397s`, `OK`, `{"violations": []}`, `python coverage: every changed script line is covered`; `check-constraints.py` and `check-docs.py` exit 0. No shell script changed.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with three Required gaps, all fixed and re-reviewed GO).
- aurevia-code-reviewer: GO (all 17 `clean()` callers checked; non-command text unchanged).
- aurevia-coverage-auditor: GO (every new branch named and covered; no pragma/skip/exclusion).
- aurevia-devsecops: GO (leading whitespace, truncation boundary and masking cannot reintroduce a command prefix).

