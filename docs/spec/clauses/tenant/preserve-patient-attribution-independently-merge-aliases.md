# ARC-01 clause 7 — API test evidence

## Clause

Preserve patient attribution independently of merge aliases.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.tenant.clauses.PreservePatientAttributionIndependentlyMergeAliasesTest`
(path: `apps/api/src/test/java/com/aurevia/tenant/clauses/PreservePatientAttributionIndependentlyMergeAliasesTest.java`).
All four tests are active (none disabled). They drive the real HTTP entry points with
synthetic fixtures on real PostgreSQL with forced row-level security, and verify the
persisted columns with `app.current_org` set in the same session as each query.

- `consolidationLeavesChartEntryAttributedToOriginalPatient` — positive proof. A chart
  entry is captured on the duplicate before the second reviewer approves. Afterwards
  `chart_entry.patient_id` is still the duplicate, no entry points at the survivor, the
  survivor's own chart is untouched, `effective_patient_id(duplicate)` resolves only the
  visibility to the survivor, both `patient_record` rows survive, and the append-only
  `chart_association` snapshots the duplicate's MRN and issuer (the two records use
  distinct issuers, `CLINIC-A` and `CLINIC-B`, so a snapshot of the wrong issuer would fail).
  The chart read path still
  lists the entry under the duplicate while the review read path shows the folded
  `chartVisibilityId`.
- `receptionRoleCannotConsolidateAndReattribute` — negative proof of the authorization
  boundary. A `reception` subject with active membership is refused with HTTP 403
  `FORBIDDEN`; no association or consolidation is appended and the entry's attribution
  and visibility remain on the duplicate.
- `otherTenantCannotReadOrReattributeAlias` — negative proof of tenant isolation. Callers
  authorized in the peer organization (a `privacy_auditor` for review/consolidate and a
  `clinician` for the chart entry) receive HTTP 404 `NOT_FOUND` on the review, the
  consolidate command and the single-entry read; forced RLS hides every ORG_A row from
  ORG_B, and the ORG_A row and its attribution are unchanged.
- `staleVersionCannotReattributeAndWritesNoAssociation` — negative proof of the version
  precondition. A stale `If-Match` is refused with HTTP 412 `STALE_VERSION`; nothing is
  appended and attribution is unchanged.

Production classes exercised through the normal HTTP entry points
`POST /api/v1/chart/records/entries`, `GET /api/v1/chart/records/entries/{id}`,
`GET /api/v1/chart/records/patients/{patientId}/entries`,
`POST /api/v1/duplicate-reviews`, `GET /api/v1/duplicate-reviews/{survivor}/{duplicate}`
and `POST /api/v1/duplicate-reviews/{reviewId}/consolidate`:

- `com.aurevia.patient.consolidation.ConsolidationController`,
  `com.aurevia.patient.consolidation.ConsolidationService` (`review` and
  `consolidate`) and `com.aurevia.patient.consolidation.DuplicatePolicy` —
  append the association under a pair lock and refuse a wrong role, a peer tenant's
  concealed review and a stale version.
- `com.aurevia.patient.consolidation.DuplicateRepository` — the SQL boundary
  whose `effectivePatientId` fold affects visibility only.
- `com.aurevia.chart.record.ChartController`, `ChartService` and
  `ChartRepository` — write and read the raw `chart_entry.patient_id` without consulting
  the merge alias.
- Migrations `db/migrations/V7__patient_registration.sql` (immutable `patient_record`),
  `db/migrations/V9__duplicate_review_consolidation.sql` (forced-RLS, append-only
  `chart_association`, preserved identity snapshot and the read-only `effective_patient_id`
  fold) and `db/migrations/V13__longitudinal_chart.sql` (forced-RLS `chart_entry` with its
  patient foreign key).

Source survey (unchanged by this task): no alias- or attribution-specific route exists in
`contracts/openapi.yaml`; the rule is implicit in the persisted columns and the
visibility-only fold. The implementation lives in the
`com.aurevia.patient.consolidation` and `com.aurevia.chart.record`
packages and the three migrations above.

Verified locally:
`AUREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=PreservePatientAttributionIndependentlyMergeAliasesTest test`
— 4 tests, 0 failures, 0 errors, 0 skipped.

## Gap

None.

## Out-of-clause hardening observation

This is not a gap in clause 7 as written: the clause is about merge aliases, and the
tests above prove that an alias folds visibility without changing attribution. It records
a separate hardening opportunity found during the survey, with the complete would-be test
kept here (rather than as a Java `@Disabled` test, which `scripts/check-constraints.py`
rejects for any newly added line).

`chart_entry.patient_id` (`db/migrations/V13__longitudinal_chart.sql:18`, foreign key at
lines 52-53, forced RLS at 56-61, `UPDATE` grant at 67) has no reject-mutation trigger.
A direct SQL rewrite by an application-role caller would therefore succeed and persist a
re-attribution that bypasses the read-time fold. Closing it needs a new Flyway migration
with a `BEFORE UPDATE` trigger enumerating `patient_id` with `IS DISTINCT FROM` and
covering every role including the migration owner, a service guard in `ChartService` and
`ChartRepository` so no command can re-point the column, and enabling the test below —
one separate task. No production file was changed by this task.

```java
@Test
@Disabled("GAP: chart_entry.patient_id has no reject-mutation trigger; a direct "
        + "SQL rewrite to the survivor succeeds and persists re-attribution.")
void clinicalRecordPatientIdCannotBeRewrittenToSurvivor() throws Exception {
    String[] pair = twoDuplicates("ATTR-G", "reception-a");
    String entryId = recordAllergy(pair[1], "synthetic rewrite attempt", "clinician-a", ORG_A);
    String reviewId = openReview(pair[0], pair[1]);
    assertEquals(HttpStatus.OK, request(HttpMethod.POST,
            "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
            approval("ATTR-G-1", "CLINIC-A", "ATTR-G-2", "CLINIC-B"),
            "approver-a", ORG_A, "\"1\"").getStatusCode());

    // When enabling this test, assert the reject-mutation trigger's SQLState/message
    // rather than any failure: an unrelated connection or RLS error must not pass.
    assertThrows(Exception.class, () -> {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("UPDATE public.chart_entry SET patient_id = CAST('" + pair[0]
                    + "' AS uuid) WHERE id = CAST('" + entryId + "' AS uuid)");
        }
    });
    assertEquals(pair[1], scalar("SELECT patient_id::text FROM public.chart_entry "
            + "WHERE id = CAST('" + entryId + "' AS uuid)"));
}
```
