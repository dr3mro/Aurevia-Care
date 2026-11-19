# S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance

# S-G3-06 — PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access

## Story and requirements

- Story: S-G3-06 (swarm fix attempt 1 of 3), branch `swarm/S-G3-06-90`, base SHA `7cd80d6` (contains the verified Doxygen fix). This delivery commit adds the PR-notes artifact.
- Primary requirements: **MSG-03** (attachments, explicit send, recipient shown before send, quarantine scan, clean-verdict access, sender binding, removal/replacement), **ARC-06** (validate size/type/checksum, private quarantine, unique object keys and verified object version, mediated downloads, keep clinical payloads out of logs/URLs).
- Supporting requirements exercised by the implementation: MSG-01 / ARC-07 (connection-scoped binding), IAM-01 (fresh actor snapshot every command), ARC-02 (tenant-scoped store).

## Failure being fixed (defect)

The fast merge gate (`scripts/integrator-gate.sh`) failed on the merge of `main` and this branch. Relevant log tail:

```
apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java:338: error: argument 'organizationId' from the argument list of com.aurevia.smartclinic.messaging.s_g3_06.AttachmentContract.Actor has multiple @param documentation sections
... same for 'subject', 'roles', 'patientId', 'unitId'
```

This is a Doxygen error (`docs/Doxyfile`: `WARN_NO_PARAMDOC = YES`, `WARN_AS_ERROR = FAIL_ON_WARNINGS`, run by `scripts/docs.sh`). Root cause: the `Actor` record repeated its five component `@param` tags on the record declaration **and** on its compact constructor and its two static factory methods. Doxygen folds a record's compact constructor and factory methods into a single canonical-constructor documentation entity, so each component appeared in more than one `@param` documentation section. The same pattern existed on the `View` and `PublishRequest` compact constructors.

## Fix

