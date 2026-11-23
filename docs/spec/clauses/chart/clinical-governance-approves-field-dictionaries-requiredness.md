# CLN-04 clause 6 — clinical governance approves field dictionaries and requiredness

## Clause

Clinical governance approves field dictionaries and requiredness.

## Status

Proven by tests

## Evidence

The clause is proven at the normal domain-service entry point by the
`clinical_governance` role approving a field dictionary and a template version
and freezing the field requiredness that notes are validated against. A second,
HTTP-level proof covers the separately implemented clinical-catalog dictionary
versioning/approval mechanism and its durable persistence.

### Domain-service proof (TemplateService)

Test class: `com.aurevia.smartclinic.chart.clauses.ClinicalGovernanceApprovesFieldDictionariesRequirednessDomainTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ClinicalGovernanceApprovesFieldDictionariesRequirednessDomainTest.java`).
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

### HTTP-level proof (clinical catalog dictionaries)

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database
`smartclinic_cln_governance_it` migrated from `db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.chart.clauses.ClinicalGovernanceApprovesFieldDictionariesRequirednessTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ClinicalGovernanceApprovesFieldDictionariesRequirednessTest.java`).
- `governanceApprovalMakesARequirednessDictionaryEffective` — positive proof. Through
  `POST /api/v1/clinical-catalog/dictionaries/{key}/versions`, `POST .../{n}/approve`,
  `GET .../effective` and `GET .../history`: a draft carrying `REQUIRED` and `OPTIONAL`
  fields stays HTTP 404 until a distinct named reviewer approves it. The approve response
  is asserted for both requiredness codes, the localized language tag and the named
  approval (note and recorded approver id); the effective response is asserted for both
  requiredness codes and the approval note; the history entry is asserted for the first
  field's requiredness and the approval note.
- `unapprovedDictionaryIsNotEffective` — negative proof, HTTP 404 before approval.
- `authorCannotApproveOwnDictionary` — negative proof, HTTP 422 self-approval and no
  published version.
- `anotherTenantCannotReadOrApproveTheDictionary` — negative proof, HTTP 404 for a peer
  tenant's read and approve under forced row level security.
- `emptyFieldsAreRefused` — negative proof, HTTP 422 and no effective version.

