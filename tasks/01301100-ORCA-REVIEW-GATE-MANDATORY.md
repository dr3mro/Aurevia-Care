- [-] ORCA-REVIEW-GATE-MANDATORY · Remove the review-verdict bypass from the quality gate
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: AG-19, ORCA-040
  - scope: scripts/quality-gate.sh, scripts/tests/test_quality_gate.py, docs/WORKFLOW.md, tasks/01301100-ORCA-REVIEW-GATE-MANDATORY.md
  - depends: none
  - details: `scripts/quality-gate.sh:14-15` skips `check-verdicts.py` when `SWARM_REVIEW_GATE=off`, and `docs/WORKFLOW.md:11` documents the switch. No workflow sets it, so removal has no live caller. Make the verdict gate unconditional, update the documentation, and add a regression test that proves the bypass token is gone and the checker is invoked on every run. Do not change any threshold, exclusion or coverage rule.

## Loaded skills
- aurevia-delivery — mandatory delivery loop.
- aurevia-senior-shell — shell gate change.
- aurevia-test-engineer — red test first, each branch mapped.
- aurevia-plan-reviewer — reviewed before coding in task 01301000's NO-GO context; this change is a one-line removal with no new branch.
- aurevia-code-reviewer, aurevia-devsecops — independent verdicts after green tests.

## Plan
1. Write a failing source-level test in `scripts/tests/test_quality_gate.py` asserting `scripts/quality-gate.sh` contains no `SWARM_REVIEW_GATE` and always invokes `scripts/check-verdicts.py`.
2. Remove the conditional from `scripts/quality-gate.sh` and its comment about the switch.
3. Update `docs/WORKFLOW.md:11` to state that the verdict gate is mandatory.
4. Run the new test, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-constraints.py`, `shellcheck scripts/quality-gate.sh`; record final lines.

## Evidence log
- (tool) `rg -n SWARM_REVIEW_GATE .` → only `docs/WORKFLOW.md:11`, `scripts/quality-gate.sh:7,14` and two task files. No workflow sets the variable.
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_quality_gate.py' -v` → `Ran 2 tests`, `FAILED (failures=2)` before the change. Green after: `Ran 2 tests`, `OK`.
- (tool) `shellcheck scripts/quality-gate.sh` exit 0; `python3 -I scripts/check-docs.py` exit 0; `python3 -I scripts/check-constraints.py` exit 0.
- (tool) No production Python line or branch changed; the new file is a test only, so JaCoCo/Vitest/Jest and `python-coverage.sh` changed-line coverage do not apply. The removed shell conditional had two sides; the retained unconditional invocation is asserted by `test_the_verdict_checker_is_always_invoked`, and `test_no_review_bypass_remains` asserts the retired token is absent.
- (tool) After review, a behavioural test was added at the reviewers' suggestion: a temp checkout with a stubbed `python3` on PATH runs the gate with `SWARM_REVIEW_GATE=off` and asserts both checks run and the verdict status propagates. `python3 -I -m unittest discover -s scripts/tests -p 'test_quality_gate.py' -v` → `Ran 3 tests`, `OK`; `shellcheck scripts/quality-gate.sh` exit 0.

## Review verdicts
- aurevia-code-reviewer: GO — unconditional `set -e` gate, no bypass token, docs match, no weakened rule.
- aurevia-coverage-auditor: GO — red 2 failures observed, every assertion asserts an outcome, no production Python branch added, removed guard has no coverage obligation.
- aurevia-devsecops: GO — no secret/PHI, no gate weakening, reviewer independence strengthened; no live caller of the retired switch.
