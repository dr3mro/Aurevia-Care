# Recovery evidence artifact and verification

The evaluator judges a JSON artifact, never a running system. It can prove that
supplied facts meet OPS-04/OPS-05 and that the declared envelope is complete; it
cannot prove a restore or drill actually happened. Real acceptance is a human G5
gate.

## Artifact schema

| Field | Shape | Enforced content |
|---|---|---|
| `schema_version` | string | `1.0` |
| `kind` | string | `synthetic_rehearsal` or `conducted_drill` |
| `source` | non-blank string | Drill or fixture identifier; a synthetic marker on a conducted drill is an `evidence_conflict` |
| `targets` | object | `rpo_minutes` in `[0, 15]`, `rto_hours` in `(0, 4]`, both numbers |
| `scope` | array | Names `core_chart`, `order_data`, `documents`, `document_associations`; no duplicates |
| `restore` | object | `targets` names `database`, `objects`, `idp_dependencies`; `clean_environment` is `true` |
| `reconciliation` | object | Four named gates plus `completed_before_writes_reopened`, all `true` |
| `verification` | object | `before_pilot` and `quarterly_cadence`, both `true` |
| `monitoring.signals` | array | All 13 OPS-05 signal names; no duplicates |
| `ownership` | object | `operational_on_call_owner` and `site_clinical_coverage_owner`, non-blank strings |
| `runbooks` | array | `paper_manual_capture`, `patient_identification`, `critical_contacts`, `supervised_back_entry`; no duplicates |
| `duplicate_prevention` | boolean | Literal `true` |

A wrong-typed section is recorded as a failed check rather than raising or being
skipped. Boolean gates use strict `is True` so a string such as `"false"` cannot
pass. See [Recovery targets](recovery-targets.md) and
[Operational runbooks](operational-runbooks.md) for the meaning of each field.

## Commands

| Command | Purpose |
|---|---|
| `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/synthetic_recovery_evidence.json` | Evaluate the conforming synthetic artifact (exit 0) |
| `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/violation_recovery_evidence.json` | Evaluate the violation artifact (exit 1; prints every failed check) |
| `bash infra/recovery/check.sh` | Canonical gate: unit tests plus positive and negative fixture runs |
| `python3 -I -m unittest discover -s infra/recovery/tests -q` | Evaluator unit tests alone |
| `python3 -I infra/recovery/tests/coverage_gate.py` | Build-failing coverage gate; exits 1 if any evaluator line is uncovered |
| `python3 -I -m trace --count --missing --coverdir /tmp/opencode/recovery_cov infra/recovery/tests/run_coverage.py` | Line coverage; inspect the evaluator's `recovery_evidence.cover` file for `>>>>>>` marks. `--missing` is required, otherwise unexecuted lines are never annotated |

Exit codes: `0` = artifact passes, `1` = it fails at least one check, `2` =
unreadable input (bad path, malformed JSON or invalid UTF-8).

## Observed rehearsal result

Run locally on synthetic fixtures:

- The conforming artifact finishes with `verdict: pass`, `classification:
  rehearsal`, `final_evidence: false` and all section checks passing.
- The violation artifact finishes with `verdict: fail` and failed checks for both
  targets, the missing scope class, the missing restore target and
  `clean_environment`, two reconciliation gates, `before_pilot`, monitoring
  signals, the missing clinical owner, runbooks and `duplicate_prevention`.
- `bash infra/recovery/check.sh` exits 0; 97 unit tests pass; the build-failing
  coverage gate reports 271 evaluator lines with no uncovered line (measured
  with `trace --count --missing`; the CLI `__main__` guard is a `#pragma NO COVER`
  exception exercised through `main()` in the tests).

These results prove the evaluator works on synthetic data. They are not a restore
drill and are not final evidence.

## Remaining gate

A real conducted drill must be run in an authorized environment with the
operations, privacy and site clinical owners. Its artifact must carry observed
RPO/RTO values, the reconciliation results, the named owners and the drill
outcome. Human acceptance of that artifact remains an open G5 gate and is outside
what this repository can provide.
