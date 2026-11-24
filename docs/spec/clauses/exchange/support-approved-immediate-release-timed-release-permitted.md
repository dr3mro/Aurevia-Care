## Clause

Support approved immediate release, timed release, or permitted holds with reason, expiry/review and audit.

## Status

Proven by tests

## Evidence

All links are relative to this file; the repository root is four levels up. Every assertion below was executed against a throwaway PostgreSQL database migrated with the production Flyway scripts, using synthetic tenants, staff and patients only. No human, clinical or real-patient evidence is claimed.

**Proof class.** [SupportApprovedImmediateReleaseTimedReleasePermittedTest.java](../../../../apps/api/src/test/java/com/aurevia/exchange/clauses/SupportApprovedImmediateReleaseTimedReleasePermittedTest.java) is a `@Tag("integration")` HTTP test on `synthetic-auth` with a per-class `DROP DATABASE ... WITH (FORCE)` setup, `filesystem:../../db/migrations` Flyway location and the `aurevia_migrate` / `aurevia_app` roles. It enters through `POST /api/v1/releases` and `POST /api/v1/releases/{id}/lift-hold` and observes the patient view through `GET /api/v1/patients/{patientId}/released-revisions`.

| Test method | Behaviour proved |
|---|---|
| `immediateReleaseIsAuthorizedVisibleAndAudited` | IMMEDIATE authorization returns 201 `AUTHORIZED`, the blank `effectiveFrom` resolves to the shared clock within tolerance, the revision is visible at once and one `RELEASE_AUTHORIZE` / `SUCCESS` audit row is committed. |
| `timedReleaseHidesUntilItsEffectiveInstant` | A future TIMED release is 201 `AUTHORIZED` but hidden; a past TIMED release is visible; a near-future TIMED release is hidden and then observed to open by elapsed time with no lift. |
| `holdNeedsReasonAndFutureExpiryAndStaysHiddenUntilLifted` | A blank hold reason and a past hold expiry each return 422 with `INVALID_HOLD_REASON` / `INVALID_HOLD_EXPIRY`; a valid HOLD is 201 `HELD` with reason and expiry persisted and round-tripped, stays hidden, and after an authorized lift with a non-blank note becomes 201→200 `AUTHORIZED` and visible. The lift writes `RELEASE_LIFT_HOLD` / `SUCCESS`, a `HOLD_LIFTED` notification and a second append-only event; a forged `reviewedBy` in the body is ignored in favour of the authenticated actor. |
| `receptionCannotAuthorizeOrLiftAHold` | A receptionist is refused 403 on both the authorize and lift decisions with separate `DENIED` audit rows, can still read the release and its history, and cannot enumerate the patient-scoped revision list (403). |
| `anotherTenantCannotReadTheRelease` | A real seeded ORG_B clinician reading the ORG_A release id gets 404 `NOT_FOUND` with no identifier leaked, and the ORG_B visible list omits the revision — forced RLS concealment, not a malformed-tenant 422. |
| `staleIfMatchOnLiftHoldIsRejected` | `If-Match: "9"` on a HELD release returns 412 `STALE_VERSION` with a `STALE` audit row; the release stays HELD and hidden and no lift event is written. |
| `liftHoldOnAuthorizedReleaseIsRefused` | Lifting an already AUTHORIZED release returns 409 `INVALID_TRANSITION` with a `CONFLICT` audit row; state stays AUTHORIZED and no lift notification or event is written. |

**Production classes exercised.** [ReleaseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java) (`authorize`, `liftHold`, `visibleRevisions`, `resolveEffectiveFrom`, `transition`, `authorize` policy check), [ReleaseState.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseState.java) (`visibleAt`, `canTransitionTo`), [ReleaseKind.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseKind.java), [PatientRelease.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientRelease.java) (`visibleNow`), [ReleaseActionPolicy.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseActionPolicy.java) (DECIDERS, READERS and patient-scoped readers), [AuthorizeRelease.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/AuthorizeRelease.java), [PatientReleaseController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleaseController.java), [PatientReleasedRevisionsController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleasedRevisionsController.java) and [ReleaseRepository.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseRepository.java) (RLS-bounded reads, append-only event/notification ledgers, OPS-02 audit rows). Persistence is created by [V24__patient_release.sql](../../../../db/migrations/V24__patient_release.sql) and [V84__report_release_store.sql](../../../../db/migrations/V84__report_release_store.sql).

**Companion proof already in the repository.** [ReleaseServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleaseServiceTest.java) covers the deterministic validation and compare-and-set branches, [ReleasePolicyTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleasePolicyTest.java) the policy matrix, and [PatientReleaseIsolationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/PatientReleaseIsolationTest.java) the cross-tenant and correction/withdrawal flows. The new class adds the hold-reason/expiry error codes, the audited lift success, the lift stale-version path and the lift forbidden-state path at the HTTP boundary.

Command and result: `AUREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=SupportApprovedImmediateReleaseTimedReleasePermittedTest -Dgroups=integration test` → `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

## Gap

Behaviour gap: **None** — all three release kinds, the reason/expiry/review rules and the audit trail are implemented and proven by the seven tests above.

Contract documentation gap (pre-existing, outside this story's scope): the published [generated/openapi.yaml](../../../../contracts/openapi/generated/openapi.yaml) still shows `POST /api/v1/releases/{id}/authorize`, which the controller does not expose, and omits the real `POST /api/v1/releases` and `POST /api/v1/releases/{id}/lift-hold`. [missing-routes.txt](../../../../contracts/openapi.yaml) lists those live routes as missing from the contract. Closing it means correcting the contract entries and regenerating the client in `contracts/` under a separate task; this story does not edit `contracts/`. It does not affect the proven runtime behavior.
