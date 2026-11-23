# FIX-STAFF-MAIL-FACILITY — Resolve staff mail role recipients by selected facility

## Summary
Story FIX-STAFF-MAIL-FACILITY (requirements MSG-02, ARC-07). Work started from branch
`swarm/FIX-STAFF-MAIL-FACILITY-1176` at HEAD `deea5ca0`, merged with `origin/main`
(already up to date). Synthetic fixtures only. Evidence provenance: tool (executed
commands) and simulated reviewers; no human, clinical or patient-participant evidence.

## Specification / acceptance mapping
- MSG-02 (`SMARTCLINIC_V1_SPEC.md:99`): role-scoped recipients must resolve for the
  selected facility at send time; confirmation before send.
- ARC-07 (`SMARTCLINIC_V1_SPEC.md:128`): staff access through membership, unit
  assignment and role; tenant-scoped transaction; durable publication.
- Acceptance: two facilities sharing a role produce different recipient sets;
  inactive staff and other organizations excluded; changing eligible holders between
  compose and send requires recipient reconfirmation (428/409 contract).

## Scope
- `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/mail`
- `apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/mail`
- `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/directory`
- `apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/directory`

## Brief correction
The advisor brief said to reuse `StaffDirectoryService.findFacility(String code)` directly for
the mail `facilityId`. That is wrong for this feature: `findFacility` resolves by
`facility.code` (`FacilityRepository.java:49-52`), while a mail draft carries the facility
**row id** (`MailPersistenceIntegrationTest.java:60-61,270-271` seeds `id=FACILITY_A`, `code='f'`
and composes with `FACILITY_A`; `JdbcInstantConversationStore.java:226-229` casts facilityId to a
uuid). Using `findFacility` would fail every real role send with 404. Per the prompt, the brief
is guidance and never changes the rules, so I follow the story and AGENTS.md: add an in-scope
`StaffDirectoryService.findFacilityById` (same `staffmsg/directory` scope) that resolves by
`FacilityRow.id()`, and feed `facility.code()` to the authoritative facility-scope fold.

The brief also named `DirectoryPolicy.requireSameOrganization` and
`AccessRepository.outsideAssignedFacilities` correctly; both are reused unchanged.

## Plan (revised after plan review)
1. RED first: rewrite `StaffRecipientDirectoryTest`; update
   `MailServiceTest.productionConstructorResolvesRolesThroughTheStaffRepository` to the new
   7-arg production constructor; record the draft facility and add the reconfirmation case in
   `MailControllerHttpTest`; add `findFacilityById` tests in `StaffDirectoryServiceTest`.
2. Add `StaffDirectoryService.findFacilityById(String id)` using the existing
   `FacilityRepository.query(null,null,MAX)` inside the tenant-guarded transaction, matching
   `FacilityRow.id()`; empty/blank id fails closed. Document the V93 legacy-member and
   oversight-role exception to facility narrowing.
3. Rewrite `StaffRecipientDirectory.holders`: fail closed on missing TenantContext (403),
   resolve facility by id (404 when absent), require same organization (403 via real
   `DirectoryPolicy`), query active staff once, keep rows with the role and whose candidate
   subject is not outside their assigned facility (`AccessRepository.outsideAssignedFacilities`),
   map subject + distinct. Keep all reads in one `transactions.execute`.
4. Update `MailService` `@Autowired` constructor to 7 args and its Javadoc; keep the 3-arg
   test constructor (used by three other test call sites).
5. GREEN: targeted Maven suite, per-class JaCoCo report, `check-wiring.py`, Python tooling
   suite, `worker-check.sh`; then the post-code specialist reviews.

`StaffDirectoryService.staff(facilityCode)` is deliberately NOT reused for expansion: it queries
all active staff and merely tags each entry with the browsed facility code — it applies no
facility filter, so it cannot produce different recipient sets for two facilities.

## Test evidence
Evidence provenance: **tool** (executed commands) unless labelled **simulated reviewer**.
All fixtures are synthetic; no patient data, credential or raw log is recorded here.

