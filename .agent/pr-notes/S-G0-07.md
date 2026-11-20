# S-G0-07 — IAM-05: patient identity proofing, verified chart linking and unlinking

## Scope and base state

Authoritative scope from `TODO.md`: `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity` and `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity`; reserved migration **V10** (already present as `V10__patient_identity_proofing.sql`, unchanged this attempt).

The worker branch `swarm/S-G0-07-119` starts from a single squashed snapshot commit `82e194c` in which an IAM-05 implementation and tests under `it/patientidentity` already exist (enrollment, single-use expiring invitations, append-only link events, re-verification, forced RLS, audit). I surveyed that code first and did not duplicate it. This attempt adds one missing safety invariant found by reviewing IAM-05 against the existing flow. No shared tracker, migration, generated config or file outside the two scoped paths was edited.

## Requirement IDs and acceptance mapping

| IAM-05 clause | Existing implementation | This attempt |
|---|---|---|
| Site-verified enrollment | `IdentityProofingService.enroll`, staff action policy, V10 `patient_account` | unchanged |
| Expiring, single-use invitations | `issueInvitation`, `linkWithInvitation`, hashed token, DB-clock `consume_invitation` | **stale invitations are now invalidated when the relationship is unlinked or re-verified** |
| Re-verification for lost access or disputed identity | `reverify` unlinks + issues a fresh `REVERIFY` invitation | **old invitations are consumed before the fresh one is issued**, so only the new token can restore access |
| Unlinking immediately ends future access | append-only `account_chart_link` + live `current_chart_links()` / `has_chart_access()` fold | unchanged; closing the stale-token loophole makes the ended access durable |
| A verified account may link several charts; contact change does not change ownership | per-chart link events, immutable chart id; `IdentityProofingIsolationTest` | unchanged |
| Tenant isolation, append-only history, audit | V10 forced RLS, triggers, transactional append-only audit | new update is subject-scoped and chart-scoped inside the same guarded transaction |

Supporting IDs: ARC-01/ARC-02 (tenant keys, forced RLS, non-owner runtime role), ARC-07/OPS-02 (audited identity commands), IAM-01/IAM-02 (membership and role re-derived per request).

## Defect found and fixed

**Medium, privacy/integrity.** `unlink` and `reverify` retired the current link but left any *other, unconsumed* invitation for the same `(account, chart)` valid. A patient holding such a token could redeem it after a dispute and silently re-establish access, bypassing the fresh verification that re-verification exists to require. This is reachable whenever staff issue more than one invitation before redemption (routine, retried or reissued invitations) and then unlink or re-verify the relationship.

Fix (smallest change):
- `IdentityProofingRepository.consumeOutstandingInvitations(subject, chartId)` — one SQL boundary that sets `used_at = now()` on unconsumed invitations for the subject/chart. V10 already grants `UPDATE` on `account_invitation` to `smartclinic_app` and applies forced RLS to both tables, so **no migration change was needed**.
- `IdentityProofingService.unlink` — calls it after authorization and link-existence checks, before appending the `unlinked` event.
- `IdentityProofingService.reverify` — calls it before the `unlinked` event and before issuing the fresh invitation (verified by `InOrder`).

## Test-first sequence (tool evidence)

