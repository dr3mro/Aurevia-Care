# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Requirements and scope

Primary: **IAM-06**. Supporting: ARC-01, ARC-02, ARC-03, OPS-02. Gate: G0.

Specification (SMARTCLINIC_V1_SPEC.md §2): record business purpose, role, facility and
user for sensitive actions. Emergency access, where approved, is limited to qualified
clinicians, scoped inside the owning organization, time-limited, reasoned, alerted and
reviewed; it cannot unlock another organization's data. Platform support access to
clinical data is disabled by default and available only through a separate, approved
and audited incident procedure.

Authoritative scope (TODO.md / worker brief):

- `apps/api/src/main/java/com/aurevia/smartclinic/authz/emergency`
- `apps/api/src/test/java/com/aurevia/smartclinic/it/authz/emergency`
- Reserved migration version **V11** only.

## Base state and scope decision (read first)

The IAM-06 production slice already exists at the worker base `48a6e39` in
`com.aurevia.smartclinic.authz.access` (AccessController, EmergencyAccessService,
SupportAccessService, AccessRepository, AccessRules, AccessContracts), with the
reserved migration `db/migrations/V11__emergency_and_support_access.sql` and suites
`it/authz/AccessUnitTest` (41 tests), `it/authz/AccessIntegrationTest` (DB, integrator-run)
and `it/authz/emergency/Iam06AcceptanceTest` (10 tests).

Following AGENTS.md ("reuse existing domain types instead of duplicating them",
"name packages by business domain, never by story or task ID"), the existing
`authz.access` package is **reused, not duplicated**. Creating a parallel controller,
service pair and REST mapping under the empty scoped `authz.emergency` path would
duplicate a rule set and create ambiguous Spring mappings. No out-of-scope production
file, migration, shared tracker or generated config was edited. The scoped main path
therefore stays empty this attempt; the scoped contribution is an independent
acceptance/verification suite under `it/authz/emergency`.

**Open decision for the integrator / owner (not taken by this worker):** the production
package is `authz.access`, not the scoped `authz.emergency`. Relocating it requires an
out-of-scope file move plus an HTTP contract update; if the package path is a hard
acceptance condition it needs a dedicated, properly scoped cross-boundary story.
No evidence or authority was missing to *verify* IAM-06 behaviour — only to relocate
production code.

## Files changed this attempt

- `apps/api/src/test/java/com/aurevia/smartclinic/it/authz/emergency/Iam06AuditEvidenceTest.java`
  (new, 3 tests) — captures the `TenantContext` passed to `appendAudit` on the grant,
  denial and support-check paths, closing the IAM-06 "purpose, role, facility and user"
  evidence gap that the inherited scoped suite left open by matching the context with
  `any()`. Verifies the grant and its separate alert are both bound to the same
  authenticated context, that a denied grant still audits the acting user with no
  insert, and that a support-check denial audits the acting user.
- `.agent/pr-notes/S-G0-08.md` — this record.

No production code changed this attempt; the inherited production slice was originally
built test-first in earlier attempts. This addition is a regression guard owned by the
scoped path, not new behaviour.

## Acceptance mapping (IAM-06)

