"""Executable acceptance tests for the OPS-04 backup/restore drill script.

Every test is synthetic and runs against fixtures and injected fakes only: no
PostgreSQL instance, backup artifact or credential is ever contacted. The tests
enumerate each branch and failure path of ``restore_drill`` so its behaviour is
fully specified before the implementation exists.
"""
import importlib.util
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "restore_drill.py"
SPEC = importlib.util.spec_from_file_location("restore_drill", MODULE_PATH)
restore_drill = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(restore_drill)

drill = restore_drill

NOW = datetime(2026, 11, 21, 8, 30, 0, tzinfo=timezone.utc)


def write_json(directory, name, payload):
    """Write a synthetic JSON fixture and return its path."""
    path = Path(directory) / name
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def source_payload():
    return {
        "row_counts": {
            "core_chart": 120,
            "order_data": 75,
            "documents": 40,
            "document_associations": 90,
        },
        "checksums": {
            "core_chart": "a" * 64,
            "order_data": "b" * 64,
            "documents": "c" * 64,
            "document_associations": "d" * 64,
        },
    }


def probe_output_for(payload):
    """Render synthetic psql output for a snapshot payload."""
    lines = []
    for relation, count in payload["row_counts"].items():
        lines.append(f"{relation}|row_count|{count}")
    for relation, checksum in payload["checksums"].items():
        lines.append(f"{relation}|checksum|{checksum}")
    return "\n".join(lines) + "\n"


class RecordingRunner:
    """Fake CommandRunner that records argv and replays canned stdout."""

    def __init__(self, outputs=None):
        self.calls = []
        self.outputs = list(outputs or [])

    def run(self, argv):
        self.calls.append(list(argv))
        if argv and argv[0] == "psql" and self.outputs:
            return self.outputs.pop(0)
        return ""


class SnapshotLoadingTests(unittest.TestCase):
    def test_load_snapshot_parses_counts_and_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_json(directory, "source.json", source_payload())
            snapshot = drill.load_snapshot(path)
            self.assertEqual(snapshot.row_counts["core_chart"], 120)
            self.assertEqual(snapshot.checksums["documents"], "c" * 64)

    def test_load_snapshot_coerces_keys_and_values_to_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_json(directory, "source.json", {
                "row_counts": {7: "3"},
                "checksums": {"rel": 12345},
            })
            snapshot = drill.load_snapshot(path)
            self.assertEqual(snapshot.row_counts, {"7": 3})
            self.assertEqual(snapshot.checksums, {"rel": "12345"})

    def test_load_snapshot_missing_key_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_json(directory, "bad.json", {"row_counts": {}})
            with self.assertRaises(drill.DrillError):
                drill.load_snapshot(path)

    def test_load_snapshot_non_integer_count_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = source_payload()
            payload["row_counts"]["core_chart"] = "not-a-number"
            path = write_json(directory, "bad.json", payload)
            with self.assertRaises(drill.DrillError):
                drill.load_snapshot(path)

    def test_load_snapshot_uncoercible_count_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = source_payload()
            payload["row_counts"]["core_chart"] = ["not", "a", "count"]
            path = write_json(directory, "bad.json", payload)
            with self.assertRaises(drill.DrillError):
                drill.load_snapshot(path)

    def test_load_snapshot_malformed_json_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text("{not json", encoding="utf-8")
            with self.assertRaises(drill.DrillError):
                drill.load_snapshot(path)

    def test_load_snapshot_unreadable_path_fails_closed(self):
        with self.assertRaises(drill.DrillError):
            drill.load_snapshot(Path(tempfile.gettempdir()) / "missing-snapshot.json")


