# RAD-01 clause 3 — produce a signed interpretation with technique, comparison, findings, impression and follow-up

## Clause

Produce a signed interpretation with technique, comparison, findings, impression and follow-up.

## Status

Proven by tests

## Evidence

Test class
`com.aurevia.radiology.clauses.ProduceSignedInterpretationTechniqueComparisonFindingsImpressionTest`
(`apps/api/src/test/java/com/aurevia/radiology/clauses/ProduceSignedInterpretationTechniqueComparisonFindingsImpressionTest.java`),
a standalone-MockMvc HTTP test over the real controller and domain service with
synthetic subjects, a fixed clock and the `synthetic-auth` tenant context:

- `signedInterpretationCarriesAllFiveNarrativeSections` — a `radiology_interpreter`
  publishes through `POST /api/v1/radiology/reports` and the 201 body carries one
  revision with distinct values for all five sections (`technique`, `comparison`,
  `findings`, `impression`, `followUp`) plus `signer`, `attestation`, `signedAt`,
  `templateVersion` and `version = 1`.
- `staffOnlyActorCannotProduceASignedInterpretation` — a subject whose only active
  role is `radiology_staff` is refused with 403 `FORBIDDEN`; the interpreter's
  subsequent publish still binds the signature to the authenticated subject.
- `unsignedInterpretationIsRefusedBeforeAnyDeliveryFact` — a null signing time is
  refused with 422 `UNSIGNED_REPORT`; the referrer's event queue records
  `UNSIGNED_REPORT_REFUSED` but no `INTERPRETATION_DELIVERED` or
  `REFERRER_NOTIFIED`, and the report read returns 404.
- `aSecondSignedReportForTheSameOrderIsRefused` — replaying the publish is refused
  with 409 `DUPLICATE_REPORT`, so a signed revision cannot be overwritten.

Entry-point-to-test trace: `POST /api/v1/radiology/reports` →
`com.aurevia.radflow.report.ReportController.publish` →
`com.aurevia.radflow.report.ReportService.publish`, guarded by
`com.aurevia.radflow.report.ReportRules.mayDeliver` (delegating to
`com.aurevia.radiology.RadRules.mayInterpret`, satisfied only by
`radiology_interpreter`) and `ReportRules.isSigned`, producing an immutable
`com.aurevia.radiology.Interpretation` inside a
`com.aurevia.radflow.report.ReportDelivery`. The signer is derived from
`com.aurevia.tenant.TenantContext` and roles from a freshly resolved
`com.aurevia.staff.StaffRepository`; `PublishRequest` has no signer field.

Verified locally (tool evidence):
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ProduceSignedInterpretationTechniqueComparisonFindingsImpressionTest test`
→ Surefire `tests="4" errors="0" skipped="0" failures="0"`. "Proven by tests"
covers this HTTP delivery behaviour only. The HTTP delivery boundary *carries*
the five narrative sections; it does not check that each is non-blank. That
presence check lives in the separate `com.aurevia.radiology.ImagingService.sign`
kernel (`TECHNIQUE_REQUIRED`, `COMPARISON_REQUIRED`, `FINDINGS_REQUIRED`,
`IMPRESSION_REQUIRED`, `FOLLOW_UP_REQUIRED`), which is not on this HTTP path.

## Gap

Contract-documentation debt only; no clause behaviour is missing. The authoritative
`contracts/openapi.yaml` has no radiology paths, while
`contracts/openapi/generated/openapi.yaml:311` declares
`/api/v1/radiology/interpretations/{id}/sign` (also named in
`apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java:168`)
for which no controller route exists. The live route is `POST /api/v1/radiology/reports`,
so this proof does not claim anything for the phantom `/sign` path. Closing that
discrepancy would change `contracts/openapi.yaml`, the generated contract and
client plus the contract tests — one separate ARC-04/WEB-05 contract story. It is
out of scope here and no contract file was edited.
