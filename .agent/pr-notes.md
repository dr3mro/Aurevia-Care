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
