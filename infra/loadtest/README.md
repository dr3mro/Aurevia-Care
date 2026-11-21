# Synthetic pilot load generator (OPS-03)

Requirement: [OPS-03](../../SMARTCLINIC_V1_SPEC.md).

This module supplies a dependency-free load generator for the specified pilot
size and a fail-closed p95 report. It uses only the Python standard library.

## Profile

The declared profile in [pilot-profile.json](pilot-profile.json) matches the
specification literal: 20 organizations, 100,000 synthetic charts, 200
concurrently active staff, 50 mixed requests per second for 30 minutes. The
workload mix is a declared 70% read / 30% command assumption; OPS-03 requires
revising it after observation. A 5-minute warm-up is excluded from the reported
latencies.

The declared staff count sizes a `ThreadPoolExecutor`: the 200 concurrently
active staff are real concurrent synthetic sessions sharing the 50 rps budget,
not inert metadata. Requests are submitted on the fixed-rate schedule and each
worker times its own request, so queueing behind the submit loop is not charged
to a request's latency.

Reads and commands are reported separately. The run exits non-zero when read
p95 exceeds 500 ms, command p95 exceeds 900 ms, or any request fails. Reports
are labelled `synthetic_rehearsal` with `final_evidence: false` and print a
`SYNTHETIC REHEARSAL - not final pilot evidence` banner.

## Usage

```bash
# Rehearsal against a local synthetic target (default: http://127.0.0.1:8080)
python3 -I infra/loadtest/load_generator.py \
  --base-url http://127.0.0.1:8080 \
  --report-json /tmp/opencode/pilot-load-report.json
```

Defaults are the pilot profile. A short rehearsal overrides the window, for
example `--rate 20 --duration-seconds 60 --warm-up-seconds 10`.

## Synthetic-only safety

- The default target is a loopback address. Only an environment labelled
  `synthetic` is accepted; any other value is refused.
- A non-loopback host requires an explicit `--allow-remote` flag and the
  `synthetic` environment label. The script never points at a real environment
  by default.
- All identifiers are deterministic synthetic keys such as
  `org-003/chart-000042`; no real patient data, credential or domain is used.

## What this proves and what it does not

The generator measures end-to-end latency against a synthetic target and
rehearses the workload, thresholds and reporting. OPS-03 requires server-side
measurement excluding document transfer, rendering and external calls, which
an authorized run in an approved environment must collect. The synthetic
report is a rehearsal, not final pilot evidence; the evaluator in
`infra/s_g5_05/capacity_evidence.py` judges collected artifacts, and pilot
acceptance remains a human gate.

## Tests and coverage

```bash
bash infra/loadtest/check.sh
```

The gate runs the unit suite and a build-failing 100% line-coverage check using
the standard-library `trace` module (coverage.py is not installed here). Tests
exercise pure functions, a deterministic fake clock for the scheduling loop,
and a short profile against a local `http.server` bound to an ephemeral
loopback port. Every condition in the generator is named by a test; the
branch-to-test map is in the task notes under `.agent/pr-notes/`.