1. Added `OutstandingInvitationRevocationTest` with three synthetic-Mockito tests asserting the service invokes the revocation on unlink, before the fresh invitation on reverify, and never on a denied unlink.
2. `mvn … -Dtest=OutstandingInvitationRevocationTest test` → **RED, exit 1**: compilation error `cannot find symbol: method consumeOutstandingInvitations(String,String)`.
3. Added the repository method (SQL + Javadoc) only. Same command → **RED, exit 1**: `Tests run: 3, Failures: 2`, both `Wanted but not invoked` — genuine behavioral failure, not just a missing symbol.
4. Wired the service into `unlink` and `reverify`. Same command → **GREEN, exit 0**, 3 tests, 0 failures.
5. Added `StaleInvitationIntegrationTest` (`@Tag("integration")`, tagged out of the DB-less fast tier) proving end-to-end that a stale token returns 409 after an unlink and after a re-verification while the fresh token still works.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (baseline, before edits) | exit 0; 146 Surefire reports, **1685 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OutstandingInvitationRevocationTest test` | RED 1 (compile) → RED 2 (`Tests run: 3, Failures: 2`) → **GREEN exit 0** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OutstandingInvitationRevocationTest test` (final, 5 tests) | exit 0 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (final) | exit 0; 147 reports, **1690 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | exit 0; `IdentityProofingService` 0 missed of 989 instructions, 0 missed of 74 branches; `IdentityActionPolicy` 0/47, 0/10 branches. `IdentityProofingRepository` is exercised by the DB suite (integrator) — the fast tier mocks it. |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V10 still `Change: expand`; migration unchanged) |
| `python3 -I scripts/check-pack.py` | **exit 1**: `opencode.json is stale`. Known pre-existing cloud-worker CI-profile artifact (`git ls-files -v opencode.json` shows `skip-worktree`; MEMORY OPS-CW-003-01 / delivery skill note). No agent, skill or generated config was touched by this story; file left unchanged. |
| `git diff --check` | exit 0 |

## Files changed

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity/IdentityProofingRepository.java` — new `consumeOutstandingInvitations`.
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity/IdentityProofingService.java` — two call sites with rationale comments.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/OutstandingInvitationRevocationTest.java` — 3 new unit tests.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/StaleInvitationIntegrationTest.java` — 2 new tagged DB tests (integrator executes).
- `.agent/pr-notes/S-G0-07.md` — this file.

## Decisions and review

- **Decision — no migration:** V10 already grants the needed UPDATE and forces RLS; adding a version would duplicate the reserved version and risk collision with parallel workers.
- **Decision — scope:** tests live in the authoritative scoped path `it/patient/identity`; the older `it/patientidentity` suite was left untouched.
- **Decision — denial path:** authorization and link-existence failures still return before any revocation, so a rejected command cannot invalidate invitations (covered by the third unit test).
- **Specialist review (simulated, invoked this attempt):** `smartclinic-senior-backend` reviewed the service/repository diff and unit test; `smartclinic-senior-database` reviewed the SQL against V10 and the tenant guard. Both explicitly executed **no** build, test or check — static review only. Dispositions:
  - **Backend, Medium (deferred — owner decision required):** revocation runs after the `currentLink` precondition, so an invitation issued but *never redeemed* cannot be revoked by `unlink`/`reverify` (they return 404). The stale token then remains valid until its TTL. Proposed fix: a separate staff-authorized `revoke-invitations` command with no link precondition. **Not implemented here** because it introduces a new public endpoint and an OpenAPI/contract change, which is outside the declared scoped paths and needs Product Owner/Architect acceptance; the existing 404 semantics are asserted by prior tests. Recorded here as a defect for triage, not silently accepted.
  - **Backend, High/Medium (addressed):** a mocked unit test cannot prove the SQL invariant or RLS behaviour. `StaleInvitationIntegrationTest` was added for exactly this: it runs on real PostgreSQL in the integrator gate and asserts a stale token gets 409 and access stays false. The database reviewer independently confirmed the V10 grants, forced RLS on both tables and the scalar subselect are correct.
  - **Backend, Medium (addressed):** missing branch tests for patient-initiated unlink and denied reverify. Added `patientInitiatedUnlinkAlsoRevokesOutstandingInvitations` and `aDeniedReverifyDoesNotRevokeOutstandingInvitations`; suite now 5 unit tests.
  - **Backend, Minor (accepted):** `used_at = now()` conflates redeemed with revoked in history. The existing schema has no separate status column and adding one would require a migration change; the audit event still records the unlink/reverify reason. Accepted for this slice.
  - **Database, Low (accepted):** expiry is enforced in `consume_invitation(uuid)` but intentionally not for invalidation; documented in the repository Javadoc rather than adding a duplicate DB function and expand migration.
  - **Database, Low (deferred to integrator):** no direct assertion of `used_at` for the target versus a peer tenant. The end-to-end 409/access-false assertions cover the safety outcome; a direct `used_at` cross-tenant probe can be added by the owner as a follow-up test.
- **Evidence source:** all gate results above are from executed commands; specialist findings are simulated review; **no simulated-user exercise and no human or clinical participant evidence**. Real-patient approval remains a G5 gate. All fixtures are synthetic; no patient data or credentials are present.

## Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL** (`pg_isready`/`psql` absent); the two new `@Tag("integration")` tests and the pre-existing IAM-05 DB suite could not be executed here. The integrator must run `bash apps/api/check.sh` on the exact merged SHA and record the JaCoCo ratios, including the new repository method.
2. **`check-pack.py` red at this base** because the worktree `opencode.json` is the CI profile under `skip-worktree`; pre-existing and outside this story's scope (see decision above). Owner/loop should regenerate from `scripts/gen-opencode.py` if the gate is enforced on the worker branch.
3. **No clinical or human participant evidence** for identity-proofing semantics; simulated specialist review only.

---

## Follow-up attempt — branch `swarm/S-G0-07-121` (2026-11-20)

### Base survey and reuse

This worker started from merge commit `48a6e39`, which already contains the complete IAM-05 implementation described above: the nine-file `patient/identity` production package, V10, the `consumeOutstandingInvitations` repository method, the five unit tests in `it/patient/identity/OutstandingInvitationRevocationTest`, the two DB tests in `it/patient/identity/StaleInvitationIntegrationTest`, and the original `it/patientidentity` suite. I surveyed these first and did not duplicate them. No shared tracker, migration, generated config, or file outside the two scoped paths was edited.

### Defect closed (previously deferred)

The prior attempt's review recorded a **Medium** finding: `unlink`/`reverify` retired invitations only *after* the `currentLink()` precondition, so an invitation issued but never redeemed survived a 404 response and could be redeemed later to silently re-establish access after a dispute. The proposed fix — a new staff-authorized `revoke-invitations` endpoint — was deferred because it changes the public contract and needs Product Owner/Architect acceptance.

**This attempt closes the same hazard through the existing commands, with no new endpoint and no contract-file change.** `IdentityProofingService.unlink` and `.reverify` now call `retireOutstandingInvitations(context, subject, chartId)` *before* evaluating `currentLink()`. That helper captures the repository's retired count and appends a distinct `INVITATION_RETIRE` audit event when the count is positive, so a 404 that revokes a credential is distinguishable from a 404 that changed nothing (OPS-02). The HTTP contract is unchanged: a missing link still returns `404 LINK_NOT_FOUND`. The guarded transaction commits the revocation and its audit because `TransactionTemplate` commits when the callback returns `null` and the `ApiException` is thrown only after `execute()` returns. Membership/role validation and the self-or-staff check still run first, so a denied command never revokes. This resolves the earlier `stop-and-report` item without an owner decision, and supersedes that deferral.

### Requirement IDs and acceptance mapping (this change)

| IAM-05 clause | Acceptance | Evidence |
|---|---|---|
| Unlinking immediately ends future account access | An unspent invitation is retired even when `unlink` returns 404 (no link); the stale token then gets 409 | `unlinkWithoutACurrentLinkStillRevokesOutstandingInvitations` (unit); `anUnspentInvitationCannotBeRedeemedAfterARejectedUnlink` (DB, integrator) |
| Re-verification for lost access or disputed identity | A rejected `reverify` also retires prior invitations; only a successful reverify issues a fresh token | `reverifyWithoutACurrentLinkStillRevokesOutstandingInvitations` (unit) |
| Denied commands fail closed | Authorization and link/account/chart checks still precede revocation | `aDeniedUnlinkDoesNotRevoke…`, `aPatientWithoutAVerifiedAccountCannotRevokeInvitations`, `aDeniedReverifyDoesNotRevoke…` (8-test suite green) |
| Tenant isolation, append-only history, audit | No SQL or migration change; V10 forced RLS, grants and append-only triggers unchanged; a revocation is separately audited | `check-migrations.py --require-baseline` green; `INVITATION_RETIRE` audit assertions in the unit suite; DB suite with integrator |

Supporting IDs: ARC-01/ARC-02 (tenant keys and forced RLS), OPS-02 (audited identity commands), IAM-01/IAM-02 (membership and role re-derived per request).

### Entry-point-to-test trace

