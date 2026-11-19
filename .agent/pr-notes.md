# S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance

## Requirement IDs and acceptance mapping

Primary: **MSG-06**. Supporting: **ADM-01** (named approval, version history), **ARC-01/ARC-02/ARC-03** (tenant-owned keys, isolation, version precondition), **OPS-02** (audit, no PHI), **UX-01** (en/ar, RTL, IANA time zone).

Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-06, §6 ADM-01, §7 ARC-01..03.

| MSG-06 acceptance behaviour | Implementation (in scope) | Test evidence (synthetic only) |
|---|---|---|
| Each enabled unit publishes service hours, response targets, approved topics and urgent contact instructions | `ServiceProfileService.publish` + `ServiceProfileInputs` (zone/window/topic/guidance validation); `ServiceProfile` record carries `hours`, `responseTargetMinutes`, `topics`, `urgentInstructions`, `urgentChannel`, `downtimeChannel` | `ServiceProfileServiceTest`, `ServiceProfileDomainTest`, `ServiceProfileControllerTest` |
| Published guidance is named-approved, versioned and only effective at its effective date | `ServiceProfileService.approve` (distinct named approver + version precondition); `effectiveAt`; immutable `ProfileHistory` | `ServiceProfileServiceTest`, `ServiceProfileDomainTest` |
| Show guidance before sending; outside hours show when the unit next handles messages | `ServiceAvailability` from `ServiceHoursCalculator` (IANA zone, DST-safe, next handling instant); mobile `noticeLines` / `ServiceProfileNotice` render urgent guidance and channels first, then next-handling banner | `ServiceHoursCalculatorTest`, `ServiceProfileNotice` tests in `index.test.tsx` |
| Require a named queue owner, backup coverage and overdue escalation | `queueOwner`, `backupCoverage`, `escalationChannel` are required validated publish fields; `urgentChannel`/`downtimeChannel` for time-sensitive care (site-approved keys, never clinical values) | `ServiceProfileDomainTest`, `ServiceProfileServiceTest` |
| Surface backlog, unassigned work, failed notifications and overdue responses to unit supervisors | `SupervisorSnapshotCalculator.compute` + `ServiceProfileService.supervisorSnapshot` (SUPERVISOR_VIEW restricted to an assigned unit) | `SupervisorSnapshotCalculatorTest`, `ServiceProfileServiceTest` |
| Tenant isolation, fail-closed authorization, audit | Every store call carries the organization; `authorize`/`requireUnitAccess` re-derive membership, role and unit connection per request; all reads/denials appended to `MessagingAudit` | `ServiceProfileServiceTest`, `InMemoryServiceProfileStoreTest` |

Entry-point-to-test trace:
- `GET /api/v1/care-units/{unitId}/service-profile` → `ServiceProfileController.view` → `ServiceProfileService.viewProfile` → `authorize`+`requireUnitAccess` → `ServiceProfileStore.effectiveProfile` → audit → 200. Covered by `ServiceProfileControllerTest` and `ServiceProfileServiceTest`.
- `GET .../{unitId}/availability` → `availability` → `ServiceHoursCalculator.calculate` → 200. Covered by `ServiceHoursCalculatorTest`.
- `GET .../{unitId}/supervisor-snapshot` → `supervisorSnapshot` → `SupervisorSnapshotCalculator.compute` → 200. Covered by `SupervisorSnapshotCalculatorTest`.
- `POST .../{unitId}/service-profiles` + `POST .../{unitId}/service-profiles/{profileId}/approve` → `publish`/`approve` (If-Match version precondition) → guarded store → audit. Covered by `ServiceProfileControllerTest` and `ServiceProfileServiceTest`.
- Mobile: `createSyntheticServiceInfoClient.notice` → `noticeLines` → `<ServiceProfileNotice>` → testIDs asserted in `apps/patient-mobile/src/features/s_g3_09/index.test.tsx`.

## Scope and prior state

The complete in-scope implementation (9 Java main files, 6 Java test files, 4 mobile files) was already committed at the shard base `da4fbb4` from earlier attempts; this worker did **not** rewrite it. I reviewed every in-scope source and test file against MSG-06 and confirmed the behaviour above is implemented and covered. This worker's scoped source change is one documentation defect fix (below); the rest of the contribution is verification evidence.

## Commands run and results (evidence source: executed tool commands; synthetic fixtures only)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0** (run twice: before and after the source fix). The exact Java command in `scripts/quick-check.sh`. In-scope Surefire suites: `InMemoryServiceProfileStoreTest` 3, `ServiceHoursCalculatorTest` 13, `ServiceProfileControllerTest` 6, `ServiceProfileDomainTest` 13, `ServiceProfileServiceTest` 23, `SupervisorSnapshotCalculatorTest` 5 = **63 tests, 0 errors, 0 failures, 0 skipped**.
- `cd apps/patient-mobile && npm ci --no-audit --no-fund` → EXIT 0 (1272 packages).
- `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0.
- `npm run test:coverage -- --ci` → **EXIT 0**. **230 tests / 25 suites passed.** Coverage for `s_g3_09`: `ServiceProfileNotice.tsx` 100% stmts/branch/funcs/lines, `serviceNotice.ts` 100% stmts/branch/funcs/lines (`types.ts` is interface-only, 0% like every sibling feature and excluded from behavioural coverage).
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0.

Java JaCoCo 100% line/branch/method/class and the real-PostgreSQL/integration gate are enforced by `mvn verify` / `db/check.sh`, which the **integrator** runs; I did not run them and did not fabricate a Java coverage number.

## Defects found and fixed (in scope)

1. **Stale Javadoc `@param escalationAfterMinutes`** in `ServiceProfileContract.PublishProfileRequest` documented a record component that does not exist (overdue escalation is represented by `escalationChannel` plus the response target used by `SupervisorSnapshotCalculator`). Removed the stale tag and clarified the three `escalationChannel` `@param` descriptions to "site-approved channel key for overdue escalation". Documentation-only; no behaviour change. All fast tests re-run green after the fix.

No functional defect was found in the pre-existing implementation. No PHI, real identifiers or credentials appear in fixtures, code or these notes.

## Decisions / notes for the integrator

- **No V37 migration.** The reserved version was not used: MSG-06 messaging stories in this slice use the tenant-scoped in-memory `ServiceProfileStore` adapter, matching siblings S-G3-02/03/04/07/08 (their reserved migrations are also absent). The store Javadoc records the G5 production gap (forced RLS, durable uniqueness, atomic outbox). Do not fabricate a migration for this story.
- The fast checks above are green on this branch. The full `scripts/quick-check.sh` is expected to fail here for two environmental reasons unrelated to this story: (a) worktree `opencode.json` is the CI profile (SHA `279aa1e7…`, skip-worktree flag `S`, equals `.github/opencode.ci.json`) while `git show HEAD:opencode.json` is the local profile (`14bba0da…`), so `gen-opencode.py --check` reports stale — the documented OPS-CW-003-01 cloud-worker trap; I did not overwrite it; (b) `check-migrations.py --require-baseline` sees only this shard's subset of migrations, so the V9+ contiguity check fails until merged with `main`. Both resolve on the integrator's merge result.
- I did not push, tag or call `gh`. Evidence sources are tool commands only; no simulated user, clinician or human-participant evidence was available for this slice.
- Commit contains the scoped Javadoc fix plus the ignored `.agent/pr-notes.md` (force-added) used as the pull-request description.