| ID | Acceptance behaviour | Where verified |
|---|---|---|
| A1 | Qualified clinician opens a time-limited, reasoned, own-org grant; grant and alert are separate audited facts | `Iam06AcceptanceTest.a1…`; `AccessUnitTest.grantCreatesTimeLimitedRecordAndAuditsGrantAndAlert`; `Iam06AuditEvidenceTest.grantAndAlertAuditsCarryTheAuthenticatedTenantContext` |
| A2 | Inactive membership / wrong role denies 403 and audits DENIED; no grant inserted | `a2…`; `grantDeniesInactiveMembershipAndAudits`, `grantDeniesNonClinicianRoleAndAudits`; **new** `deniedGrantStillAuditsWithTheActingContextAndNoGrantIsWritten` |
| A3 | Emergency access unavailable unless the site has an approved config entry; strict `true` parse | `a3…`; `grantDeniesWhenSiteConfigurationIsNotApproved`; Org B case in integration |
| A4 | Facility outside the active organization is concealed (404) | `a4…`; `grantDeniesFacilityOutsideTheActiveOrganization`; `BETA` case in integration |
| A5 | Only one unexpired, unrevoked grant at a time; expiry or revocation allows another | `a5…`; `grantRejectsStackedActiveAccessButAllowsAfterExpiry`, `grantAllowsAfterAPriorGrantWasRevoked` |
| A6 | `ACTIVE`, `NO_GRANT`, `REVOKED`, `EXPIRED` are explicit, audited decisions | `a6…`; `activeReports*`; `expiredGrantCannotUnlockAccess` (DB) |
| A7 | Revoke is clinician-bound, review is privacy-bound; grantee cannot self-review; reason/note persisted | `a7…`; V11 `CHECK (reviewed_by <> subject)`; `revokeAndReviewAreRoleBoundAndPreserveActorReason` (DB) |
| A8 | Support access default-deny until a separately approved, unexpired incident for the exact subject | `a8…`; `supportCheckIsDeniedByDefault…`, `supportCheckAllowsSeparatelyApprovedIncident`; `supportAccessIsDeniedByDefaultAndNeedsSeparateApproval` (DB) |
| A9 | Incident creation (tenant_admin) and approval (privacy_auditor) require different roles; DB forbids self-approval | `a9…`; `approveIncidentDeniesTenantAdmin`; `supportIncidentCannotBeSelfApprovedAtDatabaseLevel…` (DB) |
| A10 | Organization, actor, role and time are derived from context/clock, never from the command body | `a10…`; **new** audit-context capture tests; V11 forced RLS + composite facility FK |
| A11 | Audit rows carry purpose, `role:facility` scope, correlation id, outcome and the acting user | **new** `Iam06AuditEvidenceTest` (captures the exact `TenantContext` on grant/denial/support paths); integration `sensitiveActionsAreAuditedWithPurposeRoleFacilityAndActor` |
| A12 | Two tenants with identical local IDs stay isolated; cross-tenant grant/conversation concealment | `reviewQueueConcealsOtherTenantGrants`, `supportIncidentCannotBeSelfApproved…` (integrator-run DB) |

## Entry-point-to-test trace

`POST /api/v1/access/emergency` → `AccessController.grant` →
`EmergencyAccessService.grant` → `AccessRules.validate*` → guarded `TransactionTemplate`
→ `AccessRepository.hasActiveMembership` / `activeRoles` / `effectiveConfig` /
`facilityExists` / `latestGrant` / `insertGrant` / `appendAudit` (purpose
`EMERGENCY_ACCESS`, scope `clinician:<facility>`, `EMERGENCY_GRANT` + `EMERGENCY_ALERT`)
→ 201. `GET .../emergency/active` → `EmergencyAccessService.active` → explicit
`AccessDecision`. `POST .../support/incidents` + `/approve` → `SupportAccessService`;
`POST .../support/check` → default-deny `AccessDecision`.

`Iam06AuditEvidenceTest` reaches the same services directly with a mocked repository
and a transaction template that executes the callback synchronously, captures the
`appendAudit` `TenantContext` argument, and asserts organization, subject, purpose and
correlation id — an independently owned second entry point for the A2/A10/A11 rows.

## Commands run and results (evidence source: executed tool commands)

