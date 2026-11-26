# FIX-JOBS-EXPORTS-TEST-COMPILE — hand-off notes

## Summary

Verification-only pass. The requested change — removing the two out-of-scope
`status.setRollbackOnly()` calls from the refusal helpers in the ARC-02 tenant
clause suite and correcting the accompanying comments — is **already present on
`origin/main`**. This branch has zero delta against `origin/main`, the working
tree is clean, and there is no production or test code for this run to change.
I re-proved test compilation, the targeted integration suite and Java
conventions from the current tree, and record the diagnosis and the one trusted
next step (the exact-SHA Delivery verdict) below.

No code, pom, threshold, migration, contract or tracker file was edited by this
run. The only file added is this hand-off note.

## Affected requirements

- **ARC-02** — "Jobs, exports, search, attachments and integrations apply the same tenant policy." The acceptance suite `JobsExportsSearchAttachmentsIntegrationsApplySameTest` could not compile because two refusal helpers referenced a `status` variable that does not exist inside the `Supplier` lambda passed to `as()`; `as()` only binds `TenantContext` (no transaction status is in scope). The suite is now green (5 tests).
- **OPS-06** — Delivery repair. The compile error broke the Java and database verification job of Delivery run 45337601399. Restoring test compilation unblocks that job for any head carrying the fix.

Acceptance mapping: story acceptance = (a) invalid statements removed and comments corrected, (b) test compilation succeeds, (c) the targeted suite passes. All three are observed on the current tree; the exact-SHA CI verdict is a trusted-job step recorded below.

## Risk class

Routine (docs/WORKFLOW.md): test-only compile repair, no production behavior, no data or contract change. No clinical sign-off required.

## Review owner

Senior Java build tooling (`aurevia-senior-java-build`), plus the mandatory quality-loop reviewers listed under Review verdicts.

## Data changes

None. No Flyway migration, backfill, lock or volume impact; no rollback or forward-fix needed.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope changed; no audit event added or changed. No real patient data, credential or secret is present in this note or in any fixture used by the commands below.

## Migration and rollout considerations

None. No migration version was reserved or used.

## Test evidence

Evidence source: executed tool commands (final lines shown) plus one prior CI log record; no human or real-user evidence.

- Branch and delta (tool):
  - `git rev-parse --abbrev-ref HEAD` → `swarm/FIX-JOBS-EXPORTS-TEST-COMPILE-1561`
  - `git status --porcelain=v1` → *(empty — clean tree)*
  - `git diff --name-only origin/main...HEAD` → *(empty)*
  - `git diff origin/main...HEAD -- apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java` → *(empty)*
  - Source read (tool): the two refusal helpers at lines ~388-419 no longer call `status.setRollbackOnly()`; both catch blocks read "The service owns its transaction and rolls it back on refusal; there is no outer transaction to mark rollback-only here." The `as()` helper at line 263 only binds and clears `TenantContext`.
- Red, prior CI (tool, GitHub Actions job log recorded in the task file): Delivery run 45337601399, Java and database verification job 138258325642 → `[ERROR] ... JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:[397,17] cannot find symbol: variable status` (and line 419). Not re-run here: worker has no token for `gh` job logs and the outcome is already recorded.
- Green (tool, this run):
  - `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → `EXIT: 0` (no compiler output).
  - `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → `EXIT: 0`. Surefire report `apps/api/target/surefire-reports/TEST-com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest.xml`: `tests="5" errors="0" skipped="0" failures="0" flakes="0"` (time 10.644 s). Note the trap in AGENTS.md was avoided: `-Pno-security` only, no `fast` profile, so `excludedGroups=integration` does not suppress the suite.
  - `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.077s / OK`, `EXIT: 0`.
- Not run, deliberately: `scripts/check.sh` / `apps/api/check.sh` (full `mvn verify` plus db checks) and `scripts/coverage-check.sh`. AGENTS.md forbids swarm workers from running them (over ten minutes, ends the run on timeout); `scripts/check.sh:26-34` delegates to `apps/api/check.sh`. The integrator and Delivery own the full gate. No frontend module is in scope, so no `check.sh` typecheck applies.

Entry-point-to-test trace: the ARC-02 clause is exercised through `JobsExportsSearchAttachmentsIntegrationsApplySameTest` (test entry point) → tenant-scoped `as()` helper binds `TenantContext` → production `TenantExportService` and the other four surfaces run under their own transactions → integration suite asserts identical tenant policy across all five surfaces. The compile break was in the two test helpers that catch the export refusals; removing the out-of-scope `status.setRollbackOnly()` calls restores compilation and the five assertions execute.

