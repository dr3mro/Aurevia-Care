"""Executable acceptance tests for the OPS-03 pilot load generator.

All data and targets are synthetic. The module is exercised through three
routes: pure functions (percentiles, planning, verdict), a deterministic fake
clock/sender for the scheduling loop, and a short profile against a local
``http.server`` fake server bound to an ephemeral loopback port. No test ever
points the generator at a non-loopback host.

Every branch of the production module is named by a test so a reader can trace
a condition to its assertion (AGENTS.md, Coverage rules).
"""
import contextlib
import importlib.util
import io
import json
import re
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
from urllib.parse import urlsplit

MODULE_PATH = Path(__file__).resolve().parents[1] / "load_generator.py"
SPEC = importlib.util.spec_from_file_location("load_generator", MODULE_PATH)
load_generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load_generator)

lg = load_generator


# --------------------------------------------------------------------------
# Test doubles
# --------------------------------------------------------------------------
class FakeClock:
    """Deterministic monotonic clock and sleeper for the scheduling loop."""

    def __init__(self, start=100.0):
        self.now = start

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += max(0.0, seconds)


def fixed_sender(clock=None, latency_ms=5.0, fail_names=()):
    """Return a sender that optionally advances a fake clock or raises."""
    def send(spec):
        if spec.name in fail_names:
            raise OSError("synthetic connection failure")
        if clock is not None:
            clock.now += latency_ms / 1000.0
    return send


class InlineFuture:
    """A completed future so concurrency tests stay deterministic."""

    def __init__(self, function, *arguments):
        try:
            self._value = function(*arguments)
            self._error = None
        except Exception as error:
            self._value = None
            self._error = error

    def result(self):
        if self._error is not None:
            raise self._error
        return self._value


class InlineExecutor:
    """Execute submissions immediately in the calling thread."""

    instances = []

    def __init__(self, max_workers):
        self.max_workers = max_workers
        self.shutdown_called = False
        InlineExecutor.instances.append(self)

    def submit(self, function, *arguments):
        return InlineFuture(function, *arguments)

    def shutdown(self, wait=True):
        self.shutdown_called = True


def inline_factory(max_workers):
    """Executor factory used by deterministic scheduling tests."""
    return InlineExecutor(max_workers)


