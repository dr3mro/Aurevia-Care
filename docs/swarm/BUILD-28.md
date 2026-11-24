# BUILD-28 · Persist message attachments and quarantine with JDBC

PR #307, tested branch tip 0471d27f394f116fd8786633071c1fe09c04a714.

Task: BUILD-28

Swarm worker 7. Local checks: passed. Agent result: ok.

# BUILD-28 — Persist message attachments and quarantine with JDBC

## Requirement IDs and acceptance mapping

| ID | Acceptance | Evidence |
|---|---|---|
| MSG-03 | A draft upload is bound to its uploader and conversation and cannot be reused; validation/scan/quarantine stay private; an unscanned file is not downloadable | `JdbcAttachmentStoreIntegrationTest.draftIsBoundToItsUploaderAndConversationAndCannotBeReused`, `databaseTriggerRejectsRebindingForTheMigrationOwnerToo`, `unscannedFileIsNotDownloadable`; `AttachmentAcceptanceTest` (all existing domain acceptance) |
| ARC-06 | Checksum, size, type and scan state persisted; quarantine private; mediated download; append-only fact ledger; object keys out of the DB | `V82__attachment_store.sql` tables `attachment`, `attachment_event`; `JdbcAttachmentStore` mappers; `JdbcAttachmentStoreIntegrationTest.otherUnitCannotSeeOrDownloadTheFile`; `AttachmentControllerHttpTest.uploadsThroughMultipartScansAndDownloads` |
| ARC-07 | Conversation-scoped attachment binding; two tenants with identical local IDs stay isolated | `JdbcAttachmentStoreIntegrationTest.twoTenantsWithIdenticalLocalIdentifiersStayIsolated`, `productionBeanIsTheJdbcStore` |

Scope respected: only `messaging/attachments` main/test packages and `db/migrations/V82__attachment_store.sql`. No dependency added. No tracker, workflow, agent, script, baseline or OpenAPI file was edited. All identifiers are synthetic.

## Implementation

- **New** `db/migrations/V82__attachment_store.sql`: `attachment`, `attachment_connection_grant`, `attachment_event`. Each table has ENABLE + FORCE RLS with a `tenant_isolation` policy (`USING`/`WITH CHECK` = `public.app_org()`), explicit GRANT/REVOKE to `aurevia_app`. Append-only triggers on `attachment_event` cover every role including the owner; `attachment_binding_immutable` rejects rebinding of uploader/conversation/unit/patient/checksum for every role. `Change: expand` header.
- **New** `JdbcAttachmentStore` (`@Repository`): the single `src/main` `AttachmentStore` bean. Uses `NamedParameterJdbcTemplate` + the tenant-guarded `TransactionTemplate`; `INSERT … ON CONFLICT DO UPDATE … WHERE <binding matches> RETURNING id` yields an empty result when a draft would be rebound, which raises `ApiException(409, "WRONG_CONVERSATION")`.
- **Moved** `InMemoryAttachmentStore` from `src/main` to `src/test` (same package), so all existing tests compile unchanged and no in-memory fake remains in production.
- `AttachmentService` is now a `@Service` bean; constructor unchanged.
- `AttachmentController` now receives `AttachmentService` and `AttachmentStore` by injection; no `new ...Service(...)` or `new ...Store(...)` remains in main source.
- `AttachmentContract.AttachmentState.from(String)` added for DB wire-value mapping.

## Test evidence (tool evidence; commands executed locally)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='AttachmentAcceptanceTest,AttachmentControllerHttpTest,JdbcAttachmentStoreTest' test` | exit 0; 29 + 8 + 10 tests, 0 failures/errors |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JdbcAttachmentStoreIntegrationTest test` | exit 0; 7 tests, 0 failures/errors; Flyway applied 33 migrations ending `v82` |
| `python3 -I scripts/check-wiring.py` | exit 0; 3 stale-baseline notes for the attachment files (debt paid down; baseline edit is out of scope) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` (committed `opencode.json` profile) | 436 tests, OK — see Findings for the worker-profile artifact |

Entry-point-to-test trace: `POST /api/v1/patient/files/upload` and `GET /api/v1/patient/files/{id}/download` → `AttachmentController` (injected `AttachmentService` + `AttachmentStore`) → `AttachmentService` → `JdbcAttachmentStore` → V82 tables. HTTP route proven by `AttachmentControllerHttpTest` and full Spring context load in `JdbcAttachmentStoreIntegrationTest.productionBeanIsTheJdbcStore`.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='JdbcAttachmentStoreTest,JdbcAttachmentStoreIntegrationTest,AttachmentAcceptanceTest,AttachmentControllerHttpTest' test jacoco:report` — exit 0. JaCoCo `apps/api/target/site/jacoco/jacoco.xml` read per class:

