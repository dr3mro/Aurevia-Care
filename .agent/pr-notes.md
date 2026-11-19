# S-G2-07 — Trend only comparable observations with units, ranges, time and qualifiers visible

Primary requirement: **XCH-07**. Supporting: **ADM-01, ARC-01, ARC-02, ARC-03, OPS-02, IAM-01**.
Specification: `SMARTCLINIC_V1_SPEC.md` §4 XCH-07, §7 ARC-01..03, §8 OPS-02.
Story scope: `apps/api/src/main/java/com/aurevia/smartclinic/g2/s_g2_07`, `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g2_07`. Reserved migration: **V25** only.

## Acceptance mapping (XCH-07)

| Requirement text | Implementation | Synthetic test evidence |
|---|---|---|
| Trend numeric observations only when test identity, units and clinically relevant comparability are established | `TrendAnalyzer.classify` rejects non-matching test, non-matching comparability group, non-final revision, qualified numeric, qualitative and non-canonical unit; `ComparabilityRule` + effective-dated `current_trend_comparability` fold (V25) define the rule; `TrendActionPolicy`/`TrendService` enforce role separation | `TrendAnalyzerTest` (17), `TrendServiceTest` (17), `TrendControllerTest` (3), `TrendRepositoryTest` (8), `TrendMigrationContractTest` (1); `TrendingTest` (6 integration, integrator) |
| Display source unit, reference range, time and interpretation | `TrendPoint` carries `unit`, `referenceLow/High/Unit`, `effectiveAt`, `interpretation`, `sourceSystem/Version`; `Observation` validates reference-range unit equals source unit and inverted ranges are rejected | `TrendAnalyzerTest.eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance`, `observationValidationRejectsMissingIdentityValueTimeAndBadRanges`; `TrendRepositoryTest.observationMapperReadsEveryDisplayedColumn` |
| Keep less-than / greater-than, qualitative and incompatible-unit results visible, never as comparable measurements | `ExcludedObservation` retains the whole `Observation` unmodified with a closed `ExclusionReason`; `TrendView.points() + excluded()` keeps every observation observable (nothing silently dropped) | `TrendAnalyzerTest.qualifiedNumericResultsAreVisibleButNotTrended`, `qualitativeResultsAreVisibleButNotTrended`, `incompatibleUnitsAreVisibleButNotTrended`, `exclusionsAreAlsoOrderedByEffectiveTimeThenId`, `viewCopiesItsListsDefensively`; `TrendingTest` asserts 3 points + 6 exclusions from 9 observations |
| Tenant isolation, fail-closed authorization and audit | Forced RLS on both V25 tables with `(organization_id, id)` composite keys, append-only triggers, actor-forced `created_by`/`approved_by`, tenant+test advisory lock for version allocation; `TrendService.authorize` re-derives membership and roles each request and audits denials | `TrendingTest.tenantsStayIsolatedAndRolesAreEnforced` (two tenants, identical local codes/MRN, wrong-unit/wrong-role denial, revocation on next request); `TrendServiceTest` denial/audit branches |

Entry-point-to-test trace: `POST /api/v1/trends/comparability` → `TrendController.define` → `TrendService.defineComparability` → guarded `TransactionTemplate` → `TrendRepository.nextComparabilityVersion` (V25 advisory lock) + `insertComparability` (forced actor) → `COMPARABILITY_DEFINE` audit → 201. `POST /api/v1/trends/observations` → `TrendController.record` → `TrendService.recordObservation` → guarded transaction → `TrendRepository.insertObservation` (forced RLS + actor) → `OBSERVATION_RECORD` audit → 201. `GET /api/v1/trends/{patientId}?testCode=…` → `TrendController.trend` → `TrendService.readTrend` → guarded transaction → `TrendRepository.findComparability`/`observationsForPatient` → `TrendAnalyzer.analyze` → `TREND_READ` audit → 200 with points + exclusions.

## Defect fixed in this pass (test-first, RED → GREEN)

