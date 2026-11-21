# Recovery drill (OPS-04)

`restore_drill.py` rehearses the reconciliation part of the
[recovery drill runbook](../../docs/operations/recovery/runbook.md): it restores
the latest backup into a scratch database, compares row counts and checksums,
writes a dated report and exits non-zero on any mismatch. It implements the R1
hash-reconciliation check of the runbook; it is a check, not an approval, and it
never reopens writes.

All bundled inputs are synthetic. The drill never contacts a real database in
tests or in the canonical gate. Every report states its `Kind`: a fixture-driven
run is labelled `synthetic_rehearsal` and carries an explicit "NOT a conducted
drill" line, so a rehearsal can never be attached as evidence of a real restore.

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
output deterministic for tests. Without it the current UTC time is used. A
rehearsal must not be combined with `--catalog` or any conducted-only option.

## Conducted drill (authorized operator only)

```sh
python3 -I restore_drill.py \
  --source-snapshot <recorded-source-snapshot.json> \
  --catalog fixtures/backups \
  --scratch-dsn <isolated-scratch-dsn> \
  --probe-sql <schema-specific-probe.sql> \
  --confirm-scratch \
  --report-dir /tmp/opencode/recovery-reports
```

The catalog contains one JSON manifest per backup with `label`, `created_at`
(ISO-8601 with an explicit UTC offset or `Z`) and `artifact`. Backups are
ordered by the parsed instant, not by wall-clock text, so an offset cannot make
an older backup look newer. By default the newest manifest is restored;
`--backup <label>` selects a named one.

`--confirm-scratch` is mandatory: without it the tool exits 2 before running
any command. It is an operator acknowledgement that the DSN names an isolated,
unused scratch database (runbook step D1), not a protection against a wrong
target — only the operator can verify that. On confirmation the tool drops and
recreates the scratch database, runs `pg_restore`, and probes with `psql`.
Adapt the probe SQL to the pinned schema so it emits `relation|row_count|N` and
`relation|checksum|hex` rows; see
[`fixtures/synthetic_probe.sql`](fixtures/synthetic_probe.sql). The probe must
never print patient data, credentials or payload bytes. Credentials come from
the operator's environment, never from this repository, a report or a log: the
DSN is never persisted, and a failed command is reported by name and exit
status only.

The report lists every expected relation and metric with expected/actual values
and a PASS/FAIL verdict. Reports are created exclusively, so a repeat with the
same pinned timestamp cannot overwrite earlier evidence. Exit codes: `0` all
checks matched, `1` at least one row-count or checksum mismatch, `2` unreadable
input, failed command or invalid invocation. A mismatch holds the affected data
for review; it does not silently replace it.

## Checks

```sh
bash infra/recovery/check.sh
```

The gate runs the unit suite, a build-failing 100% line-coverage check for
`restore_drill.py` (stdlib `trace`, because `coverage.py` is not installed),
and positive/negative fixture runs of the CLI. Full branch/failure-path mapping
is recorded in the task notes. Real restore execution, measured RPO/RTO and
human acceptance remain a separate, authorized G5 gate.
