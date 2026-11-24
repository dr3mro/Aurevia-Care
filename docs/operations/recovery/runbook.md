# Recovery drill runbook (OPS-04)

This runbook is the procedure for rehearsing, or conducting under authorization,
the clean-environment restore required by [OPS-04](../../../.opencode/config/PROJECT_V1_SPEC.md).
It is a *procedure*, not evidence that any restore happened. Observed times,
results, evidence links and approvals are recorded in the
[drill evidence template](drill-evidence.md), which starts empty.

Run a rehearsal on synthetic fixtures only. Run a conducted drill only under an
authorized incident or scheduled exercise with the named owners. Never place
patient data, credentials, real domains or PHI in a log, an evidence link, a URL
or this document. A rehearsal is a check of the procedure and of the evidence
envelope; it is never presented as a conducted restore or as final evidence.
Human acceptance of a conducted drill remains a G5 gate.

## 1. Scope and recovery targets

OPS-04 sets RPO ≤ 15 minutes and RTO ≤ 4 hours for the following four data
classes. All four must be present in every drill record; reporting a near miss
such as "documentation" does not satisfy "documents".

| Data class | What must survive the restore |
|---|---|
| `core_chart` | Longitudinal chart, including signed revisions and provenance |
| `order_data` | Orders, requisitions, specimens, results and their workflow state |
| `documents` | Original PDF and image bytes and rendered report versions |
| `document_associations` | Links between documents and chart, order or result, with their checksums and void history |

| Objective | Specification value | How it is measured |
|---|---|---|
| RPO | ≤ 15 minutes (a value in `[0, 15]`; `0` means zero-data-loss design) | Elapsed time from the last recoverable committed point to the incident declaration, from timestamped backup/PITR records |
| RTO | ≤ 4 hours (a value in `(0, 4]`) | Elapsed time from incident declaration to the RTO stop condition in section 6, from timestamped drill observations |

The units are carried by the field names `rpo_minutes` and `rto_hours` in the
evidence record. Record the *observed* values; copying the target number into
the result field fabricates evidence. Non-finite numbers and string booleans
fail closed.

## 2. Roles and approval authority

This runbook names roles, not individuals. The rota, escalation contacts and
named approvals live in the organization's approved policy pack (ADM-01).
A flag in an artifact or a passing checker is never an approval.

| Role | Responsibility in a drill |
|---|---|
| Operational on-call owner | Executes the restore, operates the reconciliation checks and declares the RTO stop condition |
| Privacy / accountable operations owner | Authorizes reopening of writes after the reconciliation gate is signed |
| Site clinical coverage owner | Confirms clinical integrity of chart, order and result data before clinical use resumes |
| G5 acceptance owners | Accept the conducted-drill evidence; a separate human gate from reopening writes |

Rules for approval:

- Writes are reopened only on a signed decision from the operational on-call
  owner **and** the privacy / accountable operations owner, with the site
  clinical coverage owner confirming clinical integrity. One person must not
  both execute the restore and be the sole approver (separation of duties).
- The approval is recorded as a named role, timestamp and outcome in the
  evidence template. No script, boolean flag or automated check can approve.
- Reopening approval and final G5 drill acceptance are distinct; passing the
  reconciliation gate permits writes to resume but does not itself accept the
  drill.

## 3. Restore order

Establish a clean environment first, then restore the three dependency classes
in order. The step IDs below match the rows of the evidence template.

### D1 — Establish a clean environment

1. Provision an isolated environment with no live write traffic; confirm network
   and identity separation from the live deployment.
2. Pin the schema version and artifact digest so the target is reproducible.
   Do not reuse a database or object bucket that still holds live state.
3. Record the environment identifier in the drill log. The isolation proof lives
   in the drill log, never in a URL or a log line carrying patient data.

### D2 — Restore the database (first)

Restore PostgreSQL to the chosen point in time using managed PITR. Apply Flyway
migrations up to the pinned version only after the data is in place. Integrity
checks: tenant row counts, presence of forced row-level security policy and an
intact audit hash chain. This is the authoritative state, including tenant
tables and the append-only audit tables.

### D3 — Restore objects (second)

Restore versioned private objects: quarantined uploads, approved originals and
rendered reports. Recompute each checksum against the digest recorded at write
time. Object keys are unique and the object version verified; protected payloads
stay out of ordinary operational logs and URLs.

### D4 — Restore identity-provider dependencies (third)

Restore the relying-party configuration and centrally stored secrets, then rebind
the runtime to the restored store. Confirm that staff and test-patient sign-in
succeeds in the isolated environment. The managed identity provider itself is
not restored from this repository; only configuration and centrally held secrets
are in scope. Never copy a production credential into the artifact, the fixture,
a log or an evidence link.