**F1 — effective-dated fold ordered by version instead of effective date (reviewer SR-BACKEND, Medium correctness).** `defineComparability` accepts an arbitrary `effectiveFrom` on every version but V25's `current_trend_comparability` used `ORDER BY org, test_code, version DESC`. A backdated higher version would displace the rule actually in force, applying the wrong canonical unit and hiding correct-unit results as `INCOMPATIBLE_UNIT`.

- **RED:** added `TrendMigrationContractTest.effectiveComparabilityOrdersByEffectiveDateBeforeVersion`; `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=TrendMigrationContractTest test` → EXIT 1, 1 failure (`order by c.organization_id, c.test_code, c.version desc`).
- **GREEN:** changed V25 fold to `ORDER BY c.organization_id, c.test_code, c.effective_from DESC, c.version DESC` (version only breaks an effective-date tie); same command → EXIT 0, 1 pass.
- **Behavioural coverage for the integrator:** added `TrendingTest.aBackdatedHigherVersionDoesNotOverrideTheLaterEffectiveRule` — defines v1 (2026-01-01, mmol/mol) then a backdated v2 (2025-01-01, %) and asserts `ruleVersion` stays v1, only the mmol/mol observation trends, and the % result stays visible as `INCOMPATIBLE_UNIT`. This exercises the real PostgreSQL fold; I could not run it locally (no PostgreSQL server/client in this worker image — connection refused on 127.0.0.1:5432).

## Commands run and results (evidence: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **1464 tests, 0 failures, 0 errors, 0 skipped** (exact Java command in `scripts/quick-check.sh`). In scope: `TrendAnalyzerTest` 17, `TrendControllerTest` 3, `TrendMigrationContractTest` 1, `TrendRepositoryTest` 8, `TrendServiceTest` 17. `TrendingTest` is `@Tag("integration")` and correctly excluded from this DB-less tier; its class compiled successfully.
- `mvn ... -Pno-security -Dtest=TrendingTest test` (probe) → EXIT 1, `Connection refused` at 127.0.0.1:5432 — **no PostgreSQL in this worker image**. Database/integration verification is delegated to the integrator as instructed; no result was fabricated.
- `python3 -I scripts/check-migrations.py --require-baseline` → EXIT 0 (V25 `Change: expand`, version sequence and format valid after the fold fix).
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0.
- `git status --short` clean at the base commit `da4fbb4` before this change.

## Findings not changed (with rationale)

- **F2 — corrected/entered-in-error revisions have no lineage (reviewer SR-BACKEND, Medium).** `corrected` is classified `NON_FINAL_REVISION` and excluded, but V25 has no `supersedes_id`, so a superseded `final` can stay plotted while its correction is hidden. Cross-story dependency on **XCH-04 / S-G2-04** and **LAB-02 / S-G2-09** (correction versions, reconciliation). Adding lineage here would exceed the declared scope and duplicate another story's model; recorded for the Product Owner / architect rather than silently changing clinical semantics. The rest of XCH-07 (comparability gates, display fields, visible exclusions) is unaffected.
- Residual risks noted by review: a single current rule is applied to all historical observations; unit comparison is exact string equality; `tenant_admin` self-approves comparability policy (clinical approval is a G5 site decision); no pagination on `observationsForPatient` (ARC-03 pagination is X-01); the `DUPLICATE_OBSERVATION` 409 catches any `DataIntegrityViolationException` (V25 has no source-revision unique constraint; cross-event dedup is ARC-05 / X-02). None blocks XCH-07's stated acceptance.
- Pre-existing, out-of-scope: `test_java_conventions.py` reports 1 failure for `messaging/s_g3_11/PatientFlowContract.java` (S-G3-11, committed at base). Not modified.
- I did **not** run `db/check.sh`, `mvn verify`, OWASP, SpotBugs or JaCoCo — per the worker contract these belong to the integrator. No coverage result was fabricated.
- Synthetic fixtures only. No patient data, credentials or raw logs recorded. Evidence sources: executed tool commands; one independent simulated senior-backend review; no human or clinical evidence. Real-patient approval remains a G5 gate.

---

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

---

# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Scope honoured