class BackupCatalogTests(unittest.TestCase):
    def manifest(self, label, created_at):
        return {"label": label, "created_at": created_at,
                "artifact": "synthetic://backups/" + label + ".dump"}

    def test_load_backup_parses_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_json(directory, "b.json",
                              self.manifest("nightly", "2026-11-21T02:00:00Z"))
            backup = drill.load_backup(path)
            self.assertEqual(backup.label, "nightly")
            self.assertEqual(backup.created_at, "2026-11-21T02:00:00Z")
            self.assertIn("nightly.dump", backup.artifact)

    def test_load_backup_missing_field_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_json(directory, "b.json", {"label": "nightly"})
            with self.assertRaises(drill.DrillError):
                drill.load_backup(path)

    def test_latest_backup_picks_newest_creation_time(self):
        with tempfile.TemporaryDirectory() as directory:
            write_json(directory, "a.json", self.manifest("old", "2026-11-20T02:00:00Z"))
            write_json(directory, "b.json", self.manifest("new", "2026-11-21T02:00:00Z"))
            write_json(directory, "c.json", self.manifest("mid", "2026-11-20T20:00:00Z"))
            self.assertEqual(drill.latest_backup(directory).label, "new")

    def test_latest_backup_filters_by_explicit_label(self):
        with tempfile.TemporaryDirectory() as directory:
            write_json(directory, "a.json", self.manifest("old", "2026-11-20T02:00:00Z"))
            write_json(directory, "b.json", self.manifest("new", "2026-11-21T02:00:00Z"))
            self.assertEqual(drill.latest_backup(directory, label="old").label, "old")

    def test_latest_backup_ties_break_by_label(self):
        with tempfile.TemporaryDirectory() as directory:
            write_json(directory, "a.json", self.manifest("alpha", "2026-11-21T02:00:00Z"))
            write_json(directory, "b.json", self.manifest("beta", "2026-11-21T02:00:00Z"))
            self.assertEqual(drill.latest_backup(directory).label, "beta")

    def test_latest_backup_empty_catalog_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(drill.DrillError):
                drill.latest_backup(directory)

    def test_latest_backup_unknown_label_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            write_json(directory, "a.json", self.manifest("old", "2026-11-20T02:00:00Z"))
            with self.assertRaises(drill.DrillError):
                drill.latest_backup(directory, label="missing")


class RestoreCommandTests(unittest.TestCase):
    def test_restore_runs_drop_create_then_restore_in_order(self):
        backup = drill.Backup("nightly", "2026-11-21T02:00:00Z", "synthetic://b.dump")
        runner = RecordingRunner()
        drill.restore(backup, "scratch_drill", runner)
        self.assertEqual(runner.calls, [
            ["dropdb", "--if-exists", "--force", "scratch_drill"],
            ["createdb", "scratch_drill"],
            ["pg_restore", "--dbname", "scratch_drill", "--clean",
             "--if-exists", "synthetic://b.dump"],
        ])

    def test_subprocess_runner_returns_stdout(self):
        with mock.patch.object(drill.subprocess, "run") as run:
            run.return_value = mock.Mock(stdout="relation|row_count|1\n")
            output = drill.SubprocessRunner().run(["psql", "dsn", "-f", "probe.sql"])
        self.assertEqual(output, "relation|row_count|1\n")
        run.assert_called_once_with(["psql", "dsn", "-f", "probe.sql"],
                                    check=True, capture_output=True, text=True)


