- [x] FIX-CHART-DDL-01 · Move the chart_entry schema into db/migrations so production Flyway creates it (CLN-03)
  - evidence: PR #510 merged to main (reconciled 2026-11-23)
  - pr: #510
  - attempts: 1
  - requirements: CLN-03, ARC-04
  - scope: db/migrations, apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record/migrations
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/check-migrations.py`, `scripts/worker-check.sh`.

    Problem: `CREATE TABLE public.chart_entry` exists only in the test-scoped `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record/migrations/V13__longitudinal_chart.sql`. `db/migrations/` has no chart_entry definition, so `chart.record` passes against the test migration while production Flyway cannot create the table. Notes AUD-20261120-15 and AUD-20261120-15-190310 say the integrator was meant to sequence V13 into `db/migrations` (see `.agent/pr-notes/S-G1-03.md`) and never did.
    1. Read the test copy and `scripts/check-migrations.py` and `db/migrations/README.md` for the versioning rules: whether a gap version (V13 and V14 are unused) may be filled or the next free version above the highest must be used, and any `Requires-expand` or forward-fix header the checker demands. Follow the checker; do not guess.
    2. Add one migration under `db/migrations` that creates `chart_entry` with the same columns, constraints and indexes as the test copy, `ENABLE` and `FORCE ROW LEVEL SECURITY`, the same policies and the same grants. Reserve the version in the task notes so no other task uses it.
    3. Remove the test-scoped duplicate, or make the test migration location point at `db/migrations`, so tests and production share one schema. Do not leave two definitions.
    4. Add an integration assertion that, after Flyway runs the production migrations only, `chart_entry` exists, has row level security forced, and a cross-tenant read returns no rows.
    5. Run `bash scripts/worker-check.sh` and `python3 -I scripts/check-migrations.py --require-baseline`.

    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.
