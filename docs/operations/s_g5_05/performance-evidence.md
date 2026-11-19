# Performance evidence (OPS-03)

- Requirement: OPS-03
- Back to the [S-G5-05 handoff](README.md); see the [targets](capacity-targets.md)
  and the [pilot profile](pilot-load-profile.md).

## Artifact schema

An evidence artifact is a JSON object with these fields:

| Field | Content |
|---|---|
| `schema_version` | `"1.0"`; an unsupported version fails closed |
| `kind` | `synthetic_rehearsal` or `measured_pilot`; anything else fails closed |
| `source` | Label of the run; a measured pilot may not name a synthetic source |
| `profile` | The five exact OPS-03 profile values |
| `measurement` | `scope`, the exact `excluded` list and the `route_class` allowlist |
| `results.availability` | `window: "calendar_month"` and `percent` |
| `results.p95_read_ms`, `results.p95_command_ms` | Server-side latency percentiles in milliseconds |
| `results.total_requests`, `read_requests`, `command_requests` | Request counts proving a sustained mixed run |

The [conforming fixture](../../../infra/s_g5_05/fixtures/synthetic_pilot_evidence.json)
is a complete example. The [violation fixture](../../../infra/s_g5_05/fixtures/violation_pilot_evidence.json)
deliberately breaks several fields to demonstrate fail-closed reporting.

## Commands and results observed here

```
$ python3 -I infra/s_g5_05/capacity_evidence.py infra/s_g5_05/fixtures/synthetic_pilot_evidence.json
OPS-03 capacity evidence: PASS
  evidence class: rehearsal (final: False)
  [PASS] profile.organizations: 20
  [PASS] measurement.scope: scope='server_side'; expected 'server_side'
  [PASS] measurement.excluded: exactly the required categories excluded
  [PASS] measurement.route_class: both read and command routes declared
  [PASS] availability.window: window='calendar_month'; expected 'calendar_month'
  [PASS] results.p95_read_ms: 480 ms; target <= 500 ms
  [PASS] results.p95_command_ms: 870 ms; target <= 900 ms
  [PASS] results.sustained_rate: total=90000; minimum=81000 at the declared profile
exit 0
```

The violation fixture exits 1 and reports `measurement.excluded`,
`availability.window`, both p95 breaches and `results.sustained_rate` as failures.
Line coverage of the evaluator is 100% under stdlib `trace`
(`python3 -m trace --count --coverdir <dir> infra/s_g5_05/tests/run_coverage.py`,
155 executable lines, none uncovered); branch coverage is by exhaustive
enumeration of every conditional in the 45 unit tests, since `coverage.py` is not
installed. Evidence source: executed tool commands in this repository.

## What the evaluator proves — and what it cannot

The evaluator proves that a supplied artifact has a complete envelope and that
its numbers satisfy the OPS-03 literals. It **cannot** prove that a measurement
really happened, that latency was really collected server-side, or that a load
generator sustained the declared rate. An artifact is a claim judged against a
contract; the claim's truth rests on the run's provenance and on human review.

Accordingly:

- `kind: synthetic_rehearsal` yields `final_evidence: false` even when every check
  passes. It demonstrates the harness, not capacity.
- `kind: measured_pilot` yields `final_evidence: true` only when every check
  passes and the source is not synthetic — but that flag records what the
  artifact *claims*, not an independent verification.

## Remaining gate

No real load test, deployment or calendar-month monitoring is available in this
repository (Docker is unavailable locally and the container stack is a bootstrap
fixture). This story therefore does **not** claim that OPS-03 is satisfied. The
open work is:

1. Run the 30-minute profile against an authorized environment with the tool,
   version, command, UTC timestamp, environment and source SHA recorded.
2. Produce the separate calendar-month availability measurement.
3. Compare, revise the assumptions where observation disagrees, and record the
   measured artifact together with an engineering and operations sign-off for
   G5 acceptance.

A reviewer should treat the delivered synthetic pass as evidence that the check
works, and the three items above as the evidence that the target was met.