Worker base: branch `swarm/S-G0-08-121`, HEAD `48a6e39`, JDK 21.0.13 (Temurin), Maven
3.10.0, synthetic fixtures only.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (baseline, before edit) | exit 0; 154 reports, **1760 tests, 0 failures/errors/skips** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Iam06AuditEvidenceTest test` | exit 0; new suite **3 tests, 0 failures/errors** (Surefire XML) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Iam06AuditEvidenceTest,Iam06AcceptanceTest,AccessUnitTest test` | exit 0; all three IAM-06 suites green |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (final) | exit 0; 155 reports, **1763 tests, 0 failures/errors/skips** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` + CSV/XML inspection | exit 0; every `authz.access` class **100% instruction/branch/line/method/class** except `AccessRepository` (mocked in the fast tier; its SQL is covered by the integrator's DB suite). Example: `EmergencyAccessService` 0 missed / 802 instructions, 0/60 branches, 0/158 lines, 0/16 methods |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V11 unchanged, still `Change: expand`) |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | exit 0; 2 tests OK (no mutable-record or bad `{@link}` regressions) |
| `git diff --check` | exit 0 |
| `python3 -I scripts/gen-opencode.py --check`, `python3 -I scripts/check-pack.py` | exit 1 each: known cloud-worker CI-profile artifact. `git ls-files -v opencode.json` shows flag `S` (skip-worktree), the worktree file is byte-identical to `.github/opencode.ci.json`, and `git show HEAD:opencode.json` reproduces the generator's local profile. No agent, skill or generated config was touched by this story; file left unchanged. (MEMORY OPS-CW-003-01, delivery-skill note.) |

Not run locally by this worker (integrator owns them): `bash apps/api/check.sh` full
`mvn verify` with OWASP/SpotBugs and real PostgreSQL, `bash db/check.sh`, the eight
`@Tag("integration")` cases in `AccessIntegrationTest`, and image smoke. PostgreSQL
client tooling is unavailable locally (`SMARTCLINIC_PG_ADMIN`/`psql` absent), so DB and
integration evidence is deliberately left to the integrator on the exact merged SHA.
Note: `mvn -Pno-security,fast verify` fails at JaCoCo because the fast profile excludes
the DB tests that cover `AccessRepository`; the documented fast command is `... test`.

## Defects found and decisions

- No functional IAM-06 defect was found. Every acceptance row above holds, including
  negative, expiry, separation-of-duties and cross-tenant cases.
- **Hardened (this attempt):** the inherited scoped suite matched the audit
  `TenantContext` with `any()`, so it did not independently prove that purpose, user,
  organization and correlation id were carried on the audit call. The new suite captures
  and asserts that argument on grant, denial and support-check paths.
- **Residual design observation (low, out of scope, no change):** `active()` gates on
  current membership, clinician role, revocation and expiry, but does not re-read the
  `access.emergency.enabled` site approval at use time; a grant opened while approved
  stays `ACTIVE` until its ≤60-minute expiry if approval is withdrawn meanwhile. The
  approval is checked at grant time and grants are short-lived, so this is a bounded
  window; changing it would require editing out-of-scope `authz.access` production code
  and an owner/architecture decision on whether withdrawal must revoke live grants.
- **Residual defence-in-depth observation (low, out of scope, no change):**
  `SupportAccessService.checkAccess` trusts `AccessRepository.activeIncident` to filter
  by `subject`, and `EmergencyAccessService.active` trusts `latestGrant(subject)`. The
  repository SQL does filter correctly; re-checking the returned row's subject in the
  service would need out-of-scope edits. No bypass is reachable through the current
  entry points.
- **Decision — no V11 change:** V11 already grants the needed DML, forces RLS on both
  tables and enforces the composite facility FK and separation-of-duties checks. No
  migration version other than the reserved V11 was touched.
- **Decision — tests over new production code:** the scoped main path stays empty
  because the domain implementation already exists; duplicating it would break the
  build and violate the reuse rule. The open package-location decision is handed to
  the integrator/owner above.

## Evidence provenance and limitations

- Evidence source for all gate results above: executed tool commands and generated
  Surefire/JaCoCo reports. Specialist review of this attempt: none invoked (no new
  production code to review); no simulated-user exercise and no human or clinical
  participant evidence. Real clinical approval remains a G5 gate.
- All fixtures are synthetic (organization UUIDs, subject names, incident references);
  no patient data, credentials or raw logs appear in any note or test.
- No requirement ID, approval decision or execution access was missing to verify the
  IAM-06 behaviour, so the "stop and record missing evidence" condition does not apply;
  the only unresolved item is the out-of-scope package-location decision recorded above.

---

## Hard pass — branch `swarm/S-G0-08-123` (2026-11-20, this worker)

### Base survey and reuse

This worker started from branch `swarm/S-G0-08-123` (HEAD `e5665c8`) with a clean
tree. The IAM-06 production slice already exists in `com.aurevia.smartclinic.authz.access`
(AccessController, EmergencyAccessService, SupportAccessService, AccessRepository,
AccessRules, AccessContracts), V11 is present, and the two scoped suites
`Iam06AcceptanceTest` (10 tests) and `Iam06AuditEvidenceTest` (3 tests) plus the
out-of-scope `AccessUnitTest` (41) and `AccessIntegrationTest` (DB) already exist.
Per AGENTS.md ("reuse existing domain types instead of duplicating them") I surveyed
all of these first and did **not** duplicate or edit any production class. No migration,
shared tracker, generated config or file outside the two declared scoped paths was
touched. The scoped main path `authz.emergency` therefore remains empty by the same
reuse decision documented above; the open package-location decision is unchanged.

### Verification gap closed (no functional defect found)

The inherited scoped suites capture the acting `TenantContext` argument on only three
paths (grant, membership-denied grant, support-check denial). IAM-06 and OPS-02 require
business purpose, role, facility scope and acting user to be recorded for **every**
sensitive action. The following paths had no independently owned context-capture guard:
`active()` NO_GRANT/REVOKED/EXPIRED/ACTIVE decisions, the stacked-grant CONFLICT, the
unapproved-site DENIED and unknown-facility NOT_FOUND denials, `revoke`, `review`,
`createIncident`, `approveIncident` and the authorized support decision. This attempt
adds one scoped suite covering all of them.

| IAM-06 / OPS-02 clause | Acceptance | Evidence |
|---|---|---|
| Purpose, user, org, correlation recorded for every sensitive action (IAM-06, OPS-02) | Eight audit paths capture the exact `TenantContext` and assert org, subject, purpose, correlation plus action/scope/resource/outcome | `Iam06SensitiveActionAuditTest` (8 tests) |
| Explicit, audited access decisions (A6) | NO_GRANT / REVOKED / EXPIRED audited as DENIED; ACTIVE audited as SUCCESS with the grant's own facility scope | `noGrantDecision…`, `revokedAndExpired…`, `activeDecisionAuditsSuccess…` |
| One grant at a time, conflict audited (A5) | Stacked grant returns 409 and audits CONFLICT against the existing grant id | `stackedGrantAuditsConflict…` |
| Site approval and facility scope enforced and audited (A3/A4) | Unapproved site → DENIED, unknown facility → NOT_FOUND, both bound to the acting context | `unapprovedSiteAndUnknownFacilityAudits…` |
| Revoke clinician-bound, review independent, distinct actors (A7) | Revoke audits the clinician context; review audits the auditor context with `privacy_auditor:MAIN` scope | `revokeAndReviewAudits…` |
| Support incident create/approve roles and authorized check (A8/A9) | Create audits `tenant_admin`, approve audits `privacy_auditor`, authorized check audits SUCCESS — each with its own acting subject | `incidentCreationAndApprovalAudits…`, `authorizedSupportDecisionAudits…` |

Supporting IDs: ARC-01/ARC-02 (tenant keys and forced RLS, unchanged), ARC-03/04
(machine-readable outcomes 403/404/409), OPS-02 (transactional append-only audit).

### Entry-point-to-test trace

`GET /api/v1/access/emergency/active` → `AccessController.active` →
`EmergencyAccessService.active` → guarded `TransactionTemplate` → `AccessRepository.latestGrant`
+ `appendAudit(EMERGENCY_ACCESS, EMERGENCY_ACTIVE_CHECK, ...)`;
`POST /api/v1/access/emergency` (stacked/denial branches), `/emergency/{id}/revoke`,
`/emergency/{id}/review`, `/support/incidents`, `/support/incidents/{ref}/approve`,
`/support/check` → the corresponding domain service → `appendAudit`. The new suite
reaches these services directly with a mocked repository and a synchronous transaction
template, captures the `appendAudit` `TenantContext` argument with an `ArgumentCaptor`,
and asserts the authenticated organization, subject, purpose and correlation id on
each path — an independently owned second entry point for the A3–A9 and A11 rows.

### Test-first sequence (tool evidence, JDK 21.0.13 / Maven 3.10.0)

1. Added `Iam06SensitiveActionAuditTest` (8 tests). Two initial compile failures
   (missing `GrantRow` import; `findIncident` stub returned a bare `IncidentRow`
   instead of `Optional.of(...)`) were corrected — test-harness errors, not product
   behavior. Targeted run then **GREEN, exit 0, 8 tests / 0 failures**.
2. **Red sensitivity check:** temporarily mutated the shared `assertContext` helper to
   expect `<subject>-MUTANT`. Targeted run → **RED, exit 1, 8 tests / 8 failures**,
   each `expected: <clinician-a-MUTANT> but was: <clinician-a>` (and the admin/auditor
   variants). This proves all eight new guards detect a substituted acting user.
3. Restored the assertion. Targeted run → **GREEN, exit 0, 8 tests / 0 failures**.
   No production code was changed: these are regression guards over already-correct
   behavior, so no red→green production fix was warranted.

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Iam06AcceptanceTest,Iam06AuditEvidenceTest,AccessUnitTest test` (baseline) | exit 0; 10 + 3 + 41 tests, 0 failures/errors |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Iam06SensitiveActionAuditTest test` (after harness fix) | exit 0; Surefire **8 tests, 0 failures, 0 errors, 0 skipped** |
| same targeted command with mutated `assertContext` | **exit 1; 8 tests, 8 failures** (acting-user substitution detected) |
| same targeted command after restore | exit 0; **8 tests, 0 failures, 0 errors** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (final full fast tier) | exit 0; Surefire aggregate **1788 tests, 0 failures, 0 errors, 0 skipped**; scoped suites 10 + 3 + 8 all green |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.csv` | exit 0; every `authz.access` class 0 missed instructions/branches/lines/methods/classes — `EmergencyAccessService` 0/802 instr, 0/60 branches, 0/158 lines; `SupportAccessService` 0/419, 0/24; `AccessRules`, all records and `AccessController` 100%. `AccessRepository` remains the mocked-in-fast-tier boundary (357 missed / 368 covered instructions), covered by the integrator's DB suite. |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V11 unchanged, still `Change: expand`) |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py -q` | exit 0 (no mutable-record or bad `{@link}` regressions) |
| `git diff --check` | exit 0 |

