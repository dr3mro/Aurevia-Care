# Clean-environment restore procedure (OPS-04)

This runbook describes the restore path the evidence artifact records. It is a
procedure specification, not evidence that a restore was executed. Execute it
only under an authorized drill or incident with the operations and privacy owners
named in [Operational runbooks](operational-runbooks.md).

## 1. Establish a clean environment

1. Provision an isolated environment with no production write traffic. Confirm
   network and identity separation from the live deployment.
2. Pin the schema version and the artifact digest being restored so the target is
   reproducible. Do not reuse a database or object bucket that still holds live
   state.
3. Record the environment identifier in the drill log. The evaluator's
   `restore.clean_environment` boolean records this claim; the isolation proof
   lives in the drill log, not in the artifact.

## 2. Restore the three dependency classes

| Order | Dependency | Action | Integrity check |
|---|---|---|---|
| 1 | `database` | Restore PostgreSQL to the chosen point in time using managed PITR; apply Flyway migrations up to the pinned version only after the data is in place | Tenant row counts, forced RLS policy presence and the audit hash chain are intact |
| 2 | `objects` | Restore versioned private objects: quarantined uploads, approved originals and rendered reports | Recompute each checksum against the digest recorded at write time |
| 3 | `idp_dependencies` | Restore relying-party configuration and centrally stored secrets; rebind the runtime to the restored store | Staff and test patient sign-in succeeds in the isolated environment; no production credential is copied |

The managed identity provider itself is not restored from this repository. Only
the configuration and centrally held secrets that the application depends on are
in scope. Never write a secret into the artifact, the fixture or a log.

## 3. Pass the reconciliation gate before reopening writes

All of the following must complete while the environment is still read-only:

1. **Hashes.** Recompute stored object and report digests and compare them with
   the recorded values. A mismatch blocks the gate; the affected object is held
   for review rather than silently replaced.
2. **Source versions.** Confirm source system identifiers, versions and clinical
   effective times survived the restore unchanged. Do not renumber source IDs or
   rewrite effective times.
3. **In-flight deliveries.** Reconcile the transactional outbox and dead-letter
   queue by immutable event ID within the sender/type namespace. Replay must
   produce one downstream effect and one clinical task for the intended
   revision, even if the event appears more than once. Confirm order and patient
   identifiers before any external link is re-created.
4. **Audit coverage.** Verify the per-tenant append-only audit hash chain across
   restored rows. A broken sequence or missing chain segment blocks the gate.
5. **Reapplied revocations and withdrawals.** A point-in-time restore can bring
   back state that was later revoked. Re-derive current membership and role
   grants (IAM-02), verified patient-chart links (IAM-05) and withdrawn patient
   releases before any protected request is served. This is a mandatory gate
   derived from IAM-02, IAM-05 and §11, and is documented here rather than added
   as an extra evaluator threshold because OPS-04 does not name it explicitly.

Set `reconciliation.completed_before_writes_reopened` to `true` only after all
five steps pass. A drill that reopened writes first fails the evaluator even if
the individual checks later passed.

## 4. Reopen writes and record the result

1. Reopen writes only after the gate passes. Watch error rate, queue age and
   partner delivery during the first observation window.
2. Record the achieved RPO and RTO against `targets.rpo_minutes` (≤ 15) and
   `targets.rto_hours` (≤ 4). The recorded values are measurements, not the
   targets: entering the target value without a measurement would fabricate
   evidence, so the drill log must carry the observed timestamps.
3. Record drill outcome, owners, timestamps and any failed step in the outcome
   log. Keep the artifact synthetic-labelled when it is a rehearsal.

## 5. Prevent duplicates during recovery

- Use idempotency keys scoped to tenant, caller and command with a request
  digest. A replay with the same key and payload returns the original outcome
  subject to current authorization; a different payload with the same key is a
  conflict (ARC-03).
- Keep durable business uniqueness for external clinical actions even when a
  retry key has expired, so a re-applied command cannot create a second order,
  result, release or payment entry (ARC-03).
- Deduplicate external events by immutable event ID inside the sender/type
  namespace, and use per-aggregate version to reject or hold out-of-order
  changes (ARC-05).
- On supervised back-entry from paper or manual capture, preserve original
  timestamps and actor, and reconcile against entries already committed before
  the outage. This runbook is in [Operational runbooks](operational-runbooks.md).

Reserved migration version **V48** is deliberately unused: this story changes no
schema, and the repository's change-safety gate allows version gaps between
parallel workers.
