# S-G5-11 — Operational queues for unsigned work, unmatched reports, critical findings, failures and revoked accounts (ADM-02)

## Requirement IDs and acceptance mapping

Primary: **ADM-02**. Supporting: **OPS-02, IAM-01, IAM-02, ARC-01**. Originating-requirement links documented for queue provenance: CLN-05, XCH-03, XCH-04, XCH-05, ARC-06, ADM-01. Specification: `SMARTCLINIC_V1_SPEC.md` §5 ADM-02, §2 IAM-01/02, §7 ARC-01, §8 OPS-02, §9 G5 gate.

Scope declared by the story: `infra/s_g5_11`, `docs/operations/s_g5_11`. Reserved migration **V53 intentionally unused** — no `db/migrations` edit, no schema change.

| ADM-02 clause | Implementation (in scope) | Synthetic test evidence |
|---|---|---|
| Queues for unsigned encounters, unreconciled reports, unacknowledged critical findings, rejected exchanges, document failures, missing coverage and revoked accounts | `ops_queues.REQUIRED_QUEUES` (exact seven-name vocabulary, no omission and no extra), `queues` / `queues.<name>` checks | `QueueCatalogTest` (missing queue, extra queue, each non-list queue, exact set), `ConformingArtifactTest.test_every_required_queue_is_present_and_validated`, `FixtureIntegrityTest.test_fixtures_cover_all_required_queues` |
| Authorized operators assign, resolve, retry or escalate | `ACTION_TYPES`, `AUTHORIZED_OPERATOR_ROLES`, `REQUIRED_ACTION` (assigned→assign, resolved→resolve, escalated→escalate), `items.actions`, `items.state`, `items.actor` | `ItemTest` (action type, action instant, terminal-state justification, empty/non-list actions), `OperatorTest` (role allowlist, active flag, unknown/inactive/unhashable actor), `OperatorTest.test_inactive_operator_cannot_perform_an_action` |
| Every action is "with reason" | `items.reason` requires a non-empty string on every action | `ItemTest.test_every_action_requires_a_reason` |
| Retry is a controlled action | `RETRYABLE_QUEUES = (rejected_exchanges, document_failures)`; `retry` on any other queue fails | `ItemTest.test_retry_is_only_valid_on_retryable_queues`, `test_retry_is_valid_on_retryable_queues` |
| Revocation applies to the next protected action | Active/authorized actor set is rebuilt from the operator roster on every evaluation; inactive or unlisted actor fails; unique subjects prevent a stale `active` entry from re-authorizing a revoked person | `OperatorTest.test_inactive_operator_cannot_perform_an_action`, `test_unlisted_operator_cannot_perform_an_action`, `test_duplicate_operator_subjects_are_rejected` |
| Exports use explicit scope | `exports.scope` requires `organization_id` equal to the artifact tenant and a non-empty list of known queue names | `ExportTest.test_scope_must_be_an_object`, `test_scope_must_name_the_active_organization`, `test_scope_must_list_at_least_one_queue`, `test_scope_queues_must_be_known`, `test_scope_queues_must_be_strings` |
| Exports use expiry | `exports.expiry` requires a timezone-aware `created_at`/`expires_at` with a positive TTL no greater than 86,400 s | `ExportTest.test_export_times_must_be_instants`, `test_export_ttl_must_be_positive`, `test_export_ttl_must_be_bounded`, `test_export_ttl_boundary_is_accepted` |
| Exports use audit | `exports.audit` requires a `audit_id` referencing an existing audit event; `exports.actor` requires an active authorized operator | `ExportTest.test_export_requires_an_audit_reference`, `test_export_actor_must_be_authorized`; `AuditTest` |
| OPS-02 audit facts on every action and export | `AUDIT_FIELDS` (id, actor, action, resource_id, at, outcome, correlation_id, organization_id), tenant match, timezone-aware instant, unique ids; `items.audit` and `exports.audit` require references | `AuditTest` (missing field, wrong tenant, naive instant, blank id/actor/action, duplicate id, unknown/unhashable reference) |
| Fail-closed evidence; honesty | `evaluate` records all named checks and returns one verdict; `final_evidence` only for a clean `operator_attested` artifact; a synthetic-named attested source is a conflict | `ConformingArtifactTest`, `AllBreachesTest`, `ReportAndCliTest`, `FixtureIntegrityTest` |