RED (before production change):
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffRecipientDirectoryTest,MailServiceTest,MailControllerHttpTest,StaffDirectoryHttpTest,StaffDirectoryServiceTest test`
- exit 1, `testCompile` failure: `cannot find symbol: method findFacilityById(String)`,
  `no suitable constructor found for MailService(Clock, StaffRepository, TransactionTemplate,
  StaffDirectoryService, AccessRepository, DirectoryPolicy, InMemoryMailStore)`,
  `constructor StaffRecipientDirectory ... cannot be applied to given types`.

GREEN (final code):
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffRecipientDirectoryTest,MailServiceTest,MailControllerHttpTest,StaffDirectoryHttpTest,StaffDirectoryServiceTest test` — exit 0.
  Surefire: StaffRecipientDirectoryTest 9, MailServiceTest 17, MailControllerHttpTest 7,
  StaffDirectoryHttpTest 2, StaffDirectoryServiceTest 10 — **45 tests, 0 failures, 0 errors, 0 skipped**.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffRecipientDirectoryTest,MailServiceTest,MailControllerHttpTest,StaffDirectoryHttpTest test` (exact brief set) — exit 0.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=MailPersistenceIntegrationTest test` — exit 0.
  Surefire: **5 tests, 0 failures, 0 errors, 0 skipped**, including
  `roleRecipientsAreScopedToTheSelectedFacilityThroughHttp` over real PostgreSQL, Flyway V1–V95,
  forced RLS and the real production `MailService`/directory wiring.
- `python3 -I scripts/check-wiring.py` — exit 0 (no new wiring violations; workers do not write the baseline).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0 (0 findings).
- `bash scripts/frontend-check.sh` — exit 0 (no frontend files changed; final line "frontend standards: policy and tool commands passed").
- `bash -n scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh` — OK; shellcheck available and clean.
- `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`,
  `check-constraints.py`, `check-migrations.py --require-baseline` — all exit 0.
- `python3 -I -m unittest discover -s scripts/tests` — exit 1, "Ran 1307 tests ... FAILED (failures=6)".
  All six are `test_check_pack` (e.g. `test_repository_pack_is_valid`: `["opencode.json is stale: run
  scripts/gen-opencode.py"] != []`). Known cloud-worker artifact OPS-CW-003-01: `git ls-files -v
  opencode.json` → `S`, the worktree file is the CI profile installed under `skip-worktree`, and the
  HEAD bytes are the generated local profile. Confirmed once; no protected file was edited or regenerated.
- `bash scripts/worker-check.sh` — exit 1 at its first stage, `check-pack: opencode.json is stale: run
  scripts/gen-opencode.py` (same OPS-CW-003-01). Remaining stages were run individually above; the
  full integration suite and full JaCoCo remain the integrator's gates (worker prompt forbids them).

Entry-point-to-test trace: `MailController.create` (`POST /api/v1/staff/mail/drafts`) stores the
validated `facilityId` on the draft → `MailController.send` (`POST .../drafts/{id}/send`) →
`MailService.send` → `expand` → `RecipientDirectory.holders(organizationId, draft.facilityId(), role)`
→ `StaffRecipientDirectory.holders` → `StaffDirectoryService.findFacilityById` → `FacilityRepository.query`
(forced RLS) → `DirectoryPolicy.requireSameOrganization` → `StaffRepository.query("active")` →
`AccessRepository.outsideAssignedFacilities(subject, facility.code())`. Proven by
`MailPersistenceIntegrationTest.roleRecipientsAreScopedToTheSelectedFacilityThroughHttp` (HTTP + DB)
and `MailControllerHttpTest.sendRequiresReconfirmationWhenFacilityHoldersChange` (standalone HTTP).

