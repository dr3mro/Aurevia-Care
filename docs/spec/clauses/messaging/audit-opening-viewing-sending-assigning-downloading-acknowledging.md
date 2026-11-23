# MSG-07 clause 1 — audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting and restricting

## Clause

Audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting and restricting conversations or files.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.AuditOpeningViewingSendingAssigningDownloadingAcknowledgingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/AuditOpeningViewingSendingAssigningDownloadingAcknowledgingTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, driving the real controller, service and
JDBC store with synthetic subjects in two tenants. Each method uses a distinct
synthetic resource identifier because the fixture database is shared:

- `recordEveryWorkflowActionAppearsInTheTenantAuditTrail` — all nine actions are
  recorded through `POST /api/v1/messaging-governance/actions` by roles the
  action matrix allows (conversation roles for open/view/send/download/acknowledge,
  `tenant_admin` for assign/restrict, `clinician` for file/correct). Each response
  carries its own action, `outcome=success`, subject, unit, purpose, correlation,
  tenant and resource. An unfiltered and a resource-filtered `GET
  /api/v1/messaging-governance/audit` return nine distinct success facts plus the
  `audit_read` fact appended by the read itself, and the same nine facts are
  asserted in the persisted V62 ledger.
- `wrongRoleCannotRecordFileOrRestrict` — a `patient` cannot record `file` or
  `restrict` (403, `FORBIDDEN`); each refusal appends its own `outcome=denied`
  fact for the attempted action, visible both through the audit endpoint and in
  the V62 table.
- `crossTenantCannotReadAnotherTenantsAuditFacts` — after ORG_A records an open
  fact, ORG_B's `privacy_auditor` can read its own trail (including the fact that
  the read happened) but sees none of ORG_A's facts, and no ORG_B row exists for
  ORG_A's resource even when filtered by it.
- `governanceCommandCannotBeSelfReportedAsWorkflowAction` — `hold_place` sent to
  the workflow recorder is refused (422, `INVALID_ACTION`) and appends no fact,
  so a governance command cannot fabricate a success through this endpoint.
- `invalidResourceIdIsRefused` — a malformed resource identifier is refused
  (422, `INVALID_RESOURCEID`) and the tenant's audit count is unchanged.

Production classes exercised through the two routes above:
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController`
(thin HTTP adapter with an injected service),
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService`
(`recordAction`, `auditTrail`, `authorize`, `deny`, `append`),
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceInputs`
(`WORKFLOW_ACTIONS`, `workflowAction`, `uuid`),
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions`
(role matrix), `com.aurevia.smartclinic.messaging.governance.MessagingAuditContract`
(the nine `AuditAction` wire keys, `AuditEvent`, `RecordActionRequest`), and the
production JDBC boundary `com.aurevia.smartclinic.messaging.governance.JdbcMessagingGovernanceStore`
backed by `db/migrations/V62__messaging_governance.sql` (append-only
`messaging_audit_event` with row-level security and FORCE row-level security).

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceServiceTest`
(`recordActionAuditsEveryConversationAndFileAction` loops all nine actions;
`recordActionRejectsGovernanceCommandsItCannotPerform` covers `hold_place`;
`recordRevocationCapturesBlockingAndRecordAccessBasis`,
`attachmentAccessFailsClosedAfterRevocation` and
`newMessagingIsRefusedThroughARevokedConnection` cover the dedicated RESTRICT,
DOWNLOAD and SEND paths), `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceControllerHttpTest`
(MVC pipeline) and `com.aurevia.smartclinic.messaging.governance.it.JdbcMessagingGovernanceStoreIntegrationTest`
(`auditFactsAreOrderedTenantScopedAndImmutable` and
`appendOnlyFactsAreImmutableForTheMigrationOwnerToo` prove scope and immutability).
The new HTTP test adds the real entry point, the interceptor-derived tenant
context, an actual server on a random port, real PostgreSQL and V62's forced
row-level security on the exact nine clause actions.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=AuditOpeningViewingSendingAssigningDownloadingAcknowledgingTest test` —
`tests="5" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the two governance routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: both governance
routes are absent from `contracts/openapi.yaml` and appear in
`contracts/openapi.yaml` (`GET /api/v1/messaging-governance/audit`
and `POST /api/v1/messaging-governance/actions`). Publishing them, with the
required tag, POST `Idempotency-Key`, list `cursor`/`limit`/`nextCursor` and a
regenerated client, is one separate ARC-04/WEB-05 contract task; no contract
file was changed here.
