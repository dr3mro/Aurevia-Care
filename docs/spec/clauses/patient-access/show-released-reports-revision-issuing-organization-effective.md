# PAT-02 clause 1 — Test evidence

## Clause

Show released reports with revision, issuing organization, effective date and correction context.

## Status

Proven by tests

## Evidence

Test file: `apps/patient-mobile/src/spec/patient-access/show-released-reports-revision-issuing-organization-effective.test.ts`.
All three tests are active (none skipped) and use synthetic fixtures only.

- `showsRevisionIssuerEffectiveDateAndCorrectionContextOnReleasedReport` — positive proof. It renders one released report and asserts the `revision` (`Revision 2`), `issuer` (`Clinic A`), `effective-date` (`2026-06-10`) and `correction` (`Corrected revision 1 — corrected glucose value`) accessibility labels. This takes the correction-present display branch.
- `rejectsReleasedReportMissingIssuerOrEffectiveDate` — negative proof of missing input. A blank `issuingOrganization` and a non-ISO `effectiveDate` (`10-06-2026`) each fail with `MALFORMED_REPORT` in `parseReleasedReport`, reached through `ReleasedReportClient.listReleasedReports()`. The rule refuses a projection that cannot be displayed safely.
- `rejectsCorrectionContextThatIsOnlyHalfPresent` — negative proof of a forbidden state. A correction reason without `supersedesRevision`, and a superseded revision without a reason, each fail with `MALFORMED_REPORT` in `normalizeCorrection`. No partial clinical correction context can reach the screen.

Production symbols exercised through those entry points:

- `ReleasedReportsScreen` in `apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx` renders the four labels named above.
- `ReleasedReport` in `apps/patient-mobile/src/features/reports/types.ts` is the patient-visible projection carrying revision, issuing organization, effective date and the correction pair.
- `ReleasedReportClient.listReleasedReports`, `parseReleasedReport` and `normalizeCorrection` in `apps/patient-mobile/src/features/reports/reportClient.ts` validate every field at the client trust boundary before any value is displayed.
- `correctionLabel` in `apps/patient-mobile/src/features/reports/presentation.ts` formats the complete correction context and yields no banner when either part is absent.

The live read path is `ReleasedReportsPage` in `apps/patient-mobile/src/app/patientPages.tsx`, which passes loaded reports into `ReleasedReportsScreen`. The neighbouring suites `reports-screen.test.tsx`, `report-core.test.ts` and `ReleasedReportsScreen.tablet.test.tsx` already assert the same production behavior. This file adds a focused, clause-only proof and changes neither suite.

## Gap

None. The clause is built and proven by active tests. This task added proof only: it did not change production code, an existing test, the OpenAPI contract or a migration.
