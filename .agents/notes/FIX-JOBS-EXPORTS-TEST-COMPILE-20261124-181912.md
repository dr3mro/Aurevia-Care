## Summary

Restore Java test compilation for the ARC-02 tenant clause suite. The red build was
`cannot find symbol: variable status` at
`apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java:397,419`:
`exportDownloadRefusal` and `exportStartRefusal` called `status.setRollbackOnly()` inside a
`Supplier` lambda passed to `as(...)`, which binds only `TenantContext` and has no transaction
status in scope. Their Javadoc also claimed an outer `TransactionTemplate` that does not exist.

This branch is a **verification hand-off, not a new fix**. The equivalent removal of both
out-of-scope calls and the comment correction already landed on `main` in commits `644d0e1f`
(`fix(test): remove obsolete rollback-only flag from export refusal helpers`) and `97dcbb0f`
(`Fix compile break in shared tenant-policy test helper`). At the start of this run
`HEAD == origin/main == c06ca49c21b1420fc87c37549bad631bc9f35b55`, the working tree was clean,
and `git diff --name-only origin/main...HEAD` was empty. No production or test Java was changed
in this run; I re-proved the fix locally and recorded the evidence.

The advisor brief's "Consult these read-only specialists" list was empty. The worker prompt
independently mandates the quality loop, so I used `aurevia-plan-reviewer`,
`aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops` as directed by
AGENTS.md / docs/WORKFLOW.md. No other specialist was needed because there is no code delta.

## Affected requirements

- **ARC-02** — tenant isolation: derivate context from authentication/membership, enforce in
  services and PostgreSQL RLS, and apply the same policy to jobs, exports, search, attachments
  and integrations. The affected suite is the five-surface isolation evidence test.
- **OPS-06** — synthetic fixtures and CI policy checks; this change keeps fixtures synthetic
  and adds no production or data change.

## Risk class

Cross-boundary verification of a test-only compile break on a safety-relevant tenant isolation
suite (docs/WORKFLOW.md). No runtime behavior, contract, migration or deployment changed.

## Review owner

Primary agent (orca/swarm worker). Read-only reviewers: plan reviewer before work; code
reviewer, coverage auditor and DevSecOps after the checks. The integrator owns tracker marks,
the full gate and CI confirmation.

## Data changes

None. No migration, backfill, table, policy, lock or volume impact. No reserved migration
version was used. No real patient data or credential appears in any command, fixture or note.

## Authorization boundaries and audit events

None changed. The suite exercises existing tenant context (`TenantContext.set` via `as(...)`),
RLS and role/export refusal paths with synthetic organizations `ORG_A`/`ORG_B`; no new grant,
role, endpoint, audit event or patient link was introduced.

## Migration and rollout considerations

None. No schema or configuration change; nothing to roll out or roll back. The fix is already
part of the `main` baseline that CI builds.

## Test evidence

Environment: OpenJDK 21.0.13.1 LTS, Maven 3.10.0, PostgreSQL 18.6 on 127.0.0.1:5432
(`pg_isready` → `accepting connections`). All fixtures are synthetic.

Branch reconciliation at start: `git merge --no-edit origin/main` → `Already up to date.`
(exit 0); `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no output.

Tool evidence (this run):

1. `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → exit `0`, no output. This is the
   green counterpart to the CI red `cannot find symbol: variable status` at lines 397/419.
   It proves the whole test source set compiles, including the two repaired helpers.
2. `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test`
   → exit `0`; Surefire
   `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.555 s -- in com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest`.
   The `-Pno-security` profile was used deliberately without `fast`: `fast` sets
   `excludedGroups=integration`, which would make the run vacuously green. Entry-point-to-test
   trace: the suite drives the real Spring HTTP routes (`/api/v1/tenant/exports`,
   `/api/v1/tenant/exports/{jobId}`, `/api/v1/delivery/events`, order/document/patient routes)
   and the `JdbcDeliveryStore` / `TenantExportService` beans through `as(...)`, and asserts the
   cross-tenant 401/403/404/410/412 refusals. The helper region (lines 378-420) now contains no
   `setRollbackOnly()` reference.
3. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` →
   `Ran 2 tests in 0.048s / OK`, exit `0`. This is the exact Java-conventions command that the
   previous reviewer had asked for.
4. Individual policy scripts, each exit `0`:
   `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-wiring.py`,
   `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`,
   `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py`.
   They are N/A as change gates here (no production Java, migration or API delta) but were run
   to confirm the baseline is clean.

CI status (not local evidence): the Delivery run for PR 1613 (run `45337601399`, Java and
database verification job `138258325642`) reported the red compile error. The same fix then
landed on `main` independently; the exact-SHA Delivery run for `main` is still queued behind
the runner backlog (task `01273000`, `ci-status` in task `01276000`). Local compile and the
5/5 targeted suite are **tool evidence only**, not the story's CI verdict. This branch is a
hand-off: it is not complete for CI until the integrator confirms the exact SHA's green run.

Prior-run reviewer evidence recorded in the task file: `aurevia-senior-java-build: GO` and
`aurevia-code-reviewer: GO` (the latter after the exact Java-conventions command was added).
Those are prior-run verdicts, not newly obtained in this run. Plan reviewer: first pass NO-GO
on a superseded plan that omitted the pending-CI-status statement; re-review of the updated
plan was GO. The superseded NO-GO is recorded here, not in `## Review verdicts`.

`scripts/check.sh` was intentionally **not** run: it delegates to `apps/api/check.sh`
(full `mvn verify`) plus every frontend gate, which AGENTS.md identifies as a swarm-worker
timeout risk. The integrator and Delivery own that full gate.

## Coverage

No production line, branch, method or class was added or changed by this run, so there is no
coverage delta to prove and no new branch to name. The compile fix lives only in a test file
and was already on `main` before this branch started: `git diff --name-only origin/main...HEAD`
→ empty before the notes commit; `git diff origin/main...HEAD --
apps/api/src/test/java/.../JobsExports...Test.java` → empty. The working tree is notes-only now
and the base diff will be notes-only once committed (the docs-only short-circuit applies to the
verdict and coverage gates). The proof that the
already-on-main fix behaves correctly is the targeted suite: `Tests run: 5, Failures: 0,
Errors: 0, Skipped: 0`. No threshold, exclusion or suppression was changed; no test was
skipped. I do not claim a measured JaCoCo percentage because no production file changed.

## Clinical and architecture review

Not required for this compile-only test repair: no signed data, cross-organization sharing
change, patient association, prescribing or critical result changed. The suite's existing
negative cases (peer tenant, missing context, wrong role, stale/expired export) still pass,
which is the relevant tenant-isolation evidence for ARC-02.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Pre-existing baseline failure, out of scope (not fixed):** `python3 -I scripts/check-pack.py`
  exits `1` on unmodified `main` with: skill `run-workflow` missing the sections
  `## Common rationalizations`, `## Red flags` and `## Verification`; `skills differ from
  specialists plus aurevia-delivery: ['run-workflow']`; and `.opencode/opencode.json is stale:
  run scripts/gen-opencode.py`. `git ls-files -v .opencode/opencode.json` shows `S`
  (skip-worktree CI profile, OPS-CW-003-01). The full tooling suite
  `python3 -I -m unittest discover -s scripts/tests -q` therefore reports
  `Ran 1600 tests ... FAILED (failures=6)`, all six in `test_check_pack` for the same reason.
  These are on `main` before this task and live in protected `.opencode/` and skill files; a
  swarm worker must not edit them. Exact Java-conventions and security policy scripts are
  green. Two distinct causes, so the next owner should not churn a protected file for the
  wrong reason: (a) the newly committed `run-workflow` skill lacks the three required sections
  and is absent from both the committed and worktree `opencode.json` (`grep -c run-workflow`
  is `0` in each), so `gen.render` legitimately reports drift; (b) the worktree file is the CI
  runtime profile installed under `skip-worktree` (`git ls-files -v` = `S`, OPS-CW-003-01),
  which is the documented worker-image artifact. Concrete next step and owner: reproduce on
  unmodified `main`, then let the integrator route the missing `run-workflow` sections through
  a reviewed protected-file change and regenerate the committed config from that result; do
  not rewrite the skip-worktree file from a worker. Neither failure is caused by this
  notes-only branch; the integrator must not attribute it to this story.
- The story's compile fix is already on `main`, so this branch carries no Java delta. The
  integrator should treat the notes and re-verification as the hand-off and mark the task from
  the CI verdict, not from a file diff.

## Lessons for workers

- Check `git rev-parse HEAD` against `origin/main` and inspect the target region before
  editing: a repair story may already be fixed on the base branch, in which case the honest
  deliverable is verification evidence and a notes hand-off, not a fabricated diff.
- For integration suites use `-Pno-security -Dgroups=integration`, never `-Pno-security,fast`:
  `fast` sets `excludedGroups=integration` and produces a vacuously green run.
- When the advisor brief lists no reviewers but the worker prompt mandates the quality loop,
  follow the worker prompt and say so in the notes.

## Why this is not finished

Not applicable: no code change was required and all in-scope checks passed. The remaining
item is an external CI confirmation owned by the integrator (exact-SHA Delivery run for `main`,
task `01273000`), recorded above rather than claimed here.
