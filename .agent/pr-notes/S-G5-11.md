# S-G5-11 — Operational queues for unsigned work, unmatched reports, critical findings, failures and revoked accounts

- **Requirement:** **ADM-02** (primary). Supporting requirement IDs referenced by the handoff: OPS-02, IAM-01, IAM-02, ARC-06, CLN-05, XCH-03, XCH-04, XCH-05.
- **Scope:** `infra/s_g5_11/**`, `docs/operations/s_g5_11/**`, `.agent/pr-notes/S-G5-11.md`. No tracker, `apps/`, `db/`, `.github/`, `scripts/` or root files were edited. Reserved migration **V53 was not used** (no schema change is required; version gaps are permitted by the change-safety gate, `db/migrations/README.md`).

## What is delivered

A fail-closed ADM-02 evidence evaluator (`infra/s_g5_11/ops_queues.py`), two synthetic fixtures, a canonical module gate (`infra/s_g5_11/check.sh`), 104 unit tests and an operations handoff under `docs/operations/s_g5_11/`. The evaluator judges a JSON snapshot against the ADM-02 contract; it does not claim that any site queue is staffed or that any operator really acted (see "Evidence boundary" below).

## Acceptance mapping (ADM-02 → executable check → test)

| ADM-02 clause | Enforced by | Representative tests |
|---|---|---|
| Queues for unsigned encounters, unreconciled reports, unacknowledged critical findings, rejected exchanges, document failures, missing coverage, revoked accounts | `REQUIRED_QUEUES`, `_check_queues` → `queues`, `queues.<name>` | `QueueCatalogTest.test_every_required_queue_is_enforced`, `test_an_unlisted_queue_is_rejected`, `test_required_queue_names_are_exactly_the_spec_set`, `FixtureIntegrityTest.test_fixtures_cover_all_required_queues` |
| Authorized operators | `AUTHORIZED_OPERATOR_ROLES`, `_check_operators` → `operators`, `items.actor`, `exports.actor` | `OperatorTest.test_operator_role_must_be_authorized`, `test_duplicate_operator_subjects_are_rejected`, `test_inactive_operator_cannot_perform_an_action`, `test_unlisted_operator_cannot_perform_an_action` |
| Assign, resolve, retry or escalate | `ACTION_TYPES`, `RETRYABLE_QUEUES`, `REQUIRED_ACTION`, `_check_items` → `items.actions`, `items.state` | `ItemTest.test_action_type_must_be_known`, `test_retry_is_only_valid_on_retryable_queues`, `test_assigned_state_requires_an_assign_action`, `test_resolved_state_requires_a_resolve_action`, `test_escalated_state_requires_an_escalate_action` |
| With reason | `_check_items` → `items.reason` | `ItemTest.test_every_action_requires_a_reason` |
| Exports use explicit scope | `_check_exports` → `exports.scope` | `ExportTest.test_scope_must_name_the_active_organization`, `test_scope_must_list_at_least_one_queue`, `test_scope_queues_must_be_known` |
| Exports use expiry | `_check_exports` → `exports.expiry` (`MAX_EXPORT_TTL_SECONDS = 86400`) | `ExportTest.test_export_ttl_must_be_positive`, `test_export_ttl_must_be_bounded`, `test_export_ttl_boundary_is_accepted` |
| Exports use audit | `_check_exports` → `exports.audit` | `ExportTest.test_export_requires_an_audit_reference`, `AuditCorroborationTest.test_export_cannot_cite_a_non_export_event` |
| OPS-02 audit facts on every action and export | `_check_audit`, `_audit_corroborates` → `audit_events`, `items.audit`, `exports.audit` | `AuditTest.*`, `AuditCorroborationTest.*` |
| IAM-01/02 revocation effective on the next action | Active-operator set rebuilt from the roster on every `evaluate` call | `OperatorTest.test_inactive_operator_cannot_perform_an_action` |

## Defects found and fixed this run (each red → green)

1. **Non-corroborating audit reference (fail-open).** `items.audit`/`exports.audit` only proved that an id existed in the snapshot and matched the tenant; an event for a different actor, verb, resource or time could stand in for the action. Added `ACTION_AUDIT_NAMES`, `EXPORT_AUDIT_NAME` and `_audit_corroborates` (actor + verb + resource + instant; exports omit resource because the envelope has no separate id) and folded corroboration into the existing check names. Instants are compared by value (`_same_instant`), so `Z` and `+00:00` agree.
2. **Backdated action (fail-open).** An action could be timestamped before its work item's `created_at`, faking a timely response. `_check_items` now fails `items.actions` when `action.at < item.created_at`.
3. **Unhashable action `type` crashed `evaluate` (found by independent QA).** With `type` set to a JSON array, the new `ACTION_AUDIT_NAMES.get(kind)` raised `TypeError: unhashable type: 'list'`, aborting instead of reporting a named FAIL. Guarded with `isinstance(kind, str)`.

Red/green timeline (test added before each production change):
- 93 → 103 tests: `python3 -I -m unittest discover -s infra/s_g5_11/tests -q` → **FAILED (failures=7)**, exit 1, exactly the new `AuditCorroborationTest` (6) and `TemporalOrderingTest` (1) negative assertions; after the fix → `Ran 103 tests ... OK`, exit 0. The two new positive tests (`test_equivalent_offset_instant_corroborates_the_same_action`, `test_action_at_the_creation_instant_is_accepted`) passed in both states.
- 103 → 104 tests: `test_unhashable_action_type_fails_closed` → **FAILED (errors=1)** with the `TypeError` traceback at `ops_queues.py:293`, exit 1; after the guard → `Ran 104 tests ... OK`, exit 0.

