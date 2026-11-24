# JAVA-PACKAGE-RENAME — interactive maintainer run

**Goal (user directive 2026-11-23):** remove the legacy `aurevia` segment so every
Java package root is `com.aurevia.*`. Evidence source: executed
`find`/`grep`/`mvn`/`python3` commands and file reads (tool evidence). No reviewer,
simulated user or human participant evidence in this run.

## What changed

- All 1529 Java files under `apps/api/src` (750 main, 779 test): dot-form
  `com.aurevia` -> `com.aurevia`, path-form `com/aurevia` -> `com/aurevia`.
- Moved all `com/aurevia/**` directories up one level to `com/aurevia/**` in both
  main and test. No `aurevia` or legacy package directory remains. Spring entry point
  is now `com.aurevia.AureviaApiApplication`; default component scan of `com.aurevia.**`
  still covers every bean.
- Functional references updated: `apps/api/pom.xml` pitest targets,
  `apps/api/spotbugs-exclude.xml`, `CommandClientGenerator.GENERATOR` and the regenerated
  `contracts/openapi/generated/{client.ts,manifest.json,openapi.yaml}`, all `contracts/**`
  READMEs, `apps/staff-web/src/features/duplicates/domain.ts` comment, and 294 Markdown
  path links under `docs/` and `contracts/`. Dot-form class references in current-state
  `docs/spec`, `docs/gates` and `docs/reference` were updated too.
- `scripts/wiring-baseline.json` regenerated with `check-wiring.py --write-baseline`; the
  diff is a pure old-path -> new-path relocation (counts unchanged: 1/3/3/2/1/13).
- `.opencode/config/MEMORY.md`: durable package-root decision recorded.

Deliberately unchanged: class names (`AureviaApiApplication`), Maven artifact `aurevia-api`,
service name, generated contract extension keys (`x-aurevia-*`), historical evidence logs
(`.agent/pr-notes/`, `tasks/`, `docs/swarm/`, `docs/archive/`, `build/logs/`) and protected
agent/script files that mention the old path only as an example (`scripts/agent-run.sh`,
`scripts/check-names.py` LEGACY allowlist, two Python test fixtures).

## Test evidence

| Command | Final line / result |
|---|---|
| `mvn -f apps/api/pom.xml -B compile` | BUILD SUCCESS, 750 sources compiled |
| `mvn -f apps/api/pom.xml -B -DskipTests test-compile` | BUILD SUCCESS, 779 sources compiled |
| `mvn -f apps/api/pom.xml -B -Dtest=AureviaApiApplicationTest,ExchangeResponseNamingTest,CommandClientGeneratorTest,CommandAuthorizationTest test` | Tests run: 31, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS |
| `java -cp "$CP" com.aurevia.api.authorization.CommandClientGenerator contracts/openapi/generated` | regenerated artifacts, no error |
| `java -cp "$CP" ... --check contracts/openapi/generated` | exit 0 |
| `python3 -I contracts/openapi/check.py` | x-04 drift check OK: 24 commands, contract and client match manifest |
| `python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q` | Ran 7 tests, OK |
| `python3 -I scripts/check-wiring.py` | exit 0 |
| `python3 -I scripts/check-wiring.py --fail-on-stale` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | Ran 2 tests, OK |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_check_names.py' -q` | Ran 6 tests, OK |

The first targeted run failed `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration`:
the committed generated artifacts were stale from the earlier in-progress rename. Running the
project's own generator fixed it; the re-run is green above. This was a pre-existing defect in
the working tree, not caused by the package rename.

`python3 -I -m unittest discover -s scripts/tests -q` was terminated by the 300 s timeout
(exit 143 SIGTERM) after printing only synthetic-fixture output from wiring tests and known
model-router fixture errors; it produced no suite summary. Targeted convention/name tests
above are green. Full `mvn -f apps/api/pom.xml -B test` is the final regression check; its
result will be appended here.

## Coverage

This is a mechanical package/path rename with no new production branches. Every touched
Java file was already covered by the existing suite; the full unit run is the regression
evidence. No threshold, exclusion, suppression or test skip was added or changed.

