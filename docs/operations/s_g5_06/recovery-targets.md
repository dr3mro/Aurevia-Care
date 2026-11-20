# Recovery targets (OPS-04)

This document pins the OPS-04 literals exactly as the
[evaluator](../../../infra/s_g5_06/recovery_evidence.py) enforces them. An
evidence artifact that omits or misstates any item below is reported as a
failure; it is never silently accepted.

## Recovery objectives

| Artifact field | Specification value | Rule |
|---|---|---|
| `targets.rpo_minutes` | ≤ 15 minutes | A real number in `[0, 15]`; booleans and strings fail. `0` is accepted as a zero-data-loss design. |
| `targets.rto_hours` | ≤ 4 hours | A real number in `(0, 4]`; booleans and strings fail. A zero RTO is not meaningful. |

The units are carried by the field names, not by a free-text label. Reporting
900 seconds as `rpo_minutes: 900` fails, and reporting 0.25 hours as
`rto_hours: 0.25` is a valid (fast) result rather than an accidental pass. Non-finite
numbers fail: a value that is not comparable to the limit cannot satisfy it.

## Recovery scope

`scope` must name each of these four classes at least once, with no duplicates:

- `core_chart` — the longitudinal chart, including signed revisions and provenance.
- `order_data` — orders, requisitions, specimens, results and their workflow state.
- `documents` — original PDF and image bytes and rendered report versions.
- `document_associations` — the links between documents and chart, order or result,
  including their checksums and void history.

Additional classes are permitted; recording more scope cannot conceal a missing
required class. A near miss such as `documentation` does not satisfy `documents` —
membership is exact, not a substring.

## Restore targets and environment

`restore.targets` must name `database`, `objects` and `idp_dependencies`, and
`restore.clean_environment` must be the literal boolean `true`.

- **database** — the authoritative PostgreSQL state, including tenant tables,
  forced row-level security policy and the append-only audit tables.
- **objects** — private object-store versions for quarantined uploads, approved
  originals and rendered reports.
- **idp_dependencies** — the relying-party configuration and centrally stored
  secrets needed for staff and patient sign-in, not the managed identity provider
  itself.

"Clean environment" means an isolated environment with no live write traffic and
no state carried over from the failed deployment. The boolean records the claim;
the procedure that makes it true is in [Restore procedure](restore-procedure.md).

## Reconciliation gate

`reconciliation` must set each of these to the literal boolean `true`:

| Field | Meaning at restore time |
|---|---|
| `hashes` | Stored object and report checksums recomputed and compared with the recorded digests. |
| `source_versions` | Source system identifiers, versions and clinical effective times preserved, not renumbered. |
| `in_flight_deliveries` | Outbox and dead-letter entries deduplicated by immutable event ID in the sender/type namespace, so replay produces one downstream effect (ARC-05). |
| `audit_coverage` | The per-tenant append-only audit hash chain verified across restored rows, with no missing sequence. |
| `completed_before_writes_reopened` | All of the above completed *before* the restored system accepts writes again. |

Reconciliation is a gate, not a summary: a drill that reopened writes first and
reconciled afterwards fails even if every individual check later passed.

## Verification cadence

`verification.before_pilot` and `verification.quarterly_cadence` must both be the
literal boolean `true`. The first records that the restore path was rehearsed
before pilot use; the second declares the recurring schedule OPS-04 requires.
A single artifact cannot prove that future quarterly drills happened — that
evidence comes from successive dated drill records and is an open G5 item.

## Evidence honesty

`kind` must be `synthetic_rehearsal` or `conducted_drill`. `source` must be a
non-blank identifier. A `conducted_drill` whose `source` contains a synthetic
marker (`synthetic`, `fixture`, `seed` or `mock`, case-insensitive) fails as an
`evidence_conflict`, and never becomes final evidence. A conforming
`synthetic_rehearsal` is a rehearsal of the check, not proof of a restore.

The evaluator judges an artifact, not a running system: it can prove the declared
envelope is complete and the recorded numbers meet the targets, but it cannot
prove a restore actually occurred. Human acceptance remains a G5 gate.
