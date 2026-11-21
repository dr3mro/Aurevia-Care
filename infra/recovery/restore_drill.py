#!/usr/bin/env python3
"""OPS-04 backup/restore drill: restore the latest backup into a scratch
database, compare row counts and checksums, and write a dated report.

The reconciliation core is database-independent. A rehearsal supplies both
snapshots as JSON fixtures (``--source-snapshot`` / ``--restored-snapshot``) so
the drill never contacts a real database and every report is labelled as a
synthetic rehearsal. A conducted drill supplies a backup catalog, a scratch DSN
and a probe SQL file plus ``--confirm-scratch``; the restore and the snapshot
probe run through an injectable :class:`CommandRunner`.

The script compares only the relations recorded in the source snapshot. It is a
reconciliation check, not an approval: the drill report never reopens writes.
Real restore execution, measured RPO/RTO and human acceptance remain a separate,
authorized G5 gate described in ``docs/operations/recovery/runbook.md``.

Usage: ``python3 -I infra/recovery/restore_drill.py --help``
Exit codes: 0 = every check matched, 1 = a row count or checksum mismatch,
2 = unreadable input, failed command or an invalid invocation.
"""
import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

SYNTHETIC_REHEARSAL = "synthetic_rehearsal"
CONDUCTED_DRILL = "conducted_drill"


class DrillError(Exception):
    """Raised when drill input is unreadable, malformed or incomplete."""


@dataclass(frozen=True)
class Snapshot:
    """Observed state of a database: row counts and checksums by relation."""

    row_counts: dict
    checksums: dict


@dataclass(frozen=True)
class Backup:
    """A synthetic backup manifest describing the artifact to restore."""

    label: str
    created_at: str
    artifact: str


@dataclass(frozen=True)
class Comparison:
    """One expected-versus-observed reconciliation check and its result."""

    relation: str
    metric: str
    expected: str
    actual: str

    @property
    def ok(self):
        """Return whether the observed value equals the expected value."""
        return self.expected == self.actual


class SubprocessRunner:
    """Run an operating-system command and return its standard output.

    Commands receive no shell and no credentials from this process; the caller
    supplies the full argument vector. A conducted drill must be run by an
    operator whose environment already holds the scoped database credentials.
    """

    def run(self, argv):
        """Execute ``argv``, failing the drill when the command fails.

        The error message names only the command and its status. The argument
        vector can contain a credential-bearing DSN and is never echoed.
        """
        try:
            return subprocess.run(argv, check=True, capture_output=True,
                                  text=True).stdout
        except subprocess.CalledProcessError as error:
            raise DrillError(
                f"command {Path(argv[0]).name} failed with exit status "
                f"{error.returncode}") from error


def _read_json(path):
    """Read a JSON fixture, raising :class:`DrillError` on any read failure."""
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise DrillError(f"cannot read JSON {path}: {error}") from error


def load_snapshot(path):
    """Load a snapshot fixture of row counts and checksums from ``path``.

    Values are normalised so synthetic fixtures with numeric values are
    compared consistently. Malformed containers and uncoercible values fail
    closed rather than surfacing a raw ``AttributeError`` or ``TypeError``.
    """
    data = _read_json(path)
    try:
        counts = {str(key): int(value) for key, value in data["row_counts"].items()}
        checksums = {str(key): str(value) for key, value in data["checksums"].items()}
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise DrillError(f"invalid snapshot in {path}: {error}") from error
    return Snapshot(counts, checksums)


def load_backup(path):
    """Load a single backup manifest, requiring label, time and artifact."""
    data = _read_json(path)
    try:
        return Backup(str(data["label"]), str(data["created_at"]),
                      str(data["artifact"]))
    except (KeyError, TypeError, AttributeError) as error:
        raise DrillError(f"invalid backup manifest {path}: {error}") from error


def parse_instant(text):
    """Parse an ISO-8601 instant and normalise it to UTC.

    A trailing ``Z`` denotes UTC; a naive timestamp is rejected so a report
    can never label local wall time as UTC.
    """
    try:
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        value = datetime.fromisoformat(text)
        if value.tzinfo is None:
            raise ValueError("timestamp must include a timezone")
        return value.astimezone(timezone.utc)
    except (ValueError, TypeError, AttributeError) as error:
        raise DrillError(f"invalid timestamp {text!r}") from error