The live approver on this HTTP path is a distinct `tenant_admin`: `authz.ActionPolicy`
grants the `CONFIG_APPROVE` action only to that role. These tests prove that
requiredness-bearing dictionaries are versioned, approved by a named distinct reviewer
and preserved — they do **not** prove that a `clinical_governance` actor performed the
approval over HTTP.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.catalog.dictionary.ClinicalCatalogController` — thin adapter
  publishing the dictionary version, approve, effective and history routes.
- `com.aurevia.smartclinic.catalog.dictionary.ClinicalDictionaryService` and
  `DurableVersionStore` — dictionary version facade and durable configuration-backed
  persistence.
- `com.aurevia.smartclinic.configuration.ConfigurationService` — append-only drafts,
  named approvals, the effective fold and role authorization.
- `com.aurevia.smartclinic.authz.ActionPolicy` — the action/role matrix that currently
  limits approval to `tenant_admin`.
- Contract types `DictionaryField`, `Requiredness`, `LocalizedText`, `Approval` and
  `CatalogVersion`.

Source survey: the CLN-04 template workflow lives in
`apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates`, not in the `chart.record`
package (which implements CLN-03 entry capture). Field dictionaries and approval rules are
defined in `FieldDictionary`/`FieldDefinition`; no controller under
`apps/api/src/main/java/com/aurevia/smartclinic/web` and no path under `contracts/openapi.yaml`
currently publishes the template-approval workflow — it is reached through `TemplateService`
and covered by the existing `it/clinical/TemplateVersioningTest` and
`clinical/templates/TemplateNoteAcceptanceTest`. The clinical-note draft/sign workflow that
consumes a template version is `clinical/notes/ClinicalNoteService` (CLN-05). The migrations
`db/migrations/V80__template_store.sql` and `db/migrations/V87__baseline_templates.sql`
provide its durable template store and baseline templates.

## Gap

The clause rule itself is built and proven by active tests at the domain-service entry
point: there is no gap in whether `clinical_governance` may approve a dictionary and freeze
requiredness. (The HTTP-level evidence classifies the same clause as "Partly proven"
because the governance-role approval is not reachable over HTTP; that specific gap is
recorded below for a follow-up task.)

### HTTP governance-actor gap

The HTTP clinical-catalog approval mechanism exists, but the governance actor named by the
clause does not: `authz.ActionPolicy` has no `clinical_governance` role and grants
`CONFIG_APPROVE` only to `tenant_admin`. The governance role, dictionary approval and
requiredness validation live in `clinical.templates.TemplateService`, which has no
controller, so that domain cannot be reached by an HTTP request today. Claiming the HTTP
surface is "Proven by tests" because `TemplateService` contains the role check would be
false.

Complete proof for the governance-role half, recorded here rather than added as a
skipped test: `scripts/check-constraints.py` rejects every newly added `@Disabled` and
`docs/WORKFLOW.md` makes that a hard quality gate, while this repository forbids a
worker from editing `scripts/`. The method below is complete and is intended to be
added to the HTTP test class by the follow-up task together with the enabling production
change; it would fail today because `ActionPolicy` denies `clinical_governance`.
Its `governorUserId` is the `user_identity.id` seeded for the `governor-a` subject.

```java
@Test
void onlyClinicalGovernanceCanApproveFieldDictionariesAndRequiredness() {
    String governorKey = "governance_role_dictionary";
    String receptionKey = "governance_role_reception_dict";
    assertEquals(HttpStatus.OK, define(governorKey, "admin-a", ORG_A).getStatusCode());
    assertEquals(HttpStatus.OK, define(receptionKey, "admin-a", ORG_A).getStatusCode());

    ResponseEntity<String> governorApproval = approve(governorKey, 1, "governor-a", ORG_A);
    assertEquals(HttpStatus.OK, governorApproval.getStatusCode(), governorApproval.getBody());
    JsonNode governorVersion = tree(governorApproval);
    assertEquals(governorUserId, governorVersion.get("approval").get("approver").asString());
    assertEquals("REQUIRED", governorVersion.get("entries").get(0).get("requiredness").asString());
    assertEquals("OPTIONAL", governorVersion.get("entries").get(1).get("requiredness").asString());

    ResponseEntity<String> receptionApproval = approve(receptionKey, 1, "reception-a", ORG_A);
    assertEquals(HttpStatus.FORBIDDEN, receptionApproval.getStatusCode(), receptionApproval.getBody());
    assertEquals(HttpStatus.NOT_FOUND, effective(receptionKey, "admin-a", ORG_A).getStatusCode(),
            "a refused role must not publish a version");
}
```

Files that would change to close the HTTP gap:

1. `apps/api/src/main/java/com/aurevia/smartclinic/authz/ActionPolicy.java` — add a
   governance action and role so a `clinical_governance` member may approve field
   dictionaries and requiredness. `DurableVersionStore.approve` reads version history
   before approving, so the role also needs `CONFIG_HISTORY`, not just `CONFIG_APPROVE`.
2. A governance approval controller under
   `apps/api/src/main/java/com/aurevia/smartclinic/web/` (or an equivalent thin HTTP
   adapter over `clinical.templates.TemplateService`) that derives the actor from the
   tenant context, together with the service wiring it needs.
3. `contracts/openapi.yaml` — publish the governance approval and template-dictionary
   routes with their tags, then regenerate the staff-web OpenAPI client with
   `npm --prefix apps/staff-web run gen:api`.
4. The staff-web governance review form so an authorized user can approve dictionaries
   and their requiredness.

`db/migrations/V80__template_store.sql` and `db/migrations/V87__baseline_templates.sql`
already create the durable template store and baseline templates, so the governance-role
gap itself needs no new migration. Closing the HTTP gap needs **at least two separate
tasks**: one cross-boundary backend/contract task (ActionPolicy, the governance approval
controller under `web/`, OpenAPI and the regenerated client) and one staff-web task, plus an
advisory clinical-safety review before live use.

Both evidence tasks added only test classes and this note. They changed no production code,
no existing test, no migration and no contract.
