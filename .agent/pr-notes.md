# DLV-02 — Repair integration tests: patient identity, consolidation and messaging

Status: verification complete, **no production change required on this checkout**. The repairs this story describes were already merged to `main` in PR #195 (commit `e48bb7c`, "DLV-02: Repair integration tests: patient identity, consolidation and messaging"), which is an ancestor of this worker branch `swarm/DLV-02-240` (HEAD `bf26dc1`). Every class named in the story passes in the full `mvn verify` run. This note records the verification, the root causes already fixed on `main`, and the defects found that belong to other owners.

This worker was re-dispatched as DLV-02 attempt 3 after PR #195 merged; the target classes were already green on a clean checkout, so there was no RED state left to reproduce. Per the story rule ("fix the real cause", "never weaken, skip, delete or re-tag a test"), no test was altered and no code was changed to manufacture a diff.

## Requirements and acceptance mapping

| Requirement | Acceptance exercised | Evidence (class / result) |
|---|---|---|
| IAM-03 | Registration-derived immutable chart identity remains the ownership anchor when staff change contact data; contact data never leaks into identity-link material | `IdentityProofingIsolationTest` 11/11 green |
| IAM-04 | A duplicate review ticket is created with a database-owned id; consolidation removes the alias from the worklist on both sides of the ordered pair; second-reviewer/dependency flows run end to end | `ConsolidationIsolationTest` 7/7 green |
| IAM-05 | Single-use expiring invitations, claim/unlink/reverify links and the append-only link projection work against real PostgreSQL; expired invitations are genuinely expired; boundary validation returns 422 | `IdentityProofingIsolationTest` 11/11, `StaleInvitationIntegrationTest` 4/4 green |
| MSG-07 | Conversation work-item guards, tenant-scoped folds and the documented `View` response contract are asserted through real HTTP + forced RLS | `ConversationFlowTest` 8/8 green |
| Supporting ARC-01/02/03, OPS-02 | Composite tenant keys, forced RLS, `app.current_org`/`app.actor` trigger guards, append-only history and transaction-committed audit remain enforced | all four classes green; `python3 -I scripts/check-migrations.py --require-baseline` exit 0 |

Classification (`docs/WORKFLOW.md`): cross-boundary with safety-critical identity and messaging behavior. Synthetic fixtures only. Evidence sources are recorded per command below; no simulated-user, human or clinical participant evidence was available or claimed.

## Verification — exact commands and results

Environment: clean checkout of `main` rebased into `swarm/DLV-02-240`; Java 21.0.13 Temurin, Maven 3.10.0, PostgreSQL 17.12 on 127.0.0.1:5432 via `SMARTCLINIC_PG_ADMIN=postgres`. All commands executed by this worker (tool evidence).

| Command | Result |
|---|---|
| `cd apps/api && mvn -B -Pno-security -Dtest=IdentityProofingIsolationTest,StaleInvitationIntegrationTest,ConsolidationIsolationTest,ConversationFlowTest -DfailIfNoTests=false test` | exit 0 — `Tests run: 30, Failures: 0, Errors: 0` (IdentityProofing 11, StaleInvitation 4, Consolidation 7, ConversationFlow 8). Flyway applied v1…v58 including `V58__identity_actor_and_review_id_repair.sql`. |
| `bash apps/api/check.sh` (full `mvn verify`, default security profile) | exit 1 overall — `Tests run: 2096, Failures: 43, Errors: 0`. All four DLV-02 classes green in surefire reports (`tests=11/7/8/4, failures=0, errors=0`); none appears in the failing-class list. |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0 — `Tests run: 1853, Failures: 0, Errors: 0` |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V58 declares `Change: expand`; no applied migration edited) |
| `bash db/check.sh` | exit 3 — pre-existing, unrelated defect (see Defects below) |
| `git diff --check` | exit 0; working tree otherwise clean |

