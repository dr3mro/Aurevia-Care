# DLV-04 — Repair integration tests: clinical notes, result routing, access, documents, specimens and trending

Status: ready for integrator. Branch `swarm/DLV-04-255`, base `fefe4cd` (= `origin/main`, clean tree, 0/0). Changes are confined to `apps/api` (4 production files, 4 integration-test fixtures); no migration added or edited, no `.github/`, `scripts/`, `opencode.json` or shared tracker touched. All six named classes pass in the full real-PostgreSQL `mvn verify` run (49/49) and in the canonical `bash apps/api/check.sh` test phase (2096/2096 tests, 0 failures, 0 errors). Synthetic data only. No test was skipped, deleted, re-tagged or excluded from a gate, and no assertion was weakened.

## Requirements and acceptance mapping

| Requirement | Acceptance exercised | Classes / result |
|---|---|---|
| CLN-03, CLN-05 | Clinical note drafts, version-bound edits, signing metadata, amendment/addendum, entered-in-error history, append-only revisions and audit-after-rollback | `ClinicalNoteRevisionIntegrationTest` 14/14 |
| LAB-02, XCH-04 | Identifier matching, import/routing, reconciliation, corrections with reopened review and notifications, critical routing, trend identity | `ResultRoutingIntegrationTest` 12/12; `SpecimenAccessionIsolationTest` 4/4; `TrendingTest` 5/5 |
| IAM-03, IAM-06, ARC-02 | Registration/issuer-scoped MRN, emergency and support access grants, tenant isolation, actor/reason audit | `AccessIntegrationTest` 8/8; `DocumentIsolationTest` 6/6 |
| CLN-07, ARC-01/02/03/04 (supporting) | Originals/renders with checksums, forced RLS concealment, version preconditions, machine-readable errors, atomic audit | `DocumentIsolationTest`, all classes above |

Classification (`docs/WORKFLOW.md`): cross-boundary (SQL boundary, domain enums, HTTP contract, integration fixtures), with a safety-critical authorization/isolation component. Evidence sources are recorded per command below.

## Reproduction (RED) and root cause per class

Environment: Java 21.0.13 Temurin, Maven 3.10.0, PostgreSQL 17 on 127.0.0.1:5432 via `SMARTCLINIC_PG_ADMIN=postgres`. All commands executed by this worker (tool evidence). RED baseline — `cd apps/api && mvn -B -Pno-security -Dtest=ClinicalNoteRevisionIntegrationTest,ResultRoutingIntegrationTest,AccessIntegrationTest,DocumentIsolationTest,SpecimenAccessionIsolationTest,TrendingTest -DfailIfNoTests=false test`: **Tests run 49, Failures 36** (server stack traces read from `apps/api/target/surefire-reports` and a captured full Maven log).

The story's two shared causes were already fixed on this base: V58 redefines `set_link_actor()` and gives `duplicate_review.id` a default (DLV-02), and V59 widens the prescription transition (DLV-03). Only the six DLV-04 classes remained red. The `SELECTid` cause was *not* pre-fixed — it was still present at HEAD and is fixed here at its source.

