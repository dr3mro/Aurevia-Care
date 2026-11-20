# Capacity targets (OPS-03)

- Requirement: OPS-03
- Machine source of truth: [capacity_evidence.py](../../../infra/capacity/capacity_evidence.py).
  The constants there are the single enforced copy of these numbers; this page
  explains them and must be kept in step with that module.
- Back to the [S-G5-05 handoff](README.md).

## Enforced targets

| Target | Value | Enforced as |
|---|---|---|
| Routine API read latency | p95 ≤ 500 ms | `results.p95_read_ms` must be a positive number not exceeding `P95_READ_MS_MAX` |
| Routine command latency | p95 ≤ 900 ms | `results.p95_command_ms` must be a positive number not exceeding `P95_COMMAND_MS_MAX` |
| Core availability | ≥ 99.9% monthly | `results.availability.percent` ≥ 99.9 **and** `results.availability.window` = `calendar_month` |
| Organizations | 20 | `profile.organizations` |
| Total patient charts | 100,000 | `profile.patient_charts` |
| Concurrently active staff | 200 | `profile.concurrent_staff` |
| Mixed request rate | 50 per second | `profile.requests_per_second` |
| Measured window | 30 minutes | `profile.duration_minutes` |

All five profile values are exact, not minimums: a test that used a different
scale did not run the specified pilot scenario. A larger run may be recorded as
a separate artifact, but it does not replace the specified profile.

## Measurement envelope

The specification requires latency measured **server-side**, excluding document
transfer, document rendering and external calls. The evaluator fails closed when:

- `measurement.scope` is anything other than `server_side`;
- `measurement.excluded` is not a list, is missing any of `document_transfer`,
  `document_rendering` or `external_calls`, adds an unexpected category, or
  repeats a category;
- `measurement.route_class` is not a route-to-class object declaring at least
  one `read` route and at least one `command` route, or contains a class value
  outside those two names.

The route allowlist matters for correctness: a slow command that is silently
recategorised as a read would be judged against the 500 ms limit instead of the
900 ms one. The evaluator checks the *declaration*, not the truth of the
classification — an honest artifact is still required.

## Why availability is separate

The 30-minute load profile measures latency and sustained capacity. 99.9%
monthly availability is a separate property of a calendar month of operation.
The evaluator therefore rejects an availability figure whose window is the load
run. A conforming artifact may carry a synthetic monthly figure to exercise the
check; only an authorized measurement can supply real evidence.

## Derived figures — not new targets

These are arithmetic consequences of the profile, shown for planning only. They
are **not** specification targets. Two of them are nevertheless *enforced as
consistency checks*, because a run that violates them did not execute the
declared scenario:

- At 50 requests/second for 30 minutes, the literal steady-state total is
  90,000 requests. A declared 10% load-generator ramp allowance lowers the
  **enforced floor** to 81,000 (`results.sustained_rate`); a run below that floor
  fails. This is a measurement allowance, not a relaxation of the 50 rps and
  30-minute profile values, which are both checked exactly in `profile`.
- Each class must hold at least 1% of the run (`results.class_balance`) so the
  reported p95 is computed over a meaningful sample.
- 0.25 requests/second per concurrently active staff member (50 / 200) and
  5,000 charts per organization on average (100,000 / 20) are planning figures
  only and are not enforced.
- A 99.9% monthly availability target corresponds to roughly 43.2 minutes of
  error budget in a 30-day month; the window is checked, the budget is not.

## Fail-closed behaviour

An artifact that is not an object, declares an unsupported schema version or
kind, omits a section, or misstates any value is reported with named failing
checks rather than raising or defaulting to a pass. A non-finite number (NaN or
Infinity) is not a valid measurement, and an unhashable `kind` is classified as
unknown rather than crashing the report. A synthetic source combined with
`kind: measured_pilot` is explicitly rejected as a conflict, and such a claim
must also carry a named source and the provenance fields listed in
[Performance evidence](performance-evidence.md). The exit code is 0 only when
every check passes; see [Performance evidence](performance-evidence.md).
