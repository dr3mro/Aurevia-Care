# OPS-06 evidence contract and verification

## Artifact schema

An artifact is a JSON object with a `schema_version` of `1.0`, a `kind` of
`synthetic_rehearsal` or `measured_deployment`, a `source` string and these
sections: `environments`, `fixtures`, `ci_checks`, `signed_builds`,
`migration_checks`, `rollout`, `production_access`, plus the top-level boolean
`reversible_deployments`. A `measured_deployment` artifact additionally requires
a `provenance` object with six non-blank string fields: `tool`, `tool_version`,
`command`, `utc_timestamp`, `environment` and `source_sha`. The
[conforming fixture](../../../infra/delivery-safety/fixtures/synthetic_evidence.json) is
the worked rehearsal example; there is no *conforming* measured-deployment
fixture because real rollout provenance is produced and reviewed outside this
repository. A negative
[relabelled-rehearsal fixture](../../../infra/delivery-safety/fixtures/relabelled_violation_evidence.json)
does exist: it is the conforming rehearsal with only `kind` and `source`
changed and no provenance, and the canonical gate requires it to be rejected.

The evaluator aggregates every breach into one report; it never raises on
content problems. Each check has a stable name:

| Group | Named checks |
|---|---|
| Structure | `artifact`, `schema_version`, `kind`, `source`, `evidence_conflict` |
| Environments | `environments`, `environments.names`, `environments.<name>`, `environments.distinct_accounts`, `environments.distinct_resources` |
| Fixtures | `fixtures`, `fixtures.synthetic_only`, `fixtures.no_real_patient_data`, `fixtures.environments` |
| CI | `ci_checks`, `ci_checks.policy`, `ci_checks.dependency_scan`, `ci_checks.secret_scan` |
| Signed builds | `signed_builds`, `signed_builds.method`, `signed_builds.digest`, `signed_builds.verified`, `signed_builds.signer_identity`, `signed_builds.promotion_gate` |
| Migrations | `migration_checks`, `migrations.gate`, `migrations.expand_contract`, `migrations.forward_fix_required` |
| Rollout | `rollout`, `rollout.strategy`, `rollout.stages`, `rollout.health_gates`, `rollout.monitoring`, `rollout.rollback_tested` |
| Production access | `production_access`, `production_access.approval_required`, `production_access.separation_of_duties`, `production_access.audited`, `production_access.expiring` |
| Reversibility | `reversible_deployments` |
| Measured provenance (measured only) | `provenance`, `provenance.tool`, `provenance.tool_version`, `provenance.command`, `provenance.utc_timestamp`, `provenance.environment`, `provenance.source_sha` |

## Classification and honesty rule

| `kind` | Classification | `final_evidence` |
|---|---|---|
| `synthetic_rehearsal` | `rehearsal` | `false` |
| `measured_deployment` with zero failures and conforming provenance | `measured-deployment` | `true` |
| `measured_deployment` with any failure (including missing provenance) | `measured-deployment` | `false` |
| unknown or non-string | `unknown` | `false` |

A `measured_deployment` artifact whose `source` contains `synthetic` records an
`evidence_conflict` failure and is never final. It must also carry the six-field
`provenance` object described above, so changing only `kind` and `source` on a
rehearsal cannot promote it to final evidence. The sibling
[OPS-03 evaluator](../capacity/README.md) enforces the same rule with its own
classification key (`measured-pilot`) and provenance fields; the keys are not
shared. Provenance is checked for presence and shape only — verifying that a
rollout really happened, and G5 acceptance, remain human and cloud gated.

## Commands and exit codes

```bash
bash infra/delivery-safety/check.sh
python3 -I -m unittest discover -s infra/delivery-safety/tests -q
python3 -I infra/delivery-safety/delivery_safety_evidence.py \
  infra/delivery-safety/fixtures/synthetic_evidence.json
python3 -I infra/delivery-safety/delivery_safety_evidence.py \
  infra/delivery-safety/fixtures/violation_evidence.json   # exit 1 is expected
python3 -I infra/delivery-safety/delivery_safety_evidence.py \
  infra/delivery-safety/fixtures/relabelled_violation_evidence.json  # exit 1: relabelled rehearsal
```

