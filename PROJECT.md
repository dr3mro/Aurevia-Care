# SmartClinic delivery record

The authoritative product requirements are in SMARTCLINIC_V1_SPEC.md. Repository discovery, baseline verification and implementation status are pending.

| Decision | Baseline |
|---|---|
| Platform | Modular Java/Spring backend, PostgreSQL, staff React web and patient React Native/Expo |
| Delivery | WIP one, G0 through G5, targeted verification before broader gates |
| V1 workflow | Clinic, lab and patient access pilot first. Radiology, PT and pharmacy enable per site. |
| Patient communication | MSG-01 through MSG-07 and ARC-07. Verified care unit connections, scoped threads, secure files and versioned instructions. |
| Real-patient prerequisites | Market approval, policy pack, partner contracts and site-specific operational and clinical evidence |

## Implementation decisions

| Date | Requirement IDs | Decision and rationale |
|---|---|---|
| 2026-11-18 | IAM-01, ARC-02 | Base package `com.aurevia.smartclinic`; active tenant comes only from `X-Active-Organization`, validated against active membership on every request — never inferred from body, resource ID or first membership. Evidence: `TenantContextInterceptor`, `FacilityService`. |
| 2026-11-18 | ARC-02 | Roles `smartclinic_migrate` (Flyway owner) and `smartclinic_app` (non-owner, NOBYPASSRLS). GUCs bound with `set_config(..., true)` inside `TenantGuardTransactionManager.doBegin` after `super.doBegin()`, so pooled connections cannot leak context. Evidence: V2 migration, `db/check.sh`. |
| 2026-11-18 | OPS-02 | Append-only `audit_event` with per-tenant sequence and core `sha256` hash chain; no pgcrypto dependency. Evidence: V3 migration, `audit_verify_chain`, `db/check.sh`. |
| 2026-11-18 | ARC-03, OPS | Deferred from S-G0-01: idempotency keys, managed OIDC/MFA/CSRF, staff lifecycle history (S-G0-02), search/files/workers/export isolation and concurrent stress. Synthetic `X-Synthetic-Subject` is gated on the `synthetic-auth` profile and is not an authentication mechanism. |
| 2026-11-18 | Build | Java 21 bytecode on JDK 27, Spring Boot 3.4.4, JaCoCo 0.8.16, Mockito subclass maker (inline maker cannot instrument JDK 27). Evidence: `apps/api/pom.xml`, `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`. |

Record implementation decisions with date, requirement IDs, rationale and evidence. Keep unconfirmed choices marked as pending. Reconcile with current repository evidence at each handoff.
