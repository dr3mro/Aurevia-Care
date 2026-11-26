# FIX-JOBS-EXPORTS-TEST-COMPILE — worker hand-off

Task 01276000 · requirements ARC-02, OPS-06 · notes file `.agents/notes/FIX-JOBS-EXPORTS-TEST-COMPILE-20261125-002155.md`

## Summary

PR 1613's Java and database verification failed with `cannot find symbol: variable status` at
`apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:397,419`.
The current working tree no longer contains those two out-of-scope `status.setRollbackOnly()` calls:
`exportDownloadRefusal` (around lines 378–400) and `exportStartRefusal` (around lines 402–420) now
catch `ApiException` and return it directly, and both Javadoc comments correctly describe the
service-owned transaction instead of claiming a non-existent outer transaction. `as()` only binds
`TenantContext` around a `Supplier` (lines 262–270), so no `status` was ever in scope.

I made **no source edit**. The equivalent removal landed on `main` independently before this
resumed run (already recorded in the task entry and WORKSPACE.md), so any further edit would be a
no-op or a fabricated delta. I re-verified the current tree with test compilation, the targeted
integration suite and the Java conventions checks; all are green.

Owner durable directive in `.opencode/config/MEMORY.md` ("dont run any script that use git")
prohibits Git commands and Git-backed operations. No `git`, `gh`, tracker edit, commit, push or
tag was run in this pass; `scripts/coverage-check.sh` was not run because it calls `git diff`.
Publishing and exact-SHA CI confirmation belong to the trusted integrator.

## Affected requirements

- **ARC-02**: "Jobs, exports, search, attachments and integrations apply the same policy"; the
  repaired suite is the acceptance evidence for that clause. The repair removes only invalid test
  helper statements — the tenant-isolation assertions themselves are unchanged.
- **OPS-06**: synthetic fixtures only; CI policy checks and the targeted verification below are
  the evidence. No real patient data, credential or identifier appears in any note or fixture.

## Risk class

Routine — test-only compilation repair. No production path, API contract, migration or policy changed.

## Review owner

Swarm integrator (trusted publisher). No clinical sign-off is needed for a test-helper compile repair.

## Data changes

None. No Flyway migration, backfill, lock or volume impact; no rollback or forward fix.

## Authorization boundaries and audit events

None changed. The suite still asserts fail-closed 401/422 on missing subject or organization and
uniform 404 or empty results when a synthetic tenant tries to use a peer's record.

## Migration and rollout considerations

None. No schema or configuration change; no rollout or deployment action.

## Test evidence

All commands were executed in this pass from the repository root. Evidence source: executed tool
commands (no simulated user, no human feedback).

1. `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → `EXIT=0` (Java test compilation green).
2. `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test`
   → `EXIT=0`; Surefire report
   `apps/api/target/surefire-reports/com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest.txt`:
   `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.50 s`.
   (The `fast` profile was deliberately not used: it excludes `integration` and would give a false green.)
3. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q`
   → `Ran 2 tests in 0.099s / OK / EXIT=0`.

Entry point to test trace: JUnit `@SpringBootTest` with `RANDOM_PORT` and the `synthetic-auth`
profile boots the real Spring context (`com.aurevia.AureviaApiApplication`) against a disposable
PostgreSQL database created and migrated by `@BeforeAll`. HTTP requests go through
`RestClient` to the real controllers; `TenantExportService.download`/`start` are invoked directly,
with the service itself opening the tenant-guarded transaction, and the repaired helpers capture
the resulting `ApiException` for assertions in `peerTenantCannotUseJobsExportsSearchAttachmentsOrIntegrations`,
`staleAndExpiredStatesAreRefused` and `wrongRoleCannotStartAnExport`. The five `@Test` methods are
`allFiveSurfacesApplyTheSameTenantPolicy`, `peerTenantCannotUseJobsExportsSearchAttachmentsOrIntegrations`,
`missingSubjectOrOrganizationIsRefusedOnEverySurface`, `wrongRoleCannotStartAnExport` and
`staleAndExpiredStatesAreRefused`. The suite drops its database in `@AfterAll`.

Red evidence from the original CI failure is carried in task 01276000: Delivery run 45337601399,
Java and database verification job 138258325642,
`[ERROR] ... JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:[397,17] cannot find symbol: variable status`
(and line 419).

## Coverage

No production file was touched by me, so there is no new production line, branch, method or class
to cover and no Java JaCoCo delta to attribute. The touched test file has no JaCoCo production
coverage obligation; its five tests were not skipped, weakened or newly excluded.

- Coverage command for the touched area (targeted suite, not full verify): command 2 above;
  result `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.
- New branches added: none, so there is no branch-to-test mapping to list. The two repaired
  helpers are exercised as follows: `exportDownloadRefusal` by
  `peerTenantCannotUseJobsExportsSearchAttachmentsOrIntegrations` and
  `staleAndExpiredStatesAreRefused`; `exportStartRefusal` by `wrongRoleCannotStartAnExport`.
- Thresholds, exclusions and suppression comments were not changed. `scripts/coverage-check.sh`
  was not run (it invokes `git diff`, forbidden by the owner directive).

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- The compile fix is already in the tree; this pass contributes verification and diagnosis only,
  not a code delta. Any worker re-dispatched on this story should read the current file before
  editing: the invalid `status.setRollbackOnly()` calls and the incorrect comments are gone.
- The original break came from a merge where main's superset retained the helper lines while an
  outer transaction wrapper had been removed, leaving `status` out of scope (see WORKSPACE.md).
- No Git command, `gh` call, tracker edit, commit or push was performed: the owner's no-Git
  directive (MEMORY.md) takes precedence over the advisor brief's merge/commit instructions,
  which are guidance only. The task file already carries prior evidence and reviewer verdicts;
  the integrator owns the `[x]` mark and publication.
- The advisor brief named no read-only specialist (empty list) and referred to reviewers with an
  `aurevia-` prefix that the subagent tool does not accept; the registered short agent names
  (`code-reviewer`, `coverage-auditor`, `devsecops`) were used instead. Otherwise the brief matched
  the story.
- Reviewer findings were Optional only (no Critical or Required): the task file's line 27 still
  says "no reviewer verdict yet" although later lines record verdicts (tracker text, owned by the
  integrator and not edited by me); the inline comments at test lines 395–396 and 415–416 partly
  restate the Javadoc but are accurate; and `TenantExportService.authorize()` appends an
  `EXPORT_DENIED` audit entry immediately before throwing its 403 inside the service transaction,
  so the RuntimeException rolls that entry back. The last item is pre-existing production behavior
  outside this test-only scope; it is recorded here for the owning production story, not changed
  here.
- Evidence mix: executed tool commands for compile, targeted integration suite and Java
  conventions; read-only reviewer verdicts listed above; no simulated user walkthrough and no
  real human or patient feedback.

## Lessons for workers

- On a re-dispatched repair, inspect the current file first. If the defect is already absent and
  the targeted green check passes, record the verification and stop rather than inventing a delta.
- Under the owner's no-Git directive, scan a script for `git` before running it:
  `scripts/coverage-check.sh` calls `git diff`, so it is off-limits. Targeted `-Dtest=...` runs and
  standalone Python unittest discovery are safe here.
- Reviewer subagents are registered under short names (`code-reviewer`, `coverage-auditor`,
  `devsecops`), not the `aurevia-`-prefixed skill/agent labels used in prose.
