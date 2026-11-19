# X-01 — API versioning, idempotency, cursor pagination, correlation IDs and machine-readable errors

## Requirements and acceptance mapping (ARC-03, ARC-04)

| Acceptance | Implementation | Evidence |
|---|---|---|
| Versioned routes under `/api/v1`; unsupported version is a machine-readable failure | `ApiVersion.from` resolves an absent/blank header to `V1`, accepts `v1`/`V1`, rejects others with `422 UNSUPPORTED_API_VERSION`; route prefix is `/api/v1` only, no new route is registered | `ApiContractPrimitivesTest.versionDefaultsToV1AndExposesItsRoute`, `versionAcceptsCaseInsensitiveV1`, `versionRejectsUnsupportedValue` |
| Cursor pagination with stable tenant-scoped ordering | `ByteParts` canonical byte-length-prefixed UTF-8 parts; `Cursor` encodes base64url without padding over `(organizationId, sortKey, id)`, rejects non-canonical, malformed, oversized and foreign-tenant tokens with generic `422 INVALID_CURSOR`; `PageRequest` bounds `limit` to 1..200, default 50 | `ApiContractPrimitivesTest.byteParts*`, `cursor*`, `page*` (10 tests) |
| Request/correlation IDs | Documented as owned by the pre-existing `web.TenantContextInterceptor`; **not duplicated**. Replay mints a fresh correlation id and never replays an HTTP envelope | `contracts/x_01/README.md` header and error tables; no new correlation code |
| Machine-readable errors | New `ApiErrorCode` codes thrown as `ApiException` and rendered by the existing single `@RestControllerAdvice`; includes ARC-04 `409`, `412`, `404`, `422`, `428` semantics | `ApiContractPrimitivesTest.errorCodesCarryStatusMessageAndException`; all parser tests assert status and code |
| Idempotency scoped to tenant, caller and command with a request digest | `Scope(organizationId, subject, command)`, `IdempotencyKey.parse`, `RequestDigest` (length-prefixed UTF-8 canonical form, SHA-256, injectable `MessageDigestFactory`), `IdempotencyLedger` claim/complete/fail/expire with replay re-authorization, conflict on different payload or in-flight duplicate, no caching of failures | `IdempotencyLedgerTest` (11 tests), digest and key tests |
| Explicit POST commands and the ARC-04 worked example | `POST /api/v1/orders/{id}/accept` fragment with `Idempotency-Key`, `If-Match`, authenticated destination context, atomic state/event/audit commit and the specified response fields/statuses; generic sign/correct/acknowledge/release/exchange envelope documented | `contracts/x_01/openapi.yaml`, `contracts/x_01/README.md` |
| Version preconditions for draft writes and irreversible transitions | Declared `412 STALE_VERSION`; `IfMatch` and owning-service CAS remain the authority (pre-existing, out of scope) | Error table; no duplicate CAS added |

Requirement trace and entry-point-to-test table are in `contracts/x_01/README.md`.

## Scope and decisions

- Production files only under `apps/api/src/main/java/com/aurevia/smartclinic/api/x_01`; contract files only under `contracts/x_01`. No controller, route registration, tracker, `opencode.json`, workflow or shared file was touched. Reserved `V54` is **not consumed**: `db/migrations` is outside the declared scope, and gaps are permitted by the migration policy.
- Tests are placed in the mirror package `apps/api/src/test/java/com/aurevia/smartclinic/api/x_01` because Maven compiles tests only from `src/test/java`; the story scope lists production paths and no story in this repository places Java tests under `src/main`. This is the only path deviation from the literal scope text.
- No second `@RestControllerAdvice` was added (would conflict with the existing handler). All new failures are `ApiException` and reach `api.ApiErrorHandler` by specificity.
- Correlation handling, the uniform error body, `IfMatch` and tenant derivation were not reimplemented; the contract documents their ownership.
- `RequestDigest` uses a fresh `MessageDigest` per computation and stores only lowercase hex; injectable factory covers the unavailable-algorithm branch.
- Idempotency expiry is a dedup-cache concern: an expired key starts a new claim rather than replaying an arbitrary cached outcome. Durable business uniqueness for external clinical actions belongs to the owning module's transactional constraint.
- `InMemoryIdempotencyLedger` is explicitly documented as synthetic, process-local and non-durable; it does not satisfy ARC-04's atomic commit or ARC-05's outbox. Those belong to X-02 with a migration. Live enablement of signing, release, critical-result acknowledgment and prescribing additionally needs clinical and architecture review — a follow-on decision, not a blocker for this synthetic contract slice.