Document each record component exactly once, on the record declaration, and carry **no** `@param` tags on compact constructors or static factory methods (they keep only `@return`/prose). This satisfies `WARN_NO_PARAMDOC` without producing duplicate sections. The change is present at branch HEAD commit `7cd80d6` in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06/AttachmentContract.java` (`Actor`, `View`, `PublishRequest`). No further source change was needed; this attempt verifies and records the fix.

## Red → green evidence (synthetic, tool-generated)

- RED reproduction: a minimal synthetic record `/tmp/doxrepro/src/Repro.java` with `@param` on both the record declaration and its compact constructor/factory, checked with the repository `docs/Doxyfile` (doxygen 1.9.4), reproduced the exact failure:
  `error: argument 'organizationId' from the argument list of repro.Repro.Actor has multiple @param documentation sections` (exit 1).
- GREEN verification: the same minimal record with component `@param` only on the record declaration → doxygen exit 0, no warnings.
- GREEN verification of the actual scope: a Doxyfile with `INPUT = apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_06` and `WARN_AS_ERROR = FAIL_ON_WARNINGS` over the current files → exit 0, zero warnings/errors.
- `grep -n "@param organizationId" .../AttachmentContract.java` shows one declaration-level tag per record; no constructor/factory duplicates remain.

## Exact commands run and results

| Command | Result |
|---|---|
| `doxygen docs/Doxyfile` (focused Doxyfile, scope package only) | exit 0, 0 warnings/errors |
| minimal synthetic duplicate-`@param` record + doxygen | exit 1, exact "multiple @param documentation sections" error (reproduction) |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0; 297 tests, 0 failures/errors/skipped; `AttachmentAcceptanceTest` 28 tests OK; BUILD SUCCESS |
| `cd apps/patient-mobile && npm ci --no-audit --no-fund` | exit 0 (1272 packages) |
| `cd apps/patient-mobile && npm run typecheck && npm run lint && npm test -- --ci --runInBand` | exit 0; tsc clean, eslint clean; Jest 1 suite / 22 tests passed |

Pre-existing/environmental, out of scope (not caused by this change, not modified):
- `python3 -I -m unittest discover -s scripts/tests -q` → 162 tests, 6 failures / 4 errors. Failures include `test_repository_pack_is_valid`, `test_committed_opencode_json_is_generated`, `test_check_mode_detects_drift` (`opencode.json is stale`), `test_agent_run.*` (environment-specific git refs) and import errors for `test_fast_tier` / `test_workflow_concurrency`. The stale `opencode.json` is the documented cloud-worker CI-profile/skip-worktree artifact; the rest are unrelated to S-G3-06.
- Full `doxygen docs/Doxyfile` locally reports one error outside this scope: `apps/api/src/main/java/com/aurevia/smartclinic/g1/s_g1_04/TemplateService.java:23: unable to resolve link ... Actor for \link command`. Local doxygen is 1.9.4 (CI installs via apt); this is outside the declared scope and was left untouched.
- No local `main` ref is present, so the exact "merge of main and this branch" could not be reproduced locally; the integrator's failure log above is the authoritative red evidence. The integrator runs the database and integration tests; per worker rules only the fast unit tests were run here.

## Acceptance mapping (requirement → evidence)

- MSG-03: validate type/magic/size/checksum (`AttachmentInputs`, `FileType`), bind upload to a server-side verified connection grant and subject (`AttachmentService.upload`), explicit send of named clean files to one recipient unit (`publish`), quarantine verdict gating (`scan`), clean-verdict-only mediated downloads (`download`), removal/replacement (`remove`), and unit inbox of published patient-supplied material (`inbox`). Evidence: `AttachmentAcceptanceTest` 28 tests (Java fast tier), patient-mobile `attachments.test.tsx` 22 tests.
- ARC-06: private quarantine state machine, unique versioned object key, mediated handle carrying no bytes/file name/URL, and non-sensitive append-only event facts (`AttachmentContract`, `AttachmentService`, `AttachmentStore`). Evidence: same suites.
- Doxygen/doc gate: component `@param` documented once on each record declaration; scope docs build clean (commands above).

## Decisions

- Keep component documentation on the record declaration; compact constructors and static factories must not repeat `@param` (Doxygen folds them into one canonical-constructor entity). This is the minimal fix that satisfies both `WARN_NO_PARAMDOC` and the no-duplicate rule.
- No tracker, workflow, script, migration or out-of-scope source file was edited; only the declared scope was reviewed.

## Evidence provenance

All results are from executed tools (Doxygen 1.9.4, Maven/Surefire, npm/tsc/eslint/Jest, Python unittest) on synthetic fixtures. No real patient data, credentials or raw logs are recorded. No human participant or authorized clinical feedback was used; the only review input was the integrator's automated gate log. Database and integration checks are the integrator's responsibility and were not run here.

---

# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Scope honoured

Only the declared scope was available for edits:
`apps/api/src/main/java/com/aurevia/smartclinic/g0/s_g0_08`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g0_08`, and reserved migration `V11`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) and no file outside that scope was edited. No push, tag or `gh` call
was made. Synthetic fixtures only.

## Requirement IDs and acceptance mapping

- **IAM-06** (primary): emergency access is time-limited (1–60 min), scoped to one
  tenant-local facility, reasoned, attributed to a qualified clinician, audited on the
  V3 append-only chain, independently reviewable by privacy auditor / tenant admin
  (grantee cannot review own grant), and revocable early. Platform support access is
  **off by default** and only effective inside an approved, time-bounded (1–240 min)
  incident window; checks return an explicit fail-closed `AccessDecision`.
- **ARC-02 / OPS-02** (supporting): V11 tables carry `(organization_id, id)` keys and
  forced row-level security; purpose, role, facility, actor and timestamp are recorded
  and committed with the audit event.