## 4. Reconciliation gate before writes reopen

All checks run while the environment is still read-only. The gate is complete
only when every step passes and is signed; a drill that reopened writes first
fails even if the checks later passed.

### R1 — Hashes

Recompute stored object and report digests and compare them with the recorded
values. A mismatch blocks the gate; hold the affected object for review rather
than silently replacing it. This reuses the checksum and provenance discipline
of the tenant export manifest (`docs/operations/tenant-lifecycle/export-manifest.md`).

### R2 — Source versions

Confirm source system identifiers, versions and clinical effective times
survived the restore unchanged. Do not renumber source IDs or rewrite effective
times. Preserve historical signature evidence without creating a new
attestation, as required for approved onboarding data
(`docs/operations/onboarding/onboarding-manifest.md`).

### R3 — In-flight deliveries

Reconcile the transactional outbox and dead-letter queue by immutable event ID
inside the sender/type namespace. Replay must produce one downstream effect and
one clinical task for the intended revision even if the event appears more than
once. Confirm order and patient identifiers before any external link is
re-created. Deduplicate external events and reject or hold out-of-order changes
by per-aggregate version (ARC-05).

### R4 — Audit coverage

Verify the per-tenant append-only audit hash chain across restored rows. A broken
sequence or missing chain segment blocks the gate. Confirm audit coverage of
clinical reads, exports, downloads, disclosures, mutations and administration;
a restored record without its audit trail is not reconciled.

### R5 — Reapply revocations and withdrawals

A point-in-time restore can bring back state that was later revoked. Before any
protected request is served, re-derive current membership and role grants
(IAM-02), verified patient-chart links (IAM-05) and withdrawn patient releases.
This is a mandatory gate derived from those clauses; it is a runbook step, not a
new numerical threshold.

### Duplicate prevention across the gate

Use idempotency keys scoped to tenant, caller and command with a request digest:
the same key and payload return the original outcome subject to current
authorization, while a different payload with the same key is a conflict
(ARC-03). Keep durable business uniqueness for external clinical actions even
when a retry key has expired, so a re-applied command cannot create a second
order, result, release or payment entry.

## 5. Reopening writes and the observation window

### A1 — Approve reopening writes

Reopen only after the reconciliation gate is signed by the roles in section 2.
Record the named approvers, their roles and the approval timestamp in the
evidence template.

### W1 — Reopen writes and observe

Reopen writes, then watch error rate, queue age and partner delivery during the
first observation window. Any breach is recorded as a failed step or a corrective
action, not suppressed.

## 6. The RTO mark

### M1 — Check the RTO mark and record measurements

The RTO clock starts at the incident declaration (UTC). It stops when the
restored environment is available for authorized read access **and** the
reconciliation gate has been signed (writes may then reopen). At the RTO mark,
check and record all of the following:

1. An isolated, clean environment is running the pinned schema version and
   artifact digest.
2. All three dependency classes (database, objects, IdP dependencies) are
   restored and their integrity checks have passed.
3. Authorized staff and test-patient sign-in succeeds in the isolated
   environment, with no production credential copied.
4. The reconciliation gate (R1–R5) passed and was signed *before* writes
   reopened.
5. All four recovery scope classes are present in the record.
6. Named owners and timestamps are recorded, and RPO and RTO are measured from
   observed timestamps rather than copied from the targets.
7. No patient data, credential, real domain or PHI appears in the evidence,
   links, URLs or logs.

If any check fails, or the measured elapsed time exceeds 4 hours, the RTO target
is missed. Record the actual value and the failed check; do not fabricate a pass.
RPO is met only when the last recoverable committed point is no more than 15
minutes before the incident declaration.

## 7. Verification cadence

OPS-04 requires the restore path to be verified before pilot and quarterly
thereafter. A single artifact cannot prove a recurring schedule; quarterly
compliance is shown by successive dated drill records. Record the before-pilot
and quarterly-cadence claims, with the drill date and the next scheduled drill,
in the evidence template.

## 8. Related material

- Product specification: [OPS-04](../../../.opencode/config/PROJECT_V1_SPEC.md).
- Production readiness evidence: [checklist](../production-readiness/README.md).
- Deployment handoff: [DEPLOYMENT.md](../../../infra/DEPLOYMENT.md).
- The existing engineering handoff for recovery targets, the fail-closed
  evidence evaluator and the monitoring envelope lives in
  `docs/operations/recovery/` (targets, restore procedure, operational runbooks
  and evidence documents). This runbook is the drill procedure that drives the
  empty template in `docs/operations/recovery/drill-evidence.md`.
