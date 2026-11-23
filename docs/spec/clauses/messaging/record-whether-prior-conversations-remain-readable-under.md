# MSG-07 clause 5 — API test evidence

## Clause

Record whether prior conversations remain readable under the verified patient's record-access policy.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.RecordWhetherPriorConversationsRemainReadableUnderTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/RecordWhetherPriorConversationsRemainReadableUnderTest.java`).
All seven tests are active (none are disabled). They drive the real controller,
service and tenant-scoped governance store through the Spring MVC pipeline
(standalone MockMvc, `ApiErrorHandler` and `TenantContextInterceptor`, a fixed
clock and synthetic `X-Synthetic-Subject` / `X-Active-Organization` headers).
All identifiers, policy versions and actors are synthetic. The verdicts assert
both the HTTP response and the persisted effect in the synthetic store, so a
test would fail if the effect were computed but never recorded, or recorded but
never returned.

Executed proof (tool evidence): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecordWhetherPriorConversationsRemainReadableUnderTest test` — exit 0; Surefire report `tests="7" errors="0" skipped="0" failures="0"`. Static analysis: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0, no findings. Full commands, gate stages and environment observations are recorded in `.agent/pr-notes/SPEC-MSG-07-C05-20261123-011808.md` under `## Test evidence`.

- `revocationEffectRecordsPriorConversationsReadableFromVerifiedPolicy` —
  positive proof. `POST /api/v1/messaging-governance/connections/{id}/revocation-effects`
  returns `priorConversationsReadable=true`, `recordAccessBasis="policy-v1"`,
  `messagingBlocked=true` and `attachmentAccessBlocked=true`; one effect with
  those readability values is persisted, and `GET .../audit` reports a `restrict`
  fact with outcome `success`.
- `aNotReadablePolicyRecordsPriorConversationsAsNotReadable` — policy-branch
  proof. With a verified policy of `false/"policy-v2"`, both the response and
  the persisted effect record `false` and `policy-v2`.
- `missingPolicyFailsClosedToNotReadable` — fail-closed proof. With no approved
  policy, both the response and the persisted effect record `false` and
  `"UNKNOWN"`.
- `aNonAdminCannotRecordReadabilityEffects` — negative proof (wrong role).
  RESTRICT is `tenant_admin`-only, so a `patient` subject receives HTTP 403
  `FORBIDDEN` and no effect is persisted; a patient cannot self-report
  readability.
- `anActiveConnectionCannotRecordReadabilityEffects` — negative proof
  (forbidden state). An active connection receives 409 `CONNECTION_ACTIVE` and
  no effect is persisted.
- `aMissingConnectionCannotRecordReadabilityEffects` — negative proof (missing
  input). An unknown connection receives 404 `NOT_FOUND` and no effect is
  persisted.
- `anotherTenantCannotRecordEffectOnThisConnection` — negative proof (tenant
  concealment). A tenant admin of a different organization targeting this
  tenant's connection receives 404 `NOT_FOUND`, and this tenant's store holds
  no effect for the connection.

Production classes exercised through the real HTTP entry point
(`MessagingGovernanceController`):

- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController` —
  thin adapter for `POST /connections/{id}/revocation-effects` and `GET /audit`.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService` —
  `recordRevocation` validates the connection is revoked, reads the tenant's
  verified `RecordAccessPolicy`, copies `priorConversationsReadable()` and
  `policyVersion()` into the effect, persists it and appends a `restrict` audit
  fact; the fail-closed default comes from the store.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions` —
  grants RESTRICT only to `tenant_admin` (negative proof above).
- `com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect`
  and `RecordAccessPolicy` — the wire and stored facts carrying readability and
  its provenance basis.

The production `JdbcMessagingGovernanceStore` implements the same
`MessagingGovernanceStore` interface, but these HTTP tests use the synthetic
`InMemoryMessagingGovernanceStore` adapter and do not exercise the JDBC boundary
directly.

Database evidence: `db/migrations/V62__messaging_governance.sql` creates
`messaging_record_access_policy` (`prior_conversations_readable`,
`policy_version`), `messaging_connection` (`revoked_at`) and
`messaging_revocation_effect` (`prior_conversations_readable`,
`record_access_basis`) with forced row-level security, so the recorded
readability decision survives a restart and stays tenant-scoped. The
`/api/v1/messaging-governance` routes are currently baselined as missing from
`contracts/openapi.yaml` in `contracts/openapi.yaml`, not
documented there. That is a separate contract-completeness observation and does
not change this clause's runtime proof.

## Gap

None.

The MSG-07 clause-5 rule is built and proven by active tests through the real
HTTP entry point and the production migrations. This task added proof only; it
did not change production code, an existing test, the OpenAPI contract or a
migration. The OpenAPI missing-routes baseline noted above is an observation
owned by a separate contract task, not a gap in this clause.
