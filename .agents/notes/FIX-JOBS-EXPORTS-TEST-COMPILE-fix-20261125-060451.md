# FIX-JOBS-EXPORTS-TEST-COMPILE — fix-pass hand-off notes

## Summary

Verification-only fix pass. The requested repair — removing the two out-of-scope
`status.setRollbackOnly()` calls from the refusal helpers in the ARC-02 tenant
clause suite and correcting the comments that claimed a nonexistent outer
transaction — is **already present on `origin/main`**. This branch has no
production or test code delta; re-editing the fix would produce an empty diff.
I re-proved test compilation, the targeted integration suite and the Java
conventions policy from the current tree, obtained four read-only GO verdicts,
and record the one trusted next step (the exact-SHA Delivery verdict) below.

No production Java, pom, threshold, migration, contract, workflow, script or
tracker file was edited by this run. The only file added is this hand-off note.
For the full diagnosis, command list and earlier GO verdicts see the preceding
note `.agents/notes/FIX-JOBS-EXPORTS-TEST-COMPILE-20261125-003715.md`; this note
is a pointer plus this pass's fresh evidence, not a verbatim repeat.

Loaded skills (skill ID — matched task clause):

- `aurevia-delivery` — every story runs the delivery loop; swarm-worker mode applies.
- `aurevia-senior-java-build` — Java test compilation and JUnit/JaCoCo tooling are the story's domain.
- `aurevia-test-engineer` — the story is a failing-compile repair proved by a targeted test suite.
- `aurevia-plan-reviewer` — mandatory quality loop before any code.
- `aurevia-code-reviewer` — mandatory quality loop after the change.
- `aurevia-coverage-auditor` — mandatory 100% coverage evidence check.
- `aurevia-devsecops` — mandatory defensive security verdict.

## Affected requirements

- **ARC-02** — "Jobs, exports, search, attachments and integrations apply the same tenant policy." The acceptance suite `JobsExportsSearchAttachmentsIntegrationsApplySameTest` previously failed to compile because two refusal helpers referenced a `status` variable not in scope inside the `Supplier` passed to `as()` (which only binds `TenantContext`). The suite is green (5 tests) on the current tree.
- **OPS-06** — Delivery repair. The compile error broke the Java and database verification job of Delivery run 45337601399; restoring compilation unblocks that job for any head carrying the fix.

Acceptance mapping: (a) invalid statements removed and comments corrected —
observed in the scoped file; (b) test compilation succeeds — `test-compile` exit 0;
(c) targeted suite passes — Surefire 5/5, zero failures/errors/skips. The
exact-SHA CI verdict is a trusted-job step recorded under Findings.

## Risk class

Routine (docs/WORKFLOW.md): test-only compile repair, no production behavior,
data or contract change. No clinical sign-off required.

## Review owner

Senior Java build tooling (`aurevia-senior-java-build`), plus the mandatory
quality-loop reviewers listed under Review verdicts.

## Data changes

None. No Flyway migration, backfill, lock or volume impact; no rollback or
forward-fix needed. No migration version was reserved or used.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope changed; no audit event
added or changed. No real patient data, credential or secret is present in this
note or in any fixture used by the commands below.

## Migration and rollout considerations

None. The 62 migrations applied by the targeted suite are the existing schema,
created fresh in a synthetic database; no new migration was introduced.

## Test evidence

Evidence source: executed tool commands (final lines shown) and four read-only
reviewer verdicts. No simulated-user or human-participant evidence; no `gh`
call was made.