## Coverage
Command: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffRecipientDirectoryTest,MailServiceTest,MailControllerHttpTest,StaffDirectoryHttpTest,StaffDirectoryServiceTest clean test jacoco:report` — exit 0.
`apps/api/target/site/jacoco/jacoco.csv` for INSTRUCTION/BRANCH/LINE/COMPLEXITY/METHOD and
`jacoco.xml` for the CLASS counter (missed/covered):
- `StaffRecipientDirectory`: 0/105, **0/4**, 0/22, 0/9, 0/7, 0/1 — 100% on every counter.
- `StaffDirectoryService`: 0/175, **0/6**, 0/35, 0/17, 0/14, 0/1 — 100% on every counter.
- `MailService`: 0/963, **0/52**, 0/160, 0/47, 0/21, 0/1 — 100% on every counter.
- `MailController`: 0/227, 0/2, 0/42, 0/15, 0/14, 0/1 — 100% (unchanged, exercised via HTTP).

Branch-to-test map for the new/changed production code:
- `StaffRecipientDirectory.holders`: `TenantContext.current() == null` → 403 —
  `failsClosedWhenFacilityMissingOrContextAbsent` (null-context side); present — every other test.
- `findFacilityById(...).orElseThrow` empty → 404 `FACILITY_NOT_FOUND` —
  `failsClosedWhenFacilityMissingOrContextAbsent` (missing-facility side); present — all other tests.
- `DirectoryPolicy.requireSameOrganization` mismatch → 403 `CROSS_ORGANIZATION_MESSAGING_DISABLED` —
  `inactiveStaffAndOtherOrganizationsExcluded`; match — all successful cases.
- role predicate `roles().contains(role)` false → `nurse-a/reception` row in
  `expandsOnlyActiveHoldersOfTheRole`; true → `nurse-b`/`doctor-a` rows.
- facility fold `!outsideAssignedFacilities(subject, facility.code())` false (exclude) and true
  (include) — both sides in `twoFacilitiesSharingARoleReturnDifferentHolders`, which also asserts the
  fold is keyed by facility **code**, never the draft id; all-excluded empty result in
  `returnsEmptyWhenEveryHolderIsOutsideTheFacilityScope`.
- V93 legacy exception (unassigned holder stays in scope) — `unassignedLegacyHoldersRemainInScopeByTheAuthoritativeFold`.
- `distinct` — `deduplicatesHolders`; empty staff result — `returnsEmptyWhenNobodyHoldsRole`;
  single tenant transaction ordering — `resolvesTheFacilityInsideTheTenantTransaction` (InOrder).
- `StaffDirectoryService.findFacilityById`: id match — `findFacilityByIdResolvesTheTenantFacilityInsideTheGuardedTransaction`;
  id mismatch and null id — `findFacilityByIdFailsClosedForUnknownAndMissingIds`;
  missing context — `findFacilityByIdFailsClosedWithoutTenantContext`.
- `MailService` 7-arg `@Autowired` constructor body — `productionConstructorResolvesRolesThroughTheStaffRepository`
  (asserts a role draft resolves through the real `StaffRecipientDirectory`).
- 428/409 send contract — `MailControllerHttpTest.sendRequiresReconfirmationWhenFacilityHoldersChange`
  (409 `RECIPIENT_CONFIRMATION_MISMATCH` and facility assertion) plus existing confirmation tests.

No production guard was added that no input can trigger; no threshold, exclusion, `@Disabled`,
`.skip`, `xit` or coverage-ignore comment was added. The full bundle-level JaCoCo check is integrator-owned.

## Coverage repair — attempt 1 of 2 (2026-11-23T14:12Z)

Evidence provenance: **tool** (executed Maven commands and JaCoCo CSV) plus **git history**;
no human, clinical or patient-participant evidence.

- Reported failure was a **test error, not a coverage miss**: `bash scripts/coverage-check.sh`
  (full `mvn -Pno-security verify`, 5797 tests) exited 1 with
  `SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest.prepare:138->seedApprovedChannelPolicy:162
  ? PSQL ERROR: duplicate key value violates unique constraint
  "config_approval_organization_id_entry_id_key"`.
- Root cause (git history evidence): two ancestor commits seed the same approved-channel policy
  into the same test. `0fdc9ae3` added the seed inline in `seedOwningTenant()`; the later
  "repair" commit `7e6f5933` re-added it via the documented `seedApprovedChannelPolicy()`
  helper called from `prepare()`. Both run in `@BeforeAll`; the second `config_approval`
  `INSERT ... SELECT ... WHERE version = 1` selects the same version-1 `config_entry` already
  approved by the first, violating the unique constraint. Deterministic on every run.
- Fix (test-only, no production change): removed the redundant inline seed block from
  `seedOwningTenant()` and replaced it with a comment pointing to the single authoritative
  helper in `prepare()`. No threshold, exclusion, suppression, skip or lowered rule was added.
- RED confirmation: `mvn -q -B -f apps/api/pom.xml -Pno-security
  -Dtest=SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest test` — exit 1, 1 error,
  the exact duplicate-key error above.
- GREEN: same targeted command — exit 0, Surefire `tests="4" errors="0" failures="0" skipped="0"`.
- GREEN full gate: `bash scripts/coverage-check.sh origin/main` — exit 0, final line
  `coverage check: passed`. The tail warning `[ERROR] Surefire is going to kill self fork JVM.
  The exit has elapsed 30 seconds after System.exit(0).` is the Surefire shutdown notice, not a
  test failure; no `<<< ERROR`/`<<< FAILURE` line exists in the log and the gate reported passed.
- JaCoCo (`apps/api/target/site/jacoco/jacoco.csv`) after the full verify — every touched
  production class 0 missed: `StaffRecipientDirectory` instr 0/105, branch 0/4, line 0/22;
  `StaffDirectoryService` instr 0/175, branch 0/6, line 0/35; `MailService` instr 0/963,
  branch 0/52, line 0/160; `MailController` instr 0/227, branch 0/2, line 0/42.

Branch-to-test list (unchanged — the repair touched test setup only, so every production
line and branch still has its named test from the `## Coverage` section above):
- `StaffRecipientDirectory.holders` missing TenantContext (403) → `failsClosedWhenFacilityMissingOrContextAbsent`
  (null side); context present → every other test.
