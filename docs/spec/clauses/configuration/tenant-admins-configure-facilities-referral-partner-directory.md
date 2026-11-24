# ADM-01 clause 1 — tenant admins configure facilities, referral partner directory, scheduling policies, templates, catalogs, coverage rosters, critical-result escalation rules and patient release policies

## Clause

Tenant admins configure facilities, referral partner directory, scheduling policies, templates, catalogs, coverage rosters, critical-result escalation rules and patient release policies.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.configuration.clauses.TenantAdminsConfigureFacilitiesReferralPartnerDirectoryTest`
(`apps/api/src/test/java/com/aurevia/configuration/clauses/TenantAdminsConfigureFacilitiesReferralPartnerDirectoryTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile, a dedicated throwaway database `orevia_admin_configuration_it`, Flyway
`filesystem:../../db/migrations`, two synthetic tenants and three synthetic subjects
(`admin-a`, `admin-b`, `reception-a`). All names and values are synthetic.

Test methods (two positive rule proofs and five negative refusals):

- `tenantAdminDefinesFacilityReferralPartnerAndSchedulingPolicy` — an administrator defines
  a facility, defines a referral partner directory entry in the `referral_partner` catalog
  family, approves that entry with a note so it becomes effective, and defines a scheduling
  visit type with a duration. Each HTTP response is asserted for its status and its
  tenant-local code or value. This proves the built configure surfaces named by the clause.
- `tenantAdminApprovesConfiguredPolicySoItBecomesEffective` — an administrator appends a
  draft for a configured escalation policy, approves version 1 with a named note, and reads
  the effective version back. The approved projection carries the approver derived from the
  actor (not from the request body), the approval instant, the note and version 1, and the
  effective read returns the same value and status. This proves that a configured policy
  only takes effect through a named approval.
- `configurationCreationIsRefusedForWrongRole` — a `reception` actor cannot append a
  configuration draft (403), and a forced-RLS database read confirms no draft row was left
  behind.
- `facilityCreationIsRefusedForWrongRole` — the same non-admin actor cannot define a
  facility (403), so "tenant admins" is an enforced boundary on the facility surface too.
- `configurationIsConcealedFromAnotherTenant` — a policy configured and approved in tenant A
  is invisible to an administrator of tenant B (404) under forced row-level security.
- `approvalOfMissingVersionIsRefused` — approving a version that was never appended is
  refused (404) instead of creating an approval.
- `blankValueIsRefused` — an opaque value containing only whitespace is refused (422).

HTTP entry point to production class to test method trace:

- `POST /api/v1/facilities` → `com.aurevia.web.FacilityController.create` →
  `com.aurevia.tenancy.FacilityService.create` —
  `tenantAdminDefinesFacilityReferralPartnerAndSchedulingPolicy`,
  `facilityCreationIsRefusedForWrongRole`.
- `POST /api/v1/catalogs/{family}/entries`,
  `POST /api/v1/catalogs/{family}/entries/{code}/versions/{version}/approve`,
  `GET /api/v1/catalogs/{family}/entries/{code}` → `web.CatalogController` →
  `catalog.CatalogService` → `configuration.ConfigurationService` —
  `tenantAdminDefinesFacilityReferralPartnerAndSchedulingPolicy`.
- `POST /api/v1/scheduling/visit-types` → `web.SchedulingAdminController.createVisitType` →
  `scheduling.SchedulingAdminService.createVisitType` —
  `tenantAdminDefinesFacilityReferralPartnerAndSchedulingPolicy`.
- `POST /api/v1/configuration`, `POST /api/v1/configuration/{key}/versions/{version}/approve`,
  `GET /api/v1/configuration/{key}` → `web.ConfigurationController` →
  `configuration.ConfigurationService` → `configuration.ConfigurationRepository` —
  `tenantAdminApprovesConfiguredPolicySoItBecomesEffective`,
  `configurationCreationIsRefusedForWrongRole`,
  `configurationIsConcealedFromAnotherTenant`, `approvalOfMissingVersionIsRefused`,
  `blankValueIsRefused`.