Not run locally (integrator owns them on the exact merged SHA): `bash apps/api/check.sh`
full `mvn verify` with OWASP/SpotBugs and real PostgreSQL, `bash db/check.sh`, the eight
`@Tag("integration")` cases in `AccessIntegrationTest`, and image smoke. No PostgreSQL
client tooling is available in this worker, so DB and integration evidence is
deliberately left to the integrator.

### Files changed in this attempt

- `apps/api/src/test/java/com/aurevia/smartclinic/it/authz/emergency/Iam06SensitiveActionAuditTest.java`
  (new, 8 tests).
- `.agent/pr-notes/S-G0-08.md` — this record.
- No production code, migration, tracker, generated config or out-of-scope file.

### Decisions and review

- **Decision — guards over new production code:** no functional IAM-06 defect was found;
  the mature `authz.access` slice satisfies A1–A12 in the inherited suites. Adding a
  parallel production package would duplicate rules and break Spring mappings, so the
  scoped contribution is an independently owned verification suite.
- **Decision — no V11 change:** V11 already grants the needed DML, forces RLS on both
  tables and enforces the composite facility FK and separation-of-duties checks.
- **Evidence source:** every gate result above is an executed tool command. No specialist
  reviewer, simulated user or human/clinical participant evidence was added in this
  pass (no production behavior changed). Real clinical approval remains a G5 gate.
