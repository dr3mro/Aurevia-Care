# RX-01 clause 4 — check against verified allergy ingredients

## Clause

Check against verified allergy ingredients and surface unknowns and mismatches.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.prescription.clauses.CheckAgainstVerifiedAllergyIngredientsSurfaceUnknownsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/CheckAgainstVerifiedAllergyIngredientsSurfaceUnknownsTest.java`),
a real-PostgreSQL HTTP integration test under the `synthetic-auth` profile. It drives
`POST /api/v1/prescription-drafts/{id}/allergy-check` and the conflict-guarded
`POST /api/v1/prescription-drafts/{id}/select-medication` through the real HTTP
server, seeding two organizations with clinician and reception members and several
synthetic patients whose allergy statuses are verified-list, verified-none, absent
(mapped to unknown) and unverified. Only production Flyway migrations from
`db/migrations` are applied.

- `verifiedAllergyCheckSurfacesConflictsWarningsAndUnknownsOverHttp` (rule met) —
  asserts the response `outcome`, `allergyStatus`, `catalogVersion` and retained
  `findings` for a verified exact ingredient (`conflict`/`exact_match`), a shared
  approved cross-reactivity group (`warning`/`cross_reactivity`), absent and
  unverified assessments (`unresolved`/`incomplete_assessment`), an unmapped
  ingredient on a verified-none patient (`unresolved`/`unresolved_ingredient`),
  and both a verified clean assessment and an unrelated approved group
  (`no_conflict` with no findings).
- `exactVerifiedIngredientSurfacesConflictOverHttp` — a verified exact substance
  identity alone produces `conflict` with the matched substance and group retained.
- `sharedCrossReactivityGroupSurfacesWarningOverHttp` — a different substance in
  the same approved group produces `warning`, not `conflict`, and no exact-match
  finding.
- `unknownUnverifiedAndUnmappedDataSurfaceUnresolvedOverHttp` — absent, unverified
  and unmappable data are surfaced as `unresolved`; an unverified assertion is
  surfaced but is never a verified contraindication.
- `verifiedNoneWithoutFindingsIsNoConflict` — only a verified clean assessment
  falls through to `no_conflict`.
- `receptionRoleIsDeniedTheAllergyCheck` — a role without decision-support
  permission is refused with HTTP 403 and the `FORBIDDEN` code.
- `otherTenantCannotCheckAnotherOrganizationsDraft` — forced row-level security
  hides another organization's draft and returns HTTP 404 with `NOT_FOUND`.
- `missingSubjectReturnsUnauthorized` — a request without a synthetic subject is
  refused with HTTP 401 and `UNAUTHENTICATED`.
- `malformedOrganizationReturnsUnprocessable` — a malformed active organization
  is refused with HTTP 422 and `INVALID_TENANT_SELECTION`.
- `invalidCatalogEntryIdIsRejected` — a malformed or unknown catalog entry is
  refused with HTTP 422.
- `selectMedicationWithoutOverrideReasonIsRefusedOnConflict` — a verified exact
  match cannot be selected without a documented override (HTTP 409,
  `ALLERGY_OVERRIDE_REQUIRED`), and the draft and override ledger stay unchanged.

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.prescription.PrescriptionController` — the thin HTTP
  adapter for `POST /api/v1/prescription-drafts/{id}/allergy-check` and the
  medication-selection route.
- `com.aurevia.smartclinic.prescription.PrescriptionService` — tenant-guarded,
  version-checked commands; folds the allergy check, refuses an unreviewed
  conflict with `ALLERGY_OVERRIDE_REQUIRED`, and binds the override snapshot to
  the exact draft revision.
- `com.aurevia.smartclinic.prescription.AllergyChecker` — the pure decision rule:
  only verified exact ingredient matches conflict, only approved group matches
  warn, and absent, unknown, unverified or unmapped data is retained and surfaced
  as unresolved; free-text name similarity is never evidence.
- `com.aurevia.smartclinic.prescription.PrescriptionContract` — the allergy status,
  finding kind, check outcome, ingredient identity and check result types.
- `com.aurevia.smartclinic.prescription.PrescriptionActions` — the action matrix
  that reserves prescribing and grants decision support only to clinical and
  governance roles.
- `com.aurevia.smartclinic.prescription.PrescriptionRepository` — the RLS-bounded
  fold over the latest allergy status and all allergy statements; absence of a
  status row maps to the explicit `UNKNOWN` state.
- `db/migrations/V43__prescription_drafts.sql` — the catalog, allergy, draft and
  append-only override tables with forced row-level security and tenant policy.

## Gap

None. The clause behavior is implemented and proven by the tests above. One
contract-documentation gap is deliberately outside this test-only story: the
route `POST /api/v1/prescription-drafts/{}/allergy-check` is baselined at
`contracts/openapi/missing-routes.txt:291` and is absent from
`contracts/openapi.yaml`. Publishing it would need a separate contract task
touching the OpenAPI entry, its route tag, the `Idempotency-Key` header and the
regenerated client; this story changes no contract file.
