# fix-20261120-172525 — AUD-20261120-12 (merge/check repair, fix attempt 1 of 3)

## Task and provenance

Story: **AUD-20261120-12 — Patients cannot read instruction content: the only instruction reads deny the patient role**. The dispatch message reports that the swarm-integrator could not resolve the merge of `main` and this branch ("merged but checks failed", fix attempt 1 of 3) and asks for a reproduction of `scripts/check.sh` on the merge of `main` and this branch.

Branch: `swarm/AUD-20261120-12-289`; HEAD at start `25c7c31` (a merge of an older `origin/main`), current `origin/main` `2bb82e2`, merge base `ccfbce4`. The in-scope Java fix was already committed on this branch by earlier attempts of this same story. I retained it unchanged, re-verified it end to end (red/green and 100% coverage), and fixed the recurring merge blocker.

**Evidence source:** executed tool commands (tool evidence) from this checkout with synthetic fixtures only. Skills loaded: `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-spring-security`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`. No simulated user, human participant, clinician or real-patient data contributed to this work.

## Root cause of the failed merge / checks (what this fix changes)

Reproduced the integrator merge with `git merge --no-commit --no-ff origin/main` on the clean branch: the **only** content conflict was the shared orchestrator-owned file `.agent/pr-notes.md`:

```
Auto-merging .agent/pr-notes.md
CONFLICT (content): Merge conflict in .agent/pr-notes.md
CONFLICTED FILES: .agent/pr-notes.md
```

An earlier worker of this very story had written its fix-attempt notes into the shared `.agent/pr-notes.md` (heading `fix-20261120-170451 — AUD-20261120-12 ...`), which AGENTS.md forbids. When `main` advanced, that shared file diverged and every subsequent merge conflicted — this is the "merged but checks failed" loop. Resolution: take `origin/main`'s version of `.agent/pr-notes.md` (`git checkout --theirs`), i.e. remove the branch-local edit to a shared file; the PR then contains only the in-scope instruction fix, its tests and this task's own notes file. I also removed eight orphan per-story notes (`fix-20261120-145144.md` … `fix-20261120-170451.md`) that existed on this branch but not on `main`, so the branch carries no shared-tracker changes. After resolution the staged diff against `origin/main` is exactly the six in-scope Java files (191 insertions, 2 deletions).

No `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, workflow, `.opencode/` or `opencode.json` file is authored by this change.

## Requirement IDs and acceptance mapping

| Requirement | Acceptance | Where |
|---|---|---|
| MSG-04 | A verified patient can read the instruction title/body the unit published, via `GET /api/v1/patient-instructions` and `GET .../{threadId}` | `InstructionActions.READ` now permits `patient`; `InstructionService.get/listForPatient` patient branch |
| MSG-05 | Patient receives instruction content needed to acknowledge the exact revision | Patient read returns the full `View` (current revision + history + facts) |
| PAT-02 | Only released content visible to the linked patient; no other chart | `requirePatientChartAccess` + `verifiedPatientCharts` binding |
| IAM-05 | A verified account may link several charts; reads bind to verified charts, not a caller-supplied id alone | `verifiedPatientCharts(subject).contains(patientId)`; `InstructionStore.InMemory.linkPatientChart` |
| ARC-02 | Server-side authorization re-derived per request; tenant scope enforced | `execute()` re-reads active roles; store keys include organization id |
| ARC-04 | Existence concealed with 404; wrong patient / cross-tenant denied | `notFound()` on chart mismatch; cross-tenant verified-chart isolation |
| ARC-07 | Connection revocation ends future attachment/content access | `connectionActive` re-checked on every patient read |
| OPS-02 | Denials leave no success audit; authorization matrix change is bounded | No success fact appended on denied patient reads |

## Change (already on branch, re-verified unchanged)

- `InstructionActions.java`: `READ` now grants `patient` in addition to `staffSender`, `tenant_admin`, `privacy_auditor`. All other actions unchanged.
- `InstructionService.java`:
  - `get(threadId)` and `listForPatient(patientId)` detect a patient caller with `isPatient()` and take a patient branch.
  - `requirePatientChartAccess(subject, revision)` binds the caller to a chart linked to its verified account and to an active patient-unit connection (404 wrong chart, 403 revoked connection).
  - `listForPatient` checks the caller-supplied `patientId` against `verifiedPatientCharts(subject)` (404 on mismatch) and filters by `connectionActive`; the staff branch (unit assignment + active connection) is retained.
  - A subject holding both `patient` and a staff role uses the stricter patient branch.