Only the declared scope was available for edits:
`apps/api/src/main/java/com/aurevia/smartclinic/g0/s_g0_08`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g0_08`, and reserved migration `V11`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) and no file outside that scope was edited. No push, tag or `gh` call
was made. Synthetic fixtures only.

## Requirement IDs and acceptance mapping

- **IAM-06** (primary): emergency access is time-limited (1–60 min), scoped to one
  tenant-local facility, reasoned, attributed to a qualified clinician, audited on the
  V3 append-only chain, independently reviewable by privacy auditor / tenant admin
  (grantee cannot review own grant), and revocable early. Platform support access is
  **off by default** and only effective inside an approved, time-bounded (1–240 min)
  incident window; checks return an explicit fail-closed `AccessDecision`.
- **ARC-02 / OPS-02** (supporting): V11 tables carry `(organization_id, id)` keys and
  forced row-level security; purpose, role, facility, actor and timestamp are recorded
  and committed with the audit event.
- Delivered surfaces already present in scope (unchanged by this fix attempt):
  `AccessController` (8 endpoints under the IAM route family), `EmergencyAccessService`,
  `SupportAccessService`, `AccessRepository`, `AccessRules`, `AccessContracts`,
  `V11__emergency_and_support_access.sql`, `AccessUnitTest` (41 tests),
  `AccessIntegrationTest` (8 DB tests, integrator-run).

## Reported failure — investigation and disposition

The integrator's "requested change" reported a `scripts/check.sh` failure whose only
evidence was this stack trace:

```
Error: useTheme must be used inside ThemeProvider
    at useTheme (.../apps/staff-web/src/theme/ThemeProvider.tsx:39:11)
    at Consumer (.../apps/staff-web/tests/theme/ThemeProvider.test.tsx:6:32)
