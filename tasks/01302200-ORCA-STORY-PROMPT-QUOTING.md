- [x] ORCA-STORY-PROMPT-QUOTING · Quote workflow-command markers in untrusted story prompt text
  - evidence: Red `Ran 123 tests in 65.718s`, `FAILED (failures=2)` with raw `##[error]`/`::error::` in the prompt log; green `Ran 123 tests in 67.431s`, `OK`; full suite `Ran 1745 tests in 123.595s`, `OK`; `bash scripts/python-coverage.sh` → `OK`, `{"violations": []}`, `python coverage: every changed script line is covered`; `shellcheck scripts/agent-run.sh`, `check-constraints.py`, `check-docs.py` exit 0. Verdicts: plan GO (after two NO-GO rounds), code GO (two rechecks), coverage GO, devsecops GO. Evidence source: tool commands, read-only reviewers and the owner's earlier authorized finding; no simulated user or human participant.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-034, AG-34, WF-34
  - scope: scripts/agent-run.sh, scripts/tests/test_agent_run.py, tasks/01302200-ORCA-STORY-PROMPT-QUOTING.md
  - details: DevSecOps found a residual injection surface after story 01302100: `STORY_TITLE`, `STORY_BODY` and `FIX_COMMENT` are interpolated into agent prompts at `scripts/agent-run.sh:415-416,464-466` and echoed into Actions logs. A task title or body beginning with `##[` or `::` can still create false failure annotations. Quote those lines once before either prompt is built. Do not change the clean-worktree guard, gates or story semantics.

## Loaded skills
- aurevia-delivery — mandatory story loop.
- aurevia-senior-shell — shell change in `scripts/agent-run.sh`.
- aurevia-test-engineer — red test first.
- aurevia-plan-reviewer — plan before coding.
- aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops — verdicts after green tests.

## Plan
1. Red first: add a test in `scripts/tests/test_agent_run.py` that runs `implement` with `STORY_TITLE='##[error]bad title'`, `STORY_BODY=$'::error::bad body\nplain line\n\n'`, `FIX_COMMENT='  ##[warning]indented'` and a prompt log. Assert the rendered prompt contains `> ##[error]bad title`, `> ::error::bad body`, the indented form `  > ##[warning]indented` (sed keeps the captured whitespace before `> `), the unchanged `plain line` and the trailing blank lines, and that no line's stripped form starts with `##[` or `::`. A second assertion on a default `implement` run checks that no "Requested change:" line appears when `FIX_COMMENT` is unset.
2. Add one shell helper `quote_command_lines()` in `scripts/agent-run.sh` with a header comment explaining that untrusted story text is echoed into Actions logs. Implementation: `printf '%s' "$1" | sed -E 's/^([[:space:]]*)(##\[|::)/\1> \2/'`. `printf '%s'` (not `%s\n'`) and sed's final-newline behaviour preserve bytes. The only command substitution is in the caller below; its sentinel prevents that substitution from stripping trailing blank lines. Do not add an inner unguarded `$(...)` to the helper.
3. Reassign the same globals with empty-safe inputs at the start of `cmd_implement`, before `make_brief` and `story_prompt` build any prompt, using an outer sentinel that is stripped immediately: `STORY_TITLE=$(quote_command_lines "${STORY_TITLE:-}"; printf X); STORY_TITLE=${STORY_TITLE%X}` and the same for `STORY_BODY` and `FIX_COMMENT`. The outer sentinel prevents the outer command substitution from stripping trailing newlines; empty input produces `X` and becomes empty again, so `${FIX_COMMENT:+...}` still omits the line. Do not use `local` copies, because lines 415-416 and 464-466 read the globals.
4. Scope limit: this story guarantees the three named variables. `$out/issue-context.md` is already neutralised by `clean()` in story 01302100; the extracted advisor BRIEF is generated text, not raw untrusted story content.
5. Gates: the new test and `test_agent_run.py -q`; full Python suite; `bash scripts/python-coverage.sh`; `shellcheck scripts/agent-run.sh`; `check-constraints.py`; `check-docs.py`. No threshold, exclusion, pragma or skip.

## Coverage
Command: `bash scripts/python-coverage.sh` → `Ran 1745 tests in 117.180s`, `OK`, `{"violations": []}`, `python coverage: every changed script line is covered`. No production Python line or branch changed. Shell helper: one `sed` substitution with a leading-whitespace capture; `> ##[error]bad title` and `> ::error::bad body` cover both regex alternatives, `  > ##[warning]indented` covers the capture, `plain line\n\n` covers the no-match and trailing-blank-line paths, and the default test covers empty input.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_agent_run.py' -q` → `Ran 123 tests in 65.718s`, `FAILED (failures=2)`; the prompt log showed raw `##[error]bad title` and `::error::bad body`. The first red attempt also showed 21 transient DirectModelEnvironment failures from a polluted run; a clean repeat isolated the two intended failures.
- (tool) Green: `test_agent_run.py` → `Ran 123 tests in 69.462s`, `OK`; full suite `Ran 1745 tests in 120.006s`, `OK`; `bash scripts/python-coverage.sh` → `Ran 1745 tests`, `OK`, `{"violations": []}`, `python coverage: every changed script line is covered`; `shellcheck scripts/agent-run.sh` exit 0; `check-constraints.py` and `check-docs.py` exit 0.
- (tool) After the reviewers' optional suggestions, the quoting test now covers both prompt builders (plain and `SWARM=1` briefed) with distinct run numbers to avoid a fixture branch collision; both variants assert the quoted text and preserved trailing blank lines.
- (tool) No production Python changed, so Python changed-line coverage is unaffected; the shell helper has no `if`/`case` branch, and the two regex alternatives plus the indented and trailing-blank-line cases are asserted by `test_untrusted_story_markers_cannot_become_workflow_commands`.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO twice; sentinel placement and indented expectation corrected and re-reviewed GO).
- aurevia-code-reviewer: GO (both prompt builders asserted after two rechecks).
- aurevia-coverage-auditor: GO (shell helper has no branch; both regex alternatives, indentation, trailing blanks and empty input asserted).
- aurevia-devsecops: GO (no bypass by indentation or sentinel; no secret or PHI; no gate weakened).
