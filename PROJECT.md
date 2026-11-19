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
| 2026-11-19 | Build, OPS-06 | One supported baseline: **Java 21** bytecode (`maven.compiler.release=21`), built on CI JDK 21 (`.github/actions/java-verify`). The local JDK 27 is tolerated only because `--release 21` is used; the Mockito **subclass** maker is required for those local runs because the inline maker cannot instrument JDK 27 classes, and is harmless on JDK 21 — it is a JDK 27 workaround, not a baseline requirement. Spring Boot 4.1.2, PostgreSQL driver 42.7.14, JaCoCo 0.8.16. Evidence: `apps/api/pom.xml`, `.github/actions/java-verify/action.yml`, `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`. |
| 2026-11-19 | OPS-06 | CI action majors aligned: `checkout@v7`, `upload-artifact@v7`, `download-artifact@v8` (all tags verified with `git ls-remote`; Dependabot proposals already merged as PRs #1–#3). `setup-java@v4` and `cache@v4` retained in the composite action — v6 tags exist and were verified, but there is no Dependabot proposal, so no unrequested major bump. No tag could not be resolved. |
| 2026-11-18 | OPS-02, OPS-06 | OWASP Dependency-Check 12.1.0 (`failBuildOnCVSS 7`) and SpotBugs 4.10.5.0 run in `mvn verify`. NVD key is read from `NVD_API_KEY` via `nvdApiKeyEnvironmentVariable` and never stored in the repo; scans succeed from cached data when unset. CVEs in the older stack were fixed by upgrading dependencies, not by suppression. Justified SpotBugs annotations are used only for Spring-injected collaborators and the allowlist-validated echoed header. |
| 2026-11-19 | IAM-01, IAM-02, OPS-02 | S-G0-02 staff lifecycle: deactivation is an append-only 'revoked' membership event (never an UPDATE), so the latest membership event per (org, user) determines current status and a deactivation denies the next request with zero staleness. Role grants are append-only rows carrying `granted_by` and `valid_from`/`valid_to`, enforced as effective-date gates. Bounded G0 roles reuse `tenant_admin` (allowed) / `reception` (denied). Cross-tenant and nonexistent staff concealed with 404. Staff audit purpose is `STAFF_ADMIN`; concurrency stress deferred to S-G0-04 and arbitrary role reassignment to S-G0-03. |
| 2026-11-19 | OPS-06 | Swarm workers prefer the slot-specific `OPENCODE_API_KEY_<n>` secret and fall back to `OPENCODE_API_KEY` for repository compatibility. Every agent-run command marks the checked-out `$PWD` as a Git safe directory because container workers can have a different owner than the checkout. Evidence: `.github/workflows/swarm.yml`, `scripts/agent-run.sh`, `scripts/tests/test_swarm.py`. |

Record implementation decisions with date, requirement IDs, rationale and evidence. Keep unconfirmed choices marked as pending. Reconcile with current repository evidence at each handoff.