- `findFacilityById(...).orElseThrow` empty (404) → `failsClosedWhenFacilityMissingOrContextAbsent`;
  present → all other tests.
- `DirectoryPolicy.requireSameOrganization` mismatch (403) → `inactiveStaffAndOtherOrganizationsExcluded`;
  match → all successful cases.
- role predicate `roles().contains(role)` false → `nurse-a/reception` row in
  `expandsOnlyActiveHoldersOfTheRole`; true → `nurse-b`/`doctor-a` rows.
- facility fold `outsideAssignedFacilities` false/true → both sides in
  `twoFacilitiesSharingARoleReturnDifferentHolders`; all-excluded empty in
  `returnsEmptyWhenEveryHolderIsOutsideTheFacilityScope`; V93 legacy exception in
  `unassignedLegacyHoldersRemainInScopeByTheAuthoritativeFold`.
- `distinct` → `deduplicatesHolders`; empty staff → `returnsEmptyWhenNobodyHoldsRole`;
  single-transaction ordering → `resolvesTheFacilityInsideTheTenantTransaction` (InOrder).
- `StaffDirectoryService.findFacilityById` id match → `findFacilityByIdResolvesTheTenantFacilityInsideTheGuardedTransaction`;
  mismatch and null → `findFacilityByIdFailsClosedForUnknownAndMissingIds`; missing context →
  `findFacilityByIdFailsClosedWithoutTenantContext`.
- `MailService` 7-arg constructor → `productionConstructorResolvesRolesThroughTheStaffRepository`.
- 428/409 send contract → `MailControllerHttpTest.sendRequiresReconfirmationWhenFacilityHoldersChange`
  plus existing confirmation tests.
- The repaired `SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest` is a test fixture,
  not production code: its four `@Test` methods pass over real PostgreSQL, Flyway V1–V95 and
  forced RLS after the fixture fix; no branch-to-test obligation was added. Also, per the
  AGENTS.md "known false positives", `mvn -Pno-security,fast -Dgroups=integration` does not run
  integration tests — this repair used the unmodified `verify` gate instead.

