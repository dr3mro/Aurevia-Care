# DLV-02 — Repair integration tests: patient identity, consolidation and messaging

Status: ready for integrator. Branch `swarm/DLV-02-229`, base `9ac295a` (clean worker branch containing merged DLV-01). Scope respected: `apps/api` + `db` only. No tracker, `.github/`, `scripts/`, generated config or contract file was touched. No test was skipped, deleted, re-tagged or excluded from any gate.

## Requirements and acceptance mapping

| Requirement | Acceptance repaired | Evidence (class / gate) |
|---|---|---|
| IAM-03 | Registration-derived immutable chart identity remains the ownership anchor when staff change contact data; contacts never leak into identity-link material | `IdentityProofingIsolationTest.contactChangeDoesNotChangeAccountOwnership` |
| IAM-04 | A duplicate review ticket can be opened (database-owned id), and once consolidated the alias leaves the duplicate worklist on **both** sides of the ordered pair; review/consolidation/correction flows run end to end | `ConsolidationIsolationTest` (7 tests) |
| IAM-05 | Single-use expiring invitations, claim/unlink/reverify links and the append-only link projection work against real PostgreSQL; expired invitations are genuinely expired; boundary validation is 422 | `IdentityProofingIsolationTest` (11), `StaleInvitationIntegrationTest` (4) |
| MSG-07 | Conversation work-item guards, tenant-scoped folds and the documented `View` response contract are asserted through real HTTP + forced RLS; work-item/assignment audit state is observable | `ConversationFlowTest` (8) |
| Supporting ARC-01/02/03, OPS-02 | Composite tenant keys, forced RLS, `app.current_org`/`app.actor` trigger guards, append-only history and transaction-committed audit remain enforced; no guard was weakened | all four classes, `scripts/check-migrations.py`, `mvn spotbugs:check` |

Classification (`docs/WORKFLOW.md`): cross-boundary (SQL migration + production query + integration fixtures), with safety-critical identity/worklist behavior. Synthetic fixtures only. Evidence sources are recorded per command below: **tool** (commands executed by this worker), **simulated reviewer** (bounded read-only specialists), **direct SQL reproduction** (psql against scratch DB). No simulated-user, human or clinical participant evidence was available or claimed.

## Root cause per class (all reproduced RED first)

RED command (exact):
```
cd apps/api && mvn -B -Pno-security \
  -Dtest=IdentityProofingIsolationTest,StaleInvitationIntegrationTest,ConsolidationIsolationTest,ConversationFlowTest \
  -DfailIfNoTests=false test
```
RED result: `Tests run: 30, Failures: 20, Errors: 4` (exit 1). Server traces read from `apps/api/target/surefire-reports/*.xml`.

