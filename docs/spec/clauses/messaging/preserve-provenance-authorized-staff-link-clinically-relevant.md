# MSG-07 clause 3 — preserve provenance on authorized chart links

## Clause

Preserve provenance when authorized staff link clinically relevant messages or instructions to the chart.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.PreserveProvenanceAuthorizedStaffLinkClinicallyRelevantTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/PreserveProvenanceAuthorizedStaffLinkClinicallyRelevantTest.java`),
an HTTP-level test that drives the real Spring MVC pipeline with a fresh tenant-scoped
synthetic store for every case:

- `authorizedClinicianLinksInstructionToChartPreservingProvenance` — an active `clinician`
  in the selected organization files an `instruction` source: HTTP 201, `Location` bound to
  the returned `id`, every provenance field on the response (`organizationId`,
  `conversationId`, `messageId`, `patientId`, `chartId`, `sourceType`, `sourceReference`,
  `revision`), server-stamped `linkedBy` and `linkedAt`, exactly one stored `ProvenanceLink`
  equal to the response, a `chart_link` `success` audit fact carrying the actor, message
  resource, `SYSTEM` unit, correlation id and tenant, and that fact readable as an
  authorized privacy auditor (audit read is a separate permission from filing).
- `authorizedClinicianLinksMessageToChartPreservingProvenance` — the same entry point for a
  `message` source type: 201, the stored link's source type, message id, patient id and
  server instant.
- `chartLinkByNonClinicianRoleIsForbidden` — `privacy_auditor` and `tenant_admin` are refused
  (403 `FORBIDDEN`) with two `denied` `chart_link` facts and no link written.
- `chartLinkForPatientWithoutConnectionInActiveTenantIsNotFound` — a patient connected only
  in the other tenant is concealed (404 `NOT_FOUND`) in the active tenant; a `not_found`
  fact is appended in the active tenant, none in the other tenant, and no link is written.
- `chartLinkByClinicianOfOtherTenantIsForbidden` — a clinician from the other tenant sending
  the first tenant as active scope is refused (403 `FORBIDDEN`, no active membership); no
  link is written in either tenant and the denial is attributed to that subject.
- `chartLinkWithMalformedPatientIdIsRejected` — 422 `INVALID_PATIENTID`, no link written.
- `chartLinkWithUnsupportedSourceTypeIsRejected` — 422 `INVALID_SOURCETYPE`, no link written.

Production classes exercised by the entry point:
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController` (thin
`POST /api/v1/messaging-governance/chart-links` adapter),
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService`
(validation, `CHART_LINK` authorization, active-tenant verified-patient binding, link
persistence and audit append),
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceInputs` (UUID/text
validation), `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions`
(`CHART_LINK` granted only to `clinician`),
`com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LinkToChartRequest`,
`ProvenanceLink` and `AuditAction.CHART_LINK`,
`com.aurevia.smartclinic.messaging.governance.MessagingGovernanceStore` (wired here to the
test-only `InMemoryMessagingGovernanceStore`; the production `JdbcMessagingGovernanceStore` and
the append-only `messaging_provenance_link` table with RLS/FORCE RLS in
`db/migrations/V62__messaging_governance.sql` are the durable implementation but are not
exercised by this HTTP test — they are covered by the database integration suite),
`com.aurevia.smartclinic.web.TenantContextInterceptor` and
`com.aurevia.smartclinic.api.ApiErrorHandler`. The binding proven here is the one the service
enforces: a patient with a connection in the active tenant; the check matches any such
connection and does not inspect its revocation state. The service does not verify that the
message or conversation belongs to that patient, and this test makes no such claim.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=PreserveProvenanceAuthorizedStaffLinkClinicallyRelevantTest test`
— `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (Surefire report).

## Gap

None for the clause rule: authorization, tenant/patient binding, provenance persistence and
audit are implemented and now proven through the HTTP entry point. One separately owned
contract-coverage item remains outside this story's scope: the route is absent from the
published OpenAPI contract and is baselined in `contracts/openapi.yaml:245`;
closing it needs one task changing `contracts/openapi.yaml` and regenerating the clients.
No production, contract or migration change is needed to prove this clause.