- All fixtures are synthetic (UUIDs, subject names, incident references); no patient
  data, credentials or raw logs appear in any note or test.

### Residual risks / stop-and-report

1. **No local PostgreSQL:** the eight `@Tag("integration")` cases in
   `AccessIntegrationTest` and `db/check.sh` were not run here; the integrator must run
   `bash apps/api/check.sh` on the exact merged SHA. `AccessRepository` SQL is only
   covered by that DB suite.
2. **Stacked-grant concurrency (low, out of scope, no change):** the "one unexpired,
   unrevoked grant at a time" check reads `latestGrant` then inserts without a DB lock;
   two concurrent grants for one subject could both insert. Closing it needs a DB
   advisory lock or constraint in `AccessRepository`/V11, both outside this worker's
   declared file scope. No API-reachable single-threaded bypass exists; recorded for
   owner/architecture triage, not silently accepted.
3. **Site-approval withdrawal window (low, out of scope, unchanged):** `active()` does
   not re-read `access.emergency.enabled` at use time; a grant opened while approved
   stays ACTIVE until its <=60-minute expiry if approval is withdrawn meanwhile.
4. **Package-location decision (open, unchanged):** production lives in `authz.access`,
   not the scoped `authz.emergency`; relocating it needs an owner-approved, properly
   scoped cross-boundary story (HTTP contract + file move). No evidence or authority was
   missing to *verify* IAM-06, so the "stop and record missing evidence" condition does
   not apply to the verification work.