def latest_backup(catalog_dir, label=None):
    """Return the newest backup manifest in ``catalog_dir``.

    Timestamps are parsed as instants before ordering, so an ISO offset cannot
    make an older backup look newer. Equal instants break by label, making the
    choice deterministic. An optional ``label`` selects a named backup.
    """
    manifests = sorted(Path(catalog_dir).glob("*.json"))
    if not manifests:
        raise DrillError(f"no backup manifests in {catalog_dir}")
    backups = [load_backup(path) for path in manifests]
    if label is not None:
        backups = [backup for backup in backups if backup.label == label]
    if not backups:
        raise DrillError(f"no backup with label {label!r} in {catalog_dir}")
    return max(backups, key=lambda backup: (parse_instant(backup.created_at),
                                            backup.label))


def restore(backup, scratch_dsn, runner):
    """Recreate ``scratch_dsn`` and restore ``backup`` into it.

    The scratch database is dropped first so a rehearsal cannot silently layer
    a restore on top of surviving state. Commands run through ``runner``; tests
    inject a fake so no real database is touched. :func:`main` requires an
    explicit confirmation before this function is ever called.
    """
    runner.run(["dropdb", "--if-exists", "--force", scratch_dsn])
    runner.run(["createdb", scratch_dsn])
    runner.run(["pg_restore", "--dbname", scratch_dsn, "--clean",
                "--if-exists", backup.artifact])


def _probe_rows(output):
    """Yield three-column ``relation|metric|value`` rows from psql output."""
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        columns = stripped.split("|")
        if len(columns) != 3:
            raise DrillError(f"malformed probe row: {line!r}")
        yield columns


def capture_snapshot(probe_sql, scratch_dsn, runner):
    """Probe ``scratch_dsn`` with ``probe_sql`` and build a snapshot.

    The probe SQL is supplied by the operator; it must emit one row per metric
    as ``relation|row_count|N`` or ``relation|checksum|hex``. No SQL is embedded
    here, so the drill is independent of the target schema and tests never run
    a real query.
    """
    output = runner.run(["psql", scratch_dsn, "-X", "-A", "-t", "-F", "|",
                         "-f", probe_sql])
    counts = {}
    checksums = {}
    for relation, metric, value in _probe_rows(output):
        if metric == "row_count":
            try:
                counts[relation] = int(value)
            except ValueError as error:
                raise DrillError(
                    f"invalid row count for {relation}: {value!r}") from error
        elif metric == "checksum":
            checksums[relation] = value
        else:
            raise DrillError(f"unknown probe metric {metric!r} for {relation}")
    return Snapshot(counts, checksums)


def _display(value):
    """Render an observed value, marking a missing relation explicitly."""
    return "missing" if value is None else str(value)


def compare(source, restored):
    """Build one reconciliation row per source relation and metric.

    Relations recorded only in the restored snapshot are ignored: the source
    snapshot defines the reconciliation set, so an unrelated scratch relation
    cannot manufacture a failure or hide a missing expected relation.
    """
    rows = []
    for relation, expected in source.row_counts.items():
        rows.append(Comparison(relation, "row_count", str(expected),
                               _display(restored.row_counts.get(relation))))
    for relation, expected in source.checksums.items():
        rows.append(Comparison(relation, "checksum", expected,
                               _display(restored.checksums.get(relation))))
    return tuple(rows)


def render_report(source_label, restored_label, generated_at, comparisons, mode):
    """Render a dated, explicitly labelled Markdown checksum report."""
    failures = sum(1 for row in comparisons if not row.ok)
    verdict = "PASS" if failures == 0 else "FAIL"
    lines = [
        "# Backup restore drill - checksum report",
        "",
        f"Generated (UTC): {generated_at.strftime('%Y-%m-%dT%H:%M:%SZ')}",
        f"Kind: {mode}",
        f"Source snapshot: {source_label}",
        f"Restored snapshot: {restored_label}",
        f"Verdict: {verdict}",
        f"Mismatches: {failures} of {len(comparisons)} checks",
    ]
    if mode == SYNTHETIC_REHEARSAL:
        lines.append("NOT a conducted drill - synthetic rehearsal; not final evidence")
    lines += [
        "",
        "| Relation | Check | Expected | Actual | Result |",
        "|---|---|---|---|---|",
    ]
    for row in comparisons:
        result = "PASS" if row.ok else "FAIL"
        lines.append(f"| {row.relation} | {row.metric} | {row.expected} | "
                     f"{row.actual} | {result} |")
    return "\n".join(lines) + "\n"