`0` = every check passes, `1` = at least one failed check, `2` = wrong argument
count, unreadable file, malformed JSON, invalid UTF-8 or a non-finite number.
The canonical gate also runs the real migration checker.

## Coverage and branch enumeration

`coverage.py` is not installed in this environment, so line coverage is measured
with the standard library:

```bash
python3 -m trace --count --coverdir /tmp/opencode/delivery-safety_cov \
  infra/delivery-safety/tests/run_coverage.py
grep '^>>>>>>' /tmp/opencode/delivery-safety_cov/*delivery_safety_evidence*.cover
```

The result is **248 executable lines, 0 uncovered** (100% line coverage of the
evaluator). `trace` does not measure branches, so every conditional is exercised
by paired synthetic cases:

| Conditional | Passing case | Failing / alternate case |
|---|---|---|
| artifact is an object | conforming artifact | `evaluate([])` |
| kind known / unknown / unhashable | both known kinds | `unverified` and a list value |
| source is a non-blank string | conforming source | blank, whitespace, non-string and list values |
| evidence conflict | `measured_deployment` + neutral source | `measured_deployment` + synthetic source |
| environments object / names / entry type / field type / distinctness | conforming four | wrong names, non-object entry, blank account, null resource, duplicate account, duplicate resource |
| fixtures object / flags / list shape | conforming flags and list | wrong flags, missing/extra/duplicate/non-string members, non-list |
| CI object / each flag | three `true` flags | non-object and `false`, `1`, string, `null` per flag |
| signed-build object / enum / digest / flag / string / enum | conforming fields; uppercase digest | wrong method, short/wrong-prefix/non-hex/wrong-length digest, `false`/`1`/`null`, blank identity, wrong gate |
| migration object / gate / two flags | conforming fields | non-object, wrong gate, wrong flags |
| rollout object / strategy / stages / three flags | four stages; two minimal stages | non-object, wrong strategy, short/duplicate/blank/non-string/missing-`full`/non-list stages, wrong flags |
| production-access object / approval / duties / audit / expiry | conforming fields | non-object, wrong approval, equal or blank requester/approver, wrong audit/expiry |
| reversible flag | `true` | `false`, `1`, string, `null` |
| report filtering / verdict / classification / final | pass report | mixed and all-fail reports, each kind, measured-with-failure |
| report formatting | PASS and final-true text | FAIL and final-false text |
| CLI | success, `sys.argv` path | bad usage, missing file, malformed JSON, non-UTF-8, `NaN` |
| measured provenance | conforming six-field object; rehearsal needs none | missing, empty and non-object provenance; each field blank, non-string or absent |
| normalized distinctness | conforming four environments, `canary → full` stages, distinct access parties | whitespace-padded duplicate account/resource/stages and whitespace-padded self-approval |
| gate negative controls | conforming rehearsal passes | violation fixture and relabelled-rehearsal fixture each exit exactly 1; relabelled rehearsal fails only on provenance |

Each `subTest` case above is executed by `unittest`; the full suite reports
**71 tests, 0 failures**. The policy manifest and all fixture files are also
scanned for credential and PHI-shaped strings. The canonical gate requires both
negative fixtures (the control breach and the relabelled rehearsal) to exit with
status exactly `1`; any other non-zero status is a parse or I/O regression and
fails the gate. The relabelled-rehearsal check is also a gate-level complement to
the unit tests for the `evidence_conflict` / measured-provenance branches.

## Boundary

The evaluator proves the structure and completeness of a declaration. It does
not verify cloud IAM isolation, real signatures, executed scans, live health
signals, the true identity of an approver or audit immutability. Even
`final_evidence: true` records a claim. Real verification and G5 acceptance are
human and cloud gated and are listed as open items in the
[handoff](README.md). No patient data, credential or real deployment detail is
stored in this directory.