```

Findings:

1. **Out of scope.** The error is in `apps/staff-web`, which is outside this task's
   declared scope (`apps/api/.../s_g0_08` + V11). It cannot be fixed from this branch
   without violating the task rules, and no in-scope file can cause or cure it — the
   staff-web tests use local synthetic fixtures and make no API calls.
2. **Not an actual test failure.** The identical stack trace was reproduced locally.
   It is the expected stderr output of the intentional negative-path test
   `throws when used outside its provider` (`ThemeProvider.test.tsx:31-35`), which
   asserts `expect(() => render(<Consumer />)).toThrow(/useTheme/)`. React 18 dev mode
   logs the guarded-callback rethrow through jsdom's `reportException`; the test still
   catches it and passes. `npx vitest run tests/theme/ThemeProvider.test.tsx` → 3/3
   passed; the full `npm test` suite → **103/103 passed, exit 0**. The pasted text is a
   benign log line, not a build failure.
3. **Merge not reproducible locally.** The checkout is a grafted single-commit shallow
   clone (`git log --all` shows only `bb355f1`; `.git/shallow` present; no local `main`
   ref), so "the merge of main and this branch" cannot be reconstructed here. This is
   an evidence gap, not a code defect.

No in-scope defect was found; no in-scope change was needed for a fix attempt.

## Exact commands run and results

| Command | Result |
|---|---|
| `git status --short` / `git branch --show-current` / `git log -1` | clean tree; branch `swarm/S-G0-08-67`; HEAD `bb355f1` |
| `git show --stat HEAD` / `find` scope dirs / `ls db/migrations` | full S-G0-08 implementation in scope; V11 present |
| `bash scripts/quick-check.sh` | exit 1 **only** at `check-pack: opencode.json is stale` — environment artifact (see below); all preceding policy checks passed |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree); worktree file is CI profile (`wt == generated CI == True`, `committed == generated LOCAL == True`) — documented cloud-worker CI-profile trap, not a branch defect |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; 231 tests, 0 failures, 0 errors; `AccessUnitTest` 41/41 |
| `cd apps/staff-web && npm ci && npx vitest run tests/theme/ThemeProvider.test.tsx` | **exit 0**; 3/3 passed (error text reproduced as benign stderr) |
| `cd apps/staff-web && npm test` | **exit 0**; 23 files, 103/103 passed |

Environment note: `check-pack.py` in this worker reads the skip-worktree CI-profile
`opencode.json`, so it reports it stale; the committed `opencode.json` matches the
generated local profile and is correct. Java/Database integration tests are left to
the integrator as directed.

## Defects found

- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.

## Decision / stop notice

Per the task rule "if a decision or evidence is missing, stop and say so in
`.agent/pr-notes.md`": the merge-gate failure cannot be reproduced from this shallow
clone, the only supplied evidence is a benign log line from a passing out-of-scope
test, and there is no in-scope defect to fix. I therefore made **no code change** and
stop, committing only these notes. If the integrator still observes a non-zero
`scripts/check.sh` on the merge, the failing step and its exit code must be supplied;
if it is a genuine staff-web regression it belongs to a staff-web-scoped worker, not
this IAM-06 backend slice.

---

# S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering

## Requirement IDs and acceptance mapping

Primary: **CLN-07**. Supporting: **ARC-01, ARC-02, ARC-03, OPS-02, IAM-01, UX-01** (locale/direction).
Specification: `SMARTCLINIC_V1_SPEC.md` §3 CLN-07, §7 ARC-01..03, §8 OPS-02.

| Acceptance behaviour (CLN-07) | Implementation | Test evidence (synthetic only) |
|---|---|---|
| Link original PDF/JPEG/PNG to chart, order or result; retain original bytes + checksum | `DocumentService.link`, `DocumentRepository.insert`; V17 `document_association` + `set_document_original_digest` trigger (sha256 derived from stored bytes, never caller-supplied) | `DocumentServiceTest`, `DocumentInputsTest`, `DocumentValuesTest`, `DocumentIsolationTest` (integrator) |
| Rotation is presentation metadata only; voiding preserves history with a reason | `rotation_degrees` column pinned by `guard_document_association_update` trigger; `DocumentService.voidAssociation` + append-only `document_association_event` | `DocumentServiceTest`, `DocumentPolicyTest`, `DocumentIsolationTest` (integrator) |
| Immutable, versioned PDFs; stale version / illegal transition rejected | `rendered_document` UNIQUE(org,type,id,locale,version), append-only triggers, `next_render_version` advisory-lock fold; `If-Match` on void (412) | `DocumentServiceTest`, `DocumentControllerTest`, `PdfRendererTest`, `DocumentIsolationTest` (integrator) |
| English/Arabic, RTL direction, page numbers, provenance, print profile | `PdfRenderer` direction-aware layout, embedded font placeholder, `/PrintScaling /None`, provenance keys, `Page 1 / 1`; locale->direction checked by V17 CHECK constraint | `PdfRendererTest`, `DocumentValuesTest`, `DocumentIsolationTest` (integrator) |
| Tenant isolation, fail-closed authorization, audit | Forced RLS on all three V17 tables; role matrix in `DocumentPolicy`; `DocumentService.authorize` re-derives membership/roles per transaction and audits denials | `DocumentPolicyTest`, `DocumentIsolationTest` (integrator) |

Entry-point-to-test trace: `POST /api/v1/documents/associations` → `DocumentController.link` → `DocumentService.link` → guarded `TransactionTemplate` (tenant + membership + action) → `DocumentRepository.insert` (forced RLS + DB-derived digest) → `appendEvent` + `appendAudit` → 201. `POST /api/v1/documents/rendered` → `DocumentController.render` → `DocumentService.render` → `PdfRenderer.render` → `DocumentRepository.insertRendered` (immutable, versioned, RTL/LTR) → 201. Verified by `DocumentControllerTest` (unit) and `DocumentIsolationTest` (integration, two synthetic tenants).

## Commands run and results (evidence source: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **244 tests, 0 failures, 0 errors, 0 skipped**; 54 are in `com.aurevia.smartclinic.it.s_g1_07` (DocumentControllerTest 5, DocumentInputsTest 11, DocumentPolicyTest 4, DocumentServiceTest 20, DocumentValuesTest 5, PdfRendererTest 9). This is the exact Java command in `scripts/quick-check.sh`.
- `bash scripts/frontend-check.sh` → **EXIT 0** (`frontend standards: policy and tool commands passed`). Includes staff-web `npm run typecheck`, `lint`, `npm test` (vitest) and patient-mobile plus root toolchain checks.
- `python3 -I scripts/project-name.py displayName|repositorySlug` → `Aurevia Care` / `aurevia-care`, EXIT 0.
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0. `bash -n` on all shell scripts → EXIT 0.

I did **not** run the database/integration gate (`db/check.sh`, `mvn verify`, `DocumentIsolationTest`) — per the worker contract those belong to the integrator. Reserved version **V17** is present as `db/migrations/V17__document_associations.sql`; I did not touch any other migration version.

## Defects found

No in-scope defect was found. The S-G1-07 implementation already committed on this branch is complete and its in-scope fast unit tests pass. No in-scope source or test change was necessary; this worker's scoped contribution is verification evidence and this PR description.

## Reported merge-gate failures — triaged, all out of scope / environmental

The untrusted "Requested change" text reported a fast-merge-gate failure whose visible stack trace is a jsdom `reportException` at `apps/staff-web/tests/theme/ThemeProvider.test.tsx:6`. Findings:

1. **That trace is an intentional negative test, not a failure.** `ThemeProvider.test.tsx:31-35` renders `useTheme()` outside its provider and asserts `toThrow(/useTheme/)` (`ThemeProvider.tsx:36-40` throws exactly that message), with `console.error` spied. Locally `bash scripts/frontend-check.sh` runs the full staff-web vitest suite and exits 0. The path is in `apps/staff-web`, outside my declared scope (`apps/api/.../g1/s_g1_07` and `apps/api/.../it/s_g1_07`). I did **not** modify it.
2. **`scripts/quick-check.sh` fails at `check-pack.py` — "opencode.json is stale".** Verified as the documented cloud-worker CI-profile trap: worktree `opencode.json` SHA `279aa1e7…` equals `.github/opencode.ci.json`, carries the skip-worktree flag (`git ls-files -v` → `S`), while `git show HEAD:opencode.json` SHA is `14bba0da…`. `check-pack.py` judges the worktree (CI) profile, not the committed local profile — the known false positive recorded as OPS-CW-003-01. I did **not** regenerate or overwrite `opencode.json` (root-level, out of scope, and it would replace the CI profile with the local one).
3. **`python3 -I scripts/check-migrations.py --require-baseline` fails — "version gap: expected V9, found V17".** This shard contains V1–V8 plus reserved V17; V9–V16 belong to other parallel workers' stories and are absent here. On the integrator's merge with `main` those earlier versions are present, so the contiguity gate applies to the merged tree. I did **not** fabricate V9–V16.
4. **Python tooling tests report 7 failures / 4 errors**, all explained by the above: four `test_check_pack.*` failures + `test_repository_migrations_are_valid` (items 2 and 3); `test_fast_tier`, `test_workflow_concurrency` and two `test_swarm.PlanTests` errors are `ModuleNotFoundError: No module named 'yaml'` (pyyaml absent in this worker image); three `test_agent_run.*` failures are environment-dependent. None is in the S-G1-07 scope or caused by this story.

Because the reported failure is outside my scope and not reproducible as a failure locally, and because resolving items 2–4 requires files/dependencies outside my scope, I stopped at verified in-scope evidence rather than making out-of-scope edits.

## Decisions / notes for the integrator

- No production or test source was changed by this worker; in-scope code was already complete and green.
- Confirm on the **merge result**: V9–V16 present (migration contiguity), the correct `opencode.json` profile applies for `check-pack.py`, and pyyaml is installed for the workflow tests. These are merge/environment conditions, not S-G1-07 changes.
- Coverage (JaCoCo 100% line/branch/method/class) and the real-PostgreSQL `DocumentIsolationTest` are enforced by `mvn verify` / `db/check.sh`, which the integrator runs; I did not fabricate a coverage result.
- Synthetic fixtures only. No patient data or credentials were used or recorded. No human or clinical evidence; all evidence above is tool-generated.
- Commit: no in-scope production/test source change was needed, so this branch's only new commit force-adds this ignored `.agent/pr-notes.md` file (the publish job reads the same file from the worktree artifact). The S-G1-07 implementation itself was already committed at HEAD `a9fdb91`.
