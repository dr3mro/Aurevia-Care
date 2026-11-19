# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Requirements

Primary: **IAM-06**. Supporting: ARC-01, ARC-02, ARC-03, OPS-02. Gate: G0.

Specification text (SMARTCLINIC_V1_SPEC.md §2): record business purpose, role, facility and
user for sensitive actions; emergency access, where approved, is limited to qualified
clinicians, scoped inside their own organization, time-limited, reasoned, alerted and
reviewed, and cannot unlock other organizations' data; platform support access to clinical
data is disabled by default, with a separate approved and audited incident procedure.

## Pre-existing implementation and the scope decision (read first)

The story was already implemented in the repository snapshot at HEAD `82e194c`, in the
package `com.aurevia.smartclinic.authz.access` (services, repository, controller, rules,
contracts) with the reserved V11 migration `db/migrations/V11__emergency_and_support_access.sql`
and two suites in `com.aurevia.smartclinic.it.authz` (`AccessUnitTest`,
`AccessIntegrationTest`). It is complete, committed, and green under the fast unit tier.

The declared scope for this worker is:

- `apps/api/src/main/java/com/aurevia/smartclinic/authz/emergency`
- `apps/api/src/test/java/com/aurevia/smartclinic/it/authz/emergency`

**Decision:** the existing `authz.access` implementation is reused, not duplicated.
AGENTS.md and CODING_STYLE.md require reusing existing domain types instead of duplicating
rules; a second `@RestController`/`@Service` pair bound to the same V11 tables and
`/api/v1/access` routes would also create ambiguous Spring mappings and break the build.
No out-of-scope file was edited, and no new production file with an unclear owner was
added solely to populate the scoped path. The scoped deliverable is an **independent
acceptance suite** in `it/authz/emergency` plus this record.

**Open decision for the integrator/owner (not taken by this worker):** the production
package is `authz.access`, not the scoped `authz.emergency`. Renaming it would require
moving/deleting out-of-scope files and updating the HTTP contract, so it was left unchanged.
If the package location is a required acceptance condition, it needs a dedicated,
cross-boundary story with the proper scope. No evidence or authority was missing to
*verify* the behaviour — only to relocate the production package.

## Acceptance mapping (IAM-06)

| ID | Acceptance behaviour | Where verified |
|---|---|---|
| A1 | Qualified clinician opens a time-limited, reasoned, own-org grant; grant and alert are separate audited facts | `Iam06AcceptanceTest.a1QualifiedClinicianOpensTimeLimitedReasonedAlertedGrant`; `AccessIntegrationTest.qualifiedClinicianGrantsAndChecksActiveAccess` |
| A2 | Inactive membership or wrong role denies with 403 and audits DENIED; no grant inserted | `a2InactiveMembershipAndWrongRoleFailClosedAndAreAudited`; `grantDeniesInactiveMembershipAndAudits`, `grantDeniesNonClinicianRoleAndAudits` |
| A3 | Emergency access unavailable unless the site has an approved config entry; strict `true` parse | `a3UnapprovedSiteCannotEnableEmergencyAccess`; `grantDeniesWhenSiteConfigurationIsNotApproved`; Org B case in integration |
| A4 | Facility outside the active organization is concealed (404), not merely denied | `a4FacilityOutsideActiveOrganizationIsConcealed`; `grantDeniesFacilityOutsideTheActiveOrganization`; `BETA` case in integration |
| A5 | Only one unexpired, unrevoked grant at a time; expiry or revocation allows a new grant | `a5OnlyOneUnexpiredUnrevokedGrantMayExistAtATime`; `grantRejectsStackedActiveAccessButAllowsAfterExpiry` |
| A6 | `ACTIVE`, `NO_GRANT`, `REVOKED`, `EXPIRED` are distinct, explicit, audited decisions | `a6ActiveDecisionReportsExplicitReasonAtEveryBoundary`; `expiredGrantCannotUnlockAccess` |
| A7 | Revoke is clinician-role-bound; review is privacy-role-bound; grantee cannot review; reason/note persisted | `a7RevocationAndReviewAreRoleBoundAndIndependentReviewerCannotBeGrantee`; `revokeAndReviewAreRoleBoundAndPreserveActorReason`; V11 CHECK constraints |
| A8 | Support access default-deny until a separately approved, unexpired, non-self-approved incident for the exact subject | `a8SupportAccessIsDefaultDenyUntilASeparatelyApprovedUnexpiredIncident`; `supportAccessIsDeniedByDefaultAndNeedsSeparateApproval` |
| A9 | Incident creation (tenant_admin) and approval (privacy_auditor) require different roles | `a9IncidentCreationAndApprovalRequireDifferentRoles`; `approveIncidentDeniesTenantAdmin`; V11 `approved_by <> subject` CHECK |
| A10 | Organization, actor and time are derived from `TenantContext`/injected clock, never from the command body | `a10IdentityAndTenantAreDerivedFromContextNotFromTheCommand`; V11 forced RLS and facility composite FK |
| A11 | Audit rows carry purpose, `role:facility` scope, correlation id, outcome and the acting user | a1/a7/a10 audit verifications; `sensitiveActionsAreAuditedWithPurposeRoleFacilityAndActor` |
| A12 | Two tenants with identical local IDs stay isolated; cross-tenant grant/conversation concealment | `reviewQueueConcealsOtherTenantGrants`; `supportIncidentCannotBeSelfApprovedAtDatabaseLevelAndGrantsAreTenantIsolated` (integration, integrator-run) |

