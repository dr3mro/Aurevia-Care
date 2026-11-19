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