`POST /api/v1/patient-identity/links/unlink` (or `/links/reverify`) → `IdentityProofingController.unlink`/`reverify` → `TenantContextInterceptor` (subject + explicit active organization) → guarded `TransactionTemplate` → `IdentityProofingService.unlink`/`reverify` → `retireOutstandingInvitations` → `IdentityProofingRepository.consumeOutstandingInvitations` (subject/chart-scoped UPDATE under forced RLS) + `appendAudit(INVITATION_RETIRE)` when count > 0 → `currentLink()` → 404 `LINK_NOT_FOUND` when no link, else append `unlinked` event + `appendAudit` → 200. Unit proof: `OutstandingInvitationRevocationTest`. DB proof: `StaleInvitationIntegrationTest` (integrator).

### Test-first sequence (tool evidence)

1. Added two synthetic-Mockito unit tests (`unlinkWithoutACurrentLinkStillRevokesOutstandingInvitations`, `reverifyWithoutACurrentLinkStillRevokesOutstandingInvitations`) and one synthetic DB test (`anUnspentInvitationCannotBeRedeemedAfterARejectedUnlink`).
2. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OutstandingInvitationRevocationTest test` → **RED, exit 1**: `Tests run: 7, Failures: 2` — both new tests failed with `Wanted but not invoked: repository.consumeOutstandingInvitations("patient.one", "<chart>")`.
3. Moved the existing single call before the precondition in both commands and updated the Javadoc. Same command → **GREEN, exit 0**: `Tests run: 7, Failures: 0, Errors: 0`.
4. Two independent specialist reviews converged on a MEDIUM traceability gap (a 404 that revokes was not distinguishable in the audit trail). Added a `retireOutstandingInvitations` helper that appends `INVITATION_RETIRE` when the returned count is positive, with positive-path audit assertions in the two new tests and never-audit assertions in the three denial tests; added `aPatientWithoutAVerifiedAccountCannotRevokeInvitations` after the backend reviewer noted the patient denial branch was untested. Targeted suite → **GREEN: 8 tests, 0 failures, 0 errors**.
5. Full fast suite, JaCoCo report and static checks → green (table below).

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (baseline before edits) | exit 0; 154 Surefire reports, **1760 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OutstandingInvitationRevocationTest test` (RED) | exit 1; 7 tests, **2 failures** — both new tests |
| same targeted command (GREEN, after precondition fix) | exit 0; 7 tests, **0 failures, 0 errors** |
| same targeted command (final, after audit/denial-test additions) | exit 0; **8 tests, 0 failures, 0 errors** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (final) | exit 0; 154 reports, **1763 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | exit 0; `IdentityProofingService` **0 missed / 1003 instructions, 0 missed / 76 branches, 0 missed / 22 methods, 0 missed / 198 lines, 0 missed / 1 class**; all identity models and controller records 0 missed; `IdentityProofingRepository` is mocked in the DB-less fast tier (DB suite covers it in the integrator gate) |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V10 unchanged, still `Change: expand`) |
| `git diff --check` | exit 0 |
| `python3 -I scripts/check-pack.py` | **exit 1, pre-existing environmental**: `opencode.json is stale`. `git ls-files -v opencode.json` → `S` (skip-worktree); the worktree file is the CI profile (denies `subagent`), while the generator renders the local profile. Known OPS-CW-003-01 artifact; no agent, skill, generated config or tracker touched, so the file was deliberately left unchanged. |
| `python3 -I -m unittest discover -s scripts/tests -q` | 203 tests, 8 failures / 8 errors — all in `test_check_pack`, `test_swarm`, `test_agent_run` and two workflow-test import errors; pre-existing CI-profile/workflow-file tooling state, unrelated to the 3 Java files changed. Not fixed here (would require out-of-scope `scripts/`/`.github/` edits and forbidden trackers). |

JaCoCo `check` at `verify` build-fails at 100% for INSTRUCTION, LINE, BRANCH, METHOD and CLASS (`apps/api/pom.xml`); the DB/integration suite that covers the repository runs in the integrator gate.

### Files changed in this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity/IdentityProofingService.java` — `retireOutstandingInvitations` helper (revocation before the link precondition in `unlink` and `reverify`, with `INVITATION_RETIRE` audit when count > 0); Javadoc.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/OutstandingInvitationRevocationTest.java` — 3 new unit tests (2 no-link revocation + 1 patient-denial) and audit assertions.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/StaleInvitationIntegrationTest.java` — 1 new tagged DB test.
- `.agent/pr-notes/S-G0-07.md` — this file.