- `InstructionStore.java`: new tenant-scoped `verifiedPatientCharts(subject)` port method and `InMemory.linkPatientChart` seeding helper.
- Tests added to `InstructionServiceTest`, `InstructionControllerTest`, `InstructionContractTest`: patient self-read, wrong-patient denial, revoked-connection denial, multi-role stricter-branch, cross-tenant identical-local-chart concealment, and HTTP-route patient read.

## Coverage — every new/changed branch and its test

JaCoCo report (`target/site/jacoco/jacoco.xml`) for the whole `messaging/instructions` package, from `mvn -B -Pno-security,fast verify` (unit tier): every source file has **0 missed** instructions, lines, branches, methods and classes.

| New/changed branch | Covering test |
|---|---|
| `READ` allows `patient` (new matrix arm) | `InstructionContractTest` `assertTrue(actions.allows(READ,"patient"))`; `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread` |
| `get()` `isPatient()` true path | `InstructionServiceTest.patientReadsOwnInstructionContentAndOwnThread`; `InstructionControllerTest.patientReadsOwnInstructionContentThroughHttpRoutes` |
| `get()` non-patient staff branch (retained) | `InstructionServiceTest.unitAssignmentAndConnectionAreValidatedOnEveryCommand`, `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `requirePatientChartAccess` chart mismatch → 404 | `InstructionServiceTest.patientCannotReadAnotherPatientsInstructionContent`, `multiRolePatientCallerStillUsesTheStricterPatientBranch`, `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` |
| `requirePatientChartAccess` connection inactive → 403 | `InstructionServiceTest.patientReadRequiresAnActiveVerifiedConnection` |
| `listForPatient` patient branch verified-chart containment true/false | `patientReadsOwnInstructionContentAndOwnThread` (true) / `patientCannotReadAnotherPatientsInstructionContent` + `patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother` (false) |
| `listForPatient` patient-branch `connectionActive` filter | `patientReadRequiresAnActiveVerifiedConnection` (empty result) |
| `listForPatient` staff branch unit + connection filters (retained) | `listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections` |
| `isPatient()` true/false | patient tests above / staff tests above |
| `InMemory.verifiedPatientCharts` present/absent subject, `linkPatientChart` | patient tests above (present) and staff `get`/`listForPatient` tests (absent → empty set) |

## Exact commands run and results

- `mvn -q -B -Pfast test -Dtest=InstructionServiceTest,InstructionControllerTest,InstructionContractTest` → exit 0.
- `mvn -B -Pfast test -Dtest=...` → `InstructionContractTest` 13/13, `InstructionServiceTest` 20/20, `InstructionControllerTest` 3/3; `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- `mvn -B -Pno-security,fast verify` → all 1920 unit tests pass (`BUILD FAILURE` only at the bundle-level JaCoCo check: 0.84 instructions / 0.83 lines / 0.95 branches / 0.81 methods / 0.96 classes, expected 1.00). This is expected: `-Pfast` excludes the `@Tag("integration")` suites that the integrator runs with real PostgreSQL; the full `apps/api/check.sh` (no `fast` profile) merges that execution data. The instructions package itself is at 100% on every counter (see report above).
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-names.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0. `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `git merge --no-commit --no-ff origin/main` → single conflict in `.agent/pr-notes.md`; resolved with `git checkout --theirs .agent/pr-notes.md` and removal of eight orphan fix-note files; no remaining unmerged paths.
- `git diff --cached origin/main --stat` after resolution → only the six in-scope Java files (191 insertions, 2 deletions).

## Defects found / environment notes (outside scope, not changed)

- `python3 -I -m unittest discover -s scripts/tests -q` → 9 failures / 10 errors in this GitHub Actions swarm worker. These are environment artifacts, not caused by this change and outside the declared scope: `test_check_pack` failures are the documented OPS-CW-003-01 false positive (the worktree `opencode.json` is the CI profile copied under `skip-worktree`; `git show HEAD:opencode.json` is the correct local profile and matches the generator), and `test_agent_run`/`test_swarm` failures use temp repositories affected by the worker's `GITHUB_ACTIONS`/`SWARM_*` environment. Reported accurately; no tooling file was modified. The integrator runs these gates in its own job.
- Bundle JaCoCo under `-Pfast` is intentionally below 1.00 because integration suites are excluded; the integrator's full gate supplies them. No threshold, exclusion or skip was changed.

## Decisions

- Fix the failed merge by removing the branch-local edit to the shared `.agent/pr-notes.md` (take `origin/main`'s version) rather than editing any shared tracker — this is what makes the PR mergeable and is the only conflict.
- Keep the in-scope patient-read fix byte-for-byte as prior attempts left it: it already satisfies the story (verified-chart + active-connection binding, wrong-patient 404, staff branch retained) with negative and positive tests. No production logic was rewritten.
- Do not lower any coverage threshold, add an exclusion, or skip a test.
