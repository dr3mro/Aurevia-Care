# S-G5-07 — Separate environments, synthetic fixtures, signed builds, migration checks and progressive rollout (OPS-06)

## Story, branch and scope

- Story: **S-G5-07** (`[-]`, agent `swarm-8`), attempts 1 of 3.
- Branch: `swarm/S-G5-07-99`; base SHA `40e1668447f4229641c44b99078e8ff6953aeefe`.
- Scope honoured: only `infra/s_g5_07/**` and `docs/operations/s_g5_07/**` were added.
  No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`,
  `PROJECT.md`, `docs/swarm`) was edited. No CI/workflow, root script,
  application, schema or contract file was edited. No push, tag or `gh` call.
- Synthetic data only: every identifier, digest and signer label in the fixtures
  is invented; no patient data, credential or real deployment detail is stored.
- Reserved migration version **V49 is unused**: no schema change is needed, and
  version gaps are allowed by the repository change-safety gate (decision 0002).

## Requirement IDs and acceptance mapping

Primary: **OPS-06**. Supporting context: OPS-02 (audit), OPS-05 (monitoring) are
referenced only as specification context; no new requirement ID is invented.
Specification: `SMARTCLINIC_V1_SPEC.md` §8 OPS-06 and §10 decision table.
Decision record `docs/decisions/0002-ops06-migration-change-safety.md` is the
authority for the migration checks and for the warning against a vacuous,
self-attesting environment manifest.

| OPS-06 clause | Evaluation | Named check evidence |
|---|---|---|
| Separate development/test/staging/production accounts and resources | Exact four-environment allowlist, non-blank string `account`/`resource` per env, all four account ids distinct and all four resource ids distinct | `environments`, `environments.names`, `environments.<name>`, `environments.distinct_accounts`, `environments.distinct_resources` |
| Synthetic test fixtures | `synthetic_only` and `no_real_patient_data` must be literal `true`; fixture environments exactly the three lower environments | `fixtures`, `fixtures.synthetic_only`, `fixtures.no_real_patient_data`, `fixtures.environments` |
| CI policy checks, dependency and secret scanning | Three named gates, each required to be literal `true` | `ci_checks`, `ci_checks.policy`, `ci_checks.dependency_scan`, `ci_checks.secret_scan` |
| Signed builds | Allowed signing method, full `sha256:<64 hex>` digest, verified literal `true`, named signer, promotion gated on signature verification | `signed_builds`, `signed_builds.method`, `signed_builds.digest`, `signed_builds.verified`, `signed_builds.signer_identity`, `signed_builds.promotion_gate` |
| Migration checks | Must name the existing `check-migrations --require-baseline` gate, plus expand/contract and forward-fix declarations; the canonical gate runs the checker for real | `migration_checks`, `migrations.gate`, `migrations.expand_contract`, `migrations.forward_fix_required` |
| Monitored progressive rollout | `progressive` strategy, ≥2 distinct ordered stages ending in `full`, health gates, monitoring and tested rollback | `rollout`, `rollout.strategy`, `rollout.stages`, `rollout.health_gates`, `rollout.monitoring`, `rollout.rollback_tested` |
| Reversible deployments / expand-contract | Top-level reversibility declaration plus the migration declarations above | `reversible_deployments`, `migrations.expand_contract` |
| Audited approval for production access | Approval required, distinct non-blank requester and approver, audited, expiring | `production_access`, `production_access.approval_required`, `production_access.separation_of_duties`, `production_access.audited`, `production_access.expiring` |

Entry-point-to-test trace:
`bash infra/s_g5_07/check.sh` → `unittest discover -s infra/s_g5_07/tests` →
`test_delivery_safety_evidence.py` loads `delivery_safety_evidence.py` →
`evaluate()` runs every section and collects named checks → `_verdict()` derives
`verdict`/`classification`/`final_evidence` → `main()` prints the report and
returns 0/1/2. The gate then runs the two JSON fixtures and the real
`scripts/check-migrations.py --require-baseline`.

## Red → green evidence (tool-generated, synthetic)

- **RED** — `python3 -I -m unittest discover -s infra/s_g5_07/tests -q` immediately
  after writing the tests, before the evaluator existed:
  `FileNotFoundError: .../infra/s_g5_07/delivery_safety_evidence.py` at
  `SPEC.loader.exec_module(dse)`; `FAILED (errors=1)`, exit 1.
- **GREEN** — same command after implementing the evaluator:
  `Ran 56 tests ... OK`, exit 0.
- During red→green one test-contract mismatch was found and fixed: the
  approver-value cases expected a dedicated `production_access.approver` check
  while the module reports the combined `production_access.separation_of_duties`
  check; the test expectation was aligned to the implemented check (no behaviour
  was weakened).

## Exact commands run and results

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_07/tests -q` (RED, before module) | exit 1, `FileNotFoundError` for the evaluator module |
| `python3 -I -m unittest discover -s infra/s_g5_07/tests -q` (GREEN) | exit 0, **56 tests, 0 failures/errors** |
| `bash infra/s_g5_07/check.sh` | exit 0; 56 tests OK; conforming fixture exit 0, violation fixture exit 1 as expected; real migration checker exit 0 |
| `python3 -I infra/s_g5_07/delivery_safety_evidence.py infra/s_g5_07/fixtures/synthetic_evidence.json` | exit 0, `OPS-06 delivery safety: PASS`, 28 named checks all PASS, `final_evidence: False` (rehearsal) |
| `python3 -I infra/s_g5_07/delivery_safety_evidence.py infra/s_g5_07/fixtures/violation_evidence.json` | exit 1, `FAIL`, 22 named failures out of 28 checks, including duplicate accounts, `secret_scan`, `verified`, `migrations.gate`, `rollout.strategy`, `audited`, `reversible_deployments` |
| `python3 -m trace --count --coverdir /tmp/opencode/s_g5_07_cov2 infra/s_g5_07/tests/run_coverage.py` | exit 0, 56 tests OK; evaluator `.cover` has **230 executable lines, 0 uncovered** (100% line coverage); `coverage.py` is unavailable so stdlib `trace` is used and branch cases are enumerated in `docs/operations/s_g5_07/evidence-contract.md` |
| Ad-hoc malformed-input sweep (16 shapes: `None`, list, int, string, `{}`, list-valued kind, dict-valued kind, non-object sections, unhashable list members in fixture/rollout/access) | all produced a complete verdict with all five report keys; no exception; `format_report` succeeded |
| `python3 -I scripts/check-docs.py` | exit 0 after fixing one broken relative link in `evidence-contract.md` (`../../s_g5_05` → `../s_g5_05`) |
| `python3 -I scripts/check-secrets.py` | exit 0, no credential or PHI-shaped string |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m py_compile` on the three new Python files; `bash -n infra/s_g5_07/check.sh` | exit 0 for both |

## Fast unit tests (repository-wide) — result and disposition

`python3 -I -m unittest discover -s scripts/tests -q` → `Ran 163 tests`,
`FAILED (failures=6, errors=4)`, exit 1. None of the failing tests references
`s_g5_07` (verified by grepping the run output). They are pre-existing or
environmental, not caused by this change:

- 3 `test_check_pack.*` failures (+ `test_check_mode_detects_drift`): the
  documented cloud-worker CI-profile/skip-worktree trap — worktree `opencode.json`
  is the CI profile (`git ls-files -v opencode.json` → `S`) while `check-pack.py`
  judges it against the committed local profile (false positive OPS-CW-003-01).
- 2 `test_swarm.PlanTests` errors and the `test_fast_tier` / `test_workflow_concurrency`
  loader errors: `PyYAML` (`yaml`) is not installed in this worker image.
- 2 `test_agent_run.AgentRunTests` failures: environment-dependent git ref setup.
- 1 `test_java_conventions.JavaConventionTest` failure on
  `PatientFlowContract.java`: pre-existing application file outside this scope.

These require root-level generator/install changes or application edits, all
outside the declared scope; they were not modified. Database and integration
gates are the integrator's responsibility and were not run here.

## Defects found

- Test-contract mismatch on the approver check (fixed in red→green, above).
- One broken Markdown link in the new evidence-contract doc (fixed; `check-docs.py` now green).
- No remaining in-scope defect. The out-of-scope tooling failures above are
  recorded for the integrator, not changed.

## Decisions

- D1: the evaluator judges *form and completeness*, not reality — decision 0002
  rejected a self-attesting manifest as vacuous, so the docs state plainly what
  cannot be proven (real isolation, real signature, executed scans, live health,
  approver identity, expiry).
- D2: exact allowlists and distinct identifiers; no case/whitespace folding in
  comparisons; booleans must be literal `True` (rejects `1`, `"true"`, `None`).
- D3: the migration gate must name the real `scripts/check-migrations.py
  --require-baseline`, and `check.sh` runs it for real rather than trusting the
  artifact.
- D4: production access needs separation of duties in addition to approval,
  audit and expiry.
- D5: three evidence tiers (contract / synthetic rehearsal / measured
  deployment); a rehearsal is never final, and even a passing measured
  artifact is a claim, not independent verification.
- D6: no CI/workflow or root script was wired (out of scope); the gate is
  self-contained and the integrator runs the repository-wide checks.
- D7: V49 unused; no schema change.

## Evidence provenance and missing evidence

- All verification results above came from **executed tool commands** on
  synthetic fixtures. Design review came from **bounded simulated specialists**
  (DevSecOps and senior-Python tooling design) and an independent simulated QA
  review; their findings were incorporated where in scope.
- **No human, clinical, cloud or real-deployment evidence** was available or
  used. Real four-account cloud topology, pipeline scan records, cryptographic
  build provenance, monitored rollout/rollback evidence, approval/audit records
  and G5 sign-off are open items listed in `docs/operations/s_g5_07/README.md`.
  This is expected for an offline infra/docs contract slice and is not a
  blocking missing decision for this story; it is the remaining human/cloud
  gate. No blocking in-scope decision or evidence was missing to complete the
  deliverable.