## Commands and results (all executed locally; synthetic data only)

Red first:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='ApiContractPrimitivesTest,IdempotencyLedgerTest' test` → compile failure, `cannot find symbol` for every new type (missing behaviour).

Green:
- Same command after implementation → exit 0.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` → exit 0; JaCoCo XML for package `com/aurevia/smartclinic/api/x_01`: 0 missed / 843 covered instructions, 0/78 branches, 0/167 lines, 0/43 methods, 0/14 classes (100% on all five counters for every new class, including nested records/enums).
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; 138 Surefire classes; the two new classes report 20 + 11 tests, 0 failures, 0 errors.
- `python3 -I scripts/check-docs.py` → exit 0 (Markdown links and requirement IDs valid).
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- Targeted Java convention check on all 8 new files: no `List`-bearing records; both `{@link}` targets fully qualified (`com.aurevia.smartclinic.api.ApiException`, `com.aurevia.smartclinic.api.x_01.ApiVersion#V1`).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → 14 findings, **0 in `x_01`** (all in other workers' in-progress files: `IdentityProofingService`, `TherapyContract`, `RxController`, `RxRepository`, `ConversationRepository`, `InstructionFactController`, `MessagingGovernanceController`).

## Pre-existing blockers observed on this branch (not caused by X-01, not modified)

- `bash scripts/quick-check.sh` stops at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`; `python3 -I scripts/gen-opencode.py --check` reports `opencode.json is out of date`. `git ls-files -v opencode.json` shows `S` (skip-worktree): the cloud-worker CI profile diverges from the committed profile. `opencode.json` is a protected path for swarm story PRs, so it was deliberately not regenerated or staged.
- `python3 -I -m unittest discover -s scripts/tests -q` → 163 tests, 7 failures, 4 errors. Causes observed: stale `opencode.json` (`test_check_pack.*`), missing Python `yaml` module (`test_fast_tier`, `test_workflow_concurrency`, part of `test_swarm`), workflow-permission expectations in `test_swarm`, and a `List.copyOf` guard failure on another worker's `PatientFlowContract.java`. None reference `contracts/x_01` or `api/x_01`.
- Full `bash apps/api/check.sh` (database/integration tests and full OWASP) was not run locally per the worker instruction; the integrator owns those gates. No PostgreSQL, OWASP NVD or image-smoke evidence is claimed by this worker.
- `contracts/x_01/openapi.yaml` was manually structure-checked (consistent two-space indentation, balanced flow mappings, no tabs); no YAML parser is installed locally (`yaml`/`ruamel` unavailable, no `pip`), so machine YAML validation is unverified evidence.

## Findings and defects

- Two defects were found in the new tests during the first green run and corrected before acceptance: a ledger test claimed the same key twice before completion (in-flight duplicate correctly returned `CONFLICT`, test expectation was wrong), and a `ByteBuffer` fixture allocated 4 bytes for a 6-byte payload (`BufferOverflowException`). No production defect was found.
- One production coverage gap found from JaCoCo: the canonical-base64 rejection branch in `Cursor.decode` was not exercised by appending `=` to a token whose length made the decoder fail first. Fixed with a padded-token test built from a 16-byte payload where the canonical and padded forms differ. Final coverage is 100% on all counters.
- Review inputs were three bounded specialist reports (software architect, test engineer, senior backend). Their findings — atomic claim, replay re-authorization, no blind expired-key replay, tenant-bound byte-canonical cursor, hex digest storage, injectable digest factory, no second advice, fully qualified Javadoc links — were incorporated. Review evidence is simulated/specialist; no human or clinical participant evidence is claimed.

## Evidence sources

Tool evidence: Maven/Surefire, JaCoCo XML, SpotBugs, `check-docs.py`, `check-secrets.py`, `check-migrations.py`, git file-status checks. Reviewer evidence: three bounded specialist reports (simulated). No simulated-user, human or clinical acceptance evidence for this contract slice.