Production classes and schema exercised through those entry points:
`web.FacilityController`, `web.CatalogController`, `web.SchedulingAdminController`,
`web.ConfigurationController`; the domain services
`tenancy.FacilityService`, `catalog.CatalogService`, `scheduling.SchedulingAdminService`,
`configuration.ConfigurationService` and the SQL boundary
`configuration.ConfigurationRepository`; the value types `configuration.ConfigEntry` and
`catalog.CatalogEntry`; the authorization and tenant boundary `authz.ActionPolicy`,
`tenant.TenantContext` and the synthetic identity headers; the failure contract
`api.ApiException` (403/404/409/422); and the versioned, forced-RLS schema from
`db/migrations/V5__tenant_configuration.sql` and `db/migrations/V6__catalog_fold.sql`.
The configuration routes used here are published in `contracts/openapi.yaml` at lines 69,
93 and 109; line 135 is the history route, exercised by the sibling C02 evidence rather than
by this class. The facility and catalog routes are wired and served, while
`POST /api/v1/scheduling/visit-types` is currently recorded in
`contracts/openapi.yaml` rather than the primary contract. Promoting that route
is outside this test-only evidence, so no contract or client change is needed here.

The second ADM-01 sentence — named approval, effective dates, audit and version history —
is proven by `com.aurevia.it.ConfigurationIsolationTest` methods
`approveMakesConfigurationEffective`, `configurationActionsAreAudited` and
`entriesAndApprovalsAreAppendOnly`. It will be proven for the clinical/market policy framing
by the sibling clause test `ClinicalMarketPoliciesRequireNamedApprovalEffectiveTest`, which is
planned in a separate task and does not exist yet. This class deliberately does not repeat
those deep audit and history assertions.

## Gap

The configure mechanism is generic: a tenant admin can store any bounded key through
`POST /api/v1/configuration`, and consumers such as
`exchange.critical.CriticalResultService` read approved `critical.escalation.*` values from
the effective fold, while `config.rosters.CoverageRosterFact` and the release-policy types
in `exchange.release` and `labflow.release` consume configured facts. What is missing is a
dedicated administrative surface and workflow for each of these clause subjects, so that
the named approval, effective window, audit and version history are tied to the specific
policy object rather than to an opaque key:

1. **Templates.** `clinical.templates.TemplateService` /
   `clinical.templates.JdbcTemplateStore` and `BaselineTemplates` exist, but there is no
   admin controller that lists, defines, approves and versions template objects.
2. **Coverage rosters.** `config.rosters.CoverageRosterFact` and `MissingCoverageQueue` only
   derive ADM-02 queue items; there is no roster-definition controller.
3. **Critical-result escalation rules.** `exchange.critical.CriticalResultService` reads
   `critical.escalation.*` config keys, but there is no rule object carrying owner,
   deadline, primary/backup channels and recipients, read-back and acknowledgement
   requirements and an effective window, nor a named clinical approval for that rule.
4. **Patient release policies.** `exchange.release.ReleaseKind` and
   `labflow.release.FacilityReleasePolicy` consume a policy version; there is no admin
   surface that defines and approves those versions.
5. **Pilot site onboarding.** No workflow exercises every configured channel for a site.
   The pending site-approval document task does not create any of these surfaces.

Files that would change for a dedicated surface (backend/configuration): a new domain
package and service under `apps/api/src/main/java/com/aurevia/configuration/`
(or the relevant domain package) for each policy object, a thin controller under
`web/`, a `Jdbc*` store behind a `*Store` interface, a Flyway migration under
`db/migrations/` with `ENABLE` and `FORCE ROW LEVEL SECURITY`, tenant policies and an
immutability trigger, the route entries in `contracts/openapi.yaml` with a tag,
`Idempotency-Key` on POST/DELETE and pagination on list GETs, regenerated clients, and the
matching HTTP-level clause test. **Estimated scope: five separate cross-boundary tasks**
(one per missing subject above), plus one staff-web task to expose them to tenant admins
and one clinical-safety review before pilot use.

Complete would-prove test for the most safety-critical missing surface:
`criticalResultEscalationRuleRequiresNamedClinicalApproval` would POST a rule with a named
owner, deadline, approved primary and backup channels and recipients, read-back and human
acknowledgment requirements and an effective window; assert the draft is not effective;
approve it with a named clinical approver and assert `approvedBy`, `approvedAt`, the
effective window and the append-only history; assert a future-dated rule does not apply
early and that the rule is concealed from another tenant under forced RLS; and assert a
reception actor cannot define or approve it. Enable trigger: the rule object, its migration
with forced RLS, the admin controllers and the OpenAPI entries all exist and are green.
Per the repository's quality gate this test is described here rather than added to the
suite as a skipped test, so the pull request stays fully green; a later implementation task
adds and enables it.
