- [x] FIX-PHARM-SUBSTITUTION · Persist and apply approved pharmacy substitutions
  - pr: #683
  - evidence: PR #683; integrator ran scripts/repair-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/FIX-PHARM-SUBSTITUTION.md; simulated review only
  - attempts: 1
  - requirements: RX-01, RX-02, ARC-02
  - scope: apps/api/src/main/java/com/aurevia/pharmacyflow/substitution, apps/api/src/test/java/com/aurevia/pharmacyflow/substitution, apps/api/src/main/java/com/aurevia/prescription, apps/api/src/test/java/com/aurevia/prescription, apps/api/src/main/java/com/aurevia/audit, apps/api/src/test/java/com/aurevia/audit, db/migrations/V94__durable_pharmacy_substitution.sql
  - depends: PHARM-04
  - reserved: V94
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/api/src/main/java/com/aurevia/pharmacyflow/substitution/SubstitutionStore.java`, `apps/api/src/main/java/com/aurevia/pharmacyflow/substitution/SubstitutionService.java`.

    SubstitutionService stores proposals and signed revisions in maps. Approval does not update the authoritative transmitted prescription.
    Write a failing HTTP/PostgreSQL regression before changing behavior. Use a tenant-guarded transactional JDBC store with RLS and FORCE RLS. Atomically recheck version and prescribing entitlement, persist the approval and provenance, apply the signed revision through the existing prescription workflow, and append the audit event. Preserve the original revision and decline behavior.
    Acceptance: a new service instance sees the result, failed writes leave all records unchanged, stale approval cannot win, same-key retry produces one decision, and an unauthorized prescriber is denied. Trace pharmacy and prescriber reads to the persisted result. Require clinical review before real-patient enablement.
