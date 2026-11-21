"""Synthetic OPS-03 pilot load generator and p95 reporter.

The specification fixes a pilot profile (20 organizations, 100,000 charts,
200 concurrently active staff, 50 mixed requests per second for 30 minutes)
and two latency targets: p95 <= 500 ms for reads and <= 900 ms for commands,
measured server-side excluding document transfer/rendering and external calls.

This generator runs that profile against a *synthetic* target using only the
Python standard library. It plans a deterministic mixed workload, issues it at
the requested rate, reports read and command p95 separately and exits non-zero
when either target is breached or any request fails. A 5-minute warm-up is
excluded from the reported percentiles, matching the declared pilot ramp.

Safety: the default target is a loopback address and only an environment
labelled ``synthetic`` is accepted. A non-loopback host needs an explicit
``--allow-remote`` flag. The tool never reads real patient data; every
identifier it sends is a deterministic synthetic key such as
``org-003/chart-000042``.

The generator measures end-to-end latency of a synthetic target, which is not
server-side measurement and is not final pilot evidence. An authorized run in
an approved environment must collect the server-side numbers that
``infra/s_g5_05/capacity_evidence.py`` evaluates; this tool supplies the
workload and a fail-closed rehearsal, not a human acceptance decision.

Usage: ``python3 -I infra/loadtest/load_generator.py [options]``
Exit codes: 0 = both p95 targets met, 1 = a target was breached,
2 = configuration error (unsafe target or invalid profile).
"""
import argparse
import http.client
import json
import math
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import NamedTuple
from urllib.parse import urlsplit

PILOT_PROFILE = {
    "organizations": 20,
    "patient_charts": 100000,
    "concurrent_staff": 200,
    "requests_per_second": 50,
    "duration_minutes": 30,
}
REQUIRED_PROFILE_KEYS = tuple(PILOT_PROFILE)
READ_P95_MS_MAX = 500
COMMAND_P95_MS_MAX = 900
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_READ_WEIGHT = 0.7
DEFAULT_SEED = 20261119
DEFAULT_WARM_UP_SECONDS = 300
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1")
ROUTE_CLASSES = ("read", "command")
READ_FAMILIES = (
    "patient.chart.lookup",
    "appointment.worklist",
    "catalog.configuration.read",
    "audit.query",
)
COMMAND_FAMILIES = (
    "appointment.booking",
    "encounter.checkin",
    "clinical.documentation.sign",
    "order.transition",
    "result.reconciliation",
    "patient.release",
    "messaging.instruction",
)


class RequestFailed(Exception):
    """A synthetic request returned a non-success HTTP status."""


class RequestSpec(NamedTuple):
    """One synthetic request: a canonical family name, class and HTTP shape."""

    name: str
    route_class: str
    method: str
    path: str


def percentile_ms(values, fraction=0.95):
    """Return the nearest-rank percentile of millisecond samples.

    The result is sorted defensively so callers may pass raw, unsorted
    measurements. An empty sample set has no percentile and returns ``None``;
    callers must treat that as a failure rather than as a fast result.
    """
    ordered = sorted(values)
    if not ordered:
        return None
    rank = math.ceil(fraction * len(ordered))
    index = min(max(rank - 1, 0), len(ordered) - 1)
    return float(ordered[index])


def synthetic_dataset(profile, seed):
    """Build a deterministic synthetic dataset description for a profile.

    The full 100,000 charts are represented by counts and a deterministic key
    scheme, not by materialised records: a load generator needs identifiers,
    not patient data. Charts must divide evenly across organizations so the
    declared per-organization count is exact.

    Raises ``ValueError`` for a missing, non-positive, non-integer or boolean
    profile value, or when the chart count is not divisible by organizations.
    """
    for key in REQUIRED_PROFILE_KEYS:
        value = profile.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError(f"{key} must be a positive integer")
    organizations = profile["organizations"]
    charts = profile["patient_charts"]
    if charts % organizations:
        raise ValueError("patient_charts must divide evenly across organizations")
    return {
        "organizations": organizations,
        "charts_per_organization": charts // organizations,
        "concurrent_staff": profile["concurrent_staff"],
        "patient_charts": charts,
        "seed": seed,
        "synthetic_only": True,
    }