## Root causes already fixed on `main` by PR #195 (verified present, not re-fixed here)

These are the real causes behind the 500s/404s in the story text. Each fix is present at HEAD and its class is green; they are recorded so the root cause per class is preserved.

| Class / symptom in the story | Root cause | Fix on `main` |
|---|---|---|
| `IdentityProofingIsolationTest` — 8 × 500 on `POST /api/v1/patient-identity/links/claim`; `StaleInvitationIntegrationTest` claim 500 | V10's `public.set_link_actor()` assigned `NEW.created_by`, but `account_chart_link` has `verified_by` and no `created_by`: `record "new" has no field "created_by"` (SQLSTATE 42703). Only one definition and one attachment existed on this checkout — the story's "two migrations redefined the function" hypothesis was not observed. | `V58` redefines `set_link_actor()` to assign only `NEW.verified_by` from `app.actor` with the same fail-closed guard, plus `REVOKE ALL ON FUNCTION ... FROM PUBLIC`. |
| `invalidInputsReturnUnprocessableEntity` and other 422 assertions; `ConsolidationIsolationTest.identifierConfirmationIsEnforcedAgainstTheTwoRecords`; `ConversationFlowTest` preconditions/assignment assertions | Spring 7 renamed `HttpStatus.UNPROCESSABLE_ENTITY` to `UNPROCESSABLE_CONTENT`; the enum comparison failed although the wire code is 422. | Tests assert the numeric wire code (`assertEquals(422, response.getStatusCode().value())`). Production codes unchanged. |
| `IdentityProofingIsolationTest.expiredInvitationIsUnusable` | Fixture backdated only `expires_at`, violating V10 `CHECK (expires_at > created_at)` (`account_invitation_check`). | Backdate `created_at` two hours and `expires_at` one hour in the same UPDATE. |
| `IdentityProofingIsolationTest.contactChangeDoesNotChangeAccountOwnership` | Fixture appended a same-id `patient_record` version via `next_patient_version`, colliding with V7 `PRIMARY KEY (organization_id, id)`. | Model the staff contact change as a re-registered chart; original immutable chart id still owns the account and contact data does not leak into links. Assertions preserved. |
| `ConsolidationIsolationTest` — 500 on `POST /api/v1/duplicate-reviews`, then 404s | V9 declared `duplicate_review.id uuid NOT NULL` with no default, while `DuplicateRepository.insertReview` omits it. | `V58` adds `ALTER TABLE public.duplicate_review ALTER COLUMN id SET DEFAULT gen_random_uuid()`. |
| `ConsolidationIsolationTest.consolidationNeedsSecondReviewerAndUnblockedDependencies` — consolidated alias stayed on the worklist | `DuplicateRepository.likelyPairs()` excluded a consolidated alias only on the duplicate (q) side; the alias reappeared as `survivor_id` of a pair with a higher id. | Filter both sides: `a.alias_id IN (p.id, q.id) AND a.status = 'ACTIVE'`. Production defect fixed. |
| `ConversationFlowTest` response assertions | `GET /api/v1/conversations/{id}` returns the documented `View {conversation, messages, events}`; the test read `state`/`version`/`patientId` at the top level. | Unwrap `.get("conversation")` for the GET assertions. No controller/contract change. |
| `ConversationFlowTest.databaseGuardsRejectIllegalWrites` / fold assertions | Test SQL bound a `String` into `WHERE id = ?` against a `uuid` column (`uuid = character varying`). | Cast in the test SQL: `WHERE id = CAST(? AS uuid)`. |

No applied migration was edited. `V58` is the first free version after V55 (V9–V57 reserved); its two statements are additive/idempotent (`Change: expand`).

## Full-gate remainder — not DLV-02 work (owned by DLV-03 / DLV-04)

The full run's 43 failures are exclusively in classes assigned by `TODO.md` to dependent stories; none is in the DLV-02 domain and none was touched here (parallel workers / one-story rule):