## Findings
- Brief correction (recorded above): the advisor's `findFacility(String code)` reuse is wrong for
  mail's facility row id; resolved in scope with `findFacilityById` and recorded in this note.
- V93 fold semantics: unassigned legacy members remain organization-wide and oversight roles are
  exempt. Documented in `StaffRecipientDirectory` Javadoc and asserted by
  `unassignedLegacyHoldersRemainInScopeByTheAuthoritativeFold`; no second membership rule added.
- Environment only (tool evidence, not change defects): `worker-check.sh` and six
  `test_check_pack` cases fail on the OPS-CW-003-01 CI-profile `opencode.json`; OWASP is skipped in
  workers. The integrator owns these gates.
- Optional (senior backend): `holders` loads active staff and the fold is evaluated per role holder;
  `findFacilityById` scans tenant facilities. Both mirror existing bounded, RLS-scoped directory
  patterns and are not introduced by this fix; a SQL-side fold or `FacilityRepository.findById` is a
  follow-up, not a blocker.
- Optional (devsecops): SpotBugs/OWASP tool versions were not captured in worker evidence.
- Optional (code reviewer): `NO_RECIPIENT` checks the unexpanded `to`/`cc`, so a role resolving to
  zero holders and an explicitly empty confirmation can publish an empty recipient set. Pre-existing
  send behavior outside this story's acceptance; consider a follow-up.
- Optional (code reviewer): each ROLE recipient repeats the facility scan, active-staff query and
  fold; bounded and RLS-scoped, correct as specified. A SQL-side fold is a follow-up.
- Optional (spring security): mail send still does not call `ActiveStaffGuard` or
  `CrossFacilityMessagingGuard`, and PERSON recipients bypass the directory. Pre-existing and
  explicitly assigned to `AUD-20261122-07`; this change only narrows the candidate set.
- Optional (spring security): document on `RecipientDirectory` that the organization parameter is
  informational and the bound context is authoritative, to prevent future client-body misuse.
- No Critical or Required reviewer finding remains; no suppression, exclusion, skipped test or
  lowered threshold was added.

## Review verdicts
- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO
- smartclinic-senior-spring-security: GO
- smartclinic-senior-backend: GO
Plan review needed one revision, which closed all seven Required gaps. Coverage and code review
needed one re-check each because they read a stale notes snapshot before `## Test evidence` and
`## Coverage` were written; both returned GO against the completed file.

## Lessons for workers
- A mail draft carries the facility **row id**, not the tenant-local code. Resolving it with
  `StaffDirectoryService.findFacility(code)` silently fails every real role send with 404; resolve
  by `FacilityRow.id()` first and feed `facility.code()` to the scope fold.
- `StaffDirectoryService.staff(facilityCode)` is not a facility filter: it tags active staff with
  the browsed code without narrowing the set. Reuse `findFacilityById`/`DirectoryPolicy`/
  `AccessRepository.outsideAssignedFacilities` instead when the recipient set must differ by facility.
- V93 deliberately keeps unassigned legacy members organization-wide and exempts oversight roles;
  document and test that exception instead of inventing a second membership rule.
- In a cloud worker `opencode.json is stale` is the CI profile under `skip-worktree` (OPS-CW-003-01).
  Confirm once with `git ls-files -v opencode.json` → `S`, run later gate stages individually, and
  never regenerate or commit that protected file.
- The worker's `python3 -I -m unittest discover -s scripts/tests` has six known `test_check_pack`
  failures from the same profile; report the command and final line once and move on.
- On real PostgreSQL, seeding `role_assignment` requires `app.actor` to be an authenticated user of
  the active tenant; set both `app.current_org` and `app.actor` in the fixture before the insert.
- `verifyNoInteractions(mock)` fails if an earlier successful call in the same test already used that
  mock; assert an exact invocation count after the denied call instead.

