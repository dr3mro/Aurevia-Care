# Time zones and civil dates (UX-01)

- Requirement: **UX-01**
- Machine source of truth: [ux01_evidence.py](../../../infra/s_g5_10/ux01_evidence.py).
- Back to the [S-G5-10 handoff](README.md).

## Enforced declarations

| Clause | Artifact field | Rule |
|---|---|---|
| UTC instants | `time.instant_storage` | Must equal the exact string `utc` |
| IANA time-zone context | `time.iana_timezone_context` | Must be boolean `true` |
| Retained civil dates | `time.civil_dates_retained` | Must be boolean `true` |
| Daylight-saving transitions tested | `time.dst_transitions_tested` | A non-empty list of distinct, valid IANA zone names with at least one zone that actually changes UTC offset |

## Why instants and civil dates are both required

A UTC instant answers *when* an event occurred; an IANA zone answers *which
local rules applied* at that instant. Storing only one loses information: a UTC
value without a zone cannot be rendered correctly for a future civil date, and a
local wall-clock value without an instant is ambiguous during a daylight-saving
overlap. The specification therefore requires UTC storage **with** IANA context,
and separately requires civil dates to be retained where a calendar date (for
example a date of birth or an LMP) must not shift with a time-zone conversion.

## How the DST check works

The evaluator resolves each declared name with the Python `zoneinfo` database.
An unknown name or an invalid key fails closed rather than raising. For a valid
zone it compares the UTC offset at a fixed winter noon and summer noon; a
difference proves the zone observes a daylight-saving transition. This works in
both hemispheres — `Australia/Sydney` transitions in the opposite months but is
still detected. A fixed-offset zone such as `Asia/Riyadh` cannot satisfy the
"transition tested" clause on its own, and duplicate zone names are rejected
because they do not broaden coverage.

The check proves the *declaration* is coherent. It cannot prove a real
transition was exercised in a deployed environment; that is a measured-evidence
item for engineering and operations.

## Synthetic breach coverage

The [violation fixture](../../../infra/s_g5_10/fixtures/violation_ux01_evidence.json)
uses `instant_storage: "local"`, `iana_timezone_context: false` and a single
fixed-offset zone; the evaluator reports the storage, context and DST checks as
failures. Unit cases additionally cover an empty list, non-string members, an
invalid zone name, duplicates, a non-DST zone and a southern-hemisphere DST zone,
so every branch of the time checks has an executable synthetic case.