def chart_key(organization_index, chart_index):
    """Return a synthetic, PHI-free chart identifier for the dataset."""
    return f"org-{organization_index:03d}/chart-{chart_index:06d}"


def validate_target(base_url, environment, allow_remote):
    """Validate and parse a target, refusing real environments by default.

    Loopback hosts are always allowed for local rehearsals. Any other host is
    refused unless the caller explicitly opts in with ``allow_remote``, and
    even then the environment must be labelled ``synthetic``. Raises
    ``ValueError`` describing the refused condition.
    """
    parts = urlsplit(base_url)
    if parts.scheme not in ("http", "https"):
        raise ValueError("base URL must use http or https")
    if not parts.hostname:
        raise ValueError("base URL must include a host")
    # A malformed port must surface as a configuration error (exit 2) here,
    # not as an uncaught ValueError when the sender is constructed later.
    port_for(parts)
    if environment != "synthetic":
        raise ValueError("load tests must target a synthetic environment")
    if parts.hostname in LOOPBACK_HOSTS:
        return parts
    if not allow_remote:
        raise ValueError("non-loopback target requires --allow-remote")
    return parts


def port_for(parts):
    """Return the explicit port or the scheme default when none was given."""
    if parts.port is not None:
        return parts.port
    return 443 if parts.scheme == "https" else 80


def make_request(dataset, rng, route_class):
    """Build one synthetic request in the given route class."""
    if route_class == "read":
        name = rng.choice(READ_FAMILIES)
        method = "GET"
    else:
        name = rng.choice(COMMAND_FAMILIES)
        method = "POST"
    organization = rng.randrange(dataset["organizations"])
    chart = rng.randrange(dataset["charts_per_organization"])
    path = "/synthetic/" + chart_key(organization, chart)
    return RequestSpec(name, route_class, method, path)


def plan_requests(count, dataset, seed, read_weight):
    """Plan ``count`` deterministic mixed requests using the declared split."""
    if not 0.0 <= read_weight <= 1.0:
        raise ValueError("read weight must be between 0 and 1")
    rng = random.Random(seed)
    plan = []
    for _ in range(count):
        if rng.random() < read_weight:
            plan.append(make_request(dataset, rng, "read"))
        else:
            plan.append(make_request(dataset, rng, "command"))
    return plan


def http_sender(base_url, timeout_seconds):
    """Return a stdlib HTTP sender for a pre-validated synthetic base URL.

    The sender returns ``None`` on a 2xx response, raises ``RequestFailed`` on
    any other status and lets transport errors (an ``OSError`` subclass)
    propagate so the caller can count them. The connection is always closed.
    """
    parts = urlsplit(base_url)
    host = parts.hostname
    port = port_for(parts)
    base_path = parts.path.rstrip("/")
    connection_class = (http.client.HTTPSConnection if parts.scheme == "https"
                        else http.client.HTTPConnection)

    def send(spec):
        connection = connection_class(host, port, timeout=timeout_seconds)
        try:
            connection.request(spec.method, base_path + spec.path)
            response = connection.getresponse()
            response.read()
            if not 200 <= response.status < 300:
                raise RequestFailed(
                    f"{spec.name} returned HTTP {response.status}")
            return None
        finally:
            connection.close()

    return send


def _timed_request(sender, spec, monotonic):
    """Run one synthetic request; return (start, latency_ms) or (start, None).

    Timing happens inside the worker so a concurrent pool cannot attribute a
    request's wait behind the submit loop to that request's latency. A failed
    request has no latency sample.
    """
    began = monotonic()
    try:
        sender(spec)
    except Exception:
        return began, None
    return began, (monotonic() - began) * 1000.0


