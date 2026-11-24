# Section 2 role matrix — Lab collector / verifier

## Clause

Role Lab collector / verifier: allowed workflow: Accession and collect / verify, release and correct results; scope constraint: Assigned requisitions. Site privileges determine separation of duties..

## Status

Partly proven

## Evidence

The proof is `apps/api/src/test/java/com/aurevia/smartclinic/authz/clauses/RoleLabCollectorVerifierTest.java`. It uses synthetic UUIDs, a fixed clock, a `MembershipResolver` fixture, mocked `LabAccessionRepository` / `PatientRepository` / `AuditAppender` / `TransactionTemplate` and `TenantContext.set`/`clear`. No production code, migration, contract, route or existing test was changed.

Enabled tests and the branch each asserts:

| Test | Verb / rule | Observable assertion |
|---|---|---|
| `collectorCanAccessionAndCollect` | accession, collect allowed | command gate returns `accessionRequisition` → `LAB_ACCESSION_CREATE` and `collectSpecimen` → `LAB_SPECIMEN_COLLECT`, both `ALLOWED` with `lab_collector` as `rolesUsed` |
| `verifierCanVerifyReleaseAndCorrect` | verify / release / correct allowed | `verifyLabResult` → `SPECIALTY_LAB_VERIFY_RELEASE` is `ALLOWED` for `lab_verifier`; the request's claimed `tenant_admin` role is ignored, and the decision carries the server org, subject and fixed-clock instant |
| `verifierCanIssueAndCorrectAReportThroughTheResultService` | verify and correct at the owning service | `ResultEntryService` issues FINAL then a linked CORRECTED revision (v2 → v3, `supersedesVersion` 2, verifier subject) with SUCCESS audits |
| `collectorCannotVerifyOrRelease` | wrong role refused | command-gate denial 403 `FORBIDDEN` with sink `COMMAND_FORBIDDEN`; service `verify` and `correct` denials 403 "missing laboratory permission" with DENIED audits |
| `verifierCannotAccessionOrCollect` | wrong role refused | gate denials for both accession and collect commands; `SpecimenCollectionService.collect` denial 403 "missing laboratory permission" |
| `receptionClinicianTenantAdminVerbsRefusedForLabRoles` | three other-role verbs refused | `registerPatient` (`PATIENT_REGISTER`), `bookAppointment` (`APPOINTMENT_BOOK`) and `inviteStaff` (`STAFF_INVITE`) are 403 `FORBIDDEN` for *both* lab roles |
| `labActionDeniedWithoutActiveMembership` | missing membership refused | gate 403 `FORBIDDEN` with `MEMBERSHIP_INACTIVE`; service 403 "no active membership" with DENIED audit |
| `requisitionAssignmentDoesNotNarrowAccessToday` | scope gap characterized | a collector collects a requisition at `SITE-FOREIGN` and gets COLLECTED + SUCCESS audit; this green characterization records the gap, it is not desired behaviour |

Production classes exercised (all found, none changed):

- `authz.specialty.SpecialtyRoles` (`LAB_COLLECTOR`, `LAB_VERIFIER`) and `authz.specialty.SpecialtyActions` (`LAB_VERIFY_RELEASE`, `allows`);
- `lab.accession.LabActionPolicy` (`ACCESSION_CREATE`, `SPECIMEN_COLLECT`, `allows`) and `lab.result.LabRules` (`COLLECTOR`, `VERIFIER`, `mayCapture`, `mayVerify`);
- `labflow.collection.SpecimenCollectionService` (`collect`, `authorize`) and `labflow.results.ResultEntryService` (`enter`, `verify`, `correct`, `authorize`);
- `api.authorization.CommandCatalog` (`synthetic`, commands `accessionRequisition`, `collectSpecimen`, `verifyLabResult` at lines 161-166), `CommandAuthorization` (`synthetic`, `authorize`) and `InMemoryDecisionSink`;
- `exchange.release.ReleaseActionPolicy`: its commands `authorizeRelease` / `correctRelease` / `withdrawRelease` are DECIDER-only (`clinician`, `tenant_admin`), so final-report issuance is `ResultEntryService.verify` / `correct` while generic XCH-06 patient-release decisions are **not** lab-verifier verbs (`labflow.release.LabReportReleaseService` has no role check at all). This is why "verify, release and correct results" is only partly covered by a lab role today.
- `authz.ActionPolicy`: `PATIENT_REGISTER` and `APPOINTMENT_BOOK` are reception verbs, `STAFF_INVITE` is a tenant-admin verb; none is granted to a lab role.