Entry-point-to-test trace: `python3 -I infra/s_g5_11/ops_queues.py <artifact.json>` → `main` → `json.loads(Path(...).read_text())` → `evaluate` → `schema_version`/`kind`/`evidence_conflict`/`organization_id` → `_check_queues` → `_check_operators` (active-authorized set) → `_check_audit` (auditable id set) → `_check_items` (structure, state, owner, actions, reason, actor, audit reference) → `_check_exports` (scope, expiry, audit, actor) → `_verdict` → `format_report` → exit 0/1. `ReportAndCliTest` drives the CLI through temp files; `FixtureIntegrityTest` drives the committed conforming and violation fixtures. There is no live API/database entry point in this story's scope — the deliverable is the fail-closed evidence evaluator and its operator contract.

## Commands run and exact results (evidence source: executed tool commands)

**Red → green, iteration 1 (missing production module):**
- `python3 -I -m unittest discover -s infra/s_g5_11/tests -q` → **EXIT 1**, `ImportError: ... No such file or directory: '.../infra/s_g5_11/ops_queues.py'` (1 error). The failure is caused by the missing production behavior, not an unrelated fault.
- After implementing `ops_queues.py` and the two fixtures: same command → **EXIT 0**, `Ran 88 tests ... OK`.

**Red → green, iteration 2 (duplicate-identity fail-open gap):**
- Added `test_duplicate_operator_subjects_are_rejected` and `test_duplicate_audit_event_ids_are_rejected` first → same command **EXIT 1**, 2 failures (`'operators' not found in set()`, `'audit_events' not found in set()`).
- Added uniqueness enforcement to `_check_operators` and `_check_audit` → same command → **EXIT 0**, `Ran 90 tests in 0.012s / OK`.

**Red → green, iteration 3 (invalid UTF-8 crashed the CLI):**
- Added `test_main_rejects_invalid_utf8` first → same command **EXIT 1**, 1 error (`UnicodeDecodeError: 'utf-8' codec can't decode byte 0xff ...` at `ops_queues.py:371`), i.e. the documented exit-2 "cannot read" path was not reached.
- Added `UnicodeError` to the `main` read exception tuple → same command → **EXIT 0**, `Ran 91 tests ... OK`.

**Red → green, iteration 4 (QA finding BUG-SG5-11-01: unhashable `kind`/`state`):**
- Added `test_unhashable_kind_fails_closed` and `test_unhashable_item_state_fails_closed` first → same command **EXIT 1**, 2 errors (`TypeError: unhashable type: 'list'` at `_verdict`, and the corresponding `dict` case at `_check_items`). A hostile but valid JSON value produced a traceback instead of a named FAIL, masking other breaches.
- Guarded both dict lookups with an `isinstance(..., str)` check → same command → **EXIT 0**, `Ran 93 tests in 0.013s / OK`. Clean FAIL reports with `classification: unknown` and `final_evidence: false` for `kind: []`; `items.state` FAIL for `state: {}`.