class ProbeTests(unittest.TestCase):
    PROBE_OUTPUT = (
        "core_chart|row_count|120\n"
        "\n"
        "core_chart|checksum|" + "a" * 64 + "\n"
        "documents|row_count|40\n"
        "documents|checksum|" + "c" * 64 + "\n"
    )

    def test_capture_snapshot_parses_counts_and_checksums(self):
        runner = RecordingRunner([self.PROBE_OUTPUT])
        snapshot = drill.capture_snapshot("probe.sql", "scratch_drill", runner)
        self.assertEqual(snapshot.row_counts, {"core_chart": 120, "documents": 40})
        self.assertEqual(snapshot.checksums,
                         {"core_chart": "a" * 64, "documents": "c" * 64})

    def test_capture_snapshot_invokes_psql_with_probe_file(self):
        runner = RecordingRunner([self.PROBE_OUTPUT])
        drill.capture_snapshot("probe.sql", "scratch_drill", runner)
        self.assertEqual(runner.calls[0],
                         ["psql", "scratch_drill", "-X", "-A", "-t", "-F", "|",
                          "-f", "probe.sql"])

    def test_capture_snapshot_rejects_malformed_row(self):
        runner = RecordingRunner(["only|two\n"])
        with self.assertRaises(drill.DrillError):
            drill.capture_snapshot("probe.sql", "scratch_drill", runner)

    def test_capture_snapshot_rejects_unknown_metric(self):
        runner = RecordingRunner(["core_chart|digest|abc\n"])
        with self.assertRaises(drill.DrillError):
            drill.capture_snapshot("probe.sql", "scratch_drill", runner)

    def test_capture_snapshot_rejects_non_integer_count(self):
        runner = RecordingRunner(["core_chart|row_count|many\n"])
        with self.assertRaises(drill.DrillError):
            drill.capture_snapshot("probe.sql", "scratch_drill", runner)

    def test_capture_snapshot_accepts_empty_probe_result(self):
        runner = RecordingRunner([""])
        snapshot = drill.capture_snapshot("probe.sql", "scratch_drill", runner)
        self.assertEqual(snapshot, drill.Snapshot({}, {}))


class ComparisonTests(unittest.TestCase):
    def snapshot(self, **overrides):
        payload = source_payload()
        payload.update(overrides)
        return drill.Snapshot(payload["row_counts"], payload["checksums"])

    def test_compare_matching_snapshots_yields_all_pass(self):
        source = self.snapshot()
        rows = drill.compare(source, self.snapshot())
        self.assertEqual(len(rows), 8)
        self.assertTrue(all(row.ok for row in rows))

    def test_compare_detects_missing_row_count(self):
        restored = self.snapshot(row_counts={})
        rows = drill.compare(self.snapshot(), restored)
        missing = [r for r in rows if r.metric == "row_count" and not r.ok]
        self.assertEqual(len(missing), 4)
        self.assertTrue(all(r.actual == "missing" for r in missing))

    def test_compare_detects_row_count_mismatch(self):
        restored = self.snapshot(row_counts={
            "core_chart": 999,
            "order_data": 75,
            "documents": 40,
            "document_associations": 90,
        })
        rows = drill.compare(self.snapshot(), restored)
        failed = [r for r in rows if not r.ok]
        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0].metric, "row_count")
        self.assertEqual(failed[0].expected, "120")
        self.assertEqual(failed[0].actual, "999")

    def test_compare_detects_missing_checksum(self):
        restored = self.snapshot(checksums={})
        rows = drill.compare(self.snapshot(), restored)
        missing = [r for r in rows if r.metric == "checksum" and not r.ok]
        self.assertEqual(len(missing), 4)
        self.assertTrue(all(r.actual == "missing" for r in missing))

    def test_compare_detects_checksum_mismatch(self):
        bad = dict(source_payload()["checksums"])
        bad["documents"] = "e" * 64
        restored = self.snapshot(checksums=bad)
        rows = drill.compare(self.snapshot(), restored)
        failed = [r for r in rows if not r.ok]
        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0].metric, "checksum")
        self.assertEqual(failed[0].relation, "documents")