class FakeServerHandler(BaseHTTPRequestHandler):
    """Return a fixed body; optionally sleep to simulate a slow read route."""

    read_delay_seconds = 0.0
    command_delay_seconds = 0.0
    fail_status = None

    def do_GET(self):
        self._respond("read", self.read_delay_seconds)

    def do_POST(self):
        self._respond("command", self.command_delay_seconds)

    def _respond(self, route_class, delay):
        import time
        if delay:
            time.sleep(delay)
        status = self.fail_status or 200
        body = json.dumps({"route_class": route_class, "path": self.path}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # keep test output quiet; never log patient or request data


def start_fake_server():
    """Start a loopback fake server on an ephemeral port; return (server, url)."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeServerHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[:2]
    return server, f"http://{host}:{port}"


class PercentileTest(unittest.TestCase):
    """p95 uses nearest-rank on a sorted copy; empty input has no percentile."""

    def test_empty_samples_have_no_percentile(self):
        self.assertIsNone(lg.percentile_ms([], 0.95))

    def test_single_sample_is_its_own_percentile(self):
        self.assertEqual(lg.percentile_ms([42.0], 0.95), 42.0)

    def test_percentile_uses_nearest_rank_on_unsorted_input(self):
        values = [float(v) for v in range(1, 101)]  # 1..100
        # nearest-rank ceil(0.95*100)-1 = 94 -> 95th value
        self.assertEqual(lg.percentile_ms(list(reversed(values)), 0.95), 95.0)

    def test_percentile_zero_fraction_exercises_the_lower_rank_clamp(self):
        self.assertEqual(lg.percentile_ms([10.0, 20.0, 30.0], 0.0), 10.0)

    def test_percentile_above_one_exercises_the_upper_rank_clamp(self):
        self.assertEqual(lg.percentile_ms([10.0, 20.0, 30.0], 2.0), 30.0)

    def test_percentile_fraction_of_one_selects_the_maximum(self):
        self.assertEqual(lg.percentile_ms([10.0, 20.0, 30.0], 1.0), 30.0)


class ProfileAndDatasetTest(unittest.TestCase):
    """The OPS-03 literals are fixed and the synthetic dataset is deterministic."""

    def test_pilot_profile_matches_ops03_literals(self):
        self.assertEqual(lg.PILOT_PROFILE, {
            "organizations": 20,
            "patient_charts": 100000,
            "concurrent_staff": 200,
            "requests_per_second": 50,
            "duration_minutes": 30,
        })

    def test_thresholds_match_ops03_targets(self):
        self.assertEqual(lg.READ_P95_MS_MAX, 500)
        self.assertEqual(lg.COMMAND_P95_MS_MAX, 900)

    def test_pilot_profile_artifact_agrees_with_the_enforced_literals(self):
        artifact_path = MODULE_PATH.parent / "pilot-profile.json"
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
        self.assertEqual(artifact["profile"], lg.PILOT_PROFILE)
        self.assertEqual(artifact["requirement"], "OPS-03")
        self.assertEqual(artifact["latency_targets_ms"], {
            "read": lg.READ_P95_MS_MAX, "command": lg.COMMAND_P95_MS_MAX})
        self.assertEqual(artifact["workload_mix"]["read"], lg.DEFAULT_READ_WEIGHT)

    def test_valid_profile_builds_dataset(self):
        dataset = lg.synthetic_dataset(dict(lg.PILOT_PROFILE), seed=7)
        self.assertEqual(dataset["organizations"], 20)
        self.assertEqual(dataset["charts_per_organization"], 5000)
        self.assertEqual(dataset["concurrent_staff"], 200)
        self.assertTrue(dataset["synthetic_only"])

    def test_each_required_profile_key_is_validated(self):
        for key in ("organizations", "patient_charts", "concurrent_staff",
                    "requests_per_second", "duration_minutes"):
            with self.subTest(key=key):
                bad = dict(lg.PILOT_PROFILE)
                bad[key] = 0
                with self.assertRaises(ValueError):
                    lg.synthetic_dataset(bad, seed=7)
                bad[key] = "20"
                with self.assertRaises(ValueError):
                    lg.synthetic_dataset(bad, seed=7)
                bad[key] = True
                with self.assertRaises(ValueError):
                    lg.synthetic_dataset(bad, seed=7)

    def test_missing_profile_key_is_rejected(self):
        bad = dict(lg.PILOT_PROFILE)
        del bad["duration_minutes"]
        with self.assertRaises(ValueError):
            lg.synthetic_dataset(bad, seed=7)

    def test_indivisible_chart_count_is_rejected(self):
        bad = dict(lg.PILOT_PROFILE)
        bad["patient_charts"] = 100001
        with self.assertRaises(ValueError):
            lg.synthetic_dataset(bad, seed=7)

    def test_dataset_is_deterministic_for_a_seed(self):
        profile = dict(lg.PILOT_PROFILE)
        self.assertEqual(lg.synthetic_dataset(profile, 7),
                         lg.synthetic_dataset(profile, 7))

    def test_synthetic_chart_key_has_no_phi_shape(self):
        key = lg.chart_key(3, 42)
        self.assertEqual(key, "org-003/chart-000042")
        self.assertIsNone(re.search(r"\d{3}-\d{2}-\d{4}", key))
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", key))


class TargetValidationTest(unittest.TestCase):
    """The generator must never target a real environment by default."""

    def test_loopback_http_target_is_allowed(self):
        self.assertEqual(
            lg.validate_target("http://127.0.0.1:8080", "synthetic", False).hostname,
            "127.0.0.1")

    def test_localhost_and_ipv6_loopback_are_allowed(self):
        for url in ("http://localhost:9000", "http://[::1]:9000"):
            with self.subTest(url=url):
                self.assertIsNotNone(lg.validate_target(url, "synthetic", False))

    def test_non_loopback_target_requires_explicit_flag(self):
        with self.assertRaises(ValueError):
            lg.validate_target("https://staging.example.test", "synthetic", False)
        parts = lg.validate_target("https://staging.example.test", "synthetic", True)
        self.assertEqual(parts.hostname, "staging.example.test")

    def test_non_synthetic_environment_is_refused(self):
        with self.assertRaises(ValueError):
            lg.validate_target("http://127.0.0.1:8080", "production", False)
        with self.assertRaises(ValueError):
            lg.validate_target("https://staging.example.test", "production", True)

    def test_unsupported_scheme_is_refused(self):
        with self.assertRaises(ValueError):
            lg.validate_target("ftp://127.0.0.1", "synthetic", False)

    def test_target_without_hostname_is_refused(self):
        with self.assertRaises(ValueError):
            lg.validate_target("http:///api", "synthetic", False)

    def test_port_for_uses_explicit_or_scheme_default(self):
        self.assertEqual(lg.port_for(urlsplit("http://host:8080")), 8080)
        self.assertEqual(lg.port_for(urlsplit("http://host")), 80)
        self.assertEqual(lg.port_for(urlsplit("https://host")), 443)

    def test_malformed_port_is_refused_as_a_configuration_error(self):
        with self.assertRaises(ValueError):
            lg.validate_target("http://127.0.0.1:not-a-port", "synthetic", False)


class RequestPlanningTest(unittest.TestCase):
    """Request plans carry a route class and only synthetic identifiers."""

    def setUp(self):
        self.dataset = lg.synthetic_dataset(dict(lg.PILOT_PROFILE), seed=7)

    def test_plan_is_deterministic_for_a_seed(self):
        a = lg.plan_requests(40, self.dataset, 7, 0.7)
        b = lg.plan_requests(40, self.dataset, 7, 0.7)
        self.assertEqual(a, b)

    def test_plan_contains_both_route_classes(self):
        plan = lg.plan_requests(200, self.dataset, 7, 0.7)
        self.assertEqual({spec.route_class for spec in plan}, set(lg.ROUTE_CLASSES))

    def test_read_weight_selects_only_reads_at_one_and_only_commands_at_zero(self):
        all_reads = lg.plan_requests(20, self.dataset, 7, 1.0)
        all_commands = lg.plan_requests(20, self.dataset, 7, 0.0)
        self.assertTrue(all(s.route_class == "read" for s in all_reads))
        self.assertTrue(all(s.route_class == "command" for s in all_commands))

    def test_read_weight_outside_range_is_rejected(self):
        for weight in (-0.1, 1.1):
            with self.subTest(weight=weight):
                with self.assertRaises(ValueError):
                    lg.plan_requests(10, self.dataset, 7, weight)

    def test_every_spec_uses_a_declared_family_and_synthetic_path(self):
        plan = lg.plan_requests(500, self.dataset, 7, 0.7)
        declared = set(lg.READ_FAMILIES) | set(lg.COMMAND_FAMILIES)
        self.assertTrue(declared)
        for spec in plan:
            self.assertIn(spec.name, declared)
            self.assertIn("/synthetic/", spec.path)
            self.assertIsNone(re.search(r"\d{3}-\d{2}-\d{4}", spec.path))
            self.assertIn(spec.route_class, lg.ROUTE_CLASSES)

    def test_plan_of_zero_requests_is_empty(self):
        self.assertEqual(lg.plan_requests(0, self.dataset, 7, 0.7), [])


class HttpSenderTest(unittest.TestCase):
    """The stdlib sender reports success, HTTP errors and transport errors."""

    def setUp(self):
        self.server, self.url = start_fake_server()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_sender_returns_none_on_a_success(self):
        send = lg.http_sender(self.url, timeout_seconds=2.0)
        spec = lg.RequestSpec("audit.query", "read", "GET", "/synthetic/chart-1")
        self.assertIsNone(send(spec))

    def test_sender_raises_on_a_non_success_status(self):
        FakeServerHandler.fail_status = 503
        try:
            send = lg.http_sender(self.url, timeout_seconds=2.0)
            spec = lg.RequestSpec("audit.query", "read", "GET", "/synthetic/chart-1")
            with self.assertRaises(lg.RequestFailed):
                send(spec)
        finally:
            FakeServerHandler.fail_status = None

    def test_sender_raises_on_a_transport_failure(self):
        send = lg.http_sender("http://127.0.0.1:1", timeout_seconds=0.2)
        spec = lg.RequestSpec("audit.query", "read", "GET", "/synthetic/chart-1")
        with self.assertRaises(OSError):
            send(spec)

    def test_sender_uses_https_connection_for_https_target(self):
        class Response:
            status = 200

            def read(self):
                return b"{}"

        with mock.patch.object(lg.http.client, "HTTPSConnection") as https:
            https.return_value.getresponse.return_value = Response()
            send = lg.http_sender("https://127.0.0.1:8443", timeout_seconds=1.0)
            spec = lg.RequestSpec("audit.query", "read", "GET", "/synthetic/chart-1")
            self.assertIsNone(send(spec))
            https.assert_called_once_with("127.0.0.1", 8443, timeout=1.0)


class RunLoadTest(unittest.TestCase):
    """The scheduling loop honours warm-up, deadline, sleep and error paths."""

    def setUp(self):
        self.dataset = lg.synthetic_dataset(dict(lg.PILOT_PROFILE), seed=7)

    def _run(self, clock, sender, rate=10.0, duration=1.0, warm_up=0.2,
             count=50, workers=1):
        plan = lg.plan_requests(count, self.dataset, 7, 0.5)
        return lg.run_load(plan, sender, rate, duration, warm_up,
                           workers=workers, executor_factory=inline_factory,
                           monotonic=clock.monotonic, sleep=clock.sleep)

    def test_warm_up_requests_are_excluded_from_measured_samples(self):
        clock = FakeClock()
        result = self._run(clock, fixed_sender(clock=clock, latency_ms=5.0))
        self.assertGreater(result["warmup_issued"], 0)
        self.assertGreater(len(result["read_latencies_ms"]), 0)
        self.assertGreater(len(result["command_latencies_ms"]), 0)
        self.assertEqual(result["errors"], 0)

    def test_loop_stops_at_the_deadline(self):
        clock = FakeClock()
        result = self._run(clock, fixed_sender(clock=clock), count=10000)
        self.assertLess(result["issued"], 10000)

    def test_sleep_path_is_taken_when_scheduled_time_is_in_the_future(self):
        clock = FakeClock()
        slept = []

        def sender(spec):
            slept.append(clock.now)
            clock.now += 0.001

        result = self._run(clock, sender)
        # The first request is issued at the start with no sleep; later requests
        # are scheduled in the future and therefore advance the fake clock by
        # more than their own simulated latency.
        self.assertGreater(clock.now - 100.0, result["issued"] * 0.001)

    def test_failed_requests_are_counted_without_a_latency_sample(self):
        clock = FakeClock()
        plan = lg.plan_requests(50, self.dataset, 7, 0.5)
        commands = [s for s in plan if s.route_class == "command"]
        result = lg.run_load(plan, fixed_sender(clock=clock, fail_names={
            s.name for s in commands}), 10.0, 1.0, 0.0,
            executor_factory=inline_factory,
            monotonic=clock.monotonic, sleep=clock.sleep)
        self.assertGreater(result["errors"], 0)
        self.assertEqual(result["command_latencies_ms"], [])

    def test_empty_plan_produces_no_samples(self):
        clock = FakeClock()
        result = lg.run_load([], fixed_sender(clock=clock), 10.0, 1.0, 0.0,
                             executor_factory=inline_factory,
                             monotonic=clock.monotonic, sleep=clock.sleep)
        self.assertEqual(result["read_latencies_ms"], [])
        self.assertEqual(result["issued"], 0)

    def test_concurrent_staff_drives_the_worker_pool(self):
        clock = FakeClock()
        InlineExecutor.instances.clear()
        self._run(clock, fixed_sender(clock=clock), workers=200)
        self.assertEqual([item.max_workers for item in InlineExecutor.instances],
                         [200])
        self.assertTrue(InlineExecutor.instances[0].shutdown_called)


class SummarizeTest(unittest.TestCase):
    """The verdict fails closed on breaches, errors and missing samples."""

    @staticmethod
    def result(read_ms, command_ms, errors=0):
        return {
            "read_latencies_ms": [float(v) for v in read_ms],
            "command_latencies_ms": [float(v) for v in command_ms],
            "errors": errors,
            "issued": len(read_ms) + len(command_ms) + errors,
            "warmup_issued": 0,
        }

    def test_passing_result_has_separate_p95_values(self):
        report = lg.summarize(self.result([10] * 100, [100] * 100))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["p95_read_ms"], 10.0)
        self.assertEqual(report["p95_command_ms"], 100.0)
        self.assertTrue(all(c["passed"] for c in report["checks"]))
        self.assertEqual(report["kind"], "synthetic_rehearsal")
        self.assertFalse(report["final_evidence"])

    def test_read_p95_at_threshold_passes_and_over_fails(self):
        at = lg.summarize(self.result([500] * 100, [100] * 100))
        over = lg.summarize(self.result([501] * 100, [100] * 100))
        self.assertEqual(at["verdict"], "pass")
        self.assertEqual(over["verdict"], "fail")
        self.assertFalse(over["checks_byname"]["p95.read"]["passed"])

    def test_command_p95_at_threshold_passes_and_over_fails(self):
        at = lg.summarize(self.result([10] * 100, [900] * 100))
        over = lg.summarize(self.result([10] * 100, [901] * 100))
        self.assertEqual(at["verdict"], "pass")
        self.assertEqual(over["verdict"], "fail")
        self.assertFalse(over["checks_byname"]["p95.command"]["passed"])

    def test_errors_fail_the_verdict(self):
        report = lg.summarize(self.result([10] * 100, [100] * 100, errors=1))
        self.assertEqual(report["verdict"], "fail")
        self.assertFalse(report["checks_byname"]["errors"]["passed"])

    def test_missing_class_samples_fail_the_verdict(self):
        report = lg.summarize(self.result([], [100] * 100))
        self.assertEqual(report["verdict"], "fail")
        self.assertFalse(report["checks_byname"]["samples.read"]["passed"])
        self.assertIsNone(report["p95_read_ms"])

    def test_all_breaches_are_reported_together(self):
        report = lg.summarize(self.result([600] * 100, [950] * 100, errors=2))
        self.assertEqual(report["verdict"], "fail")
        self.assertEqual(len(report["failed"]), 3)


class ReportTest(unittest.TestCase):
    """Human text and machine JSON reports both expose the two p95 values."""

    def test_format_report_shows_both_percentiles_and_verdict(self):
        report = lg.summarize(SummarizeTest.result([10] * 100, [100] * 100))
        text = lg.format_report(report)
        self.assertIn("p95 reads", text)
        self.assertIn("p95 commands", text)
        self.assertIn("PASS", text)
        self.assertIn("SYNTHETIC REHEARSAL - not final pilot evidence", text)

    def test_format_report_handles_missing_percentiles(self):
        report = lg.summarize(SummarizeTest.result([], []))
        text = lg.format_report(report)
        self.assertIn("n/a", text)
        self.assertIn("FAIL", text)

    def test_json_report_round_trips(self):
        report = lg.summarize(SummarizeTest.result([10] * 100, [100] * 100))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            lg.write_json_report(path, report)
            loaded = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(loaded["p95_read_ms"], report["p95_read_ms"])
        self.assertEqual(loaded["p95_command_ms"], report["p95_command_ms"])


class MainTest(unittest.TestCase):
    """CLI exit codes: 0 pass, 1 threshold breach, 2 configuration error."""

    def setUp(self):
        self.server, self.url = start_fake_server()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def _args(self, *extra):
        return [
            "--base-url", self.url,
            "--organizations", "4", "--patient-charts", "400",
            "--concurrent-staff", "8", "--rate", "20",
            "--duration-seconds", "1", "--warm-up-seconds", "0.2",
            "--seed", "7",
            *extra,
        ]

    def test_main_succeeds_against_a_local_fake_server(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = lg.main(self._args())
        self.assertEqual(code, 0)
        self.assertIn("p95 reads", stdout.getvalue())
        self.assertIn("PASS", stdout.getvalue())

    def test_main_exits_one_when_read_latency_breaches(self):
        # Keep this short: two slow reads are enough to exceed 500 ms p95.
        FakeServerHandler.read_delay_seconds = 0.55
        try:
            stdout = io.StringIO()
            args = self._args("--rate", "3", "--duration-seconds", "0.5",
                              "--warm-up-seconds", "0", "--read-weight", "1.0")
            with contextlib.redirect_stdout(stdout):
                code = lg.main(args)
        finally:
            FakeServerHandler.read_delay_seconds = 0.0
        self.assertEqual(code, 1)
        self.assertIn("FAIL", stdout.getvalue())

    def test_main_exits_one_when_command_latency_breaches(self):
        FakeServerHandler.command_delay_seconds = 0.95
        try:
            stdout = io.StringIO()
            args = self._args("--rate", "2", "--duration-seconds", "0.5",
                              "--warm-up-seconds", "0", "--read-weight", "0.0")
            with contextlib.redirect_stdout(stdout):
                code = lg.main(args)
        finally:
            FakeServerHandler.command_delay_seconds = 0.0
        self.assertEqual(code, 1)
        self.assertIn("FAIL", stdout.getvalue())

    def test_main_exits_two_for_a_configuration_error(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = lg.main(self._args("--environment", "production"))
        self.assertEqual(code, 2)
        self.assertIn("synthetic", stderr.getvalue())

    def test_main_exits_two_for_invalid_timing_values(self):
        for offset, value, expected in (
            ("--duration-seconds", "0", "positive finite"),
            ("--duration-seconds", "-5", "positive finite"),
            ("--duration-seconds", "inf", "positive finite"),
            ("--warm-up-seconds", "-1", "non-negative finite"),
            ("--warm-up-seconds", "nan", "non-negative finite"),
        ):
            with self.subTest(offset=offset, value=value):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    code = lg.main(self._args(offset, value))
                self.assertEqual(code, 2)
                self.assertIn(expected, stderr.getvalue())

    def test_main_exits_two_for_a_malformed_port(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = lg.main(self._args("--base-url", "http://127.0.0.1:bogus"))
        self.assertEqual(code, 2)
        self.assertIn("configuration error", stderr.getvalue())

    def test_report_records_environment_and_a_redacted_target(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            target = self.url.replace("http://", "http://user:pass@")
            with contextlib.redirect_stdout(io.StringIO()):
                code = lg.main(self._args("--base-url", target,
                                          "--report-json", str(path)))
            self.assertEqual(code, 0)
            report = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(report["environment"], "synthetic")
        self.assertEqual(report["target"], self.url)
        self.assertNotIn("user:pass", json.dumps(report))

    def test_main_writes_a_json_report_when_requested(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                code = lg.main(self._args("--report-json", str(path)))
            self.assertEqual(code, 0)
            report = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["profile"]["organizations"], 4)

    def test_main_uses_sys_argv_when_no_arguments_are_passed(self):
        stdout = io.StringIO()
        with mock.patch.object(sys, "argv", ["load_generator.py", *self._args()]):
            with contextlib.redirect_stdout(stdout):
                code = lg.main()
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
