# S-G5-06 — Recovery targets, restore into clean environment and operational runbooks

## Story and scope

- Story: S-G5-06 (swarm worker attempt 1), branch `swarm/S-G5-06-99`, base SHA `40e1668`.
- Primary requirements: **OPS-04** (RPO/RTO targets, restore of database/objects/IdP dependencies into a clean environment, reconciliation before reopening writes, verification cadence) and **OPS-05** (13 monitoring signals, named operational and clinical owners, four downtime/recovery runbook topics, duplicate prevention).
- Specification: `SMARTCLINIC_V1_SPEC.md` §8 OPS-04/OPS-05, with cross-references to IAM-02, IAM-05, ARC-05, ARC-06, XCH-05, PAT-01, MSG-05, ADM-01 and §11.
- Scope honoured: only `infra/s_g5_06/` and `docs/operations/s_g5_06/` were created/edited. No tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`), CI, script, application, schema or API file was touched.
- Reserved migration **V48 was deliberately not used**: this is an infra/documentation delivery with no schema change; the repository's change-safety gate allows version gaps.
- Synthetic fixtures only: no real person, identifier, credential, backup or deployment was used. No push, tag or `gh` call was made.

## Acceptance mapping (requirement → implementation → evidence)

| Requirement clause | Implementation | Evidence |
|---|---|---|
| OPS-04 RPO ≤ 15 min, RTO ≤ 4 h for core chart, order data, documents and associations | `recovery_evidence.py`: `targets.rpo_minutes` in `[0,15]` (0 accepted as zero data loss), `targets.rto_hours` in `(0,4]`; `scope` requires `core_chart`, `order_data`, `documents`, `document_associations` | `TargetsTest`, `CoverageListTest`; conforming fixture reports PASS at 12 min / 3.5 h |
| OPS-04 restore database, objects, IdP dependencies into a clean environment | `restore.targets` required members + `clean_environment is True` | `CoverageListTest.clean_environment_*`; [restore-procedure.md](../docs/operations/s_g5_06/restore-procedure.md) §1–2 |
| OPS-04 reconcile hashes, source versions, in-flight deliveries and audit coverage before reopening writes | `reconciliation` four gates + `completed_before_writes_reopened`, all strict `is True` | `ReconciliationTest`; [recovery-targets.md](../docs/operations/s_g5_06/recovery-targets.md), [restore-procedure.md](../docs/operations/s_g5_06/restore-procedure.md) §3 |
| OPS-04 verify before pilot and quarterly thereafter | `verification.before_pilot` and `quarterly_cadence` strict `is True` | `VerificationTest`; quarterly *execution* documented as an open item (one artifact cannot prove a recurring schedule) |
| OPS-05 13 monitoring signals | `monitoring.signals` must name all 13 exactly, no duplicates, extras allowed | `MonitoringTest`; [operational-runbooks.md](../docs/operations/s_g5_06/operational-runbooks.md) signal table |
| OPS-05 operational on-call and site clinical coverage owners | `ownership` two non-blank string roles | `OwnershipTest`; [operational-runbooks.md](../docs/operations/s_g5_06/operational-runbooks.md) |
| OPS-05 four runbook topics + duplicate prevention | `runbooks` required members; `duplicate_prevention is True` | `RunbooksTest`, `DuplicatePreventionTest`; [restore-procedure.md](../docs/operations/s_g5_06/restore-procedure.md) §5 |
| Evidence honesty | `kind` enum authority; non-blank `source`; synthetic markers on a `conducted_drill` produce `evidence_conflict` and block `final_evidence` | `StructureTest`, `ConformingArtifactTest`; [recovery-evidence.md](../docs/operations/s_g5_06/recovery-evidence.md) |

Entry-point-to-test trace: `bash infra/s_g5_06/check.sh` → `python3 -I -m unittest discover -s infra/s_g5_06/tests -q` (78 tests import `recovery_evidence.py` by path and exercise every checker) → `python3 -I recovery_evidence.py fixtures/synthetic_recovery_evidence.json` (exit 0) → violation fixture (exit 1). The CLI path is `recovery_evidence.py <artifact.json>` → `main` → `evaluate` → named checks → `format_report`.

## Exact commands run and results (tool evidence)

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_06/tests -q` (red, before implementation) | exit 1 — `FileNotFoundError` for missing `recovery_evidence.py`; confirms the tests specify behaviour not yet present |
| `python3 -I -m unittest discover -s infra/s_g5_06/tests -q` (green) | **exit 0; 78 tests, OK** |
| `bash infra/s_g5_06/check.sh` | **exit 0** — unit tests + conforming fixture PASS + violation fixture correctly FAIL |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/synthetic_recovery_evidence.json` | exit 0 — `PASS`, `classification: rehearsal`, `final_evidence: false`, all 20 checks PASS |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/violation_recovery_evidence.json` | exit 1 — `FAIL`; 12 named checks fail (both targets, scope, restore target, clean environment, two reconciliation gates, before_pilot, signals, clinical owner, runbooks, duplicate_prevention) |
| `python3 -I -m trace --count --coverdir /tmp/opencode/sg506cov4 infra/s_g5_06/tests/run_coverage.py` | exit 0; 78 tests OK; `recovery_evidence.cover` contains **no `>>>>>>` mark → 100% of executable lines**. `coverage.py` is not installed; branch coverage is enumeration-based and `test_empty_object_reports_every_section_check` proves no checker is silently skipped |
| `python3 -I scripts/check-docs.py` | exit 0 — links and requirement IDs valid |
| `python3 -I scripts/check-secrets.py` | exit 0 — no credential/PHI-shaped string |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `bash -n infra/s_g5_06/check.sh` | exit 0 (shellcheck not installed locally; the script mirrors S-G5-05's lint-clean `check.sh`) |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1 — 163 tests, 6 failures / 4 errors, all pre-existing/environmental (see below), none in S-G5-06 scope |

## Decisions

- **D1** Only the literals and named sets in OPS-04/OPS-05 are enforced; no extra threshold was invented.
- **D2** Units are pinned by field names (`rpo_minutes`, `rto_hours`) so a value cannot be silently reinterpreted.
- **D3** RPO = 0 is accepted (zero data loss satisfies "≤ 15"); RTO must be positive.
- **D4** Scope/restore-target/signal/runbook checks require "must include, no duplicates" and allow extras; exact membership (not substring). A broader drill cannot hide a required item.
- **D5** Boolean gates use strict `is True` so `"false"`, `1` and `None` fail closed.
- **D6** `kind` is the evidence-class authority; a synthetic-labelled source on a conducted drill is an `evidence_conflict`. `source` must be non-blank.
- **D7** Revocation/withdrawal reapplication, in-flight-delivery dedupe semantics and audit hash-chain verification are documented as mandatory **restore-procedure** gates rather than new evaluator thresholds, because OPS-04 does not list them among the reconciliation items. This follows IAM-02, IAM-05, ARC-05, OPS-02 and §11.
- **D8** No reserved migration V48; no schema change.
- **D9** The fourth OPS-05 runbook topic is `supervised_back_entry` exactly as named in `SMARTCLINIC_V1_SPEC.md:138`. `downtime_drills` belongs to the OPS-04 verification cadence and G5 gate and is an allowed extra, not a substitute.
- **D10** Owner names reject a placeholder vocabulary (`tbd`, `n/a`, `na`, `unknown`, `none`, `-`) in addition to blank/non-string values.
- **D11** A wrong-typed `kind` is recorded as a failed check and classified `unknown`; it never raises out of `_verdict`.

## Defects found

- No in-scope defect. The two initial test failures were a test-helper merge artefact (a "missing owner" inherited a default) and were fixed in the test itself; the post-fix suite is green.
- Two transient implementation errors were caught by the red/green loop: an eagerly evaluated numeric lower-bound comparison on a string input (fixed by moving it inside the `_is_number` short-circuit) and missing branches for `main()` reading `sys.argv` (added tests).

## Reviewer findings resolved

- **Material (System Designer + independent QA):** the fourth OPS-05 runbook topic was initially encoded as `downtime_drills`, but the authoritative specification (`SMARTCLINIC_V1_SPEC.md:138`) names **supervised back-entry**. This was a fail-open (an artifact omitting a supervised back-entry runbook could pass) and a false rejection of a conforming artifact. Fixed in the evaluator, `recovery-contract.json`, both fixtures, the tests and every document; `downtime_drills` is retained only as an allowed extra. The violation fixture now specifically omits `supervised_back_entry`, and `test_required_runbooks_match_the_spec_vocabulary` guards against regression.
- **Minor (System Designer):** placeholder owner strings such as `TBD` passed; now rejected (D10).
- **Minor (System Designer):** a wrong-typed `kind` raised `TypeError` in `_verdict`; now recorded as a failed check and classified `unknown` (D11), with `test_wrong_typed_kind_is_reported_not_raised`.
- **Observation (QA):** `infra/s_g5_06/check.sh` is not wired into `scripts/check.sh`, `quick-check.sh` or a workflow. No `s_g5_*` gate is wired repo-wide, and the story scope explicitly excludes CI scripts, so no out-of-scope wiring was attempted. The gate is documented as the manual canonical check for this story; the integrator runs the repository gates.

## Pre-existing / environmental findings (out of scope, not modified)

- `scripts/tests` 4 errors are `ModuleNotFoundError: No module named 'yaml'` (`test_fast_tier`, `test_workflow_concurrency`, two `test_swarm.PlanTests`).
- `scripts/tests` 6 failures: three `test_check_pack.*` (documented cloud-worker CI-profile trap — worktree `opencode.json` SHA `279aa1e7…` equals `.github/opencode.ci.json` and carries the skip-worktree flag, while the committed file SHA `14bba0da…` is the generated local profile; `check-pack.py` judges the worktree), two `test_agent_run.*` (environment-specific git refs) and one `test_java_conventions` finding on `PatientFlowContract.java` in another worker's scope.
- `python3 -I scripts/check-pack.py` locally exits 1 for that same documented CI-profile/skip-worktree reason; `opencode.json` was not regenerated or overwritten (root-level, out of scope, and overwriting would replace the CI profile).
- `shellcheck` is not installed locally; CI runs it. The new `check.sh` is structurally identical to S-G5-05's lint-clean script.
- Database and integration tests are the integrator's responsibility and were not run here.

## Evidence provenance

All results are from executed tool commands on synthetic fixtures, plus bounded read-only specialist design reviews (System Designer, Senior Python Tooling), a System Designer disposition review and an independent QA evidence review. The QA review could not execute shell commands, so its findings are static; the primary agent re-ran every gate after the corrections and recorded the executed results above. No real patient data, credentials, backup or deployment was used. No human participant or authorized clinical/operations feedback was used; a real restore drill and human acceptance remain open G5 gates.