### Decisions

- **No new endpoint, no contract change:** the earlier deferred fix needed a new public command and owner approval; moving the existing revocation call closes the hazard while preserving 404/409/200 semantics and the OpenAPI surface.
- **No migration:** V10 already grants `UPDATE` on `account_invitation` and forces RLS on both tables; the reserved version stays unchanged and no parallel worker can collide.
- **Deny-by-default:** revocation stays after membership/role and self-or-staff checks, so a rejected command cannot invalidate invitations.
- **404 preserved:** the revocation is a safe, idempotent side effect of a command that is still reported as "no current link". It is now separately audited as `INVITATION_RETIRE` with the tenant actor, purpose, scope and correlation ID, so an auditor can distinguish a 404 that retired credentials from one that did not. No outcome code or response body changed.

### Review disposition

Two bounded simulated specialist reviews ran; both executed **no** build, test or check (static review only). All findings were dispositioned before the commit:

- **Senior backend — MEDIUM, commit-on-404 guarantee not proven by unit evidence (accepted as tracked gate):** the unit tests use a `PassthroughTransactionManager`, so they prove call ordering but not durable commit. **Disposition:** the integrator must run `bash apps/api/check.sh` on the exact merged SHA; `StaleInvitationIntegrationTest` (3 tests) is the durable proof. Recorded as blocker 1. The reviewer explicitly had no blocking objection once that gate is green.
- **Senior backend — LOW/MEDIUM, a second `reverify` after a successful one (no current link, 404) retires the fresh token from the first reverify (accepted, intended):** every reverify attempt invalidates all prior invitations for the relationship, which fails closed; staff can issue a new invitation, and the new `INVITATION_RETIRE` audit makes the effect visible. **Disposition:** no code change; behavior is consistent with the safety rule, and the traceability concern is addressed by the audit event.
- **Senior backend — LOW, denied patient-initiated unlink untested (addressed):** added `aPatientWithoutAVerifiedAccountCannotRevokeInvitations` asserting 403 and no revocation/no `INVITATION_RETIRE` audit.
- **Senior backend — LOW / database F1 MEDIUM, revocation on 404 not distinctly auditable (addressed):** added the `INVITATION_RETIRE` audit event with the returned count gate, asserted on the positive path and absent on all three denial paths.
- **Database — F2 MEDIUM, direct DB semantics (`used_at` committed under `smartclinic_app`, peer-tenant invitation untouched) not covered by a real-PostgreSQL assertion (tracked, outside declared scope):** the natural home is `it/patientidentity/IdentityProofingIsolationTest`, which is not in this story's scoped paths. The new scoped `StaleInvitationIntegrationTest` asserts the safety outcome end to end (stale token → 409, access stays false) and the integrator runs it. **Disposition:** recorded as a follow-up for the owner; the database reviewer confirmed all six SQL/RLS/grant/immutability criteria pass on inspection.

Evidence source for all commands above: executed tool commands. Specialist findings are simulated review; **no simulated-user exercise and no human or clinical participant evidence**. Real-patient approval remains a G5 gate. All fixtures are synthetic; no patient data or credentials appear in code, tests or this note.

### Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL tools** (`psql`/`pg_isready` absent): `StaleInvitationIntegrationTest` (3 tests) and the pre-existing IAM-05 DB suite could not be executed locally. The integrator must run `bash apps/api/check.sh` on the exact merged SHA, confirm those DB tests pass, and record the JaCoCo ratios including `IdentityProofingRepository`. This is the durable proof that the revocation commits on the 404 path.
2. **`check-pack.py` red** at this base as a CI-profile artifact (above); outside this story's scope.
3. **Direct DB probe of `used_at` and peer-tenant non-revocation (database reviewer F2)** belongs in `it/patientidentity/IdentityProofingIsolationTest`, outside this story's declared scope; tracked for the owner, not silently accepted. The scoped end-to-end stale-token test plus the reviewer's static RLS/grant inspection are the current evidence.
4. **No clinical or human participant evidence** for identity-proofing semantics; simulated specialist review only.

---

