# RESP-AUDIT-PAGE — fix pass (2026-11-22 19:08) — Staff web: responsive `AuditPage` (features-admin)

## Summary

Third fix pass on a presentation-only staff-web story. The story's own implementation (`AuditPage.tsx` plus its responsive test) was already merged on `main` and is byte-identical to `origin/main`; this pass changes none of the two scoped files. The pull request was still blocked because three pairs of unresolved Git conflict markers were committed inside Javadoc in `main`. The worker prompt requires `git grep -nE '^(<<<<<<<|>>>>>>>) '` to be empty after merging `origin/main`, and the latest debugger finding (Step 7, 18:47) prescribes exactly this bounded repair: delete the marker lines, keep one copy of each byte-identical Javadoc line, and add one JUnit regression test. This pass does that, re-verifies the story's frontend gate at 100 % coverage, and records the evidence.

**Scope rationale (why an `apps/api` hygiene edit is in this frontend story's PR).** The task file's narrow scope governs the original story worker, whose two scoped files are already merged, green and untouched here. This fix worker runs under the explicit prompt gate *"check that no conflict markers remain (`git grep -nE '^(<<<<<<<|>>>>>>>) '`) and rerun the affected tests"* — that grep currently fails on `main` itself — and under debugger Step 7: *"Bounded scope: the three HL7 Java files plus one new JUnit regression test under `apps/api/src/test`. Do not touch `AuditPage.tsx`, any responsive test, any coverage threshold/exclusion, or `scripts/`."* The permanent guard belongs to a protected `scripts/` path and is raised as a `task-requests` block below, not edited here.

Entry point → test trace: CI/repo conflict-marker gate → `Hl7SourceHygieneTest` (reads each HL7 source, asserts no `^(<<<<<<< |=======$|>>>>>>> )` line) → failure message lists the offending lines. Story path remains `/admin/audit` → `AuditPage` → `AuditContent` (`useBreakpoint`, filter form, tenant key) → `Form`/`ApiPage`/`AuditEventList` → `tests/responsive/features-admin/AuditPage.responsive.test.tsx` and the unchanged `src/features/admin/AuditPage.test.tsx`.

## Affected requirements

- WEB-07 — supported clients / responsive staff web (story requirement; unchanged implementation). `SMARTCLINIC_V1_SPEC.md` line 155.
- UX-01 — English and Arabic, RTL, mixed-script clinical text, WCAG 2.2 AA (story requirement; unchanged implementation). `SMARTCLINIC_V1_SPEC.md` line 90.
- XCH-01, XCH-04 — the HL7 Javadoc that carried the markers documents negative-acknowledgment reason codes and result routing. No behavior changed. `SMARTCLINIC_V1_SPEC.md` lines 63 and 66.

## Risk class

Routine. Comment-only source hygiene plus a new test; no clinical data, authorization, permission, patient association, prescribing, critical result, server behavior or contract touched.

## Review owner

Senior Java and Spring Boot / Senior Java Build and Test Tooling for the hygiene fix and test; Senior React and Vite for the unchanged story scope. This pass used `smartclinic-delivery`, `smartclinic-plan-reviewer`, `smartclinic-code-reviewer`, `smartclinic-coverage-auditor` and `smartclinic-devsecops` (all read-only reviewers). Evidence below comes from executed tool commands and reviewer verdicts — no human, clinician, patient or simulated-user participant evidence.

## Data changes

None. No migration, backfill, schema change, lock or volume impact, rollback or forward-fix. Synthetic data only; no new dependency; no credential or PHI in code, logs, tests or notes.

## Authorization boundaries and audit events

Unchanged. Tenant key still comes from `useActiveOrganization()` and `X-Active-Organization`; `AuditContent` still remounts on an organization switch. The HL7 edit is inside Javadoc only. No role, permission or audit-event behavior changed.

## Migration and rollout considerations

None. No reserved migration version used. No server, contract or infrastructure change.

## Test evidence

All commands executed from branch `swarm/RESP-AUDIT-PAGE-997`, HEAD `47770b00c4c8d8734dfd1a29c848278237889e3d`. At the start `git merge --no-edit origin/main` → `Already up to date.`

1. Marker survey before fix: `git grep -nE '^(<<<<<<< |=======$|>>>>>>> )'` → six marker lines in three HL7 files. After fix the same command → `NONE`. The prompt's narrower `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no output.
2. **Red first.** `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Hl7SourceHygieneTest test` → exit 1, `Tests run: 3, Failures: 3, Errors: 0, Skipped: 0`. Each failure is the marker assertion (not a path error) and lists `[<<<<<<< HEAD, =======, >>>>>>> origin/swarm/BUILD-37-469]` for its file.
3. **Green.** `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest='Hl7*Test' test` → exit 0, `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0` across 10 classes (the 9 existing `Hl7*Test` classes plus the new one), `BUILD SUCCESS`.
4. **Coverage command.** `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest='Hl7*Test' test jacoco:report` → exit 0, `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; report read from `apps/api/target/site/jacoco/jacoco.csv` (see Coverage).
5. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.057s` / `OK`.
6. `python3 -I scripts/check-names.py` → exit 0. `python3 -I scripts/check-wiring.py` → exit 0 (only pre-existing notes that baseline entries are fixed and should be deleted by a scripts owner; baseline shrinks are not a worker change).
7. **Mandatory frontend gate.** `bash apps/staff-web/check.sh` (run from repo root) → exit 0. Typecheck final lines (mandatory evidence):
   ```
   > smartclinic-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   No `error TS…` line. Stages green: `gen:api generated client matches the contract`, typecheck, `eslint . --max-warnings 0`, `vite build`, bundle-size gate, `vitest run --coverage --exclude 'e2e/**'` → `Test Files  206 passed (206)` / `Tests  2542 passed (2542)` / `No files with missing coverage.` / `213 files fully covered. 4 empty files skipped.` The `useTheme must be used inside ThemeProvider` and similar console lines are expected jsdom negative-path noise from provider tests; the run ends green.
8. `git diff --check` → no output (clean). `git diff --stat origin/main...HEAD -- <the two scoped story files>` → empty (story files unchanged).

Full `mvn verify` was deliberately not run: the worker prompt forbids any command that runs every integration test (>10 min). The integrator and Delivery run the full gate and the bundle-level JaCoCo gate on the merge result.

## Coverage

**Java (targeted JaCoCo, command in step 4).** Comment-only production edits cannot change bytecode, and the three classes were already at 100 % in the green baseline; the targeted report confirms it:

| Touched class | Lines | Branches | Methods |
|---|---|---|---|
| `Hl7ParseException` | 4/4 | 0/0 | 2/2 |
| `Hl7Parser` | 25/25 | 16/16 | 2/2 |
| `Hl7Segment` | 18/18 | 16/16 | 5/5 |

The same report gives `INSTRUCTION_MISSED=0` for all three (10, 155 and 102 instructions covered); the bundle-level `CLASS` counter is unchanged because no class was added or removed by a comment-only edit.

No coverage threshold, exclusion, ignore comment, generated override, `@Disabled`, `.skip` or suppression was added or changed. The new `Hl7SourceHygieneTest` is test code (not JaCoCo instrumented).

**Frontend (authoritative command step 7, `bash apps/staff-web/check.sh`).** `Statements 100% (4059/4059)`, `Branches 100% (2071/2071)`, `Functions 100% (1346/1346)`, `Lines 100% (3876/3876)`, `No files with missing coverage. 213 files fully covered.` The only in-scope story file, `src/features/admin/AuditPage.tsx`, is unchanged; its prior scoped probe read 51/51 lines, 44/44 branches, 20/20 functions, 57/57 statements.

New/changed branch → test: the only new logic is the regression predicate `^(<<<<<<< |=======$|>>>>>>> )`, whose three alternatives are all exercised in the red run — the failure message lists `<<<<<<< HEAD` (first alternative), `=======` (second) and `>>>>>>> origin/swarm/BUILD-37-469` (third) for every file. `assertTrue(marked.isEmpty())` true side is the green run; false side is the red run. The locate helper's fallback branches are test infrastructure, not production coverage; the first candidate path succeeds under Maven and the `throw` keeps a missing file loud. No production branch was added or removed.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing, release of information or critical results. The earlier-run simulated UX review of the unchanged story layout is carried forward only as review activity, not clinical acceptance.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **Conflict markers were pre-existing in `origin/main` (tool evidence).** They were committed by `b6b349f0` (`BUILD-37: HL7 v2 order and result messages`) from `origin/swarm/BUILD-37-469`, not by this branch. Both sides of each conflict were byte-identical Javadoc lines; one copy is kept. This pass removes them because the prompt's post-merge grep gate requires it. Earlier notes (17:09) had raised them as out of scope; debugger Step 7 supersedes that by prescribing the fix as the gate-unblock.
- **Earlier claim that the markers break compilation/tests was false (reviewer/tool evidence).** They sit inside `/** ... */`, so `javac`, targeted tests, `docs.sh` and the staff-web gate passed with them present. The defect is source hygiene and the CI/repo conflict-marker rule, not a functional failure.
- **Previously broken responsive test files are fixed on the current base (tool evidence).** The seven files named in the 13:48 note (`AdminScreen`, `ChartPage`, `ChartScreen`, `DuplicatesPage`, `PatientsPage`, `NotFoundPage`, `SignedOutPage`) are resolved by `20bbffe6`, an ancestor of HEAD. `bash apps/staff-web/check.sh` is green; none was edited here.
- **Two flaky `worker_gate` tooling tests and the `opencode.json` staleness are documented cloud-worker false positives (AGENTS.md).** `opencode.json` is installed under `skip-worktree` with the CI profile; the committed blob equals `gen.render(HEAD)`. The two `worker_gate` tests fail before a Maven build creates `apps/api/target/surefire-reports`; this pass's Maven runs create them. Not branch-introduced; no protected file edited.
- `check-wiring.py` exit 0 but printed pre-existing notes that several requirement-ID-named classes are fixed and should be removed from `scripts/wiring-baseline.json` by a scripts owner. Left alone (protected path, out of scope).
- Reviewers recorded three Optional (non-blocking) observations, all GO: the `locate()` fallback branches are test-only and not JaCoCo-instrumented; the regression guard intentionally covers the three affected files rather than all twelve HL7 sources because a repo-wide gate already exists (permanent guard raised as a task-request instead); and `locate()` could accept an absolute path via a property for non-Maven runners. None is a Required gap.

## Lessons for workers

- Use `Pattern.compile("^(<<<<<<< |=======$|>>>>>>> )").matcher(line).find()`, not `String.matches()`; `matches()` would miss the `<<<<<<<`/`>>>>>>>` lines the gate actually checks.
- When a re-dispatched fix worker finds the story's code already merged, verify the scope in isolation (targeted tests + module `check.sh`) and distinguish already-resolved module failures from ones you cause, instead of re-implementing the story.
- A comment-only production edit leaves bytecode and JaCoCo untouched; a targeted `-Dtest=... test jacoco:report` plus the CSV rows is sufficient local proof under the no-full-verify worker rule.
- `bash apps/staff-web/check.sh` prints expected jsdom console errors while exiting 0; judge green by exit status and the `Test Files`/`Tests` summary, not console noise.
- `scripts/check-verdicts.py` matches `- smartclinic-<name>: GO`, not a bare `VERDICT: GO` line; write the lines in that exact form.
- The source-file locate helper duplicates an existing test pattern (`OpenApiContractDriftTest.locateContract`); a shared test utility owned by a separate scripts/test task would remove the fifth copy.

```task-requests
[
  {
    "title": "Add a permanent no-conflict-marker guard to the repo gate",
    "details": "Fold the predicate ^(<<<<<<< |=======$|>>>>>>> ) into scripts/check-docs.py or a new scripts/tests module with its own 100%-covered unit tests, so an unresolved marker cannot be committed again. scripts/ is protected for swarm workers; do not edit it from a story PR. The three current HL7 markers are already resolved in this PR.",
    "scope": ["scripts/check-docs.py", "scripts/tests/"],
    "depends": []
  },
  {
    "title": "Repair the two order-dependent worker_gate tooling tests",
    "details": "The two worker_gate tests mock Path.is_dir -> True without creating apps/api/target/surefire-reports, so they error with FileNotFoundError in a clean worker before any Maven build. Make them create their fixture directory or stop mocking is_dir. Environment/order-dependent, not a story regression.",
    "scope": ["scripts/tests/test_worker_gate.py"],
    "depends": []
  }
]
```