## Coverage

No production line, branch, method or class was changed by this run, so there are no new branches to map to tests and no threshold, exclusion or suppression was touched.

- Coverage command for the touched area (tool): `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → exit 0, Surefire `tests=5 errors=0 skipped=0 failures=0`. The five tests execute the fixed helpers (`exportDownloadRefusal`, `exportStartRefusal`) through the real `TenantExportService` under PostgreSQL RLS.
- Java conventions command (tool): `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.077s / OK`.
- Full JaCoCo / Vitest / Jest gate: trusted integrator responsibility on the merge result; deliberately not run by this worker (AGENTS.md). No coverage metric was fabricated or reported as pending-as-covered.

## Clinical and architecture review

Not required: test-only compile repair, no signed data, cross-organization sharing, patient association, prescribing or critical-result behavior changed.

## Review verdicts

- aurevia-code-reviewer: GO — no Critical/Required findings; confirmed no `setRollbackOnly` remains, comments accurate, fully-qualified `{@link}` targets, no production/contract change.
- aurevia-plan-reviewer: GO — no Required gap; verify-only plan correct, three green commands suffice, skip of `scripts/check.sh` correct, no wiring/docs run owed for a zero-delta test change.
- aurevia-coverage-auditor: GO — no Critical/Required findings; zero production delta, no new branches, no threshold/exclusion/skipped test; targeted suite runs all 5 `@Test` methods with `skipped="0"`.
- aurevia-devsecops: GO — no Critical/Required findings; tenant assertions unchanged, no credentials/PHI, no dependency or audit change; full OWASP/JaCoCo gates are trusted-job steps.

Prior reviewer evidence already on the task file (not re-run this pass): `tasks/01276000-FIX-JOBS-EXPORTS-TEST-COMPILE.md:37-38` records `aurevia-senior-java-build` GO and `aurevia-code-reviewer` GO (after the exact Java-conventions command was added). The four GO lines above are from this pass's read-only reviewers.

## Findings

- The compile fix already landed on `main` independently of this branch. Re-running the edit would produce an empty diff; the correct action is verification plus this hand-off note. This matches the task file's earlier final-tree finding (attempts 1-22).
- The misleading comments described in the story were also already corrected: both helpers now state that the service owns its transaction and there is no outer transaction to mark rollback-only. No stale "outer transaction" claim remains in the scoped file.
- Branch has **no PR-able code delta**: `git status --porcelain` empty, `git diff --name-only origin/main...HEAD` empty, scoped-file diff vs `origin/main` empty. This run adds only this note. Integrator note: close the task against `main` where the fix already landed rather than returning it from `[-]` to `[ ]` under the no-PR rule (`MEMORY.md:31`); a PR containing only this note carries no production or test fix.
- Only outstanding item is external to a worker: the exact-SHA Delivery verdict for `main` is still queued behind the runner backlog (task file `ci-status`, linked to task 01273000). Report it as **pending**, not green — local command exit codes are the only evidence produced in this pass. No `gh` call was made; the worker has no token and Git-backed publication is a trusted job.
- `scripts/check.sh` was not run for the documented worker-timeout reason; this is an environment/role constraint, not a gate failure.

## Lessons for workers

- Before re-editing a re-dispatched FIX story, run `git diff --name-only origin/main...HEAD` and read the scoped file. If the fix is already on `main`, verify it and write the hand-off note instead of manufacturing a diff. This story reached 22 attempts for exactly that reason.
- The `-Pno-security,fast` combination silently excludes `integration` tests (`fast` sets `excludedGroups=integration` in `apps/api/pom.xml` and wins over `-Dgroups`). Use `-Pno-security -Dgroups=integration` and confirm the Surefire report count.
- Do not run `scripts/check.sh`, `apps/api/check.sh` (full `mvn verify`), `scripts/coverage-check.sh` or `scripts/docs.sh` as a worker — the integrator owns them and a full verify exceeds the worker time budget.
- One clean verification pass is enough; another notes-only pass cannot clear a CI queue or a PR label (daily report 2026-11-22, lesson 55).

## Why this is not finished

This run's worker-scope work is complete (verification green, no code delta, hand-off recorded). The story cannot be marked `[x]` by a worker, and the exact-SHA Delivery verdict on `main` is still queued behind the runner backlog — that evidence belongs to the trusted integrator/Delivery job. Next step for the integrator: confirm the Delivery run for the exact merged `main` SHA is green, then mark the task `[x]` with that SHA and the green run link. No in-scope defect remains open.