class ReportTests(unittest.TestCase):
    def comparisons(self, ok=True):
        expected = "120" if ok else "999"
        return (drill.Comparison("core_chart", "row_count", "120", expected),
                drill.Comparison("core_chart", "checksum", "a" * 64, "a" * 64))

    def test_render_report_pass_contains_dated_header_and_verdict(self):
        text = drill.render_report("source.json", "scratch@nightly", NOW,
                                   self.comparisons(ok=True))
        self.assertIn("2026-11-21T08:30:00Z", text)
        self.assertIn("Verdict: PASS", text)
        self.assertIn("Mismatches: 0 of 2 checks", text)
        self.assertIn("core_chart", text)
        self.assertIn("checksum", text)

    def test_render_report_fail_lists_mismatch(self):
        text = drill.render_report("source.json", "scratch@nightly", NOW,
                                   self.comparisons(ok=False))
        self.assertIn("Verdict: FAIL", text)
        self.assertIn("Mismatches: 1 of 2 checks", text)
        self.assertIn("999", text)
        self.assertIn("| FAIL |", text)

    def test_write_report_uses_dated_filename_and_creates_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            report_dir = Path(directory) / "reports"
            path = drill.write_report(report_dir, NOW, "source.json",
                                      "scratch@nightly", self.comparisons())
            self.assertEqual(path.name, "restore-drill-20261121T083000Z.md")
            self.assertTrue(path.exists())
            self.assertIn("Verdict: PASS", path.read_text(encoding="utf-8"))

    def test_parse_instant_accepts_zulu_timestamp(self):
        self.assertEqual(drill.parse_instant("2026-11-21T08:30:00Z"), NOW)

    def test_parse_instant_rejects_bad_timestamp(self):
        with self.assertRaises(drill.DrillError):
            drill.parse_instant("not-a-time")