- Delivered surfaces already present in scope (unchanged by this fix attempt):
  `AccessController` (8 endpoints under the IAM route family), `EmergencyAccessService`,
  `SupportAccessService`, `AccessRepository`, `AccessRules`, `AccessContracts`,
  `V11__emergency_and_support_access.sql`, `AccessUnitTest` (41 tests),
  `AccessIntegrationTest` (8 DB tests, integrator-run).

## Reported failure — investigation and disposition

The integrator's "requested change" reported a `scripts/check.sh` failure whose only
evidence was this stack trace:

```
Error: useTheme must be used inside ThemeProvider
    at useTheme (.../apps/staff-web/src/theme/ThemeProvider.tsx:39:11)
    at Consumer (.../apps/staff-web/tests/theme/ThemeProvider.test.tsx:6:32)
```

Findings:

1. **Out of scope.** The error is in `apps/staff-web`, which is outside this task's
   declared scope (`apps/api/.../s_g0_08` + V11). It cannot be fixed from this branch
   without violating the task rules, and no in-scope file can cause or cure it — the
   staff-web tests use local synthetic fixtures and make no API calls.
2. **Not an actual test failure.** The identical stack trace was reproduced locally.
   It is the expected stderr output of the intentional negative-path test
   `throws when used outside its provider` (`ThemeProvider.test.tsx:31-35`), which
   asserts `expect(() => render(<Consumer />)).toThrow(/useTheme/)`. React 18 dev mode
   logs the guarded-callback rethrow through jsdom's `reportException`; the test still
   catches it and passes. `npx vitest run tests/theme/ThemeProvider.test.tsx` → 3/3
   passed; the full `npm test` suite → **103/103 passed, exit 0**. The pasted text is a
   benign log line, not a build failure.
3. **Merge not reproducible locally.** The checkout is a grafted single-commit shallow
   clone (`git log --all` shows only `bb355f1`; `.git/shallow` present; no local `main`
   ref), so "the merge of main and this branch" cannot be reconstructed here. This is
   an evidence gap, not a code defect.

No in-scope defect was found; no in-scope change was needed for a fix attempt.

## Exact commands run and results

| Command | Result |
|---|---|
| `git status --short` / `git branch --show-current` / `git log -1` | clean tree; branch `swarm/S-G0-08-67`; HEAD `bb355f1` |
| `git show --stat HEAD` / `find` scope dirs / `ls db/migrations` | full S-G0-08 implementation in scope; V11 present |
| `bash scripts/quick-check.sh` | exit 1 **only** at `check-pack: opencode.json is stale` — environment artifact (see below); all preceding policy checks passed |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree); worktree file is CI profile (`wt == generated CI == True`, `committed == generated LOCAL == True`) — documented cloud-worker CI-profile trap, not a branch defect |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; 231 tests, 0 failures, 0 errors; `AccessUnitTest` 41/41 |
| `cd apps/staff-web && npm ci && npx vitest run tests/theme/ThemeProvider.test.tsx` | **exit 0**; 3/3 passed (error text reproduced as benign stderr) |
| `cd apps/staff-web && npm test` | **exit 0**; 23 files, 103/103 passed |

Environment note: `check-pack.py` in this worker reads the skip-worktree CI-profile
`opencode.json`, so it reports it stale; the committed `opencode.json` matches the
generated local profile and is correct. Java/Database integration tests are left to
the integrator as directed.

## Defects found

- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.

## Decision / stop notice

Per the task rule "if a decision or evidence is missing, stop and say so in
`.agent/pr-notes.md`": the merge-gate failure cannot be reproduced from this shallow
clone, the only supplied evidence is a benign log line from a passing out-of-scope
test, and there is no in-scope defect to fix. I therefore made **no code change** and
stop, committing only these notes. If the integrator still observes a non-zero
`scripts/check.sh` on the merge, the failing step and its exit code must be supplied;
if it is a genuine staff-web regression it belongs to a staff-web-scoped worker, not
this IAM-06 backend slice.

---

# S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering

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
