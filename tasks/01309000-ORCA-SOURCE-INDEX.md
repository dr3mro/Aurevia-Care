- [-] ORCA-SOURCE-INDEX · Add a passive repository source index to the offline core
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-065
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, tasks/01309000-ORCA-SOURCE-INDEX.md
  - details: Implement `index_sources(files, extractors)` and `stale_entries(index, files)` per `docs/orca-refactor/11-task-intelligence-design.md`. Pure over injected path→text and extractor callables; no filesystem scan, Git, tracker write or dispatch. Reuse `evidence_fresh()` for revision comparison. Every new line and branch needs a named test; existing tests preserved.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Red tests first in `scripts/tests/test_orca_core.py`, then implement both functions with docstrings for inputs, outputs, errors and the no-I/O invariant.
2. `index_sources(files, extractors)`: `files` is a dict of path string → injected text string; `extractors` is a dict of kind string → callable taking text and returning an iterable of id strings (patterns from `check-names.py` and `check-docs.py` are referenced, not copied). For each path in sorted order, `hash` is `sha1(text.encode('utf-8')).hexdigest()[:12]`; `kind` is the first sorted extractor name whose callable returns at least one id, otherwise `"unknown"`; `ids` is a sorted tuple of unique returned ids, empty when no extractor matches. Output is a dict path → `{"hash": str, "kind": str, "ids": tuple}` with sorted keys. Non-dict inputs, non-string path/text, a non-callable extractor or a raising extractor raise ValueError.
3. `stale_entries(index, files)`: `files` here is a dict of path string → current revision string supplied by the caller (no Git or filesystem read). For each indexed path: absent from current files → removed; present but `evidence_fresh(indexed_hash, current_revision)` false → stale. Current paths absent from the index are added. Output is `{"stale": tuple, "added": tuple, "removed": tuple}`, each sorted. Empty inputs return three empty tuples. A test patches `evidence_fresh` to prove the comparison is reused rather than a fresh `==`.
4. Red test list, written before implementation: index shape and fixed digest; kind/ids derivation from two extractors; no-match extractor yields `"unknown"` and empty ids; raising extractor and non-callable extractor; non-string path/text; duplicate/reordered input determinism for both files and extractors; stale equal and different revisions with patched `evidence_fresh`; added; removed; empty input. The module-wide `BoundaryTests` AST scan already enforces the no-I/O invariant for new functions; no parallel boundary test is added.
5. Gates: targeted `test_orca_core*.py`, module coverage 100%, full Python suite, `bash scripts/python-coverage.sh`, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. No pragma, skip, exclusion or threshold change. Evidence is recorded in this task file and a worker notes file.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 57 tests in 0.007s`, `FAILED (errors=7)` (new functions absent). Green: `Ran 59 tests in 0.006s`, `OK`.
- (tool) Module coverage: 194 statements, 0 missed; 104 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1860 tests in 108.514s`, `OK`.
- (tool) `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; all policy checks exit 0.
- (reviewer) Plan GO after eight Required gaps defined the index shape, digest, extractor contract, errors, stale_entries semantics, added/removed handling and red-test list.

## Coverage (branch-to-test map)
- `index_sources`: shape and fixed digest, sorted ids and first matching extractor, no-match default, non-dict files/extractors, non-string path/text, non-callable and raising extractor, mixed key types, bad extractor ids, generator-returning extractor, reordered-input determinism → `IndexTests.test_index_sources_*`.
- `stale_entries`: patched `evidence_fresh` reuse, equal/different revisions, added, removed, empty input → `IndexTests.test_stale_entries_*`.
- Module-wide boundary AST scan still enforces no launch/Git/process API for the new functions.
No pragma, omit, skip, exclusion or threshold change.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with eight Required gaps; revised plan re-reviewed GO).
- aurevia-code-reviewer: pending recheck after validation-order fix.
- (pending) aurevia-coverage-auditor: —
- (pending) aurevia-devsecops: —