## Follow-up attempt — branch `swarm/S-G0-07-123` (2026-11-20)

### Base survey and reuse

Shallow checkout at `e5665c8` already contains the complete IAM-05 implementation from earlier attempts: all eight production classes in `patient/identity`, V10, the `consumeOutstandingInvitations`/`retireOutstandingInvitations` revocation path, the 8-test `OutstandingInvitationRevocationTest`, the 3-test `StaleInvitationIntegrationTest` and the broader `it/patientidentity` suite. I surveyed all of it first and reused it. No existing file was duplicated, no shared tracker or generated config was touched, V10 was not edited, and no file outside the two declared scoped paths was changed.

### Defect found and fixed (High — runtime failure of every link read)

`IdentityProofingRepository.currentLink(...)` and `.links(...)` selected a bare `id::text` from `public.current_chart_links()`. The V10 fold's `RETURNS TABLE` exposes `organization_id, account_id, account_subject, chart_id, event, method, reason, verified_by, occurred_at, seq` — **no `id` column**. PostgreSQL therefore raises SQLSTATE 42703 (`column "id" does not exist`) whenever those queries run. Because `linkWithInvitation`, `unlink` and `reverify` all call `currentLink()` after the mutation, this turns `POST /links/claim`, `/links/unlink` and `/links/reverify` into HTTP 500 *after the write*; `GET /accounts/{subject}/links` also fails. It is invisible in the DB-less fast tier because the repository is mocked there, which is why earlier attempts stayed green. This is the same defect as the `500 on /api/v1/patient-identity/links/claim` recorded in TODO.md DLV-02, fixed here at its production cause inside the declared scope.

Fix (no migration, no new endpoint, no contract change): project the event id from the append-only `account_chart_link` table by joining `current_chart_links()` to it on `(organization_id, account_id, chart_id, seq)` — `seq` is the fold's database-owned ordering key and is unique per organization in V10. Both queries now return `l.id::text AS id`, so `mapLink` still receives the real link-event UUID with the same column label.

### Requirement IDs and acceptance mapping (this change)

| IAM-05 / supporting clause | Acceptance | Evidence |
|---|---|---|
| Site-verified enrollment, single-use invitations, re-verification, unlink ends access, multi-chart linking, immutable ownership | existing flow retained unchanged; the read projection that exposes link state is now executable | `IdentityLinkProjectionContractTest` (fast), `StaleInvitationIntegrationTest.aClaimedLinkAndAListingExposeTheAppendOnlyEventId` (DB, integrator) |
| ARC-01/ARC-02 — tenant keys and forced RLS | the added join uses the existing tenant-scoped `(organization_id, …)` keys and both relations are `FORCE ROW LEVEL SECURITY`; no caller-supplied org id | V10 inspection by database/security reviewers; `check-migrations.py --require-baseline` green |
| OPS-02 — audited identity commands | unchanged; revocation audit path from the prior attempt stays intact | `OutstandingInvitationRevocationTest` 8 tests green |

### Entry-point-to-test trace

`POST /api/v1/patient-identity/links/claim` → `IdentityProofingController.claim` → guarded `TransactionTemplate` → `IdentityProofingService.linkWithInvitation` → `IdentityProofingRepository.insertLink` → `.currentLink(subject, chartId)` (now joins `account_chart_link` on `seq`, projects `l.id AS id`) → `mapLink` → 200 `ChartLink`. Same projection on `.links(subject)` for `GET /accounts/{subject}/links`. Fast proof: `IdentityLinkProjectionContractTest`. Durable DB proof: `StaleInvitationIntegrationTest.aClaimedLinkAndAListingExposeTheAppendOnlyEventId` (asserts claim is 200, `id` is a UUID, and the listing returns the same id).

### Test-first sequence (executed tool evidence)