- Branch and delta (tool):
  - `git branch --show-current` → `swarm/FIX-JOBS-EXPORTS-TEST-COMPILE-1561`
  - `git merge --no-edit origin/main` → `Already up to date.`
  - `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers
  - `git diff --name-only origin/main...HEAD` → only `.agents/notes/FIX-JOBS-EXPORTS-TEST-COMPILE-20261125-003715.md`
  - `git status --porcelain=v1` → empty before this note was written
- Source read (tool): `apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java` lines 395-396 and 415-416 read "The service owns its transaction and rolls it back on refusal; there is no outer transaction to mark rollback-only here."; `grep -n setRollbackOnly` on the file returns no matches; `as()` at lines 262-270 only sets and clears `TenantContext` in `try/finally`.
- Green (tool, this run):
  - `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → `EXIT: 0` (no compiler output).
  - `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → `EXIT: 0`; Surefire report `apps/api/target/surefire-reports/TEST-com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest.xml`: `tests="5" errors="0" skipped="0" failures="0"`. Spring Boot started, Flyway applied 62 migrations to a fresh synthetic `aurevia_arc02_c05_it` schema. The `-Pno-security,fast` trap was avoided: only `-Pno-security` with `-Dgroups=integration`, so `excludedGroups=integration` does not suppress the suite.
  - `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.040s / OK`, `EXIT: 0`.
  - Fast policy stages from `scripts/check.sh` run individually: `check-docs`, `check-secrets`, `check-names`, `check-wiring`, `check-constraints`, `check-migrations --require-baseline` → all `EXIT: 0`.
  - `python3 -I scripts/check-pack.py` → `EXIT: 1`, `check-pack: .opencode/opencode.json is stale: run scripts/gen-opencode.py`. Confirmed known worker-image false positive (OPS-CW-003-01): `git ls-files -v .opencode/opencode.json` → `S` (CI profile copied under `skip-worktree`), and a direct comparison of `git show HEAD:.opencode/opencode.json` with `gen-opencode.render(ROOT, ci=False)` → `HEAD profile matches generator: True`. No config file was regenerated or committed.
- Red, prior CI (tool log recorded in the task file): Delivery run 45337601399, Java and database verification job 138258325642 → `JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:[397,17] cannot find symbol: variable status` (and line 419). Not re-run: workers have no token for job logs and the outcome is already recorded.
- Not run, deliberately: full `mvn verify`, `scripts/check.sh` as a whole, `apps/api/check.sh`, `scripts/coverage-check.sh` and `scripts/docs.sh` — AGENTS.md bars workers from them (over ten minutes, ends the run on timeout); the integrator and Delivery own the full gate. No frontend module is in scope, so no `check.sh` typecheck applies; there is no `error TS…` to fix.

Entry-point-to-test trace: ARC-02 is exercised through `JobsExportsSearchAttachmentsIntegrationsApplySameTest` (test entry point) → the tenant-scoped `as()` helper binds `TenantContext` → production `TenantExportService` and the four other surfaces run under their own transactions → the suite asserts identical tenant policy across all five surfaces. The fixed helpers `exportDownloadRefusal` (called at lines 503 and 606) and `exportStartRefusal` (called at lines 583-586) capture the refusals through the real service; the 5/5 Surefire result proves they execute.

## Coverage

No production line, branch, method or class was changed by this run, so there
are no new branches to map to tests and no threshold, exclusion or suppression
was touched. No `setRollbackOnly`, `@Disabled`, pragma or new exclusion exists in
the scoped file.

- Coverage command for the touched area (tool): `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → exit 0, Surefire `tests=5 errors=0 skipped=0 failures=0`. All five `@Test` methods run; the two refusal helpers are reached on both the success-refusal assertion and the non-throw `AssertionError` guard.
- Java conventions command (tool): `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.040s / OK`.
- Full JaCoCo gate: trusted integrator responsibility on the merge result; deliberately not run by this worker. No coverage metric was fabricated or reported as pending-as-covered — the gate is **pending**, not green.

## Clinical and architecture review

Not required: test-only compile repair, no signed data, cross-organization
sharing, patient association, prescribing or critical-result behavior changed.

## Review verdicts

- aurevia-plan-reviewer: GO — no Required gap; verification-only plan correct for a fix already on main, three green commands suffice, worker skip of the full gate is right.
- aurevia-code-reviewer: GO — no Critical/Required findings; requested end state present, comments accurate, fully qualified links, no scope or suppression breach.
- aurevia-coverage-auditor: GO — no Critical/Required findings; zero production delta, no new branches, no threshold/exclusion/skipped test, 5/5 suite with `skipped="0"`.
- aurevia-devsecops: GO — no Critical/Required findings; tenant assertions unchanged, no credentials/PHI, no dependency or artifact change; full OWASP/SpotBugs/JaCoCo verdicts are trusted-job steps.

## Findings

- The compile fix already landed on `main` independently of this branch. Re-running the edit would produce an empty diff; the correct action is verification plus this hand-off note. The task file records the same final-tree finding from earlier attempts.
- The misleading comments were also already corrected; no stale "outer transaction" claim remains in the scoped file.
- Branch has **no PR-able code delta**: the only difference from `origin/main` is the prior note plus this one. Integrator note: close the task against `main`, where the fix already landed, rather than treating this note-only branch as the fix.
- Only outstanding item is external to a worker: the exact-SHA Delivery verdict for merged `main` is still queued behind the runner backlog (task file `ci-status`). Report it as **pending**, not green — local command exit codes are the only evidence produced here. No `gh` call was made.
- `python3 -I scripts/check-pack.py` is red only because the worker image masks `opencode.json` with the CI profile under `skip-worktree`; the committed profile matches the generator, so no regeneration is owed.
- Tracker-accuracy nit from the security reviewer (not fixable by a worker): the task file's `ci-status` cross-reference names task 01273000, which is now an E2E harness story; the Delivery-concurrency work lives in task 01273500. No tracker file was edited.
- `scripts/check.sh` was not run as a whole for the documented worker-timeout reason; this is a role/environment constraint, not a gate failure.

## Lessons for workers

- Before re-editing a re-dispatched FIX story, run `git diff --name-only origin/main...HEAD` and read the scoped file. If the fix is already on `main`, verify it and write a short pointer note instead of manufacturing a diff (daily report 2026-11-22, lesson 57).
- One clean verification pass is enough: another notes-only pass cannot clear a CI queue or change a PR label (lesson 55).
- The `-Pno-security,fast` combination silently excludes `integration` tests. Use `-Pno-security -Dgroups=integration` and read the Surefire count.
- In a cloud worker, `check-pack.py` staleness is a CI-profile artifact when `git ls-files -v` shows `S`; compare `git show HEAD:<file>` with the generator output instead of regenerating.
- Do not run `scripts/check.sh`, `apps/api/check.sh`, `scripts/coverage-check.sh` or `scripts/docs.sh` as a worker; the integrator owns them.

## Why this is not finished

This run's worker-scope work is complete (verification green, no code delta, hand-off recorded). The story cannot be marked `[x]` by a worker, and the exact-SHA Delivery verdict on merged `main` is still queued behind the runner backlog — that evidence belongs to the trusted integrator/Delivery job. Next step for the integrator: confirm the Delivery run for the exact merged `main` SHA is green, then mark the task `[x]` with that SHA and the green run link. No in-scope defect remains open.
