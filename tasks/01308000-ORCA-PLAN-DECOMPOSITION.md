- [x] ORCA-PLAN-DECOMPOSITION · Add passive plan decomposition to the offline core
  - evidence: Red `Ran 48 tests in 0.006s`, `FAILED (errors=7)` → green `Ran 50 tests in 0.006s`, `OK`; module coverage 151 statements/74 branches 100%; full suite `Ran 1851 tests in 108.758s`, `OK`; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; plan/code/coverage/security reviewers GO. Evidence source: tool commands and read-only reviewers.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-064
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, tasks/01308000-ORCA-PLAN-DECOMPOSITION.md
  - details: Add one pure `split_plan(contract, classification, max_parts=8)` function per `docs/orca-refactor/11-task-intelligence-design.md`. One scope returns None; several scopes create one child per scope plus an integration child depending on all children, bounded by max_parts. No dispatch, tracker write, filesystem scan or worker spawning. Every new line and branch needs a named test; existing tests are preserved and the boundary test is extended with a no-dispatch assertion.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Red tests first in `scripts/tests/test_orca_core.py`, then implement `split_plan` in `scripts/orca_core.py` with a docstring for inputs, outputs, errors and the no-dispatch invariant.
2. Result contract: a tuple of dictionaries, each with keys `task_id, objective, acceptance, scope, risk, requirements, depends, skills, kind, components, expertise, validation`. `kind` is `scope` or `integration`. This is a Stage G consumer contract, so one test pins the full shape.
3. Inputs/rules: scope fragments come from `contract.scope` (sorted for determinism), not from `classification["components"]`; `classification` must be a dict containing `domain, type, components, expertise, validation, complexity, risk, clinical, security` with the expected types, otherwise ValueError `invalid classification`. A contract with non-empty `problems()` raises ValueError `incomplete contract`. One scope fragment returns None before any cap check. Child `objective`: scope children use `"<contract.objective> — <scope fragment>"`; the integration child uses `"Integrate <contract.objective>"`. Both are asserted by the full-shape test.
4. Child ids are `<parent>-P1`, `<parent>-P2`, … and `<parent>-INT`; `scope` holds one path fragment for scope children and all fragments for the integration child. No ID-based path is ever used as a scope value (`tasks/_head.md:19`). Acceptance is distributed round-robin from `contract.acceptance` across scope children in sorted scope order; when there are fewer acceptance items than scopes, remaining children get an empty tuple. The integration child's acceptance is the full contract acceptance. Overlapping scope fragments still yield one child per entry; empty acceptance yields empty child acceptance.
5. Inherited metadata: `requirements` and `skills` come from the contract (classify has no skills key), `risk` from the contract, and `components`/`expertise`/`validation` from the classification. The integration child's `depends` lists all scope child ids; scope children depend on nothing.
6. `max_parts` bounds total children including integration: if `len(contract.scope) + 1 > max_parts`, raise ValueError stating the cap; never silently drop work. Within the cap, all children are returned.
7. Branch map with named tests: full result shape; one-scope None; multi-scope children; integration deps list all child ids; max_parts within and over cap; incomplete contract; invalid classification (missing key and wrong type); deterministic order from unordered input; overlapping fragments; empty acceptance. `BoundaryTests` additionally asserts the split source contains no dispatch/spawn/launch call.
8. The no-dispatch assertion lives in `DecompositionTests.test_split_plan_source_has_no_dispatch_call` alongside the other decomposition tests, not in `BoundaryTests`.
9. Gates: targeted `test_orca_core*.py`, module coverage 100%, full Python suite, `bash scripts/python-coverage.sh`, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. No pragma, skip, exclusion or threshold change.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 48 tests in 0.006s`, `FAILED (errors=7)` (split_plan absent). Green after implementation: `Ran 50 tests in 0.006s`, `OK`.
- (tool) Module coverage: 151 statements, 0 missed; 74 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1851 tests in 108.758s`, `OK`.
- (tool) `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; all policy checks exit 0.
- (reviewer) Plan GO after three NO-GO rounds that defined the result shape, ID suffixes, acceptance slicing, inherited metadata, cap behaviour, classification validation and child objectives.

## Coverage (branch-to-test map)
- Full child shape and objectives → `DecompositionTests.test_split_plan_pins_the_full_child_shape`.
- One-scope None, cap within/over, incomplete contract, invalid classification missing key and wrong type, deterministic order, overlapping scope with fewer acceptance items → `test_split_plan_*` named tests.
- No dispatch/spawn/launch call in the function AST → `test_split_plan_source_has_no_dispatch_call`.
No pragma, omit, skip, exclusion or threshold change.

## Review verdicts
- aurevia-plan-reviewer: GO (three NO-GO rounds resolved before code).
- aurevia-code-reviewer: GO (return shape and passive boundary verified).
- aurevia-coverage-auditor: GO (all branches named; non-dict guard rechecked).
- aurevia-devsecops: GO (no dispatch, subprocess, network, tracker write or path traversal).
