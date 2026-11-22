# IAM-03 clause 7 — shared phone or names and dates alone cannot link

## Clause

Shared phone or names and dates alone cannot link records or patient accounts.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.patient.clauses.SharedPhoneNamesDatesAloneCannotLinkTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/SharedPhoneNamesDatesAloneCannotLinkTest.java`),
six active tests with synthetic fixtures only (no `@Disabled`, no real patient data):

- `sharedPhoneNameAndDobKeepTwoIssuerScopedRecordsAndOnlyWarn` — two registrations
  carrying the same name, date of birth and phone number insert two distinct
  records under distinct issuer-scoped MRNs; reading one returns the other only
  in the `PatientView` duplicate-warning list, with a different patient id.
- `twoIssuerScopedIdentifiersOneFromEachRecordAllowConsolidationDespiteSharedDemographics`
  — the permitted link path: a second reviewer may consolidate two records with
  identical phone, name and date of birth only after confirming `MRN-1@CLINIC-A`
  and `MRN-2@CLINIC-B`, one issuer-scoped reference from each record; the
  association is appended and the confirmation outcome is audited.
- `sharedPhoneNameAndDobAreRefusedAsConsolidationIdentifierConfirmation` — negative
  proof for records. Supplying the shared phone, name and date of birth as
  `value@issuer` references is refused (422 `IDENTIFIER_MISMATCH`); no chart
  association or consolidation event is appended and the denial is audited.
- `oneMatchingIdentifierPlusSharedPhoneAndDobCannotLinkRecords` — negative proof
  that padding one genuine issuer-scoped identifier with the shared phone and date
  of birth is still refused (422 `IDENTIFIER_MISMATCH`); the second reference must
  come from the other record, and nothing is appended.
- `invitationBoundToAVerifiedAccountLinksOnlyItsOwnChart` — the permitted account
  path: a single-use invitation bound to the caller's verified account produces a
  `linked` event with method `INVITATION` for the invited chart.
- `matchingPhoneNameAndDobCannotLetAnotherAccountLinkOrAccessTheChart` — negative
  proof for accounts. A second verified account describing the same person by
  phone, name and date of birth is refused (403 `FORBIDDEN`, "invitation belongs
  to another account") when it tries to redeem the first account's token; the
  invitation is not consumed, no link is appended, and the denial is audited.

Production classes exercised through their normal server-side command entry points
(the same ones the controllers call), with deterministic mocked stores at the SQL
boundary. This is unit-boundary proof of the domain rule: it is not authorization
for a real-patient linkage and does not replace the database-level RLS/isolation
suites. The fixtures match the neighbouring `IdentityProofingUnitTest`,
`ConsolidationServiceTest` and `PatientServiceTest` fixtures:

- `com.aurevia.smartclinic.patient.PatientService` — `register`/`get` normalize
  names for search only, compute a likely-duplicate warning from normalized name
  and date of birth and never merge or link; `PatientView` carries the warning as
  a separate, immutable record.
- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService` —
  `consolidate` requires two distinct issuer-scoped identifiers present on the two
  locked records (or a documented approved process), a different authorized
  reviewer, a current version and no unresolved blockers; `DuplicatePolicy`
  bounds the reviewer roles.
- `com.aurevia.smartclinic.patient.identity.IdentityProofingService` —
  `linkWithInvitation` links only when the raw token resolves to an invitation
  bound to the authenticated verified account; `hasAccess` reads the append-only
  current-link fold; `IdentityActionPolicy` bounds the administrative actions.

Source survey (unchanged by this task): the relevant production packages are
`apps/api/src/main/java/com/aurevia/smartclinic/patient` and its `identity` and
`consolidation` neighbours; the web controllers are
`com.aurevia.smartclinic.web.PatientController`,
`com.aurevia.smartclinic.patient.identity.IdentityProofingController` and
`com.aurevia.smartclinic.patient.consolidation.ConsolidationController`; the
supporting tables (`patient_record`, `chart_association`, `consolidation`,
`patient_account`, `account_chart_link`, `patient_invitation`) come from Flyway
migrations V7, V9 and V10 under `db/migrations`; the generated REST surface is in
`contracts/openapi.yaml`. No additional phone/name/date-based linking path was
found.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=SharedPhoneNamesDatesAloneCannotLinkTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.

The rule is built in both of its senses — records and patient accounts — and this
task added proof only. It did not change production code, an existing test, the
OpenAPI contract or a migration. The mocked SQL boundaries are the same ones
covered by the database-level isolation and RLS suites under
`apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity`, which the
integrator runs; no authorization for real-patient linkage is implied here.
