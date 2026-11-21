# Recovery drill evidence template (OPS-04)

This is an **empty template** for an OPS-04 restore drill. Fill it during an
authorized rehearsal or conducted drill by following the
[recovery drill runbook](runbook.md). It is not evidence that any drill occurred
and it is not an approval. Leave every field blank or `—` until it has actually
been observed; do not invent results or copy target values into measured fields.

A rehearsal on synthetic fixtures must be labelled `synthetic_rehearsal` and is
never presented as a conducted restore or as final evidence. A conducted drill
must name a real source and carry observed timestamps. Human acceptance of a
conducted drill is a separate G5 gate.

Never put patient data, credentials, a real domain or PHI in any field, evidence
link or URL. Evidence links must reference approved internal artifacts, never a
patient record or a signed URL.

## Drill envelope

| Field | Value |
|---|---|
| Drill identifier | — |
| Kind (`synthetic_rehearsal` / `conducted_drill`) | — |
| Source / environment identifier | — |
| Schema version and artifact digest pinned | — |
| Incident declaration time (UTC) | — |
| Last recoverable committed point (UTC) | — |
| RTO stop time (UTC) | — |

## Measured objectives

| Field | Target | Measured value | Met? |
|---|---|---|---|
| RPO (minutes) | ≤ 15 | — | — |
| RTO (hours) | ≤ 4 | — | — |

| Reconciliation field | Value |
|---|---|
| Reconciliation gate completed before writes reopened | — |
| Verified before pilot | — |
| Quarterly cadence declared | — |
| Next scheduled drill (date) | — |

## Step evidence

One row per runbook step. Planned time is the planned offset from the incident
declaration; actual time is the observed UTC timestamp. Result is `pass`, `fail`
or `not observed`. Every non-pass row needs a corrective action noted in the
evidence link or findings.

| Step | Description | Planned time | Actual time | Result | Evidence link | Approver |
|---|---|---|---|---|---|---|
| D1 | Establish a clean, isolated environment on the pinned schema/digest | — | — | — | — | — |
| D2 | Restore database (PITR, migrate to pinned version, integrity checks) | — | — | — | — | — |
| D3 | Restore objects (quarantine, originals, rendered reports) and check checksums | — | — | — | — | — |
| D4 | Restore IdP dependencies and verify sign-in without copying credentials | — | — | — | — | — |
| R1 | Reconcile hashes of stored objects and reports | — | — | — | — | — |
| R2 | Reconcile source versions and clinical effective times | — | — | — | — | — |
| R3 | Reconcile in-flight deliveries (outbox/dead-letter) by immutable event ID | — | — | — | — | — |
| R4 | Verify per-tenant append-only audit hash chain coverage | — | — | — | — | — |
| R5 | Reapply revocations and withdrawals (membership, chart links, releases) | — | — | — | — | — |
| A1 | Approve reopening writes (operational on-call + privacy/operations owner) | — | — | — | — | — |
| W1 | Reopen writes and observe error rate, queue age, partner delivery | — | — | — | — | — |
| M1 | Check RTO mark and record measured RPO/RTO and scope | — | — | — | — | — |

## Sign-off

| Decision | Named role | Name / rota | Timestamp (UTC) | Outcome |
|---|---|---|---|---|
| Restore technically complete | Operational on-call owner | — | — | — |
| Clinical integrity confirmed | Site clinical coverage owner | — | — | — |
| Writes reopened | Privacy / accountable operations owner | — | — | — |
| Conducted-drill evidence accepted (G5) | G5 acceptance owners | — | — | — |

## Findings and corrective actions

Record failed steps, duplicate-prevention incidents, evidence gaps and the
corrective action with its owner. Keep this section empty until a drill is run.
