# S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance (MSG-06)

## Requirement IDs and acceptance mapping

Primary: **MSG-06**. Supporting: **ADM-01** (named approval, version history), **ARC-01/02/03/04/07** (tenant-owned keys, forced isolation at the store boundary, version preconditions, unit-scoped conversation/connection facts), **OPS-02** (audit of reads and denials, no PHI in fixtures), **UX-01** (en/ar guidance, RTL layout, IANA time-zone context), **IAM-01/05** (fresh membership and verified chart connection checks).

Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-06, §6 ADM-01, §7 ARC-01..07, §8 OPS-02, §9 G3.

| MSG-06 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Each enabled unit publishes service hours, response targets, appropriate topics and urgent contact instructions | `ServiceProfileService.publish` + `ServiceProfileInputs` (IANA zone, window, topic and bilingual guidance validation); `ServiceProfile` carries `hours`, `responseTargetMinutes`, `topics`, `urgentInstructions`, `urgentChannel`, `downtimeChannel` | `ServiceProfileServiceTest`, `ServiceProfileDomainTest`, `ServiceProfileControllerTest` |
| Guidance is named-approved, versioned, immutable and effective only at its effective date | `ServiceProfileService.approve` (distinct named approver + If-Match version precondition), `effectiveAt`, immutable `ProfileHistory`; drafts and future-dated versions are never served | `ServiceProfileServiceTest`, `ServiceProfileDomainTest` |
| Show these before sending; outside hours show when the unit next handles messages | `ServiceAvailability` from `ServiceHoursCalculator` (IANA zone, DST-safe, absolute next-handling instant); mobile `noticeLines` / `ServiceProfileNotice` render urgent guidance and site-approved channels first, then the after-hours next-handling banner, before the explicit acknowledgement action | `ServiceHoursCalculatorTest` (13), `index.test.tsx` (12) |
| Require a named queue owner, backup coverage and overdue escalation | `queueOwner`, `backupCoverage`, `escalationChannel` are required validated publish fields; `urgentChannel`/`downtimeChannel` are site-approved keys, never clinical payloads | `ServiceProfileDomainTest`, `ServiceProfileServiceTest` |
| Surface backlog, unassigned work, failed notifications and overdue responses to unit supervisors | `SupervisorSnapshotCalculator.compute` + `ServiceProfileService.supervisorSnapshot` (SUPERVISOR_VIEW restricted to an assigned unit; snapshot derives counters on demand from non-sensitive work facts) | `SupervisorSnapshotCalculatorTest`, `ServiceProfileServiceTest` |
| Tenant isolation, fail-closed authorization, audit | Every store call carries the organization; `authorize`/`requireUnitAccess` re-derive membership, role, unit assignment and verified-chart connection per request; reads and denials are appended to `MessagingAudit` | `ServiceProfileServiceTest`, `InMemoryServiceProfileStoreTest` |

Entry-point-to-test trace:
- `GET /api/v1/care-units/{unitId}/service-profile` → `ServiceProfileController.view` → `ServiceProfileService.viewProfile` → `authorize` + `requireUnitAccess` → `ServiceProfileStore.effectiveProfile` → audit → 200. Covered by `ServiceProfileControllerTest`, `ServiceProfileServiceTest`.
- `GET .../{unitId}/availability` → `ServiceProfileService.availability` → `ServiceHoursCalculator.calculate` → 200. Covered by `ServiceHoursCalculatorTest`.
- `GET .../{unitId}/supervisor-snapshot` → `ServiceProfileService.supervisorSnapshot` → `SupervisorSnapshotCalculator.compute` → 200. Covered by `SupervisorSnapshotCalculatorTest`.
- `GET .../{unitId}/service-profile/versions` → `ServiceProfileService.history` (governance roles only). Covered by `ServiceProfileServiceTest`.
- `POST .../{unitId}/service-profiles` and `POST .../{unitId}/service-profiles/{profileId}/approve` → `publish` / `approve` (If-Match precondition) → guarded store → audit. Covered by `ServiceProfileControllerTest`, `ServiceProfileServiceTest`.
- Patient side: `createSyntheticServiceInfoClient.notice` → `noticeLines` → `<ServiceProfileNotice>` with testIDs asserted in `apps/patient-mobile/src/features/s_g3_09/index.test.tsx`.

## Scope and prior state

