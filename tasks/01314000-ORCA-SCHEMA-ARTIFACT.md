- [x] ORCA-SCHEMA-ARTIFACT · Document and pin the task contract and context manifest schema
  - evidence: Red `Ran 10 tests in 2.712s`, `FAILED (failures=1, errors=6)` → final targeted `Ran 12 tests in 3.031s`, `OK`; full suite `Ran 1920 tests in 126.800s`, `OK`; `python3 -I scripts/check-docs.py` exit 0; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; all six policy checks exit 0; four verdicts GO. No production code changed. Evidence source: tool commands and read-only reviewers.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-018, ORCA-019, ORCA-020
  - scope: docs/orca-refactor/13-orca-schema.md, scripts/tests/test_orca_schema_doc.py, tasks/01314000-ORCA-SCHEMA-ARTIFACT.md, docs/orca-refactor/README.md, docs/orca-refactor/11-task-intelligence-design.md
  - details: Close the gap identified in `12-quality-gap-analysis.md:27`: the helpers exist but no schema document or worked manifest artifact does. Document the actual `TaskContract`, `compile_context` manifest and `preflight` output, and pin the document to real helper behavior with a regression test. No production code, gate, threshold, workflow or `apps/` change; no runtime dispatch or preflight enforcement claim.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Red tests first in `scripts/tests/test_orca_schema_doc.py`, then write `docs/orca-refactor/13-orca-schema.md` with a worked synthetic manifest and preflight example extracted from real helper output. Status-only edits to `README.md` rows 14/18/19/20 and `11-task-intelligence-design.md`. The reviewer saw the files already present because the parallel scheduling wrote them before its verdict; the intent of this step is the recorded red-then-green sequence, and the current pair is now being verified and hardened rather than recreated.
2. The test loads `orca_core` through `helpers.load()`, builds a synthetic frozen `TaskContract` with only synthetic identifiers and paths, and asserts: documented manifest key set equals `compile_context()` keys; the worked JSON artifact equals the real manifest for the same synthetic inputs; the stable hash in the document is reproduced; mandatory sections are never truncated while optional sections honour positive budgets and stay whole for non-positive budgets; documented `preflight()` problem strings equal the real list for missing tool, instruction and skill inputs.
3. The document states that it is analysis-only evidence: no runtime context is handed to an agent, no preflight gate is enforced and no task is dispatched. All paths and identifiers in the worked artifact are synthetic (no real repository path, patient data or credential). Every requirement ID resolves and every link is valid under `python3 -I scripts/check-docs.py`.
4. Branch map with named tests: missing schema document, missing fenced artifact, invalid JSON via a text-accepting extractor, unambiguous single heading/language, manifest key mismatch, manifest value/hash mismatch, mandatory-not-truncated for both `safety` and `execution`, optional budget positive, zero and negative, preflight problem strings, synthetic-fixture safety, docs checker remains green, existing tests preserved. `python-coverage.sh` omits `scripts/tests/*`, so this test-only change adds no measured production lines; the changed-line gate is assigned to the trusted integrator because it uses a Git base unavailable in this session.
5. Gates: targeted `test_orca_schema_doc*.py`, full Python suite, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. No pragma, skip, exclusion or threshold change; no `apps/` file touched. Requirement IDs ORCA-018/019/020 are defined in `orca-refactor.md`, not the V1 spec; check-docs validates links and structural rules for the new document.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_schema_doc*.py' -q` → `Ran 10 tests in 2.712s`, `FAILED (failures=1, errors=6)` (schema document absent). Green after writing the document: `Ran 10 tests in 2.836s`, `OK`.
- (tool) `python3 -I scripts/check-docs.py` → exit 0 after the new document and status-only README/design edits; full suite `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1920 tests in 126.800s`, `OK`; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; `check-constraints.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py` → each exit 0. Evidence source: tool commands and read-only reviewers.
- (review evidence) Worked manifest hash `03ba43b7f756` and preflight strings reproduced by `compile_context()`/`preflight()` on synthetic inputs and asserted by the targeted tests.

## Coverage (branch-to-test map)
- Document schema: existence, manifest field names, worked JSON artifact, stable hash, mandatory-section non-truncation, optional budget both sides, preflight strings, synthetic-fixture safety, docs checker and block presence → `SchemaDocTests.test_*` named tests. No production code changed; no pragma, omit, skip, exclusion or threshold change; no `apps/` file touched.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with seven Required gaps; revised plan re-reviewed GO).
- aurevia-code-reviewer: GO after resolved-budget wording and hardening changes.
- aurevia-coverage-auditor: GO (all 12 named tests assert outcomes; no production code changed).
- aurevia-devsecops: GO (synthetic artifact only, no gate or runtime claim).