## Entry-point-to-test trace

`POST /api/v1/access/emergency` → `AccessController.grant` →
`EmergencyAccessService.grant` → `AccessRules.validate*` → guarded `TransactionTemplate`
→ `AccessRepository.hasActiveMembership` / `activeRoles` / `effectiveConfig` /
`facilityExists` / `latestGrant` / `insertGrant` / `appendAudit` (purpose
`EMERGENCY_ACCESS`, scope `clinician:<facility>`, `EMERGENCY_GRANT` + `EMERGENCY_ALERT`)
→ 201. `GET .../emergency/active` → `EmergencyAccessService.active` → explicit
`AccessDecision`. `POST .../support/incidents` + `/approve` → `SupportAccessService`
→ `AccessRepository.insertIncident` / `approveIncident`; `POST .../support/check`
→ `SupportAccessService.checkAccess` → default-deny `AccessDecision`.

`Iam06AcceptanceTest` reaches the same services directly (not through HTTP) and is an
independent second entry point for the acceptance matrix; the HTTP and real-PostgreSQL
paths stay in `AccessIntegrationTest`, which the integrator runs.

## Commands run and results (tool evidence)

All commands executed by this worker on branch `main`, HEAD `82e194c`, Java 21 bytecode
via `--release 21` on the local JDK, Maven 3.10, synthetic fixtures only:

| Command | Result |
|---|---|
| `mvn -q -B -Pno-security,fast -Dtest=AccessUnitTest test` (in `apps/api`) | exit 0; pre-existing 40-test IAM-06 unit suite green |
| `mvn -q -B -Pno-security,fast -Dtest=Iam06AcceptanceTest test` | exit 0; new suite 10 tests, 0 failures/errors (Surefire XML) |
| `mvn -q -B -Pno-security,fast test` (clean reports) | exit 0; 147 classes, 1695 tests, 0 failures/errors/skips |
| `mvn -q -B -Pno-security,fast verify` | exit 1 *only* at JaCoCo: 0.83 instructions / 0.82 lines / 0.94 branches / 0.81 methods / 0.95 classes. This is the expected fast-tier artifact of `-DexcludedGroups=integration`: real-PostgreSQL repository SQL is not executed, so repository classes are uncovered. CI runs `mvn -Pno-security,fast test` for the fast tier (no `verify`/JaCoCo); the integrator runs the full `scripts/check.sh` with PostgreSQL. No new production source was added, so this does not change the full-suite gate. |
| JaCoCo CSV inspection (`target/site/jacoco/jacoco.csv`) | `authz.access` domain classes (services, rules, contracts, controller) at 100% instruction/branch/line/method/class in the fast run; `AccessRepository` uncovered only because integration tests were excluded |

Not run locally by this worker (integrator owns them): `bash apps/api/check.sh` full
`mvn verify` with OWASP/SpotBugs and real PostgreSQL, `bash db/check.sh`, Docker image
smoke. PostgreSQL client tooling is absent locally (`pg_isready: command not found`);
database and integration evidence is deliberately left to the integrator.

Additional local gate observations (pre-existing, not caused by this change):

- `bash scripts/quick-check.sh` exits 1 at `check-pack: opencode.json is stale`. Verified
  this is the known cloud-worker artifact (MEMORY OPS-CW-003-01): `git ls-files -v
  opencode.json` shows flag `S`, the worktree file is byte-identical to
  `.github/opencode.ci.json`, and regenerating locally reproduces `git show
  HEAD:opencode.json` exactly. The committed file is correct; the worktree CI profile is
  what the check measured. Worktree config was restored, not committed.
- `python3 -I -m unittest discover -s scripts/tests -q` → 195 tests, 14 failures/errors.
  Sampled causes are environmental and pre-existing: `test_fast_tier` cannot import
  `yaml` (PyYAML not installed), `test_agent_run` fails on shared temp-branch state and
  a mocked check result, and `test_check_pack` reads the CI-profile worktree file above.
  None of these suites touch the two files added here.

## Defects found

- No functional IAM-06 defect was found. The existing implementation satisfies every
  acceptance row above, including negative, expiry, separation-of-duties and
  cross-tenant cases.
- Process/scope finding (not a code defect): the committed production package is
  `authz.access` while this story's scope names `authz.emergency`. Recorded as an open
  decision above; no out-of-scope file was changed and no duplicate was created.
- Build-tooling observation (not a regression): `mvn -Pno-security,fast verify` fails
  JaCoCo because the fast profile excludes DB tests. The documented fast command is
  `... test`; no change was made.

## Decisions, evidence provenance and limitations

- Reuse over duplication; no new production code; scoped acceptance suite added.
- Evidence source: executed tool commands and Surefire/JaCoCo reports (tool evidence).
  No reviewer, simulated user or human participant produced evidence for this story;
  there is no clinical or real-patient acceptance here.
- No requirement ID, approval decision or execution access was missing for verification,
  so the "stop and record missing evidence" condition did not apply. The only unresolved
  item is the package-location decision, which is outside this worker's scope and
  authority and is explicitly handed to the integrator/owner.
- Synthetic data only; no PHI, credentials or raw logs are recorded here.