1. Added `IdentityLinkProjectionContractTest` (two synthetic source/schema contract tests) with no production change.
2. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=IdentityLinkProjectionContractTest test` → **RED, exit 1**: `Tests run: 2, Failures: 1`, message `a bare id::text over the fold fails with SQLSTATE 42703: id::text, account_subject, ... ==> expected: <false> but was: <true>`. (First draft matched a wider SQL span; the regex was tightened to anchor on a text block before re-running RED.)
3. Fixed both repository projections to join `account_chart_link` on `seq`. Same command → **GREEN, exit 0**.
4. Added the tagged end-to-end projection assertion to `StaleInvitationIntegrationTest` (integrator executes it against real PostgreSQL).
5. Full fast suite, JaCoCo and static checks → green (table below).

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (baseline, before edits) | exit 0; 155 reports, **1780 tests, 0 failures, 0 errors, 0 skipped** |
| targeted `IdentityLinkProjectionContractTest` (RED, after tightening) | exit 1; 2 tests, **1 failure**, SQLSTATE 42703 contract message |
| same targeted command (GREEN, after repository fix) | exit 0; 2 tests, 0 failures |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (final) | exit 0; 156 reports, **1782 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | exit 0; `IdentityProofingService`, `IdentityActionPolicy`, controller, all records: **0 missed instructions/branches/methods/lines/classes**. `IdentityProofingRepository` remains mocked in the DB-less fast tier (the integrator's `bash apps/api/check.sh` covers it with the real-DB suite). |
| `git diff --check` | exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V10 untouched, still `Change: expand`) |

Java 21.0.13 / Maven 3.10 locally. No PostgreSQL client or server is available here (`pg_isready`/`psql` absent, port 5432 refused), so the tagged DB suite could not run locally; the integrator runs it on the merged SHA.

### Files changed in this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/identity/IdentityProofingRepository.java` — `currentLink` and `links` now join the append-only table on the fold's `seq` to project the real event id; rationale comments.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/IdentityLinkProjectionContractTest.java` — new, 2 fast-tier schema-contract tests.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patient/identity/StaleInvitationIntegrationTest.java` — 1 new tagged DB test for the end-to-end projection.
- `.agent/pr-notes/S-G0-07.md` — this section.

### Decisions and review disposition

- **No migration and no new endpoint:** the fix is a read-side SQL correction against the already-applied V10; adding a version would collide with other workers' reserved versions and Flyway checksums.
- **Join rather than reinterpret the id:** `ChartLink.id` is documented as the link-event id. Mapping the fold's `account_id` into it would be semantically wrong; joining on the unique `(org, seq)` key returns the real immutable event id.
- **Contract guard in the fast tier:** the defect slipped through because the repository is mocked in unit tests. The new guard compares the repository projection against the migration's declared `RETURNS TABLE` columns so the boundary is checked without a database.
- **Senior Spring Security review (simulated, static only):** no authorization or identity-proofing defect found; all commands gate before side effects, stale tokens are retired after authorization, ownership follows immutable ids, tenant isolation holds. It raised a non-blocking design observation — a clerical user can issue a new `LINK` invitation after a dispute and re-establish access without `LINK_REVERIFY` — which needs a persisted dispute-state model, so it is explicitly out of this bounded fix.
- **Senior backend review (simulated, static only):** raised one genuine secondary gap — successful `hasAccess`/`links` reads append no audit row although `LINK_READ` is privileged and denials are audited (OPS-02). **Disposition: recorded for owner triage, not changed here.** Auditing every patient self-check is a volume/design decision (the reviewer explicitly noted the team may exclude high-frequency self-checks), so changing it needs an owner decision; the primary runtime defect is fixed independently. All other reviewed invariants were confirmed correct.

**Evidence source:** all command results above are executed tool evidence. Specialist findings are simulated static review (no builds/tests run by reviewers). **No simulated-user exercise and no human or clinical participant evidence.** Real-patient approval remains a G5 gate. All fixtures are synthetic; no patient data or credentials appear in code, tests or this note.

### Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL** — the new `aClaimedLinkAndAListingExposeTheAppendOnlyEventId` test and the pre-existing IAM-05 DB suite could not be executed here. The integrator must run `bash apps/api/check.sh` on the exact merged SHA, confirm those DB tests pass (they are the durable proof that the 500 is gone), and record JaCoCo including `IdentityProofingRepository`.
2. **Audit of successful privileged identity reads (OPS-02)** — owner decision needed on whether patient self-checks should be audited before adding `LINK_READ/SUCCESS` rows; recorded above.
3. **No clinical or human participant evidence** for identity-proofing semantics; simulated specialist review only.