**Final gates (executed on the final source):**
- `bash infra/s_g5_11/check.sh` → **EXIT 0**: `Ran 93 tests ... OK`; conforming fixture CLI passes; violation fixture CLI exits non-zero as required; prints `S-G5-11: ADM-02 evaluator unit tests and fixture checks passed`.
- `python3 -I -m trace --count --coverdir /tmp/opencode/s_g5_11_cov4 infra/s_g5_11/tests/run_coverage.py` → **EXIT 0**, 93 tests OK. Coverage file `__w.Aurevia-Care.Aurevia-Care.infra.s_g5_11.ops_queues.cover`: **282 executable lines, 0 uncovered** (`grep -c '>>>>>>'` = 0) — 100% line coverage. `test_ops_queues.cover` also has 0 uncovered lines. `coverage.py` is not installed; branch coverage has no mechanical measure here, so the 93 tests enumerate every conditional explicitly (decision record 0002 precedent). No fabricated or pending-as-covered result.
- `python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/synthetic_ops_queues_evidence.json` → **EXIT 0**, `ADM-02 operational queues: PASS`, `evidence class: rehearsal (final: False)`, **25 named checks all PASS**.
- `python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/violation_ops_queues_evidence.json` → **EXIT 1**, `ADM-02 operational queues: FAIL`, `evidence class: operator-attested (final: False)`; named failures: `evidence_conflict`, `queues`, `queues.missing_coverage`, `audit_events`, `items.state`, `items.reason`, `items.actor`, `exports.expiry`.
- `python3 -I scripts/check-docs.py` → **EXIT 0** (all relative links resolve; every requirement ID mentioned is defined in the specification).
- `python3 -I scripts/check-secrets.py` → **EXIT 0** (no credential or PHI-shaped value in scope).
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0** (reserved V53 is unused; gaps are allowed by decision 0002).
- `python3 -I -m unittest discover -s scripts/tests -q` → **EXIT 1**, `Ran 163 tests ... FAILED (failures=7, errors=4)`. All are pre-existing and unrelated to this scope: `check-pack`/`committed_opencode_json_is_generated`/`check_mode_detects_drift` (skip-worktree CI-profile `opencode.json`), `agent_run` two git-ref tests (environment-specific refs), import errors for `test_fast_tier` and `test_workflow_concurrency`, `test_swarm.test_label_editing_jobs_have_issues_write`, `test_swarm.test_workflow_permissions_use_only_valid_scopes`, `test_swarm.test_open_spec_tasks_are_dispatchable` (parallel workers had claimed tasks, leaving 8 open), and `test_java_conventions.test_list_bearing_records_copy_on_store_and_read` for `PatientFlowContract.java` (pre-existing S-G3-11 file). None of these files was touched by this story.
- `bash scripts/quick-check.sh` → **EXIT 1 at step `python3 -I scripts/check-pack.py`** with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` → `S` (skip-worktree): this is the documented cloud-worker CI-profile trap, not a defect introduced here. `scripts/quick-check.sh` step `python3 -I scripts/check-migrations.py --require-baseline` was separately confirmed EXIT 0; `check-docs.py` and `check-secrets.py` were separately confirmed EXIT 0. Database and integration checks are the integrator's responsibility and were not run here.

No `db/check.sh`, `mvn verify`, real-PostgreSQL integration, push, tag or `gh` command was executed. No coverage result was fabricated.

## Defects found

**Fixed in this change (test-first, red → green, in scope):**
- Duplicate operator subjects and duplicate audit-event ids were accepted. A revoked operator with a stale duplicate `active` roster entry could still authorize an action, and a forged duplicate audit id could stand in for a real audit fact. This was a fail-open against the IAM-01/02 "revocation on next request" rule and the OPS-02 audit reference. Fixed by unique-subject and unique-audit-id enforcement with the two tests above. Evidence source: inspection plus both specialist proposals; confirmed by executed red/green commands.
- Invalid UTF-8 input to the CLI raised `UnicodeDecodeError` instead of returning the documented exit-2 "cannot read" result. Fixed by including `UnicodeError` in the read exception tuple; regression test added (iteration 3).
- **BUG-SG5-11-01** (Medium, QA reviewer finding, confirmed by an executed red/green command): a hostile but valid JSON value in `kind` (`[]`) or a work item's `state` (`{}`) raised `TypeError: unhashable type` and aborted the run instead of returning a named FAIL report, contradicting the module's "recorded, not raised" contract and masking other breaches. Fixed by guarding both dict lookups with `isinstance(..., str)`; two regression tests added (iteration 4). Evidence source: independent QA review by inspection, reproduced locally with exact error output.

**Reported, not changed (acknowledged residual risk, not a false pass):**
- `final_evidence: true` for `operator_attested` is a self-declaration: the artifact carries no signature or provenance binding, and an absent `source` does not trigger `evidence_conflict`. The docs explicitly disclose that this flag records the artifact's claim, not independent verification, and human G5 acceptance remains open. Renaming the flag or adding signed provenance is a separate decision.
- Audit references are existence-by-id only; they do not verify that the referenced event's actor/action/resource/time/tenant match the action. A wrong-tenant audit event still fails `audit_events`, so there is no false pass. Per-action binding would need a separate story. These limitations are documented in `queue-catalog.md` and `evidence-contract.md`.

**Reported, not changed (pre-existing, out of scope):**
- The `scripts/tests` failures and `scripts/quick-check.sh` `check-pack` failure listed above. They stem from the cloud-worker skip-worktree profile, environment-specific git refs, parallel task claims and a pre-existing Java convention finding; none is reachable from the two scope directories.

## Decisions / notes for the integrator

- This story delivers a fail-closed **evidence evaluator and operator contract**, not live queues. It judges a JSON artifact and cannot prove that a queue was staffed, an operator acted or an export file was deleted. The docs state this boundary explicitly; no human or clinical acceptance is claimed.
- Declared assumptions (documented in `docs/operations/s_g5_11/queue-catalog.md` and the README decisions table): the operator-role allowlist (`tenant_admin`, `privacy_auditor`, `operator`), `retry` restricted to `rejected_exchanges` and `document_failures`, and the 86,400 s export TTL. These are not spec literals and must be aligned with site configuration before live enablement.
- `final_evidence` is true only for a clean `operator_attested` artifact; a passing `synthetic_rehearsal` is always `final_evidence: false`, and an attested artifact naming a synthetic source is rejected as `evidence_conflict`.
- Scope honoured: only `infra/s_g5_11/*` and `docs/operations/s_g5_11/*` were added. `.agent/pr-notes.md` (the PR description) was replaced; its prior content contained stale stories and unresolved conflict markers from recycled worker images. No shared tracker, other migration version, credential or patient data was touched. Reserved migration **V53 was not used**.
- Confirm on the merge result: `bash infra/s_g5_11/check.sh` (93 tests + fixture gates), 100% evaluator line coverage, `check-docs.py`, `check-secrets.py`, and the real-PostgreSQL/integration gates. The `scripts/tests` and `check-pack` failures above are environmental in this worker image; do not treat them as regressions from this change.
- Synthetic fixtures only (`org-synthetic-1`, `op-1`, `op-2`, `clinician-1`, `audit-1..8`, `item ids u-1/r-1/c-1/x-1/d-1/m-1/a-1`). Fixture text is checked for email- and SSN-shaped values. No real patient data, identifier or credential was used or recorded.

## Evidence classification

- **Tool evidence:** every command result and count above, executed locally on this worker branch.
- **Reviewer evidence:** bounded design proposals from `smartclinic-senior-python` and `smartclinic-test-engineer`, plus an independent QA review by `smartclinic-qa-reviewer` (simulated reviewers; no edits by them). The QA review found BUG-SG5-11-01 and a doc count error; both were fixed. The reviewer had no shell tool in its session, so its findings were inspection-only — I reproduced the crash locally (exact `TypeError`) before fixing it, and ran every gate command myself afterward. No simulated user or clinician evidence was used.
- **Simulated-user / human evidence:** none. No clinician, operator or patient participated; real-site and G5 human acceptance remain open gates.