def run_load(plan, sender, rate_per_second, duration_seconds, warm_up_seconds,
             workers=1, executor_factory=None,
             monotonic=time.monotonic, sleep=time.sleep):
    """Issue a planned workload at a fixed rate and collect class latencies.

    ``workers`` is the number of concurrently active synthetic staff sessions;
    requests are submitted on the fixed-rate schedule to a pool of that size,
    so the declared staff count drives real concurrency rather than sitting as
    inert metadata. Requests issued during the warm-up window are counted but
    excluded from the measured samples. Failed requests are counted as errors
    and produce no latency sample.

    ``executor_factory``, ``monotonic`` and ``sleep`` are injectable so the
    scheduling loop can be tested deterministically without real threads or
    real time; production uses a ``ThreadPoolExecutor`` and the system clock.
    """
    start = monotonic()
    end = start + duration_seconds + warm_up_seconds
    read_latencies = []
    command_latencies = []
    errors = 0
    issued = 0
    warmup_issued = 0
    factory = ThreadPoolExecutor if executor_factory is None else executor_factory
    executor = factory(max_workers=workers)
    pending = []
    try:
        for index, spec in enumerate(plan):
            scheduled = start + index / rate_per_second
            if scheduled > end:
                break
            now = monotonic()
            if scheduled > now:
                sleep(scheduled - now)
            pending.append((
                executor.submit(_timed_request, sender, spec, monotonic),
                spec.route_class,
            ))
            issued += 1
        for future, route_class in pending:
            began, latency = future.result()
            if latency is None:
                errors += 1
            elif began - start < warm_up_seconds:
                warmup_issued += 1
            elif route_class == "read":
                read_latencies.append(latency)
            else:
                command_latencies.append(latency)
    finally:
        executor.shutdown(wait=True)
    return {
        "read_latencies_ms": read_latencies,
        "command_latencies_ms": command_latencies,
        "errors": errors,
        "issued": issued,
        "warmup_issued": warmup_issued,
    }


def _check(name, passed, detail):
    """Build one named check record for the report."""
    return {"name": name, "passed": bool(passed), "detail": detail}


def summarize(result):
    """Turn collected latencies into separate read/command p95 checks.

    The verdict fails closed: a missing class, a breached threshold or any
    request error is a failure. All breaches are reported together so a
    reviewer sees the complete picture in one run.
    """
    reads = result["read_latencies_ms"]
    commands = result["command_latencies_ms"]
    p95_read = percentile_ms(reads, 0.95)
    p95_command = percentile_ms(commands, 0.95)
    checks = [
        _check("samples.read", bool(reads),
               f"{len(reads)} measured read samples"),
        _check("samples.command", bool(commands),
               f"{len(commands)} measured command samples"),
        _check("p95.read", p95_read is not None and p95_read <= READ_P95_MS_MAX,
               f"{p95_read} ms; target <= {READ_P95_MS_MAX} ms"),
        _check("p95.command",
               p95_command is not None and p95_command <= COMMAND_P95_MS_MAX,
               f"{p95_command} ms; target <= {COMMAND_P95_MS_MAX} ms"),
        _check("errors", result["errors"] == 0,
               f"{result['errors']} failed request(s)"),
    ]
    failed = [check["name"] for check in checks if not check["passed"]]
    return {
        # A local rehearsal is never final pilot evidence; the vocabulary
        # matches the OPS-03 evidence evaluator.
        "kind": "synthetic_rehearsal",
        "final_evidence": False,
        "verdict": "fail" if failed else "pass",
        "p95_read_ms": p95_read,
        "p95_command_ms": p95_command,
        "read_samples": len(reads),
        "command_samples": len(commands),
        "errors": result["errors"],
        "issued": result["issued"],
        "warmup_issued": result["warmup_issued"],
        "checks": checks,
        "checks_byname": {check["name"]: check for check in checks},
        "failed": failed,
    }


def _display(value):
    """Render a percentile, using ``n/a`` when there were no samples."""
    if value is None:
        return "n/a"
    return f"{value:.1f}"


