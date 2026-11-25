- [x] ORCA-IMPACT-ANALYSIS · Add passive impact analysis to the offline core
  - evidence: Red `Ran 67 tests in 0.010s`, `FAILED (errors=13)` → green `Ran 69 tests in 0.008s`, `OK`; module coverage 250 statements/142 branches 100%; full suite `Ran 1874 tests in 106.139s`, `OK`; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; four verdicts GO. Evidence source: tool commands and read-only reviewers.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-066
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, tasks/01311000-ORCA-IMPACT-ANALYSIS.md
  - details: Add one pure `impact(changed_paths, index, routed)` function consuming the source index from 01309000. Unknown paths go to `unmapped`, never dropped. No Git, file scan, dispatch, tracker write or production import. Every new line and branch needs a named test; existing tests preserved.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan
1. Red tests first in `scripts/tests/test_orca_core.py`, then implement `impact` in `scripts/orca_core.py` with a docstring for inputs, outputs, errors and the no-I/O invariant.
2. Inputs: `changed_paths` is an iterable of path strings; `index` is the output of `index_sources` (path → `{"hash", "kind", "ids"}`); `routed` maps a path to specialist-name strings. Invalid types raise ValueError: non-iterable paths, a bare string used as paths, a non-string path element, non-dict `index` or `routed`, and non-string routed names. Output is a dict with keys `consumers, entities, web, mobile, contracts, tests, security, clinical, unmapped, decisions`, each a sorted tuple of unique strings except `decisions`, which maps each distinct path to the sorted tuple of category names it matched.
3. Deterministic, case-sensitive, exact-fragment rules per changed path (all strings): `consumers` = routed names for that path plus ids from any index entry whose `ids` contain that path by exact equality (path-yielding extractor); `entities` = the path when it contains `db/migrations` or its own index entry has kind `database`; `web` = path contains `apps/staff-web`; `mobile` = path contains `apps/patient-mobile`; `contracts` = path contains `contracts/` or its own index entry has kind `contract`; `tests` = path contains `test`; `security` = path contains `auth` or `security`, or a routed name contains `security`; `clinical` = path contains `clinical`, or a routed name contains `physician` or `clinical`. The eight categories are intentionally non-exclusive; only `unmapped` is exclusive and is the complement: a path is unmapped exactly when it matched none of the eight. Each path's `decisions` tuple makes that invariant directly assertable; no path is dropped.
4. Docstring states non-exclusive categories, exclusive `unmapped`, case sensitivity and exact fragments. `classify()` keeps its own clinical/security signals because it reasons about task risk from routed specialists and validation text, whereas `impact` reasons about changed paths and index ids; the different inputs are documented, not duplicated into a second runtime rule.
5. Branch map with named tests: each of the nine output categories; `decisions` proving no path is dropped; unknown path to `unmapped`; empty `changed_paths` yields all empty tuples; duplicate/reordered paths deduplicate deterministically; every validation branch listed in step 2; consumers routed-only, reverse-only, both and neither; security's two `or` sides, clinical's three alternatives and contracts' two alternatives; matched, mismatched and missing-index cases for entities/contracts; multi-category path appears in each category; impact-specific AST passivity assertion.
6. Gates: targeted `test_orca_core*.py`, module coverage 100%, full Python suite, `bash scripts/python-coverage.sh`, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. No pragma, skip, exclusion or threshold change.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 67 tests in 0.010s`, `FAILED (errors=13)` (impact absent). Green after implementation: `Ran 69 tests in 0.008s`, `OK`.
- (tool) Module coverage: 250 statements, 0 missed; 142 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1874 tests in 106.139s`, `OK`.
- (tool) `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-pack.py`, `python3 -I scripts/gen-opencode.py --check`, `python3 -I scripts/check-ownership.py scripts/ownership.json` → each exit 0.
- (reviewer) Plan GO after seven Required gaps defined reverse ids, path-relative entities/contracts, unmapped complement, per-path decisions, validation branches, docstring contract and the classify difference.

## Coverage (branch-to-test map)
- `impact`: full output shape and decisions, each boundary category, exact reverse ids and index kinds, security/clinical name signals, unmapped complement and no-drop invariant, empty and deterministic input, six validation branches, impact-specific AST passivity → `ImpactTests.test_impact_*` named tests.
Named tests also cover the bare-string routed value, contract-kind path, `security` path fragment, physician and clinical routed names, and generator input. No pragma, omit, skip, exclusion or threshold change.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with seven Required gaps; revised plan re-reviewed GO).
- aurevia-code-reviewer: GO after generator and short-circuit fixes.
- aurevia-coverage-auditor: GO after all four short-circuit alternatives were named.
- aurevia-devsecops: GO (no I/O, dispatch, network or path traversal).