def write_report(report_dir, generated_at, source_label, restored_label,
                 comparisons, mode):
    """Create a new dated report and return its path.

    Creation is exclusive: a second drill with the same pinned timestamp can
    never overwrite an earlier report and destroy reconciliation evidence.
    """
    report_dir.mkdir(parents=True, exist_ok=True)
    name = "restore-drill-" + generated_at.strftime("%Y%m%dT%H%M%SZ") + ".md"
    path = report_dir / name
    try:
        with path.open("x", encoding="utf-8") as handle:
            handle.write(render_report(source_label, restored_label, generated_at,
                                       comparisons, mode))
    except FileExistsError as error:
        raise DrillError(f"report already exists: {path}") from error
    return path


def build_parser():
    """Build the command-line parser for the drill."""
    parser = argparse.ArgumentParser(
        description="Restore the latest backup into a scratch database and "
                    "reconcile row counts and checksums (OPS-04).")
    parser.add_argument("--source-snapshot", required=True,
                        help="JSON snapshot recorded at the source/backup")
    parser.add_argument("--report-dir", required=True,
                        help="directory for the dated reconciliation report")
    drill_mode = parser.add_mutually_exclusive_group(required=True)
    drill_mode.add_argument("--restored-snapshot",
                            help="JSON snapshot of the restored database (rehearsal)")
    drill_mode.add_argument("--catalog",
                            help="directory of backup manifests (conducted drill)")
    parser.add_argument("--scratch-dsn", help="scratch database connection name")
    parser.add_argument("--backup", help="explicit backup label instead of latest")
    parser.add_argument("--probe-sql", help="SQL file emitting relation|metric|value")
    parser.add_argument("--confirm-scratch", action="store_true",
                        help="confirm the conducted drill may drop the scratch database")
    parser.add_argument("--now", help="pinned UTC instant for a deterministic report")
    return parser


def main(argv=None):
    """Run the drill; return 0 on a full match, 1 on mismatch, 2 on error."""
    args = build_parser().parse_args(argv)
    try:
        source = load_snapshot(Path(args.source_snapshot))
        if args.restored_snapshot is not None:
            if (args.scratch_dsn or args.probe_sql or args.backup
                    or args.confirm_scratch):
                raise DrillError(
                    "--catalog-only options cannot be used with --restored-snapshot")
            restored = load_snapshot(Path(args.restored_snapshot))
            restored_label = args.restored_snapshot
            mode = SYNTHETIC_REHEARSAL
        else:
            if not args.scratch_dsn:
                raise DrillError("--scratch-dsn is required with --catalog")
            if not args.probe_sql:
                raise DrillError("--probe-sql is required with --catalog")
            if not args.confirm_scratch:
                raise DrillError(
                    "conducted drill requires --confirm-scratch before dropping "
                    "the scratch database")
            runner = SubprocessRunner()
            backup = latest_backup(Path(args.catalog), args.backup)
            restore(backup, args.scratch_dsn, runner)
            restored = capture_snapshot(args.probe_sql, args.scratch_dsn, runner)
            # A DSN may carry credentials, so the report names only the backup.
            restored_label = "restored@" + backup.label
            mode = CONDUCTED_DRILL
        comparisons = compare(source, restored)
        generated_at = (parse_instant(args.now) if args.now
                        else datetime.now(timezone.utc))
        report_path = write_report(Path(args.report_dir), generated_at,
                                   args.source_snapshot, restored_label,
                                   comparisons, mode)
    except (DrillError, OSError, ValueError) as error:
        print(f"restore drill: error: {error}", file=sys.stderr)
        return 2
    failures = [row for row in comparisons if not row.ok]
    print(f"restore drill: {len(failures)} mismatch(es) of {len(comparisons)} "
          f"checks; report: {report_path}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())  #pragma NO COVER - CLI entry point, exercised via main() in tests
