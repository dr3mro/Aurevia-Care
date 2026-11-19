# Evidence artifact and commands (UX-01)

- Requirement: **UX-01**
- Machine source of truth: [ux01_evidence.py](../../../infra/s_g5_10/ux01_evidence.py).
- Back to the [S-G5-10 handoff](README.md).

## Artifact schema

An evidence artifact is a JSON object. Every top-level section must be an
object; a missing or wrongly typed section is a named failure, not a silent
pass.

| Section | Fields |
|---|---|
| Envelope | `schema_version` (`1.0`), `kind` (`synthetic_rehearsal` or `measured_acceptance`), `source` (a label) |
| `localization` | `languages`, `rtl`, `local_names`, `mixed_script` — see [Localization and RTL](localization-and-rtl.md) |
| `time` | `instant_storage`, `iana_timezone_context`, `civil_dates_retained`, `dst_transitions_tested` — see [Time zones and civil dates](time-and-timezones.md) |
| `accessibility` | `staff_web`, `mobile` — see [Accessibility and screen readers](accessibility-and-screen-readers.md) |
| `exports` | `language_preserved`, `units_preserved` — both boolean `true` |
| `presentation` | `styling_flexible` — boolean `true` |

An artifact whose `kind` is `measured_acceptance` but whose `source` contains the
word "synthetic" is rejected as a conflict. Every check is collected before a
verdict is returned, so one report lists all breaches.

## Exports and presentation

Language and units are part of the artifact, not decoration. A rendered report
or export that drops the Arabic text, translates a coded diagnosis or drops the
unit from a measurement is clinically unsafe. `language_preserved` and
`units_preserved` are therefore independent named checks. `styling_flexible`
records that presentation styling is not hard-wired to a single locale — RTL and
light/dark presentation must be expressible without duplicating content.

## Commands and observed result

| Command | Result in this repository |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_10/tests -q` | 51 tests, 0 failures, 0 errors |
| `bash infra/s_g5_10/check-ux01.sh` | exit 0; conforming fixture passes, violation fixture fails with exit code 1 and a FAIL marker |
| `python3 -I infra/s_g5_10/ux01_evidence.py infra/s_g5_10/fixtures/synthetic_ux01_evidence.json` | verdict `PASS`, class `rehearsal`, `final_evidence` false |
| `python3 -I infra/s_g5_10/ux01_evidence.py infra/s_g5_10/fixtures/violation_ux01_evidence.json` | verdict `FAIL`, exit 1, eight failing checks |
| `python3 -m trace --count --missing --coverdir /tmp/opencode/s_g5_10_cov infra/s_g5_10/tests/run_coverage.py` | no missed executable lines in `ux01_evidence.py`, including the CLI entry point |

`coverage.py` is not installed in this environment, so line coverage is measured
with the standard-library `trace` tool with `--missing`. The CLI bootstrap line
is executed by `test_module_entry_point_runs_main`, so it is not an exclusion.
Branch coverage has no mechanical measure here; the 51 tests exhaustively
enumerate the evaluator's branches, including malformed and hostile JSON.
No uncovered or pending-as-covered result is claimed.

## Exit codes

`0` means every check passed; `1` means at least one check failed; `2` means the
input could not be read (bad usage, missing file or invalid JSON). A command-line
run prints one line per named check with a PASS or FAIL marker and the evidence
class.

## Remaining gate

Real assistive-technology reviews, a real WCAG 2.2 AA audit and DST exercise in a
deployed environment are outside this repository. They are produced by
authorized reviewers and accepted at G5; a passing synthetic artifact is not
that evidence.
