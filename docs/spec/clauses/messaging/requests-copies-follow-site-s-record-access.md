# MSG-07 clause 7 — requests for copies follow the site's record access procedure

## Clause

Requests for copies follow the site's record access procedure.

## Status

Partly proven

## Evidence

Tool evidence: four HTTP-level tests were executed with
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequestsCopiesFollowSiteSRecordAccessTest test`
(Surefire: 4 tests, 0 failures, 0 errors, 0 skipped).

Test class `com.aurevia.smartclinic.messaging.clauses.RequestsCopiesFollowSiteSRecordAccessTest`
([source](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/RequestsCopiesFollowSiteSRecordAccessTest.java))
drives the real controller, service and tenant-scoped store through the Spring
MVC pipeline (`TenantContextInterceptor`, `ApiErrorHandler`) with synthetic
subjects, a synthetic resource and a fixed clock:

- `copyRequestIsRoutedToRecordAccessAndAuditedOverHttp` — an authorized patient
  POSTs to `/api/v1/messaging-governance/copy-requests` and receives 200 with
  `status=ROUTED` plus that caller's `organizationId`, `resourceId`,
  `requestedBy` and `purpose`. Exactly one copy request is stored in the
  caller's organization and none in another organization, and exactly one
  `COPY_REQUEST`/`SUCCESS` audit fact is appended for the resource.
- `copyRequestIsRefusedForARoleWithoutCopyPermission` — a clinician, who is not
  in the `COPY_REQUEST` role allowlist, receives 403 `FORBIDDEN`; no copy request
  is stored and a `COPY_REQUEST`/`DENIED` fact is appended.
- `copyRequestIsRefusedForBlankPurposeAndInvalidResourceId` — a blank purpose is
  refused with 422 `INVALID_PURPOSE` and a malformed resource identifier with
  422 `INVALID_RESOURCEID`; neither writes a copy request nor an audit fact,
  because validation runs before the context and authorization checks.
- `copyRequestIsRefusedForInactiveMembership` — an allowlisted role whose
  membership is inactive receives 403 `FORBIDDEN` with `no active membership`;
  no copy request is stored and a `COPY_REQUEST`/`DENIED` fact is appended.

Production classes and objects exercised by those entry points:

- `MessagingGovernanceController`
  ([source](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceController.java))
  — thin HTTP adapter whose `POST /copy-requests` delegates to the service.
- `MessagingGovernanceService.requestCopy`
  ([source](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceService.java))
  — validates the command, re-checks active membership and the `COPY_REQUEST`
  allowlist, creates the `CopyRequest` with status `ROUTED`, persists it and
  appends a separate audit fact.
- `MessagingGovernanceActions`
  ([source](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceActions.java))
  — the `COPY_REQUEST` allowlist: patient, tenant_admin and privacy_auditor.
- `MessagingGovernanceInputs`
  ([source](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceInputs.java))
  — `uuid` and `text` validation at the trust boundary.
- `MessagingAuditContract`
  ([source](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingAuditContract.java))
  — `RequestCopyRequest`, `CopyRequest`, `AuditEvent`, the `COPY_REQUEST` action
  and the `SUCCESS`/`DENIED` outcomes.
- `MessagingGovernanceStore` and `JdbcMessagingGovernanceStore`
  ([interface](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceStore.java),
  [JDBC store](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/JdbcMessagingGovernanceStore.java))
  — tenant-keyed persistence; the in-memory fake used by the test mirrors its
  tenant scoping, and the existing JDBC store integration test covers the real
  PostgreSQL round-trip and cross-tenant isolation.
- `V62__messaging_governance.sql`
  ([source](../../../../db/migrations/V62__messaging_governance.sql))
  — the `messaging_copy_request` table with row level security and FORCE ROW
  LEVEL SECURITY, so the routed request is durable and tenant-isolated.

## Gap

`Partly proven` is the honest status. The tests prove that a copy request is
validated, authorized, routed in the caller's tenant, durably stored and
audited. They cannot prove that the request follows the site's approved
record-access procedure: that procedure is external and human-gated, and no
signed site procedure, market policy pack binding `record_access_request`,
named staff queue or G5 evidence artifact exists in this repository. The
related manual record-request process is analysed as Not built in
[staff-handle-proxy-dependent-other-record-requests.md](../product/staff-handle-proxy-dependent-other-record-requests.md).

The `POST /api/v1/messaging-governance/copy-requests` route is also absent from
the generated OpenAPI contract and is baselined as missing in
[missing-routes.txt](../../../../contracts/openapi/missing-routes.txt) (line 247),
so no regenerated client exposes it. No production file is changed here.

Files that would change once the work is authorized: a site-owned approved
procedure and signed market policy pack under `docs/operations/`;
`contracts/openapi.yaml`, the generated spec and client and the removal of the
route from `missing-routes.txt`; a staff-web intake and independent-approval
workflow; and a G5 evidence artifact.

This needs at least three separate tasks: (1) a human-owned task for the
approved site procedure, market policy binding and G5 acceptance; (2) a
contract task for the OpenAPI entry, regenerated client and route-parity
baseline; (3) a staff-web intake and independent-review slice. The first task
cannot be delivered by code and its evidence is not in this repository.