| Class / symptom | Root cause | Fix | Cause owner |
|---|---|---|---|
| `IdentityProofingIsolationTest` — 8 × HTTP 500 on `POST /links/claim`; `StaleInvitationIntegrationTest` — 500 on claim; cascading `false`/404 on access and reverify | V10's `public.set_link_actor()` assigns `NEW.created_by`, but `account_chart_link` has `verified_by` and **no `created_by`** column: `ERROR: record "new" has no field "created_by"` in the row BEFORE INSERT trigger, so every link write failed. The story's hypothesis that two migrations redefined the function was **not observed** on this checkout: repo-wide grep found exactly one definition (V10) and one attachment (`account_chart_link_created_by_actor`). | New forward-only migration `V58__identity_actor_and_review_id_repair.sql` redefines `set_link_actor()` to set only `NEW.verified_by` from `app.actor` (same fail-closed guard). Applied migrations were not edited. | Schema defect |
| `IdentityProofingIsolationTest.invalidInputsReturnUnprocessableEntity` (3), `ConsolidationIsolationTest.identifierConfirmationIsEnforcedAgainstTheTwoRecords`, `ConversationFlowTest.assignTransferAndSameUnitConstraint` / `preconditionsValidationAndTenantIsolation` | Test defect: Spring 7 renamed `HttpStatus.UNPROCESSABLE_ENTITY` to `UNPROCESSABLE_CONTENT`; enum identity comparison failed although the wire code is 422 in both cases. | Assert the numeric wire code (`assertEquals(422, response.getStatusCode().value())`). Production status codes unchanged. | Test defect |
| `IdentityProofingIsolationTest.expiredInvitationIsUnusable` | Test fixture backdated only `expires_at`, violating V10's `CHECK (expires_at > created_at)` (`account_invitation_check`). | Backdate `created_at` by two hours and `expires_at` by one hour in the same UPDATE, so the invitation is genuinely expired and the invariant holds. | Test defect |
| `IdentityProofingIsolationTest.contactChangeDoesNotChangeAccountOwnership` | Test fixture tried to append a new `patient_record` version with the same id via `next_patient_version`, but V7 declares `PRIMARY KEY (organization_id, id)` — duplicate key on `patient_record_pkey`. | Keep every original assertion unchanged; model the staff contact change as a re-registered chart carrying the changed contact, so ownership must still follow the original immutable chart id and contact data must not leak into links. (Latent schema inconsistency between V7's PK and its version fold/`next_patient_version` is recorded as a follow-up defect below.) | Test defect |
| `ConsolidationIsolationTest` — 500 on `POST /duplicate-reviews`, then 404s from empty review ids | V9 declares `duplicate_review.id uuid NOT NULL` with no default, while `DuplicateRepository.insertReview` deliberately omits the column and reads the ticket back by `(survivor, duplicate)`: `null value in column "id" ... violates not-null constraint`. | V58 adds `ALTER TABLE public.duplicate_review ALTER COLUMN id SET DEFAULT gen_random_uuid()`, matching the other append-only tables. Repository contract unchanged. | Schema defect |
| `ConsolidationIsolationTest.consolidationNeedsSecondReviewerAndUnblockedDependencies` — `consolidated pair leaves the worklist` | Production defect in `DuplicateRepository.likelyPairs()`: `NOT EXISTS ... a.alias_id = q.id` excluded a consolidated alias only on the duplicate (q) side. With several same-name/DOB patients, the alias reappeared as `survivor_id` of a pair with a higher id. Reproduced with a scratch DB: after associating `bbbb…`, the worklist still returned `(bbbb…, cccc…)`. | Filter both sides: `a.alias_id IN (p.id, q.id) AND a.status = 'ACTIVE'`. Re-verified on the scratch DB: the row disappears. | Production defect |
| `ConversationFlowTest.patientOpen…` / `databaseGuardsRejectIllegalWrites` — `NullPointerException` on `JsonNode.get(...)` | Test defect: `GET /api/v1/conversations/{id}` returns the documented `View {conversation, messages, events}`; the test read `state`/`version`/`patientId` at the top level (the reply command correctly unwraps `conversation`). | Unwrap `.get("conversation")` for both GET assertions. No controller/contract change. | Test defect |
| `ConversationFlowTest.directFoldAssertionsConfirmVerifiedLinkAndForcedRls` | Test SQL bound a `String` into `WHERE id = ?` against a `uuid` column: `operator does not exist: uuid = character varying`. | Cast in the test SQL: `WHERE id = CAST(? AS uuid)`. | Test defect |

The story's note about `set_link_actor()` being attached to `duplicate_review` is inaccurate for this checkout: V9 attaches `set_duplicate_created_by_from_actor()` only to tables that do have `created_by` (`chart_association`, `consolidation`, `consolidation_dependency`). The `duplicate_review` failure was the missing id default, fixed separately in V58.

## Bounded specialist reviews (simulated reviewer evidence, no commands run by reviewers)

- **Senior Database (V58):** no blocking findings. `CREATE OR REPLACE FUNCTION` preserves the function OID, so the single `account_chart_link_created_by_actor` trigger still resolves to the new body; signature, language and `SET search_path` are unchanged. The default touches only the surrogate half of the composite key, so forced RLS/`WITH CHECK`, append-only triggers and grants are preserved; `Change: expand` matches the two non-destructive statements; keeping the historical function name is preferred because a rename would need a destructive `DROP TRIGGER`/`DROP FUNCTION` contract for one attachment. Advisory (non-blocking): V10 omitted `REVOKE ... FROM PUBLIC` on `set_link_actor()` although it did so on its sibling actor functions. **Addressed:** V58 now adds `REVOKE ALL ON FUNCTION public.set_link_actor() FROM PUBLIC;` (trigger execution needs no PUBLIC EXECUTE; V10's sibling trigger functions already prove the pattern safe).
- **Senior Backend (`likelyPairs()`):** no blocking findings. Correct domain fix: IAM-04 calls the worklist "pairs still visible as separate charts", and a consolidated alias is no longer separate, so excluding it on both sides of the ordered join is right. No regression for REVERSED corrections (status fold no longer ACTIVE, so the alias legitimately reappears), `open()` idempotency (never calls `likelyPairs()`), tenant scope (both folds are SECURITY INVOKER over forced-RLS tables) or other callers (only `worklist()`). Non-blocking pre-existing observation: after consolidate → correct the alias is advertised in the worklist but `open()` refuses it with 409 because a `CORRECTED` consolidation still exists; a product/owner decision on re-review policy is needed, not a DLV-02 regression.

## Exact commands and results (evidence source: executed tool commands unless stated)

Environment: Java 21.0.13 Temurin, Maven 3.10.0, PostgreSQL 17 on 127.0.0.1:5432 via `SMARTCLINIC_PG_ADMIN=postgres`; all commands executed by the primary agent.

| Command | Result |
|---|---|
| Targeted four-class run (before fixes) | exit 1 — `Tests run: 30, Failures: 20, Errors: 4` |
| `python3 -I scripts/check-migrations.py --require-baseline` after adding V58 | exit 0 |
| Targeted four-class run after trigger/id/status/fixture fixes | exit 1 — `Tests run: 30, Failures: 1, Errors: 1` (worklist + patient_record duplicate key) |
| Direct psql reproduction against scratch DB `dlv02_debug` (migrations applied in version order, two same-name/DOB patients, one ACTIVE association) | before fix: worklist returned `(bbbb…, cccc…)` although `current_chart_associations()` showed `bbbb… ACTIVE`; after applying the both-sides filter: 0 rows — tool/SQL evidence |
| Targeted four-class run after final fixes | **exit 0 — `Tests run: 30, Failures: 0, Errors: 0`** (IdentityProofing 11/11, Consolidation 7/7, ConversationFlow 8/8, StaleInvitation 4/4) |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0 — `Tests run: 1853, Failures: 0, Errors: 0` |
| `bash apps/api/check.sh` (full `mvn verify`, default security profile) | exit 1 overall — first run `Tests run: 2096, Failures: 44, Errors: 0`, final run after the review advisory `Tests run: 2096, Failures: 43, Errors: 0` (one pre-existing DLV-04 failure is timing-dependent). All four DLV-02 classes green in both runs (11/11, 7/7, 8/8, 4/4); **none** appears in the failing-class list. |
| `cd apps/api && mvn -B -q -Pno-security -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false spotbugs:check` | exit 0 (no findings) |
| `git diff --check` | exit 0 |
| `bash db/check.sh` | exit 3, pre-existing and unrelated (see Defects below) |

## Full-gate remainder (not DLV-02 work)

The 44 remaining failures in the full run belong exclusively to the classes owned by the dependent stories: `RxTransmissionFlowTest` (DLV-03); `ClinicalNoteRevisionIntegrationTest`, `ResultRoutingIntegrationTest`, `AccessIntegrationTest`, `DocumentIsolationTest`, `SpecimenAccessionIsolationTest`, `TrendingTest` (DLV-04). The untrusted story text suggested fixing them "after the classes listed here", but `TODO.md` assigns them to DLV-03/DLV-04 (which depend on DLV-02) and this worker is explicitly limited to one story with parallel workers active. They were deliberately left untouched to avoid cross-worker conflicts. DLV-02's V58 migration also removes the shared `set_link_actor()` cause from those suites; their other causes (state casing, 405 routes, duplicate MRN fixtures, prescription transmission) remain with their owners.

## Decisions and defects recorded

- **New migration V58, no applied migration edited.** Highest applied/reserved version is V55 (V9–V57 reserved by spec stories); V58 is the first free version and gaps are allowed by `check-migrations.py` (S-FIX-MIG-GAP-01). `Change: expand`: both statements are additive/idempotent (`CREATE OR REPLACE FUNCTION`, `ALTER COLUMN … SET DEFAULT`), not destructive.
- **Kept the trigger function name.** Only one trigger references `set_link_actor()`; renaming it would require a destructive `DROP TRIGGER`/`DROP FUNCTION` contract for no behavioral gain. V58 documents why. No grant change is needed because `CREATE OR REPLACE` preserves function ACLs.
- **No production code change for the 422 cases.** The server already returns 422 (`UNPROCESSABLE_CONTENT`); only the tests' enum comparison was stale.
- **No schema change for the V7 PK/version inconsistency** (defect found, deferred with owner): `patient_record` has `PRIMARY KEY (organization_id, id)`, `UNIQUE (organization_id, id, version)` and `current_patients()` folds by version, so a same-id append is impossible while `next_patient_version()` suggests otherwise. Fixing it means changing the key and every `(org,id)` FK — a contract migration and clinical-record-integrity review well beyond DLV-02. The affected test's assertions were preserved and its fixture adapted.
- **Pre-existing tooling defect found, not fixed:** `db/check.sh` applies migrations with `for migration in db/migrations/V*.sql`, which bash sorts lexically (`V10…` before `V1__…`). On this checkout it fails at `V10` with `relation "public.organization" does not exist` before any DLV-02 file matters. The Java integration suites apply migrations through Flyway (version order) and are green; the offline migration checker is green. Owner follow-up: sort numerically in `db/check.sh` (out of this worker's one-story boundary).
- **Self-improvement:** agent/skill files were not edited because they are outside the declared `apps/api`, `db` scope and swarm story PRs touching `.opencode/` are held for a human. The reproduction commands and root causes above are the hand-off.

## Entry-point-to-test trace

`POST /api/v1/patient-identity/links/claim` → `IdentityProofingController` → `IdentityProofingRepository.insertLink` → (V58-corrected `set_link_actor()` sets `verified_by`) → `currentLink()` → 200 with the append-only event id; asserted by `IdentityProofingIsolationTest` and `StaleInvitationIntegrationTest` against real PostgreSQL.
`POST /api/v1/duplicate-reviews` → `DuplicateRepository.insertReview` → (V58 id default) → 201 → `GET /api/v1/duplicate-reviews` → `DuplicateRepository.likelyPairs()` (both-sides filter) → consolidated alias absent; asserted by `ConsolidationIsolationTest`.
`GET /api/v1/conversations/{id}` → `ConversationController.get` → `View` → test unwraps `conversation`; `databaseGuardsRejectIllegalWrites` and `directFoldAssertionsConfirmVerifiedLinkAndForcedRls` assert the forced-RLS/append-only guards with cast-bound UUIDs; asserted by `ConversationFlowTest`.

## Evidence provenance

All commands above were executed by this worker (tool evidence); the scratch-DB worklist reproduction is tool/SQL evidence; the two bounded specialist reviews noted below are simulated reviewer evidence. No patient data, real identifiers or credentials appear in any fixture or note; every subject, organization, MRN and token is synthetic.
