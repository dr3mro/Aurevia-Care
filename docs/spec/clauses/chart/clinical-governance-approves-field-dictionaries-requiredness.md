# CLN-04 clause 6 — clinical governance approves field dictionaries and requiredness

## Clause

Clinical governance approves field dictionaries and requiredness.

## Status

Partly proven

## Evidence

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

The live approver is a distinct `tenant_admin`: `authz.ActionPolicy` grants the
`CONFIG_APPROVE` action only to that role. These tests prove that requiredness-bearing
dictionaries are versioned, approved by a named distinct reviewer and preserved — they
do **not** prove that a `clinical_governance` actor performed the approval.

Complete proof for the governance-role half, recorded here rather than added as a
skipped test: `scripts/check-constraints.py` rejects every newly added `@Disabled` and
`docs/WORKFLOW.md` makes that a hard quality gate, while this repository forbids a
worker from editing `scripts/`. The method below is complete and is intended to be
added to the test class by the follow-up task together with the enabling production
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

Surveyed but not exercised because it has no HTTP controller:
`com.aurevia.smartclinic.clinical.templates.TemplateService`, which owns the
`CLINICAL_GOVERNANCE` role constant, `defineDictionary`/`approveDictionary`/
`approveVersion` and the requiredness checks in `validate`. The migrations
`db/migrations/V80__template_store.sql` and `db/migrations/V87__baseline_templates.sql`
provide its durable template store and baseline templates. No route for it is published
in `contracts/openapi.yaml`.

## Gap

The approval mechanism over HTTP exists, but the governance actor named by the clause
does not. `authz.ActionPolicy` has no `clinical_governance` role and grants
`CONFIG_APPROVE` only to `tenant_admin`. The governance role, dictionary approval and
requiredness validation live in `clinical.templates.TemplateService`, which has no
controller, so that domain cannot be reached by an HTTP request today. Claiming
"Proven by tests" because `TemplateService` contains the role check would be false.

Files that would change:

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
gap itself needs no new migration. It needs **at least two separate tasks**: one
cross-boundary backend/contract task (ActionPolicy, the governance approval controller
under `web/`, OpenAPI and the regenerated client) and one staff-web task, plus an
advisory clinical-safety review before live use.

This evidence task added only a test class and this note. It changed no production code,
no existing test, no migration and no contract.