| Class | Observed RED | Root cause | Fix |
|---|---|---|---|
| `ClinicalNoteRevisionIntegrationTest` (14) | One 500 on `POST /api/v1/clinical-notes`, cascading into 13 `405`/`404`/empty-id failures; the 500 trace showed `PSQLException: syntax error at or near "SELECTid"` at `NoteRepository.current:103`. | **Production defect.** Java text blocks strip trailing whitespace, so `SELECT """ + COLUMNS` produced `SELECTid::text, ...`. Both `current()` and `history()` used the pattern. A further RED layer, unmasked once create worked: `/entered-in-error` returned 400. | `NoteRepository`: replaced the text-block concatenation with `"SELECT " + COLUMNS + """` in both queries. Kept the test's intent but corrected its `affectedRecipients` fixtures: `ErrorRequest.affectedRecipients` is a `String` holding string-encoded JSON (validated by `NoteInputs.recipients`, unit-tested in `ClinicalNoteServiceTest`), so the test now sends `"[\"lab-a\"]"` / `"[]"` instead of a native array that Jackson could not bind to `String`. |
| `AccessIntegrationTest` (6) | 500 on `/api/v1/access/emergency` and `/active`; trace: `PSQLException: conversion to class java.time.Instant from timestamptz not supported` at `AccessRepository.mapGrant:339`. | **Production defect.** pgjdbc maps `timestamptz` to `OffsetDateTime`, not `Instant`; `getObject(col, Instant.class)` fails on every grant/incident read. | `AccessRepository`: added `instant()` and null-safe `nullableInstant()` helpers using `getObject(col, OffsetDateTime.class).toInstant()`, matching the established pattern in `OrderRepository`, `RxRepository` and `ConversationRepository`. Required columns (`granted_at`, `expires_at`, `created_at`) use `instant`; nullable ones (`revoked_at`, `reviewed_at`, `approved_at`) use `nullableInstant`. |
| `ResultRoutingIntegrationTest` (7) | Response fields compared as `unreviewed`/`final`/`critical`/`conflict`/`unmatched`/`acknowledged` but returned as `UNREVIEWED`/`FINAL`/...; one `missing field /patientId`; one concurrent-registration assertion expecting a fixed winner. | **Production defect (wire values) + two test-fixture defects.** The public `ResultModel` enums had no `@JsonValue wire()`, so Jackson emitted uppercase enum names although V22 stores lowercase and the repository reads lowercase wire values. `ResultReport.patientId` lives under `identifiers`, never at the top level. The concurrency test assumed the first submitted `Future` always wins the registration race. | Added `@JsonValue wire()` (lowercase `name()`) to `ResultKind`, `ReviewState`, `ReconciliationReason`, `ReconciliationStatus` — the same contract pattern as `NoteState` and `ClinicalReviewState`; DB binding is unchanged (`ResultRepository` uses explicit lower/upper-case conversion). Pointed the two assertions at `/identifiers/patientId`. Rewrote the race assertion to require exactly one 201 and one 409 without assuming which future wins; the production check-then-insert + unique-key null return already returns 409 to the loser and was left unchanged. |
| `SpecimenAccessionIsolationTest` (3) | `expected: <accessioned> but was: <ACCESSIONED>` (and rejected/recollected). | **Production defect.** `AccessionState.wire()` returns lowercase and is the documented DB/API value, but lacked `@JsonValue`, so Jackson serialized the enum name. | Added `@JsonValue` to the existing `wire()` method. No behavior or DB value change. |
| `DocumentIsolationTest` (5 → after first fix, 2) | All 5 failed at `registerChart` with 409 `MRN already exists for this issuer`; after unique MRNs, 2 failed with `expected: <422 UNPROCESSABLE_ENTITY> but was: <422 UNPROCESSABLE_CONTENT>`. | **Test-fixture defects.** V7 makes `(organization_id, mrn_issuer, mrn, version)` unique and the fixture re-registered the same MRN on every test method against a shared database. Spring 7 renamed the `HttpStatus` reason phrase, so enum comparison fails although the wire code is 422 (same cause DLV-02 fixed elsewhere). | `registerChart` now appends `UUID.randomUUID()` to the issuer-scoped MRN. The three 422 assertions now compare the numeric wire code (`assertEquals(422, ...getStatusCode().value())`). |
| `TrendingTest` (1) | `invalidInputsFailAtTheBoundary`: blank `testCode=%20` returned 200 with body `{"testCode":"%20",...}` instead of 422. | **Test-fixture defect, proven by a temporary diagnostic (added, run, removed).** RestClient re-encodes the `%`, so the server receives the literal string `%20`, which is not blank; `TrendService.code()` correctly rejects only blank values. | Send an empty value `?testCode=` so the boundary receives a genuinely blank string. Production validation unchanged. |

No applied migration was edited. No new migration was needed: every observed defect was in Java bindings, enum serialization or test fixtures; the V7/V15/V22/V26 schema invariants and triggers were correct.

## Exact commands and results

