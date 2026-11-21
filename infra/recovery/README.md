# Recovery drill (OPS-04)

`restore_drill.py` rehearses the reconciliation part of the
[recovery drill runbook](../../docs/operations/recovery/runbook.md): it restores
the latest backup into a scratch database, compares row counts and checksums,
writes a dated report and exits non-zero on any mismatch. It implements the R1
hash-reconciliation check of the runbook; it is a check, not an approval, and it
never reopens writes.

All bundled inputs are synthetic. The drill never contacts a real database in
tests or in the canonical gate.

## Rehearsal (fixtures only, no database)

Supply two JSON snapshots. Each snapshot has `row_counts` and `checksums` maps
keyed by relation. The four OPS-04 scope classes are `core_chart`,
`order_data`, `documents` and `document_associations`.

```sh
python3 -I restore_drill.py \
  --source-snapshot fixtures/synthetic_source_snapshot.json \
  --restored-snapshot fixtures/synthetic_restored_snapshot.json \
  --report-dir /tmp/opencode/recovery-reports \
  --now 2026-11-21T08:30:00Z
```

`--now` pins the UTC instant used in the report filename and header, making the
output deterministic for tests. Without it the current UTC time is used.

## Conducted drill (authorized operator only)

```sh
python3 -I restore_drill.py \
  --source-snapshot <recorded-source-snapshot.json> \
  --catalog fixtures/backups \
  --scratch-dsn <isolated-scratch-dsn> \
  --probe-sql <schema-specific-probe.sql> \
  --report-dir /tmp/opencode/recovery-reports
```

The catalog contains one JSON manifest per backup with `label`, `created_at`
(ISO-8601 UTC) and `artifact`. By default the newest manifest is restored;
`--backup <label>` selects a named one. The restore drops and recreates the
scratch database, then runs `pg_restore` and a `psql` probe. Adapt the probe SQL
to the pinned schema so it emits `relation|row_count|N` and
`relation|checksum|hex` rows; see
[`fixtures/synthetic_probe.sql`](fixtures/synthetic_probe.sql). The probe must
never print patient data, credentials or payload bytes. Credentials come from
the operator's environment, never from this repository or a report.

The report lists every expected relation and metric with expected/actual values
and a PASS/FAIL verdict. Exit codes: `0` all checks matched, `1` at least one
row-count or checksum mismatch, `2` unreadable input or invalid invocation. A
mismatch holds the affected data for review; it does not silently replace it.

## Checks

```sh
bash infra/recovery/check.sh
```

The gate runs the unit suite, a build-failing 100% line-coverage check for
`restore_drill.py` (stdlib `trace`, because `coverage.py` is not installed),
and positive/negative fixture runs of the CLI. Full branch/failure-path mapping
is recorded in the task notes. Real restore execution, measured RPO/RTO and
human acceptance remain a separate, authorized G5 gate.
