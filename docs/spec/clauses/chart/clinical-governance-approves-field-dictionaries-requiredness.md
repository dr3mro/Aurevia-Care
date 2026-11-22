# CLN-04 clause 6 — API test evidence

## Clause

Clinical governance approves field dictionaries and requiredness.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.ClinicalGovernanceApprovesFieldDictionariesRequirednessTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ClinicalGovernanceApprovesFieldDictionariesRequirednessTest.java`).
All five tests are active (none disabled) and pass with synthetic data only, using the same
`InMemoryTemplateStore` fixtures as the neighbouring template tests.

- `governanceApprovesDictionaryAndTemplateVersionFreezingRequiredness` — positive proof.
  A tenant admin defines a pending dictionary and a pending OB/GYN template version; the
  `clinical_governance` actor approves both. The approved dictionary records the approving
  subject, and the approved version records its approver and freezes the field `required`
  flags and CODE-field dictionary binding that a note is later validated against. The
  effective version returned to a clinician is that approved version.
- `tenantAdminCannotApproveDictionary` — negative proof (wrong role). A `tenant_admin` who
  may define a dictionary cannot approve it: HTTP 403 `FORBIDDEN`, reason
  `missing permission for TEMPLATE_APPROVE`, and the dictionary stays pending.
- `tenantAdminCannotApproveTemplateVersion` — negative proof (wrong role). A tenant admin
  cannot approve a template version: HTTP 403 `FORBIDDEN`, and the version stays pending.
- `versionCannotBeApprovedUntilItsDictionaryIsApproved` — negative proof (forbidden state).
  Governance cannot approve a version whose referenced CODE dictionary is still pending:
  HTTP 422 `DICTIONARY_NOT_APPROVED`, and the version stays pending.
- `governanceCannotApproveAnotherTenantsTemplate` — negative proof (another tenant). A
  governance actor from a different active organization receives HTTP 404 `NOT_FOUND` and
  cannot change the local version.

Production classes exercised through the normal domain-service entry point:

- `com.aurevia.smartclinic.clinical.templates.TemplateService` — `defineDictionary`,
  `approveDictionary`, `defineVersion`, `approveVersion` and `effective`. Only the
  `clinical_governance` role may perform `TEMPLATE_APPROVE`; approving a version first
  checks that every referenced dictionary is approved, then copies the field definitions
  (including `required`) into the immutable approved version, recording `approvedBy`.
- `com.aurevia.smartclinic.clinical.templates.FieldDictionary`, `TemplateVersion`,
  `FieldDefinition` and `TemplateActor` — immutable records that freeze entries, field
  meanings, requiredness and the caller's tenant/roles; the actor is a fresh snapshot
  supplied by the integrating caller (IAM-01), never taken from the command body.
- `com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` — synthetic,
  tenant-keyed persistence boundary used by the test; `JdbcTemplateStore` is the production
  store. Dictionary, version and audit facts are all scoped by the active organization.

Source survey (not changed by this task): the CLN-04 template workflow lives in
`apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates`, not in the `chart.record`
package (which implements CLN-03 entry capture). Field dictionaries and approval rules are
defined in `FieldDictionary`/`FieldDefinition`; no controller under
`apps/api/src/main/java/com/aurevia/smartclinic/web` and no path under `contracts/openapi.yaml`
currently publishes the template-approval workflow — it is reached through `TemplateService`
and covered by the existing `it/clinical/TemplateVersioningTest` and
`clinical/templates/TemplateNoteAcceptanceTest`. The clinical-note draft/sign workflow that
consumes a template version is `clinical/notes/ClinicalNoteService` (CLN-05).

## Gap

None.

The rule is built and proven by active tests at the domain-service entry point. This task
added proof only; it did not change production code, an existing test, the OpenAPI contract
or a migration. Publishing an HTTP/OpenAPI surface for template approval is outside this
clause's rule and would be a separate story.