| Command | Result |
|---|---|
| `cd apps/api && mvn -B -Pno-security -Dtest=<six classes> -DfailIfNoTests=false test` (before fixes) | exit 1 — Tests run 49, Failures 36, Errors 0 |
| Same command after the SQL/binding/enum/fixture fixes | exit 1 — Tests run 49, Failures 4 (entered-in-error 400, two Spring-7 422 enum comparisons, one race assumption) |
| Same command after the four remaining fixture fixes | exit 0 — **Tests run 49, Failures 0, Errors 0** (Trending 5, ClinicalNote 14, Specimen 4, Access 8, ResultRouting 12, Document 6) |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0 — Tests run 1853, Failures 0, Errors 0 |
| `bash apps/api/check.sh` (full `mvn -q -B verify`) | test phase: **2096 tests, 0 failures, 0 errors**; `mvn verify` then exit 1 at `jacoco:check` for **pre-existing coverage gaps in untouched classes** (see Defects). All six target classes green in that same run. |
| `mvn -q -B -Pno-security -Dtest=ResultRoutingIntegrationTest -DfailIfNoTests=false test` × 3 | exit 0, exit 0, exit 0 — race fix is stable |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `python3 -I -m unittest discover -s scripts/tests -q` | all exit 0 |
| `git diff --check` | exit 0 |
| `bash scripts/worker-check.sh` | exit 1 at `check-pack: opencode.json is stale` — **environmental artifact, not caused by this change** (see Defects) |
| `bash db/check.sh` | exit 3 — pre-existing, unrelated tooling defect (see Defects) |

Entry-point-to-test trace: `POST /api/v1/clinical-notes` → `ClinicalNoteController.create` → `ClinicalNoteService.createDraft` → `NoteRepository.current` (fixed SQL) → 200; asserted by `createDraftAndReadFullHistory`. `POST /api/v1/access/emergency` → `AccessController.grant` → `EmergencyAccessService.grant` → `AccessRepository.insertGrant`/`mapGrant` (fixed instant mapping) → 201; asserted by `qualifiedClinicianGrantsAndChecksActiveAccess`. `POST /api/v1/xch04/results` → `ResultController.importResult` → `ResultRoutingService` → `ResultReport` with `@JsonValue` enums → lowercase wire values; asserted by `matchedResultIsImportedRoutedAndAudited`.

## Defects found and disposition

- **Pre-existing JaCoCo gaps, not changed here (owner: Delivery / owning stories).** Now that surefire reaches `verify`, `jacoco:check` reports three untouched classes below 100%: `ReleaseService` (lines 211–213, `held release must be lifted before correction` branch), `patient.chart.ChartRepository` (`verify`, line 171 partial branch) and one `FinanceService.postEntry` conflict branch. None of these classes is in `git diff`; none imports a modified enum. They are unrelated to DLV-04 and are outside the one-story rule. DLV repair PRs merge through `scripts/repair-gate.sh` (which excludes JaCoCo); JaCoCo/OWASP/SpotBugs run in Delivery after merge.
- **Pre-existing `db/check.sh` defect (owner: pipeline/integrator).** It applies migrations with `for migration in db/migrations/V*.sql`, which bash sorts lexically (`V10` before `V1`), failing with `relation "public.organization" does not exist`. Documented in DLV-02/DLV-03 notes. `db/check.sh` is a protected check file; not edited here. The Java integration suites apply migrations in Flyway version order and are green.
- **Environmental `opencode.json` artifact (owner: worker harness).** `git ls-files -v opencode.json` shows flag `S` (skip-worktree): the worktree holds the CI profile copied by `agent-run.sh`, so `check-pack.py`/`gen-opencode.py --check` judge that, not the committed local profile. Verified programmatically that `git show HEAD:opencode.json` equals `gen-opencode.py`'s generated local profile (`True`) while the worktree copy differs (`False`). This is the known OPS-CW-003-01 false positive; no regeneration was performed.
- No `BUGS.md` entry added: every defect in scope was reproduced and fixed in this change; workers do not edit shared trackers.

## Decisions

- Fixed the real cause in each case rather than re-tagging or weakening tests: SQL concatenation, JDBC type mapping and enum wire serialization were production defects; MRN reuse, a wrongly encoded query parameter, a wrong JSON field path, a native-vs-string JSON array, Spring-7 enum comparisons and a fixed-winner race assumption were fixture defects.
- Added `@JsonValue` to existing/new `wire()` methods rather than changing tests to expect uppercase: lowercase is the documented DB and API value across the codebase, and DB binding paths are unaffected.
- Did not add a migration: every schema invariant exercised by these tests was already correct.
- Did not touch the three pre-existing JaCoCo gaps or `db/check.sh`; both are outside this story's acceptance and one of them is a protected file.

## Evidence provenance

All command results, stack traces and the temporary diagnostic were **tool-generated** by this worker (Maven/Surefire, surefire XML/TXT reports, JaCoCo XML/CSV, git, policy scripts). No reviewer, simulated-user, human or clinical evidence was used or claimed. Every subject, organization, patient, MRN, accession and token in the fixtures is synthetic; no credentials, patient data or raw logs appear in this note.
