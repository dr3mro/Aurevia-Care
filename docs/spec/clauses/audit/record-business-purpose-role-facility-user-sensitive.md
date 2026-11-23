# IAM-06 clause 1 — record business purpose, role, facility and user

## Clause

Record business purpose, role, facility and user for sensitive actions.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.audit.clauses.RecordBusinessPurposeRoleFacilityUserSensitiveTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/audit/clauses/RecordBusinessPurposeRoleFacilityUserSensitiveTest.java`),
an HTTP-level integration test on a fresh real PostgreSQL database seeded with only
synthetic identities, a facility `MAIN` and an approved `access.emergency.enabled`
site configuration:

- `grantRecordsPurposeRoleFacilityAndUser` — a qualified clinician in `ORG_A` grants
  emergency access to `MAIN` (201). The persisted grant row is read back and asserted
  to carry `purpose = EMERGENCY_ACCESS`, `role_key = clinician`, `facility_code = MAIN`
  and `subject = clinician-a`. The matching `EMERGENCY_GRANT` audit row is asserted to
  carry the same purpose, the `clinician:MAIN` scope, the active organization, the
  request correlation id and an `actor_id` equal to the clinician's database-derived
  `user_identity.id` — never the subject string from the request.
- `wrongRoleGrantIsDeniedAndAudited` — an active member with the reception role is
  refused the same command (403). No grant row is created for that actor, and the
  committed `DENIED` audit row still records the purpose, the `clinician:MAIN` scope,
  the resource and the denied actor's `user_identity.id`.

The entry point and production chain exercised by both tests:
`com.aurevia.smartclinic.web.TenantContextInterceptor` (binds subject, organization
and correlation id from request headers), `com.aurevia.smartclinic.tenant.TenantContext`,
`com.aurevia.smartclinic.tenant.TenantGuardTransactionManager` (binds `app.actor` inside
the guarded transaction), `com.aurevia.smartclinic.authz.access.AccessController`
(`POST /api/v1/access/emergency`), `com.aurevia.smartclinic.authz.access.EmergencyAccessService`
(purpose, scope and denial decisions), `com.aurevia.smartclinic.authz.access.AccessRules`
(clinician role rule) and `com.aurevia.smartclinic.authz.access.AccessRepository`
(`insertGrant`, `appendAudit`). The audit actor derivation, forced tenant isolation,
append-only chain and grant columns come from the Flyway migrations under
`db/migrations` (`V1__tenant_schema.sql`, `V2__rls_roles_policies.sql`,
`V3__audit_chain.sql`, `V11__emergency_and_support_access.sql`).

Prior coverage of the same requirement, not re-tested here: `AccessIntegrationTest`,
`Iam06AuditEvidenceTest` and `Iam06SensitiveActionAuditTest` assert audit purpose,
scope and acting context for grant, denial, revoke and support paths; this class adds
the missing end-to-end assertion on the successful grant row and the wrong-role denial.

Verified locally with
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecordBusinessPurposeRoleFacilityUserSensitiveTest test`
— `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
