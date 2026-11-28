# DOC-API-04 · Java API: add source documentation to messaging acknowledgment, appointments, attachments and conversations

PR #1827, tested branch tip 03934a6283532e7b2b18e5e285a4427d7530c764.

Task: DOC-API-04

Swarm worker 3. Checks: passed. Local checks: failed. Agent result: ok.

# DOC-API-04 — Java API: add source documentation to messaging acknowledgment, appointments, attachments and conversations

Tested revision: 6608b112589fce106e7a2d735622becac5193646

Evidence source: executed shell commands (tool evidence) and read-only specialist findings recorded below. No simulated-user walkthrough, authorized human feedback or real patient data was used. Scratch artifacts stayed under `/tmp/aurevia-docapi04.O5Iwvp`; all 35 scoped sources were inventoried and scanned; 34 needed edits (`ConversationUnitResolver.java` was already complete), and the repository files written are those 34 sources plus four new `package-info.java` files and the two notes copies intended for this PR.

## Summary

Documentation-only change across the 35 main Java sources in `messaging/acknowledgment` (7), `messaging/appointments` (13), `messaging/attachments` (7) and `messaging/conversations` (8), plus four new annotation-free `package-info.java` files. Every public member now carries Javadoc: missing declarations use `{@inheritDoc}` so the documented interface stays the single source of truth, the 26 controller route methods carry `@throws com.aurevia.api.ApiException`, and the missing `@param`/`@return` tags on the brief's example methods (including `AttachmentContract.FileType.accepts`, `matchesExtension`, `matchesMagic`, `fromContentType`, enum constructors and the `Actor` factories) were added. All story, task, gate and requirement ID tokens were removed from comments while runtime string literals stayed byte-identical. Senior-backend's pre-code review found eleven stale or over-broad comments; all were corrected against the code. Every `{@link}`/`@see` target is fully qualified. No executable line changed. Requirement OPS-06.

Skills loaded: `aurevia-delivery` (every story), `aurevia-context-optimizer` (35-file inventory and entry points), `aurevia-senior-backend` (Java package invariants), `aurevia-senior-java-build` (Javadoc, JaCoCo, SpotBugs and gate limits), `aurevia-test-engineer` (RED/GREEN parity evidence), `aurevia-plan-reviewer` (pre-code plan), `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` and `aurevia-qa-reviewer` (post-code verdicts).

## Affected requirements

OPS-06 at `.opencode/config/PROJECT_V1_SPEC.md:139` (synthetic fixtures, CI policy checks and auditable operations). Documentation traceability is indirect; no requirement or specification file changed.

## Risk class

Routine — comments and four `package-info.java` files only. The verified `git diff -U0` audit reports `changed lines: +311 -123; non-comment changed lines: 0`.

## Review owner

Primary agent owns the scoped source edits and both notes files. Read-only specialists: context-optimizer, senior-backend, senior-java-build, test-engineer and plan-reviewer (pre-code proposals and plan GO); code-reviewer, coverage-auditor, devsecops and qa-reviewer (post-change).

## Data changes

None. No migration, RLS policy, backfill, lock, volume impact, route, OpenAPI entry or regenerated client. Synthetic fixtures only; no patient data, credential, real identifier or ID token remains in a comment.

## Authorization boundaries and audit events

None changed. The comments describe the existing tenant derivation, fresh membership and role checks, verified-link and connection rules, body version preconditions, If-Match preconditions, concealment ordering, guarded upserts, audit facts and the separation of a first view from an acknowledgment. No scope, role, permission or audit event is added.

## Migration and rollout considerations

None. No deploy, rollout or schema code is involved.

## Test evidence

