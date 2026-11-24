# PT-01 clause 2 — version outcome scales and store units

## Clause

Version outcome scales and store units.

## Status

Partly proven

## Evidence

Active proof at the domain-service plus JDBC-store boundary, against a fresh PostgreSQL database
migrated from `db/migrations`, with synthetic fixtures and two tenants sharing local identifiers.
Reproduction requires a loopback PostgreSQL on port 5432 with trust authentication for the local
`orevia_migrate`/`orevia_app` roles and an admin name supplied through the
`OREVIA_PG_ADMIN` environment variable (default: the OS user name):

- Test class: `com.aurevia.therapy.clauses.VersionOutcomeScalesStoreUnitsTest`
  (path: `apps/api/src/test/java/com/aurevia/therapy/clauses/VersionOutcomeScalesStoreUnitsTest.java`).
  Targeted run: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.
- `versionsAndStoredReadingsKeepTheirExactScaleVersionAndUnit` — positive proof. A tenant admin
  defines and clinical governance approves version 1 in `points`, then version 2 in `degrees` of the
  same scale key. `versions()` returns both definitions with their distinct units, and the two
  readings are reloaded from PostgreSQL with the exact `scaleVersion` (1 and 2) and `unit`
  (`points` and `degrees`) each one used. A later version does not reinterpret the earlier reading.
- `refusesUnapprovedScaleWrongUnitAndNonPermittedValue` — negative proof. A reading against a
  defined-but-unapproved version is refused with `422 SCALE_NOT_APPROVED`; a non-permitted value
  with `422 VALUE_NOT_PERMITTED`; and a unit that does not match the approved version with
  `422 UNIT_MISMATCH`. No failed write advances the episode version or stores a reading.
- `refusesOutcomeByWrongRoleAndStaleVersion` — negative proof. A PT coordinator (not a PT clinician)
  cannot record an outcome (`403 FORBIDDEN`), and a caller presenting the pre-write episode version
  after a successful reading is refused with `412 STALE_VERSION`.
- `concealsScaleAndReadingFromAnotherTenant` — negative proof. Under forced row-level security a
  second organization sees no scale version or stored scale, and reading the first organization's
  episode returns `404 NOT_FOUND`, while the first organization still holds its one reading.

Production classes exercised by these tests:

- `com.aurevia.therapy.TherapyService` — `defineOutcomeScale`, `approveOutcomeScale`,
  `versions`, `recordOutcome`, role authorization, version precondition and unit/value guards.
- `com.aurevia.therapy.JdbcTherapyStore` — `scale`, `scales`, `allocateScaleVersion`,
  `saveScale`, `find` (reading reload), all inside the tenant-guarded transaction.
- `com.aurevia.therapy.TherapyContract` — `OutcomeScale`, `OutcomeReading`,
  `ScaleRequest`, `OutcomeRequest`, `Actor`, `TherapyAggregate`.
- `db/migrations/V77__build_12.sql` — `therapy_outcome_scale` and `therapy_outcome_reading` tables,
  the immutable-definition and approved-scale/unit-match triggers, RLS and FORCE RLS.
  `db/migrations/V85__therapy_store.sql` — the atomic `therapy_scale_sequence` version counter.

Found in the survey but not part of this clause's HTTP proof: `TherapyController` publishes only
`POST /api/v1/therapy/episodes` (accept) and `GET /api/v1/therapy/episodes/{id}` (read). No route
defines, approves or lists scale versions or records an outcome, so no HTTP-level test can prove
this clause today. The only published therapy path in the generated contract is the stale
`POST /api/v1/therapy/referrals/{id}/accept` (`contracts/openapi/generated/openapi.yaml:334`).

## Gap

The domain rule, its persistence and the database guards are built and proven by the active tests
above. What is missing is HTTP reachability and its published contract:

1. **Scale and outcome HTTP surface.** `TherapyController` has no routes to define or approve a
   scale version, list `versions()`, or record an `OutcomeReading`. Files that would change: add
   the routes to `apps/api/src/main/java/com/aurevia/therapy/TherapyController.java`
   (thin delegations to the existing `TherapyService` methods, identity derived from `TenantContext`
   and fresh membership), add matching entries to `contracts/openapi.yaml` with a tag and an
   `Idempotency-Key` header on each POST, and regenerate the client with `gen:api`. The complete
   request sequence for the enabling HTTP test is recorded in the hand-off notes for this story.
2. **Staff-web use.** A clinician-facing action to record an outcome against a chosen scale version
   and unit would need a staff-web change once the routes exist.

That is **two separate tasks**: one cross-boundary backend/contract task (controller + OpenAPI +
regenerated client + enabling HTTP test), then one staff-web task. This evidence task added only a
test class and this document; it changed no production code, no existing test, no migration and no
contract. The enabling HTTP test is intentionally not present as an `@Disabled` Java method: a hard
repository gate (`scripts/check-constraints.py`, reached from `scripts/worker-check.sh` and the
integrator gate) rejects any newly added `@Disabled`, so the complete test is carried in the notes
instead and this document records the gap.
