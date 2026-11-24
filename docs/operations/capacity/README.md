# OPS-03 capacity and pilot performance evidence

- Requirement: OPS-03
- Status: the target contract, the pilot load profile and a fail-closed evidence
  evaluator are delivered. Real measured pilot evidence is **not** produced here
  and remains an open G5 gate.
- Audience: engineering, operations, the product owner and G5 acceptance reviewers.

This directory is the engineering handoff for OPS-03. It states the exact target
values, the profile of the load test to run before pilot, how an evidence
artifact is judged, and — deliberately — what this repository cannot prove.

## Requirement mapping

| Requirement | Specification clause | Handled by |
|---|---|---|
| OPS-03 | 99.9% monthly core availability; server-side p95 ≤ 500 ms for routine reads and ≤ 900 ms for routine commands, excluding document transfer/rendering and external calls; pilot profile 20 organizations, 100,000 charts, 200 concurrent staff, 50 mixed requests per second for 30 minutes; run before pilot and revise assumptions after observation | [Capacity targets](capacity-targets.md), [Pilot load profile](pilot-load-profile.md), [Performance evidence](performance-evidence.md) |

The approved clause itself is in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new threshold or requirement ID is introduced by this handoff.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Target | The spec literals encoded in the [evaluator](../../../infra/capacity/capacity_evidence.py) | Fidelity of the encoding and fail-closed validation | That any target was achieved |
| Synthetic rehearsal | A seeded, synthetic artifact run by [`check.sh`](../../../infra/capacity/check.sh) in this repository | That the evaluator accepts a conforming artifact and rejects breaches | Pilot capacity, latency or availability; no real request was served |
| Pilot measured | An authorized load run and a calendar-month availability record, produced outside this repository, with provenance | Actual capacity and availability under the declared profile | Clinical or operational acceptance; that remains a G5 human gate. Provenance presence is checked, not its truth |

A passing synthetic artifact is explicitly labelled `final_evidence: false`. The
evaluator never promotes a rehearsal to measured evidence.

## Document set

| Document | Covers |
|---|---|
| [Capacity targets](capacity-targets.md) | The enforced numbers, their measurement envelope, derived figures that are *not* requirements, and fail-closed rules |
| [Pilot load profile](pilot-load-profile.md) | The specified scenario, the declared workload mix and synthetic dataset, warm-up/ramp handling |
| [Performance evidence](performance-evidence.md) | Artifact schema, commands, the observed rehearsal result, the honesty boundary and the remaining gate |

Executable material lives in `infra/capacity`: the
[evaluator](../../../infra/capacity/capacity_evidence.py), the
[pilot profile artifact](../../../infra/capacity/pilot-load-profile.json), the
[conforming](../../../infra/capacity/fixtures/synthetic_pilot_evidence.json) and
[violation](../../../infra/capacity/fixtures/violation_pilot_evidence.json)
fixtures, and the unit tests.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/capacity/check.sh` | 55 synthetic unit tests pass; the conforming fixture passes and the violation fixture fails with the exact exit code 1 and all five named breaches |
| Unit tests alone | `python3 -I -m unittest discover -s infra/capacity/tests -q` | The evaluator's enumerated branches behave as specified |
| Line coverage | `python3 -m trace --count --coverdir /tmp/opencode/capacity_cov infra/capacity/tests/run_coverage.py` | Every executable line of the evaluator runs; stdlib `trace` is used because `coverage.py` is not installed |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Only the literals in OPS-03 are enforced; the 70/30 mix is a declared assumption | The specification fixes the totals but not the mix; inventing a threshold would overstate the contract |
| D2 | Availability must declare a `calendar_month` window | A 30-minute load run cannot establish 99.9% *monthly* availability; accepting a short window would fabricate evidence |
| D3 | `route_class` must declare at least one read and one command route | Without an explicit route allowlist a slow command could be relabelled as a read to evade the 500 ms p95 |
| D4 | The exclusion vocabulary is the exact three required categories | An additional exclusion could hide slow requests behind a broader filter |
| D5 | A passing synthetic artifact is not final evidence | Rehearsal proves the check works, not that the system met the target under real load |
| D6 | Profile values must equal the specified values exactly | A run at a different scale did not execute the specified pilot profile |
| D7 | Reserved migration version V47 is not used | No schema change is needed; version gaps are allowed by the repository's change-safety gate |
| D8 | A `measured_pilot` must carry non-empty tool, version, command, UTC timestamp, environment and source SHA provenance and a named source | Without it `final_evidence` is granted to a bare self-declared artifact; presence is checked, truth remains a human gate |
| D9 | Each request class must hold at least 1% of the run | A run with one command sample would report a meaningless command p95 |
| D10 | Non-finite numbers and unhashable `kind` values fail closed | `Infinity` and a list/dict `kind` previously passed or crashed instead of producing named failed checks |
| D11 | The negative fixture gate requires exit code 1 and the five expected check names | Accepting any non-zero exit would let an unreadable fixture silently disable the control |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier or credential.
- No clinical approval is implied. Availability, capacity and operations sign-off
  belong to the accountable owners in the specification's decision table.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Real 30-minute load report at the declared profile | No deployment or load-test environment exists in this repository; Docker is unavailable locally | Engineering with the operations owner, in an authorized environment |
| Calendar-month availability measurement | Availability cannot be observed in a short run | Operations, from production monitoring |
| Revision of the workload mix and capacity assumptions after observation | OPS-03 requires revising assumptions after the pilot | Engineering and operations, with the product owner |
| Human acceptance of the measured evidence | A script cannot attest that a measurement really happened | G5 acceptance owners |

Evidence source for the delivered checks: executed tool commands in this
repository and simulated specialist design/test reviews. There is no human,
clinical or real-pilot performance evidence in this story.
