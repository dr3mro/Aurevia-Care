# ADM-01 clause 2 — clinical and market policies require named approval, effective dates, audit and version history

## Clause

Clinical/market policies require named approval, effective dates, audit and version history.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.configuration.clauses.ClinicalMarketPoliciesRequireNamedApprovalEffectiveTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/configuration/clauses/ClinicalMarketPoliciesRequireNamedApprovalEffectiveTest.java`).
It is an active real-PostgreSQL acceptance test tagged `integration`, using the
synthetic-auth profile, a dedicated `smartclinic_adm01_clause2_it` database and two
synthetic tenants. All six tests are active (none disabled) and use synthetic data only.
It follows the fixtures and database setup of
`com.aurevia.smartclinic.it.ConfigurationIsolationTest` and
`com.aurevia.smartclinic.authz.clauses.RecordGrantedRoleEffectiveDatesTest`.

Test methods (one positive rule proof, five negative refusals):

- `namedApprovalEffectiveDatesAuditAndVersionHistoryAreEnforced` — positive proof. A
  draft with an explicit past `effectiveFrom` is appended (201 `pending`) and is still
  invisible to a GET (404) until a separate approval exists. A second approval request
  then carries a forged `approvedBy` in its body; the approve response is 200 `approved`
  with `approvedBy` equal to the acting admin's user id and the note preserved, and the
  durable `config_approval.approved_by` equals the same user id, so the named approver
  is derived from the actor both in the projection and at rest. A GET then serves the
  value from the effective window. Direct SQL finds exactly `CONFIG_CREATE` and
  `CONFIG_APPROVE` audit events for the two correlation ids, attributed to the acting
  admin with purpose `CONFIG_ADMIN`, scope `CONFIG` and outcome `SUCCESS`. History
  reports one entry at version 1, and `UPDATE`/`DELETE` of `config_entry` and
  `config_approval` are refused with SQLSTATE `44000` by the append-only triggers.
- `approvalByWrongRoleIsForbidden` — a `reception` caller cannot approve a draft (403).
- `futureDatedApprovalIsNotEffectiveEarly` — an approved version whose window opens
  tomorrow is not served yet (404).
- `crossTenantConfigurationIsConcealed` — another tenant's admin cannot read or see
  the history of an approved key (404) under forced row-level security.
- `malformedEffectiveFromIsRefused` — an `effectiveFrom` that is not a UTC instant is
  refused (422).
- `duplicateApprovalIsRejected` — a second approval of the same version conflicts (409).

Production classes and objects exercised through the normal HTTP entry point:

- Controller: `com.aurevia.smartclinic.web.ConfigurationController` —
  `POST /api/v1/configuration`, `POST /api/v1/configuration/{key}/versions/{version}/approve`,
  `GET /api/v1/configuration/{key}` and `GET /api/v1/configuration/{key}/history`.
- Domain and boundary: `com.aurevia.smartclinic.configuration.ConfigurationService`,
  `com.aurevia.smartclinic.configuration.ConfigurationRepository`,
  `com.aurevia.smartclinic.configuration.ConfigEntry`,
  `com.aurevia.smartclinic.configuration.ConfigHistory`,
  `com.aurevia.smartclinic.authz.ActionPolicy` and
  `com.aurevia.smartclinic.tenant.TenantContext`.
- Schema: `db/migrations/V5__tenant_configuration.sql` defines `config_entry` and
  `config_approval`, forces row-level security, derives author and approver from the
  authenticated actor, allocates versions under a tenant-and-key advisory lock, folds
  the single effective version from approvals and effective dates, and makes both
  tables append-only. `db/migrations/V3__audit_chain.sql` supplies the append-only
  audit chain that attributes each command to the acting user.

Where the clause is implemented today (source survey result): the four facets are
carried by the generic versioned-configuration mechanism above rather than by a
separate clinical-policy object. Named approval is the `config_approval` row whose
approver is forced from the request actor; effective dates are `effective_from` plus
the `effective_config` fold; audit is the correlated `audit_event` rows; version
history is the immutable `config_entry`/`config_approval` pair read by the history
route. This is the mechanism the clause requires, so no production gap was found.

## Gap

None.
