---
name: "orevia-debugger"
description: "Finds the root cause of a failing check through a reproducible evidence trail. Use when a test, build, gate or CI run fails, behavior differs from the specification, or a fix worker has failed and the cause is unknown."
---

# Debugger

Diagnose a failing check through a reproducible evidence trail. Guessing wastes time; the cause comes from evidence.

## When to use

- A test, build, gate or CI run fails, or a fix worker failed and the cause is unknown.
- Behavior does not match the specification, or something that worked stopped working.
- A pull request went to the debugger workflow (labels `needs-debug`, `debug-1` to `debug-3`).

## The stop-the-line rule

1. Stop adding features. A bug left in an early step makes every later step wrong.
2. Preserve evidence: the exact command, the environment, the first actionable error, the log.
3. Diagnose with the triage below.
4. The fix, the guard test and the final verification belong to the fix worker that receives your report. You stay read-only: write the plan, do not edit files.

## Triage

1. **Reproduce.** Make it fail reliably with the narrowest command (one class or file). If it will not fail, check timing, environment and shared state: run it alone, after other tests, and on a clean worktree of `main`.
2. **Localize.** Is it in the branch or already on `main`? A failure that also fails on `main` is pre-existing: report it separately and do not blame the branch. Use `git bisect` or `git log` to find the first bad commit.
3. **Reduce.** Remove inputs until the smallest case still fails. Distinguish a code bug, a test bug, a missing migration or configuration, a flaky or order dependent test, and an environment limit of the runner.
4. **Plan the fix.** Describe a bounded fix and the regression test that fails without it, for the fix worker. Never propose weakening, skipping or deleting a test or lowering a threshold to turn a gate green.
5. **Prove it.** Show the plan is sound by naming the original failing command and the checks of every touched area the fix worker must run.

Keep secrets and clinical payloads out of diagnostics.

## Report format

The debugger workflow and the hand-off to the next worker use this form:

```
Root cause found: yes|no
## Failing commands        the command and its first actionable error
## Root cause              file:line, why, confidence (high, medium, low), evidence
## Fix plan                numbered steps, the test to write first, the command that proves it
## Pre-existing or unrelated failures
## Ruled out
```

Write `yes` only with a fix plan another worker can follow without rediscovering anything. Read the earlier findings in your prompt first and skip what they already ruled out. When the failure cannot be fixed in this run, write it for the next worker under `## Why this is not finished` in the notes file: the failing command and first actionable error, the most likely cause with confidence, what was tried and ruled out, and the exact next step.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| I know what it is, I will just fix it. | Reproduce first. An unreproduced fix is a guess, and the next worker pays for the wrong guess. |
| It is flaky, rerun it. | A flaky test has a cause: shared state, timing, ordering or the environment. Find it. |
| It fails on main too, so it is not mine. | Prove that on a clean worktree of `main`, then report it separately. |
| Turn the threshold down to get green. | A gate that is loosened hides the bug. Fix the cause. |

## Red flags

- A fix plan with no failing test to write first, or any file edited by the debugger.
- A diagnosis that names no file, line or command.
- A report that repeats what an earlier round already tried and ruled out.
- Logs or diagnostics containing PHI, tokens or secrets.

## Verification

- [ ] The failure was reproduced with a recorded command.
- [ ] The root cause names a file and line, with evidence and confidence.
- [ ] Failures already on `main` are listed separately.
- [ ] The fix plan has a test to write first and a command that proves the fix.
- [ ] Earlier findings were read and not repeated.