`lab.accession.LabAccessionService` was surveyed but is not exercised here: it enforces the same
accession/collect duties through `LabActionPolicy`. Cross-tenant concealment by real forced RLS is out
of scope for this clause proof — the repositories are mocked; the server-derived organization and
subject are asserted on the decision ledger, and a dedicated tenant-isolation story owns that boundary.

The section-2 verbs map to two distinct owners and they do not fully agree: the command gate's `SPECIALTY_LAB_VERIFY_RELEASE` covers lab-result verify/release/correct for the verifier, while the XCH-06 release commands belong to clinician/tenant-admin deciders. The separation actually enforced for lab work is structural (collector vs verifier, and a different authenticated subject for verification), not site-configurable.

## Gap

Two parts of the clause are absent from the code today:

1. **Assigned requisitions is not persisted or enforced for laboratory work.** `V26__laboratory_accession.sql` creates `lab_accession` with a `site_code` collection attribute but no assignee, facility or requisition-assignment column. A generic facility-scope primitive already exists in `V93__membership_facility_scope.sql` (`is_facility_out_of_scope(subject, facility_code)`), but no lab or labflow class calls it. Any active collector can collect any accessioned specimen in the tenant, and any active verifier can verify any preliminary report; neither `SpecimenCollectionService.authorize` nor `ResultEntryService.authorize` consults an assignment or facility scope. `siteCode` in the test is only a **proxy** for an assignment because no assignment field or table exists to address a requisition directly; the expected outcome for a later enablement is one concrete result, **403 `FORBIDDEN` with an audited `DENIED`**.
2. **"Site privileges determine separation of duties" is not built.** `LabActionPolicy` and `LabRules` hard-code the collector/verifier split (and `ResultEntryService` hard-codes enterer ≠ verifier). `LabActionPolicy` and `LabRules` record ADM-01 / G5 as the reason site-configurable privileges are deferred; there is no configuration entry, migration or service that varies separation by site.

The complete would-prove test is frozen here for a later enablement task. A later worker moves it into `RoleLabCollectorVerifierTest`, deletes the green `requisitionAssignmentDoesNotNarrowAccessToday` characterization and removes the skip marker. It compiles against today's `SpecimenCollectionService.collect` signature and fails today because the call succeeds.

```java
    @Test
    @org.junit.jupiter.api.Disabled("GAP: no requisition-assignment field or table and no lab "
            + "facility-scope check; the collector is allowed today")
    void requisitionAssignmentDoesNotNarrowAccess() {
        authorized(LabRules.COLLECTOR);
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.ACCESSIONED, 1, FOREIGN_SITE)));
        when(accessions.update(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any(),
                anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        ApiException denial = denial(() -> collection.collect(SPECIMEN, collectRequest(SITE)));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(audit).append(any(), eq(SpecimenCollectionService.PURPOSE),
                eq(LabActionPolicy.SPECIMEN_COLLECT), eq("SPECIMEN"), eq("LAB"), eq(SPECIMEN), eq("DENIED"));
    }
```

This skip marker lives in the Markdown note, not in the Java file: AGENTS.md's coverage rules and `scripts/check-constraints.py` reject any added `@Disabled` line in a `.java` file, so a Java skip would fail `scripts/quality-gate.sh` and block the merge. The doc is an exempt path for the rule.

Files a build task would change, in order (roughly three separate tasks):

1. `db/migrations/` — a new tenant-owned, forced-RLS assignment relation (for example membership/facility → requisition) plus the policy function and backfill. Enable the `is_facility_out_of_scope` integration for lab resources.
2. `apps/api/src/main/java/.../lab/accession/LabAccessionRepository.java` and its `Jdbc*` implementation, `labflow/collection/SpecimenCollectionService.java`, `labflow/results/ResultEntryService.java`, `labflow/release/LabReportReleaseService.java` — persist the assignment, expose it through the RLS-bounded store and consult it in each `authorize`/release path; add the ADM-01 site-privilege configuration model.
3. `apps/api/src/test/java/.../authz/clauses/RoleLabCollectorVerifierTest.java` — delete the characterization, enable the frozen test above with its imports, and add negative cases for another facility, another encounter, an unassigned item and a field outside the role's fields. A contract/route change would also regenerate the OpenAPI client, but no new route is needed for the scope check itself.

Estimate: three implementation tasks (persistence, enforcement/configuration, test enablement). This task is test-only by scope and writes no production code, migration, contract or existing test.