def format_report(report):
    """Render the human-readable p95 report for one run."""
    lines = [
        "SYNTHETIC REHEARSAL - not final pilot evidence",
        f"OPS-03 pilot load: {report['verdict'].upper()}",
    ]
    lines.append(f"  p95 reads: {_display(report['p95_read_ms'])} ms"
                 f" (limit {READ_P95_MS_MAX} ms)")
    lines.append(f"  p95 commands: {_display(report['p95_command_ms'])} ms"
                 f" (limit {COMMAND_P95_MS_MAX} ms)")
    lines.append(f"  requests: issued={report['issued']}"
                 f" warmup={report['warmup_issued']} errors={report['errors']}")
    for check in report["checks"]:
        status = "PASS" if check["passed"] else "FAIL"
        lines.append(f"  [{status}] {check['name']}: {check['detail']}")
    return "\n".join(lines)


def write_json_report(path, report):
    """Write a machine-readable copy of the report to ``path``."""
    Path(path).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def build_parser():
    """Build the CLI with the OPS-03 pilot profile as its defaults."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--environment", default="synthetic")
    parser.add_argument("--allow-remote", action="store_true")
    parser.add_argument("--organizations", type=int,
                        default=PILOT_PROFILE["organizations"])
    parser.add_argument("--patient-charts", type=int,
                        default=PILOT_PROFILE["patient_charts"])
    parser.add_argument("--concurrent-staff", type=int,
                        default=PILOT_PROFILE["concurrent_staff"])
    parser.add_argument("--rate", type=int,
                        default=PILOT_PROFILE["requests_per_second"])
    parser.add_argument("--duration-seconds", type=float,
                        default=PILOT_PROFILE["duration_minutes"] * 60)
    parser.add_argument("--warm-up-seconds", type=float,
                        default=DEFAULT_WARM_UP_SECONDS)
    parser.add_argument("--read-weight", type=float, default=DEFAULT_READ_WEIGHT)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--report-json", default=None)
    return parser


def main(argv=None):
    """Run a synthetic load rehearsal and return a process exit code."""
    arguments = sys.argv[1:] if argv is None else list(argv)
    args = build_parser().parse_args(arguments)
    try:
        if (not math.isfinite(args.duration_seconds)
                or args.duration_seconds <= 0):
            raise ValueError("duration-seconds must be a positive finite number")
        if (not math.isfinite(args.warm_up_seconds)
                or args.warm_up_seconds < 0):
            raise ValueError("warm-up-seconds must be a non-negative finite number")
        parts = validate_target(args.base_url, args.environment, args.allow_remote)
        profile = {
            "organizations": args.organizations,
            "patient_charts": args.patient_charts,
            "concurrent_staff": args.concurrent_staff,
            "requests_per_second": args.rate,
            # A short rehearsal still reports a positive whole-minute profile;
            # the exact measured seconds are carried on the run result instead.
            "duration_minutes": max(1, math.ceil(args.duration_seconds / 60)),
        }
        dataset = synthetic_dataset(profile, args.seed)
        count = int(args.rate * (args.duration_seconds + args.warm_up_seconds)) + 1
        plan = plan_requests(count, dataset, args.seed, args.read_weight)
    except (ValueError, OverflowError) as error:
        print(f"load generator: configuration error: {error}", file=sys.stderr)
        return 2
    sender = http_sender(args.base_url, timeout_seconds=10.0)
    result = run_load(plan, sender, args.rate, args.duration_seconds,
                      args.warm_up_seconds, workers=args.concurrent_staff)
    report = summarize(result)
    report["profile"] = profile
    report["dataset"] = dataset
    report["environment"] = args.environment
    # Record only scheme/host/port: never userinfo, token or path.
    report["target"] = f"{parts.scheme}://{parts.hostname}:{port_for(parts)}"
    print(format_report(report))
    if args.report_json:
        write_json_report(args.report_json, report)
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