class MainTests(unittest.TestCase):
    def run_main(self, argv):
        return drill.main(argv)

    def rehearsal_args(self, source, restored, report_dir, now="2026-11-21T08:30:00Z"):
        args = ["--source-snapshot", str(source), "--restored-snapshot", str(restored),
                "--report-dir", str(report_dir)]
        if now is not None:
            args += ["--now", now]
        return args

    def test_rehearsal_matching_fixtures_exits_zero_and_writes_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            restored = write_json(directory, "restored.json", source_payload())
            reports = Path(directory) / "reports"
            code = self.run_main(self.rehearsal_args(source, restored, reports))
            self.assertEqual(code, 0)
            report = reports / "restore-drill-20261121T083000Z.md"
            self.assertTrue(report.exists())
            self.assertIn("Verdict: PASS", report.read_text(encoding="utf-8"))

    def test_rehearsal_mismatching_fixtures_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            bad = source_payload()
            bad["checksums"]["documents"] = "f" * 64
            restored = write_json(directory, "restored.json", bad)
            reports = Path(directory) / "reports"
            code = self.run_main(self.rehearsal_args(source, restored, reports))
            self.assertEqual(code, 1)
            report = reports / "restore-drill-20261121T083000Z.md"
            self.assertIn("Verdict: FAIL", report.read_text(encoding="utf-8"))

    def test_rehearsal_without_pinned_time_still_writes_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            restored = write_json(directory, "restored.json", source_payload())
            reports = Path(directory) / "reports"
            code = self.run_main(self.rehearsal_args(source, restored, reports, now=None))
            self.assertEqual(code, 0)
            self.assertEqual(len(list(reports.glob("restore-drill-*.md"))), 1)

    def test_conducted_mode_restores_latest_and_exits_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            catalog = Path(directory) / "catalog"
            catalog.mkdir()
            write_json(catalog, "b.json", {
                "label": "nightly", "created_at": "2026-11-21T02:00:00Z",
                "artifact": "synthetic://nightly.dump"})
            probe = Path(directory) / "probe.sql"
            probe.write_text("-- synthetic probe\n", encoding="utf-8")
            reports = Path(directory) / "reports"
            probe_output = probe_output_for(source_payload())
            runner = RecordingRunner([probe_output, probe_output])
            argv = ["--source-snapshot", str(source), "--catalog", str(catalog),
                    "--scratch-dsn", "scratch_drill", "--probe-sql", str(probe),
                    "--report-dir", str(reports), "--now", "2026-11-21T08:30:00Z"]
            with mock.patch.object(drill, "SubprocessRunner", return_value=runner):
                code = self.run_main(argv)
            self.assertEqual(code, 0)
            self.assertEqual(runner.calls[0],
                             ["dropdb", "--if-exists", "--force", "scratch_drill"])
            self.assertEqual(runner.calls[1], ["createdb", "scratch_drill"])
            self.assertEqual(runner.calls[2][0], "pg_restore")
            self.assertTrue((reports / "restore-drill-20261121T083000Z.md").exists())

    def test_conducted_mode_probe_mismatch_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            catalog = Path(directory) / "catalog"
            catalog.mkdir()
            write_json(catalog, "b.json", {
                "label": "nightly", "created_at": "2026-11-21T02:00:00Z",
                "artifact": "synthetic://nightly.dump"})
            probe = Path(directory) / "probe.sql"
            probe.write_text("-- synthetic probe\n", encoding="utf-8")
            reports = Path(directory) / "reports"
            bad = source_payload()
            bad["row_counts"]["order_data"] = 999
            probe_output = probe_output_for(bad)
            runner = RecordingRunner([probe_output, probe_output])
            argv = ["--source-snapshot", str(source), "--catalog", str(catalog),
                    "--scratch-dsn", "scratch_drill", "--probe-sql", str(probe),
                    "--report-dir", str(reports), "--now", "2026-11-21T08:30:00Z"]
            with mock.patch.object(drill, "SubprocessRunner", return_value=runner):
                code = self.run_main(argv)
            self.assertEqual(code, 1)

    def test_missing_snapshot_and_catalog_exits_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            code = self.run_main(["--source-snapshot", str(source),
                                  "--report-dir", str(Path(directory) / "reports")])
            self.assertEqual(code, 2)

    def test_catalog_without_scratch_dsn_exits_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            code = self.run_main(["--source-snapshot", str(source), "--catalog", "cat",
                                  "--report-dir", str(Path(directory) / "reports")])
            self.assertEqual(code, 2)

    def test_catalog_without_probe_sql_exits_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = write_json(directory, "source.json", source_payload())
            code = self.run_main(["--source-snapshot", str(source), "--catalog", "cat",
                                  "--scratch-dsn", "scratch_drill",
                                  "--report-dir", str(Path(directory) / "reports")])
            self.assertEqual(code, 2)

    def test_unreadable_source_snapshot_exits_error(self):
        code = self.run_main(["--source-snapshot", "/no/such/source.json",
                              "--restored-snapshot", "/no/such/restored.json",
                              "--report-dir", "reports"])
        self.assertEqual(code, 2)

    def test_missing_required_argument_exits_usage_error(self):
        with self.assertRaises(SystemExit) as raised:
            drill.main([])
        self.assertEqual(raised.exception.code, 2)


class CheckedInFixtureTests(unittest.TestCase):
    """The fixtures used by the canonical check script are valid inputs."""

    FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"

    def test_source_and_matching_restored_fixtures_pass(self):
        source = drill.load_snapshot(self.FIXTURES / "synthetic_source_snapshot.json")
        restored = drill.load_snapshot(
            self.FIXTURES / "synthetic_restored_snapshot.json")
        self.assertTrue(all(row.ok for row in drill.compare(source, restored)))

    def test_violation_restored_fixture_has_failures(self):
        source = drill.load_snapshot(self.FIXTURES / "synthetic_source_snapshot.json")
        restored = drill.load_snapshot(
            self.FIXTURES / "violation_restored_snapshot.json")
        rows = drill.compare(source, restored)
        self.assertEqual(sum(1 for row in rows if not row.ok), 2)

    def test_backup_catalog_fixture_loads_latest(self):
        backup = drill.latest_backup(self.FIXTURES / "backups")
        self.assertEqual(backup.label, "nightly")


if __name__ == "__main__":
    unittest.main()
