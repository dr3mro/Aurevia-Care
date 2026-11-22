# IAM-04 clause 6 — lock both records during commit

## Clause

Lock both records during commit.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.patient.clauses.LockRecordsDuringCommitTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/LockRecordsDuringCommitTest.java`),
an HTTP-level and SQL-level integration test on real PostgreSQL with synthetic
patients and staff in one synthetic tenant:

- `pairLockHoldsExclusiveRowLocksOnBothRecordsUntilTransactionEnds` — the pair
  lock reports two locked records, and a second transaction cannot take a
  `FOR UPDATE NOWAIT` lock on either row until the first transaction ends; both
  rows are lockable again afterwards.
- `consolidationCommitsBothAppendsAtomicallyUnderThePairLock` — a second
  authorized reviewer approves through `POST /api/v1/duplicate-reviews/{id}/consolidate`;
  the commit returns 200 and appends exactly one association and one
  consolidation while moving the duplicate's chart visibility to the survivor.
- `consolidationRefusesToCommitWhenThePairLockCannotClaimTwoRecords` — a
  malformed review naming one record as both sides (seeded directly because the
  public open command rejects it) is refused with 409 and
  `both patient records must be locked`; no association or consolidation is
  appended.
- `pairLockReportsOneLockedRecordForAMissingPatient` — the SQL pair lock counts
  only existing rows, so one absent record is reported as one locked record and
  cannot satisfy the two-record precondition.
- `staleVersionIsRefusedBeforeTheLockedCommitAppendsAnything` — a stale
  `If-Match` version is refused with 412 and no chart association or
  consolidation is appended.

Production classes and database objects exercised by the entry points:
`com.aurevia.smartclinic.patient.consolidation.ConsolidationController` (thin
HTTP adapter for `/api/v1/duplicate-reviews`),
`com.aurevia.smartclinic.patient.consolidation.ConsolidationService` (locks the
pair before reading versions and commits association, event and audit in one
tenant-guarded transaction),
`com.aurevia.smartclinic.patient.consolidation.DuplicateRepository`
(`lockPair` SQL boundary), `com.aurevia.smartclinic.tenant.TenantContext` and
`com.aurevia.smartclinic.api.IfMatch`; the lock itself is
`public.lock_patient_pair(uuid, uuid)` in
`db/migrations/V9__duplicate_review_consolidation.sql`, which takes an ordered
advisory lock and `SELECT ... FOR UPDATE` on both `patient_record` rows under
forced row level security.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=LockRecordsDuringCommitTest test`.

## Gap

None.