## Findings

- The working tree already contained an uncommitted legacy -> aurevia rename (old files
  deleted, new tree untracked) plus many unrelated modifications. All were preserved; only
  package names, paths and their references were touched.
- The generated OpenAPI artifacts were stale before this change; regenerating them also
  updated the `x-aurevia-*` extension keys and title emitted by the generator. Those keys are
  branding in a generated contract, not Java package names, so they were left as the
  generator emits them.
- A backup of the pre-change Java tree and functional files was written to
  `/private/var/folders/5f/1bz7m8qs7t380l7r_08cbx540000gn/T/opencode/java-pkg-backup-20261123-204530.tar`.

## Lessons for workers

A Java package move must include path-form strings in tests (Flyway locations, `Path.of`
source reads), FQN constants in generators, generated artifact checksums and the wiring
baseline; text-only package edits leave the generator drift test red until the artifacts are
regenerated with the project's own generator command.

## Follow-up: propagate the new layout to every remaining path (user directive 2026-11-23)

The initial run deliberately left historical files on the old path. The user then directed
"read all files, and update any path with the new project layout", so this pass propagated the
new root everywhere, including trackers and evidence logs:

- Replaced the old dot/FQN and path forms of the package root with `com.aurevia` (dot) and
  `com/aurevia` (path) across 1364 text files (4490 occurrences) under `.agent/`, `tasks/`,
  `docs/`, `.opencode/` and `scripts/`. Skipped `.git`, `node_modules`, `build`, `__pycache__`
  and binary files. A pre-change backup of those directories is at
  `/private/var/folders/5f/1bz7m8qs7t380l7r_08cbx540000gn/T/opencode/pre-path-update-backup-20261123.tar`.
- Repaired two narrative lines that the literal replacement would have made nonsensical
  (`.opencode/config/MEMORY.md` decision row and this note's Goal line) so they still state the
  from-legacy/to-new fact without carrying a stale path.
- Removed stale `scripts/__pycache__` bytecode (untracked) that held old path constants and
  caused a one-off stale-bytecode test failure until cleared.

Zero remaining text references to the old layout. Evidence (tool):
`grep -rlE 'com[./]aurevia[./]aurevia' .` -> 0 files; `python3 -I scripts/check-names.py` exit 0;
`python3 -I scripts/check-docs.py` exit 0; java-conventions tests 2/2 OK; check-names tests 6/6
OK; `python3 -I scripts/check-wiring.py` exit 0; `bash scripts/python-coverage.sh` exit 0
("every changed script line is covered"); `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`
exit 0. `scripts/check-constraints.py` still exits 1, but all 34 flags are pre-existing
`@SuppressWarnings`/`@Disabled` in the untracked Java tree from the initial rename (none in any
file this pass edited) — the checker treats those untracked files as newly added. No reviewer,
simulated user or human participant evidence in this pass.

## Final regression result (appended)

`mvn -f apps/api/pom.xml -B test` completed in 17:39 min: **Tests run: 6025, Failures: 0,
Errors: 4, Skipped: 4** (exit 1). The four errors were transient Surefire fork artifacts:
three `initializationError » NoClassDefFoundError` for in-memory test stores whose compiled
classes were present and whose own Surefire XML reports recorded 0 errors, plus one
`PSQL ERROR: permission denied for table care_unit`. The log also reported
`Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`
Isolated re-run of all four:

`mvn -f apps/api/pom.xml -B -Dtest=ApplyConfiguredRetentionExportLegalHoldsTest,OutsideHoursShowUnitNextHandlesMessagesTest,RequestsCopiesFollowSiteSRecordAccessTest,KeyModelsIncludeAppointmentQueueentryEncounterrevisionTemplateversionTest test`
→ **Tests run: 19, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS**.

Evidence source: executed commands (tool evidence). No reviewer, simulated user or human
participant evidence. No package/import error appeared anywhere in the 6,025-test run, which
is the direct regression evidence for this rename.