The complete in-scope implementation (9 Java main files + 6 Java test files under `apps/api/.../messaging/s_g3_09`, 4 files under `apps/patient-mobile/src/features/s_g3_09`) was already present in the base snapshot from earlier attempts, including a prior documentation-only fix that removed a stale Javadoc `@param` on `PublishProfileRequest`. I reviewed every in-scope production and test file against MSG-06 and re-ran the full fast tiers; no functional defect and no coverage gap was found, so no source change was necessary in this pass. Only synthetic fixtures are used; no patient data, real identifiers or credentials appear in code, tests or these notes.

## Commands run and results (evidence source: executed tool commands, recorded truthfully)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='com.aurevia.smartclinic.messaging.s_g3_09.*Test' test` → **EXIT 0**. 63 in-scope tests, 0 failures/errors.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Full fast tier from `scripts/quick-check.sh`: 1505 Java tests, 0 failures/errors.
- `mvn -q -B -f apps/api/pom.xml jacoco:report` → **EXIT 0**. All 18 s_g3_09 production classes at **100%** instruction/branch/line/method coverage: 0 missed of 2242 instructions, 179 branches, 368 lines, 99 methods (`target/site/jacoco/jacoco.csv`). This was read after the full fast run.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → **EXIT 0** (1272 packages).
- `cd apps/patient-mobile && npm run typecheck` → **EXIT 0**; `npm run lint` → **EXIT 0**.
- `cd apps/patient-mobile && npx jest src/features/s_g3_09/index.test.tsx --runInBand --coverage --collectCoverageFrom='src/features/s_g3_09/*.{ts,tsx}'` → **EXIT 0**, 12 tests; `ServiceProfileNotice.tsx` and `serviceNotice.ts` at 100% statements/branches/functions/lines (`types.ts` is interface-only, 0/0).
- `cd apps/patient-mobile && npm run test:coverage -- --ci` → **EXIT 0**. 26 suites / 268 tests pass; s_g3_09 code files at 100% across all four metrics.
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0. `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0.
- `python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check` → **EXIT 1 here for an environmental reason only**: the worktree `opencode.json` is the skip-worktree CI profile (`git ls-files -v` flag `S`, sha `279aa1e7…`, matching `.github/opencode.ci.json`) while `git show HEAD:opencode.json` is the local profile (`14bba0da…`). This is the documented OPS-CW-003-01 cloud-worker trap, not a change from this story. I did not overwrite the CI profile; the integrator's merge result uses the committed file.

I did **not** run `mvn verify` (OWASP/SpotBugs/JaCoCo gate), `db/check.sh` or any real-PostgreSQL integration suite: per the worker contract those belong to the integrator. This slice uses a tenant-keyed in-memory `ServiceProfileStore`, so there are no new integration fixtures; there is no fabricated integration or clinical/human evidence.

## Defects found

No functional, security or coverage defect was found in the pre-existing implementation. Test-first state: the failing-test suites already exist in the base and were confirmed green before and after review; no new red→green cycle was warranted because no missing behaviour was identified. The only historical in-scope change (already on the branch) was documentation-only: a stale Javadoc `@param` describing a non-existent `escalationAfterMinutes` component was removed and the `escalationChannel` descriptions clarified.

## Decisions / notes for the integrator

- **No V37 migration.** The reserved version is unused: MSG-06 uses the tenant-scoped in-memory `ServiceProfileStore` adapter, matching sibling messaging slices S-G3-02/03/04/07/08 whose reserved migrations are also absent. `check-migrations.py --require-baseline` exits 0 because gaps are allowed. Do not fabricate a migration for this story. The store Javadoc records the G5 production gap (forced RLS, durable uniqueness, atomic outbox) for the eventual production adapter.
- **Time semantics are server-authoritative.** IANA zones are validated (fixed offsets rejected), DST gap/overlap resolution follows documented `java.time` rules, and the device only formats the stamped UTC instant for display; no wall-clock service-hours arithmetic is duplicated in the app.
- **Supervisor counters are derived, never stored**, from non-sensitive work facts; a failed notification is surfaced even for a resolved item because the patient may not have received the reply (documented in `SupervisorSnapshotCalculator`).
- This pass changes only `.agent/pr-notes.md` (the pull-request description). No tracker file was edited and no push, tag or `gh` call was made. Evidence sources are executed tool commands only; no simulated user, clinician or human-participant evidence was available for this slice.