Supporting cleanup: removed an unused `datetime.timezone` import; updated the synthetic fixture's `audit-8` instant to the export `created_at` so the conforming artifact remains coherent; updated all three handoff documents (named checks, observed output, decisions D10/D11, 93 → 104 tests).

## Exact commands run and results (all executed with tools)

| Command | Result |
|---|---|
| `bash infra/s_g5_11/check.sh` | `Ran 104 tests in 0.009s`, `OK`, `S-G5-11: ADM-02 evaluator unit tests and fixture checks passed`, exit **0** |
| `python3 -I -m unittest discover -s infra/s_g5_11/tests -q` | `Ran 104 tests`, `OK`, exit **0** (red runs: 7 failures at 103 tests; 1 error from the unhashable-type crash at 104 tests) |
| `python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/synthetic_ops_queues_evidence.json` | `ADM-02 operational queues: PASS`, evidence class `rehearsal (final: False)`, 25 named checks all PASS, exit **0** |
| `python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/violation_ops_queues_evidence.json` | `ADM-02 operational queues: FAIL`, `operator-attested (final: False)`, 8 named FAILs including `items.audit` and `exports.expiry`, exit **1** |
| `mkdir -p /tmp/opencode/s_g5_11_cov3 && python3 -m trace --count --coverdir /tmp/opencode/s_g5_11_cov3 infra/s_g5_11/tests/run_coverage.py` | `Ran 104 tests`, `OK`, exit **0**; the generated `ops_queues.cover` contains no `>>>>>>` marker — **100% executable-line coverage**. `coverage.py` is not installed (`ModuleNotFoundError`), so stdlib `trace` is the available measure; branch coverage has no mechanical measure and is covered by exhaustive enumeration of every conditional in the 104 tests. |
| `python3 -I scripts/check-docs.py` | exit **0** (relative links and requirement IDs valid) |
| `python3 -I scripts/check-secrets.py` | exit **0** (no credential or patient-identifier shape introduced) |
| `python3 -I scripts/check-pack.py` / `python3 -I scripts/gen-opencode.py --check` | exit **1** in this cloud worker. `git ls-files -v opencode.json` reports flag `S` (skip-worktree) and the worktree hash differs from `git show HEAD:opencode.json`; these commands judge the CI profile copied by `agent-run.sh`, not the committed profile. This is the known worker-profile artifact (see smartclinic-delivery skill / false finding OPS-CW-003-01), not a defect introduced by this story. No generated file was edited (out of scope). |

No database, Java, mobile or integration tests were run: this story has no application, schema or migration surface. The integrator runs the database/integration gates. `scripts/check.sh` was not run because it includes `db/check.sh` (PostgreSQL) and would also judge the skip-worktree CI profile.

## Entry-point-to-test trace

`python3 -I infra/s_g5_11/ops_queues.py <artifact.json>` → `main` → `evaluate` → `_check_queues` / `_check_operators` / `_check_audit` → `_check_items` (structure, state, owner, actions, reason, actor, audit + `_audit_corroborates`, temporal ordering) → `_check_exports` (scope, expiry, audit + corroboration, actor) → `_verdict` → `format_report` → exit 0/1/2. The same path is exercised in-process by `test_ops_queues.py` (104 tests) and end-to-end by the two committed fixtures in `check.sh`.

## Decisions (durable, carried in `docs/operations/s_g5_11/README.md`)

- D1–D9 (carried from the prior slice): exact seven-queue vocabulary; non-open states require a matching action; reason + active authorized actor + unique identities; `retry` only on `rejected_exchanges`/`document_failures`; operator allowlist `tenant_admin`/`privacy_auditor`/`operator`; 24-hour export TTL; explicit export scope and audit; synthetic rehearsal is never final evidence; V53 unused.
- **D10 (this run):** an audit id alone is insufficient — the cited event must corroborate actor, verb, work item and instant (exports: actor, `EXPORT`, creation instant).
- **D11 (this run):** an action may not predate its work item's creation instant.
- D4/D5 remain declared assumptions, not specification literals; they are listed as open items for site owners.

## Evidence boundary and open items (stop conditions)

Evidence sources: **executed tool commands** for all pass/fail results and coverage; **bounded simulated specialist review** from `smartclinic-qa-reviewer` (`ses_e0a8dac56ffe2xFKN3ZVIR77TW`, read-only) for independent findings. There is **no** human, clinical, real-site or simulated-user evidence in this story.

### Independent QA review (simulated reviewer, tool-delivered report)

The reviewer produced a clause-to-check verification matrix confirming all ADM-02 clauses are covered, the three handoff docs match code/fixtures, 25 named checks and the quoted outputs are accurate, and no PHI/credential shapes appear. It raised two actionable findings, both resolved here:

- **QA finding 1 (impact: crash instead of named FAIL):** unhashable action `type` raised `TypeError` at the new dict lookup. Fixed with an `isinstance(kind, str)` guard plus the red/green test above.
- **QA finding 2 (impact: doc overstated enforcement):** README D6 and the queue catalog said an "expired export fails closed", but the evaluator only checks a bounded positive window and has no clock. Reworded D6, the catalog and the evidence contract to state that elapsed windows are an operational check, not a failed check. No time-dependent logic was added (a clock injection would make the offline snapshot check nondeterministic and is outside ADM-02).

The reviewer reported no other defects and claimed no human or clinical acceptance.

This story does not claim ADM-02 is satisfied at any site. The following evidence is unavailable here and must be supplied by authorized owners before G5 acceptance: a real queue snapshot and active operator roster from each enabled site; verification of audit facts against the append-only chain; site mapping of role names to the declared allowlist; confirmation of the declared `retry` scope; and a human G5 acceptance decision. Per the user directive, execution stops here rather than inventing that evidence.
