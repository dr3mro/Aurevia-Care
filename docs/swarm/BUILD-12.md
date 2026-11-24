# BUILD-12 · Physical therapy: episode, outcome scales with units and signed progress

PR #291, tested branch tip 4dbd2fa851e82a4e04e757eeb8e119831be298f6.

Task: BUILD-12

Swarm worker 3. Local checks: passed. Agent result: ok.

# BUILD-12 — Physical therapy: episode, outcome scales with units and signed progress

Story: **BUILD-12 · Physical therapy: episode, outcome scales with units and signed progress**.
Requirement: **PT-01**. Reserved migration: **V77**. Branch: `main` (HEAD before this work `c45f9b1`).
This file is the pull-request description and the hand-off for this worker. Synthetic data only.

## Bottom line

The PT-01 therapy domain in `apps/api/src/main/java/com/aurevia/therapy` and its
44 synthetic tests were already present on `main` from earlier work and already satisfy every
behavioural item in the story (episode lifecycle, baseline measurements with units, goals,
frequency/duration, versioned outcome scales with units, signed session/progress/discharge
notes, summary and return acknowledgment). I verified that rather than duplicating it. The one
in-scope artefact that did not exist was the reserved **V77** migration, which I added with a
complete, tenant-isolated, forced-RLS therapy schema that also enforces the two named
acceptance rules at the database layer (signed-discharge prerequisite; a new scale version
cannot rewrite an old score).

## Summary

- Added `db/migrations/V77__build_12.sql`: 11 tenant-owned tables covering episodes,
  evaluation, baseline measurements with a mandatory unit, goals, versioned outcome scales
  with mandatory unit and permitted values, unit-bound readings, interventions, session /
  progress / discharge notes with signatures and SHA-256 checksums, approved summaries,
  revision-bound return acknowledgments and an append-only PT-01 audit trail.
- Every table has `organization_id` in its key and foreign keys, `ENABLE` + `FORCE ROW LEVEL
  SECURITY`, the `public.app_org()` policy, explicit grants to `orevia_app`, and append-only
  triggers/fact-freeze triggers where the domain requires immutability.
- Two named acceptance rules are enforced twice: in `TherapyService` (existing) and now in the
  database (discharge signature guard; scale-definition immutability + reading/scale-unit guard).
- No Java production code and no existing test was changed. No dependency added. No file
  outside the declared scope was changed.

## Affected requirements

- **PT-01** — therapy episode, versioned unit-bearing outcome scales, signed progress/discharge.
- **ARC-01, ARC-02** — every new table is tenant-owned with forced RLS and the `app_org()` policy;
  identical local identifiers in two tenants stay isolated.
- **OPS-02** — append-only PT-01 audit facts with per-tenant sequence.

## Risk class

**Cross-boundary** (new database schema behind an existing domain service) with a
**safety-critical** facet (signed clinical notes and discharge). Reviewed with the defensive
rules in `docs/WORKFLOW.md`: negative/denial/failure tests already exist and the new SQL adds
independent database-level guards for the signed-discharge and scale-version invariants.

## Review owner

Senior PostgreSQL / SQL / Flyway for the migration; Senior Java and Spring Boot for the
unchanged domain; Physician reviewer for the signed-note/clinical-safety path (simulated only —
no clinician or human participant was available in this worker).

## Data changes

`V77__build_12.sql` is an **additive `Change: expand`** migration:
`therapy_episode`, `therapy_evaluation`, `therapy_baseline_measurement`, `therapy_goal`,
`therapy_outcome_scale`, `therapy_outcome_reading`, `therapy_intervention`,
`therapy_clinical_note`, `therapy_summary`, `therapy_return_acknowledgment`, `therapy_audit_event`.
No backfill, no destructive DDL, no `DROP`/`DELETE`/`TRUNCATE` of existing objects, no update of
an existing migration. Row volume impact is a handful of small clinical rows per episode.
Rollback/forward-fix: not contract; a later additive migration can drop these new tables if
ever retired (no existing data depends on them today). Applied cleanly by `db/check.sh`.

## Authorization boundaries and audit events

- Tenant scope: `organization_id` is part of every primary/foreign key; RLS is forced and the
  `USING (organization_id = public.app_org())` policy is attached per table.
- Runtime role `orevia_app` gets read + append (and controlled UPDATE on episode,
  evaluation, scale approval and draft notes); DELETE/TRUNCATE are revoked and append-only
  triggers reject immutable mutation for every role, including the migration owner.
- Role checks (pt_clinician, pt_coordinator, tenant_admin, clinical_governance, privacy_auditor)
  stay in `TherapyService`; the migration adds no application bypass.
- Audit: `therapy_audit_event` records organization, actor subject, action, resource and outcome
  per PT-01 command; append-only with a per-tenant identity sequence.

## Migration and rollout considerations

`python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (unique version, valid
`Change: expand`, no contract requirements). `bash db/check.sh` → exit 0, final line
`db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`.
Migrations apply in `sort -V` order; V77 follows V61 and all prerequisites (`organization`,
`patient_record`, `app_org()`, `reject_immutable_mutation()`) exist. Flyway is authoritative in
production. No lock-heavy backfill; only `CREATE TABLE`, policy, trigger and grant DDL.

## Test evidence

Evidence source for every line below: **executed tool commands** run in this worktree; fixtures
are synthetic. No reviewer, simulated-user or human result is claimed for this change.

| Command (final line / result) | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='TherapyCoverageTest,TherapySafetyTest,OutcomeScaleTest,TherapyEpisodeFlowTest,TherapyControllerHttpTest' test` | exit 0; Surefire: OutcomeScaleTest 5, TherapyCoverageTest 15, TherapyEpisodeFlowTest 6, TherapySafetyTest 14, TherapyControllerHttpTest 4 — **44 tests, 0 failures, 0 errors, 0 skipped** |
| `bash db/check.sh` | exit 0; `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (`MIGRATION_POLICY_OK`) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py` | all exit 0 (`check-wiring OK`; only pre-existing baseline "fixed" notes) |
| `bash scripts/frontend-check.sh` | exit 0; `frontend standards: policy and tool commands passed` |
| `python3 -I -m unittest discover -s scripts/tests -q` | 436 tests, 3 failures — all 3 are the pre-existing `test_check_pack.py` opencode.json CI-profile drift (see Findings); none touches this change |
| `bash scripts/quick-check.sh` | stops at `check-pack: opencode.json is stale` — same pre-existing CI-profile artifact (see Findings) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` | exit 0; all 26 therapy classes 0 missed (see Coverage) |

Entry point → test trace:
`POST /api/v1/therapy/episodes` → `TherapyController.accept` → `TherapyService.acceptReferral`
→ `InMemoryTherapyStore` → `GET /api/v1/therapy/episodes/{id}` → `TherapyController.read`
→ `TherapyService.get`; proven by `TherapyControllerHttpTest.acceptsReferralAndReadsEpisodeThroughHttp`
(201 + referralId/state ACTIVE, then 200 with the episode patient). The two named acceptance
rules are reached through the public service entry point in
`TherapySafetyTest.dischargeRequiresSignedEvaluationAndASignedSession` /
`TherapyCoverageTest.closedEpisodeCannotSignALeftoverDraft` (unsigned discharge refused) and
`OutcomeScaleTest.readingsBindTheExactScaleVersionAndUnitForTrending` /
`versionsAreSequentialAndImmutableAndApprovalRequiresGovernance` (a new scale version never
rewrites old scores). Database-level equivalents are the new triggers in V77 (verified by
`db/check.sh` applying the migration and the RLS/immutability policy checks).
Frontend che