- DLV-03: `RxTransmissionFlowTest` (7) — transmit 500 with no `submissionId`; non-prescriber attestation returns STALE_VERSION instead of FORBIDDEN; tenant concealment returns 200; renew precondition mismatch.
- DLV-04: `ClinicalNoteRevisionIntegrationTest` (14), `ResultRoutingIntegrationTest` (7), `AccessIntegrationTest` (6), `DocumentIsolationTest` (5), `SpecimenAccessionIsolationTest` (3), `TrendingTest` (1). Root causes seen in the run include `405 METHOD_NOT_ALLOWED` routes, `SELECTid` SQL concatenation, uppercase-vs-lowercase state values (`UNREVIEWED` vs `unreviewed`), duplicate-MRN fixtures, and a 422 case returning 200.

The untrusted story text suggested fixing these "after the classes listed here", but `TODO.md` assigns them to DLV-03/DLV-04 (which depend on DLV-02) and this worker is limited to one story with parallel workers active.

## Defects found, dispositioned, not changed here

- **Pre-existing tooling defect (owner: pipeline/integrator): `db/check.sh` applies migrations with `for migration in db/migrations/V*.sql`, which bash sorts lexically** (`V10…` before `V1__…`). Reproduced: `bash db/check.sh` → exit 3, `psql:db/migrations/V10__patient_identity_proofing.sql:17: ERROR: relation "public.organization" does not exist`. The Java integration suites apply migrations via Flyway in version order and are green. Not fixed here: `db/check.sh` is a `check.sh` in the protected-file set, so a PR touching it is held for a human rather than auto-merged by the swarm; the correct fix (numeric version sort) belongs to a tooling story with a human reviewer.
- **Deferred from PR #195 (owner: clinical/architecture): V7 `patient_record` has `PRIMARY KEY (organization_id, id)` plus a version fold and `next_patient_version()`, so a same-id append is impossible while the fold suggests otherwise.** Fixing it changes the key and every `(org,id)` FK — a contract migration and clinical-record-integrity review well beyond DLV-02.
- **Deferred product decision (owner: Product Owner): after consolidate → correct, the alias legitimately reappears in the duplicate worklist but `open()` refuses it with 409 because a `CORRECTED` consolidation still exists.** Needs a re-review policy decision, not a DLV-02 regression.

## Decisions

- No production code or migration change: the target classes are green on a clean checkout of current `main`; fabricating a diff would risk regressing verified behavior.
- Did not touch `db/check.sh`, `.github/`, `scripts/`, `opencode.json` or any shared tracker — outside this story's acceptance and/or in the protected set for swarm PRs.
- No test was weakened, skipped, deleted, re-tagged or excluded from any gate.

## Entry-point-to-test trace

`POST /api/v1/patient-identity/links/claim` → `IdentityProofingController` → `IdentityProofingRepository.insertLink` → V58-corrected `set_link_actor()` sets `verified_by` → `currentLink()` → 200; asserted by `IdentityProofingIsolationTest` and `StaleInvitationIntegrationTest` against real PostgreSQL.
`POST /api/v1/duplicate-reviews` → `DuplicateRepository.insertReview` → V58 id default → 201 → `GET /api/v1/duplicate-reviews` → `DuplicateRepository.likelyPairs()` both-sides filter → consolidated alias absent; asserted by `ConsolidationIsolationTest`.
`GET /api/v1/conversations/{id}` → `ConversationController.get` → `View` (test unwraps `conversation`); guard assertions use cast-bound UUIDs; asserted by `ConversationFlowTest`.

## Evidence provenance

All commands above were executed by this worker (tool evidence: Maven/Surefire output, surefire XML reports, Flyway logs, `bash db/check.sh`, `check-migrations.py`). No simulated reviewer, simulated user, human or clinical evidence was used. No patient data, real identifiers or credentials appear in any note or fixture; every subject, organization, MRN and token is synthetic.