| Class | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `JdbcAttachmentStore` | 433/433 | 2/2 | 81/81 | 22/22 | 1/1 |
| `AttachmentService` | 963/963 | 86/86 | 168/168 | 28/28 | 1/1 |
| `AttachmentController` | 216/216 | 4/4 | 40/40 | 11/11 | 1/1 |
| `AttachmentContract$AttachmentState` | 121/121 | 26/26 | 18/18 | 6/6 | 1/1 |
| all other `AttachmentContract$*` | 100% | 100% where present | 100% | 100% | 1/1 |

Every new branch and its test:

| New branch / path | Test |
|---|---|
| `grant` present / absent | `JdbcAttachmentStoreTest.grantReadsTheMappedRowWhenPresent`, `grantIsEmptyWhenNoRowIsReturned` |
| `attachment` present / absent | `JdbcAttachmentStoreTest.attachmentReadsTheMappedRowWhenPresent`, `attachmentIsEmptyWhenNoRowIsReturned` |
| `putAttachment` upsert returns a row | `JdbcAttachmentStoreTest.putAttachmentWritesWhenTheDraftBindingMatches` |
| `putAttachment` empty `RETURNING` → 409 `WRONG_CONVERSATION` | `JdbcAttachmentStoreTest.putAttachmentRefusesRebindingWhenNoRowIsReturned`; real DB: `JdbcAttachmentStoreIntegrationTest.draftIsBoundToItsUploaderAndConversationAndCannotBeReused` |
| `attachments` populated / empty | `JdbcAttachmentStoreTest.attachmentsReturnsTheMappedSnapshotOrAnEmptyList` |
| `events` populated / empty | `JdbcAttachmentStoreTest.eventsReturnTheMappedTrailOrAnEmptyList` |
| `putGrant` / `appendEvent` write paths | `JdbcAttachmentStoreTest.putGrantWritesTheGrant`, `appendEventWritesTheFact` |
| all three row mappers (`grantRow`, `attachmentRow`, `eventRow` incl. nullable columns) | `JdbcAttachmentStoreTest.answerQueries()` invokes each mapper with a synthetic `ResultSet` from the present-row tests |
| `AttachmentState.from` each wire value / unknown key | `AttachmentAcceptanceTest.contractEnumsParseAndEnforceLegalTransitions` |
| `@Autowired` controller constructor / test constructor | Spring context load in `JdbcAttachmentStoreIntegrationTest` / `AttachmentControllerHttpTest` |
| FORCE RLS WITH CHECK, owner-covered immutability trigger | `JdbcAttachmentStoreIntegrationTest.twoTenantsWithIdenticalLocalIdentifiersStayIsolated`, `databaseTriggerRejectsRebindingForTheMigrationOwnerToo` |
| Append-only fact ledger rejects UPDATE/DELETE/TRUNCATE for every role including the owner | `JdbcAttachmentStoreIntegrationTest.factLedgerRejectsMutationForEveryRoleIncludingTheOwner` (added after independent QA review) |

`InMemoryAttachmentStore` now lives in `src/test` and is not part of production coverage. No coverage threshold, exclusion, `@Disabled`/`.skip` or test was weakened.

## Findings

1. **Worker profile artifact (pre-existing, not caused by this change).** The worktree `opencode.json` is the CI profile copied under `skip-worktree` (`git ls-files -v opencode.json` → `S`; `cmp` vs `git show HEAD:opencode.json` differs). With that file in place, `scripts/check.sh` stops at `check-pack: opencode.json is stale` and 3 `test_check_pack` tests fail. After restoring the committed profile from `git show HEAD:opencode.json`, `scripts/check.sh` and all 436 tooling tests pass; the worker CI profile was then restored byte-identically and `git status` shows no change to `opencode.json`. Matches MEMORY.md / BUGS.md OPS-CW-003-01. Evidence: executed tool commands; no human review.
2. **OpenAPI entry is pre-existing debt outside this scope.** `contracts/openapi.yaml` has