- File inventory: 35 main `.java` files — acknowledgment 7, appointments 13, attachments 7, conversations 8. No existing `package-info.java` in the four packages (confirmed independently by context-optimizer).
- RED docs scan **before** edits, detector run against pristine `HEAD` sources extracted with `git archive` into `/tmp`: `python3 /tmp/aurevia-docapi04.O5Iwvp/docs-scan.py /tmp/aurevia-docapi04.O5Iwvp/head` → exit 1, `SCAN files=35 findings=204 ID-IN-COMMENT=104 NO-JAVADOC=52 TAG-INCOMPLETE=44 BAD-LINK=0 PACKAGE-INFO=4 per_pkg=acknowledgment:70,appointments:50,attachments:46,conversations:38`. The detector was strengthened after code review: its original prefix list missed `ADM-02` and its signature parser missed `@param` on some `from(...)`/`canTransitionTo(...)` methods; the final detector uses a general ID pattern with an acronym allow-list (SHA, ISO, RFC, …) and depth-zero parameter parsing. RED was recomputed against pristine `HEAD` with the final detector.
- GREEN docs scan **after** edits: `python3 /tmp/aurevia-docapi04.O5Iwvp/docs-scan.py .` → exit 0, `SCAN files=39 findings=0 ID-IN-COMMENT=0 NO-JAVADOC=0 TAG-INCOMPLETE=0 BAD-LINK=0 PACKAGE-INFO=0 per_pkg=acknowledgment:0,appointments:0,attachments:0,conversations:0` (39 = 35 sources + 4 new package-info files). The uncommitted detector masks Java string literals and text blocks before extracting comments, so prose apostrophes and runtime literals are not mistaken for char literals; it checks `@see` as well as `{@link}` and treats `{@inheritDoc}` as inherited tags.
- Baseline **before** edits: `python3 -I -m unittest discover -s scripts/tests -q` → exit 1, `Ran 2494 tests in 95.656s`, `FAILED (failures=5, skipped=1)`. The five named failures: `test_check_pack.PackTests.test_committed_opencode_json_is_generated`, `test_check_pack.PackTests.test_simulated_user_skills_need_no_process_sections`, `test_check_pack.PackTests.test_system_namespace_skill_is_not_a_project_skill`, `test_notes_files.NotesFileTests.test_daily_archives_keep_the_documented_dated_shape`, `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant`.
- After edits: the same Python command → exit 1, `Ran 2494 tests in 104.698s`, `FAILED (failures=5, skipped=1)`. Normalizing only elapsed seconds, `diff -u` of the before/after canonical dumps is empty: the same five named failures and counts, no new failure and none suppressed.
- Targeted Maven command (before and after): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=InstructionFactControllerHttpTest,AttachmentControllerHttpTest,PatientAppointmentControllerHttpTest,ConversationHttpSecurityTest test` → baseline exit 0, after exit 0; canonical Surefire dump `4 suites, 47 tests, 0 errors, 0 failures, 0 skipped`; `diff -u` of the before/after canonical dumps is empty (each suite, test case and outcome identical). The integration-tagged `ConversationHttpSecurityTest` ran with `-Pno-security` and without the `fast` profile.
- Static analysis: SpotBugs Maven plugin 4.10.5.0 (`apps/api/pom.xml:31`), command `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, `findings=0` (SpotBugs ran after a compile). OWASP Dependency-Check 13.0.0 is configured in the build gate but was not run by this worker; the integrator owns it.
- `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` → exit 0 (JaCoCo 0.8.16); `grep -o 'name="[^"]*package-info[^"]*"' apps/api/target/site/jacoco/jacoco.xml | wc -l` → `0`.
- `python3 -I scripts/check-wiring.py` → exit 0, no findings. Production entry-point-to-test trace (unchanged entry points): `acknowledgment/InstructionFactController` → `messaging/acknowledgment/InstructionFactControllerHttpTest` (HTTP-level MockMvc, real controller/service/store); `attachments/AttachmentController` → `messaging/attachments/AttachmentControllerHttpTest` (HTTP-level MockMvc); `appointments/PatientAppointmentController` → `messaging/appointments/PatientAppointmentControllerHttpTest` (HTTP-level MockMvc); `conversations/ConversationController` → `it/messaging/ConversationHttpSecurityTest` (real HTTP, `@SpringBootTest(RANDOM_PORT)`, full Spring wiring). No test or production entry point was edit
