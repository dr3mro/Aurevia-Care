# S-G2-03 — Accept/reject with reason, clarify, linked changes, cancellations and revocation (XCH-03)

## Requirement mapping and acceptance

- **XCH-03 (primary)**: recipient accepts with a locally verified patient association, rejects with a durable reason, or requests clarification; a post-acceptance change creates a linked immutable revision and requires a recipient response; a cancellation is a request until the recipient confirms or declines; retained recipient copies carry permitted purpose and retention policy; revocation blocks future sender-hosted retrieval and emits a downstream notification with a durable event id.
- **Supporting**: ARC-01 composite tenant keys, ARC-02 forced RLS + per-request membership/role re-derivation, ARC-03 `If-Match` preconditions and machine-readable errors (428/422/412), ARC-04 error semantics (401/403/404/409/410/412/422), ARC-05 durable notification identity, OPS-02 transactional append-only audit.
- Entry-point-to-test trace: `POST /api/v1/xch03/orders/{id}/{accept,reject,clarify,propose-change,change-response,request-cancellation,cancellation-response,revoke}` → `Xch03Controller` (`IfMatch.parse`) → `Xch03Service.run` (re-derived authorization, `SELECT … FOR UPDATE`, version CAS, one guarded transaction) → `Xch03Repository.update` + immutable ledgers → `AuditAppender`. `GET /{id}/retrieve` re-checks revocation on every call.
- New regression trace: `Xch03Service.accept` → `repository.update` raises a tenant-scoped FK integrity failure (unresolved `localPatientId`) → new `catch (DataIntegrityViolationException)` in `Xch03Service.run` → `SqlFailureTranslator.translate` → `ApiException(404, NOT_FOUND)` (concealed) instead of an unhandled 500. Test: `Xch03IntegrityTest`.

## Working-tree state on resume (important for the integrator)

- HEAD on resume was `4f9bbeb` (bundled merge PR #113) with a clean tree. The XCH-03 slice already existed in the tree: 7 main files in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/response`, V21 migration, and Xch03 tests in `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangetask`, plus a prior attempt's pr-notes. A repo-wide package refactor (`.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`) renamed `g2/s_g2_03` → `exchange/response`.
- **Scope resolution**: the task-prompt scope paths (`…/g2/s_g2_03`, `…/it/s_g2_03`) do not exist in the current tree; TODO.md's authoritative main scope (`…/exchange/response`) does. I worked only in `exchange/response` (main) and created a new test in `…/it/exchange/response` (TODO.md's test scope). I did not touch the pre-existing Xch03 tests in `it/exchangetask` (S-G2-02's scope) because a parallel worker may own it. No tracker, skill, workflow or migration file was edited. V21 was **not** modified (no schema change needed); no other reserved migration version was used.
- Naming: class names retain the legacy `Xch03*` prefix from the prior attempt (packages are business-domain). Renaming would require editing test files in another story's scope and produce unrelated churn, so it is deliberately out of this change; flagged below.

## Defect fixed (test first, synthetic fixtures)

**HIGH — `accept` with a syntactically valid but unresolvable/cross-tenant `localPatientId` returned HTTP 500.**
`Xch03Inputs.uuid` validates only UUID syntax. V21's `validate_xch03_local_patient` trigger raises SQLSTATE `23503` on `xch03_order.local_patient_id`, Spring throws `DataIntegrityViolationException`, and `ApiErrorHandler` has no handler for it (only `DuplicateKeyException`) → default 500. This violates XCH-03's "locally verified association" and leaks an internal failure instead of a concealment response. Other writing services (AppointmentService, ChartService) already use `SqlFailureTranslator`; this service did not.

Fix (smallest change, repo-standard pattern): wrap the command's writes in `Xch03Service.run` with `try/catch (DataIntegrityViolationException)` and `throw SqlFailureTranslator.translate(exception)` — `23503` → `404 NOT_FOUND` (conceals the unresolved reference), other integrity refusals → `409 CONFLICT`. No migration, contract or new dependency change.

Red/green (tool evidence):
- Red: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=Xch03IntegrityTest test` → `Tests run: 2, Failures: 2` — both cases threw uncaught `DataIntegrityViolationException` where an `ApiException` was expected.
- Green: same command after the fix → exit 0, `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`.
- New tests: `anUnresolvableLocalPatientIsConcealedInsteadOfFailingWith500` (23503 → 404) and `anotherIntegrityFailureBecomesAConflict` (23514 → 409), both synthetic UUID fixtures.

## Commands run and results (tool evidence)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; Surefire aggregate **1644 tests, 0 failures, 0 errors, 0 skipped** (1626 pre-existing + 2 new in this change; earlier baseline on this tree was 1642/0/0/0 and 41 Xch03 unit tests before the 2 new ones).
- JaCoCo (`mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report`): `com/aurevia/smartclinic/exchange/response/Xch03Service` = **LINE 0 missed / 178 covered, BRANCH 0/44, METHOD 0/36, CLASS 0/1 — 100%**; `Xch03Service$Decision` 100%. The new catch branch is covered by both new tests.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 1 pre-existing, out-of-scope failure: `Qualifier.java` (`exchange/trend`, S-G2-07) has `{@link #from(String)}`. None of my files introduce a link or list-bearing record violation.
- `bash scripts/quick-check.sh` → exit 1 solely because `opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` = `S` (skip-worktree): this cloud worker's CI profile is injected over the committed file, so `check-pack`/`gen-opencode --check` judge that profile, not the committed one (WORKSPACE.md artifact). I did **not** regenerate the protected file (out of scope). This failure is pre-existing on the clean HEAD and not caused by this change.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast verify` → JaCoCo `check` fails because the `fast` profile excludes `@Tag("integration")` suites, so repository/JDBC classes fall below threshold. Expected for the worker fast tier; the integrator's full `check.sh` (real PostgreSQL) is the authoritative coverage gate. No production coverage was fabricated or marked pending-as-covered.

## Reviewer findings and dispositions (simulated reviewers; no human/clinical evidence)

Two bounded specialist reviews (senior backend, software architect) plus my own trace:

| # | Finding | Severity | Disposition |
|---|---|---|---|
| 1 | Unverified `localPatientId` → 500 | HIGH | **Fixed** test-first as above. |
| 2 | `CLARIFICATION_REQUESTED` is a dead-end state (only `revoke` exits) | MEDIUM | Deferred — requires a product/architecture decision on clarification resolution; widening accept/reject would change established semantics and needs tests in S-G2-02's scope. Recorded for the owner. |
| 3 | `retrieve()` can serve a revision with no retained copy (e.g. rejected order) | MEDIUM | Deferred — design choice between sender-hosted retrieval and recipient retained copies; needs owner decision and an integration test in another story's scope. |
| 4 | Rejecting a linked change sets the whole order to terminal `REJECTED` | MEDIUM | Deferred — existing tests in `it/exchangetask` explicitly assert `state == "rejected"`; correcting this needs those out-of-scope tests updated and an owner decision. |
| 5 | Revoke `retentionPolicy` written to projection but not to retained copies (legal hold cosmetic) | MEDIUM/LOW | Deferred — retention/hold workflow belongs to ADM-01/OPS-01; needs an append-only hold record. |
| 6 | Notification is terminal append-only, `FAILED` unreachable, no idempotency-key replay | MEDIUM | Deferred — outbox/idempotency/replay is X-01/X-02 (both already delivered cross-cutting); this slice intentionally keeps only a durable `eventId` and version preconditions. |
| 7 | Sender lifecycle commands authorized by recipient-tenant roles, no ExchangeGrant binding | MEDIUM | Deferred — sender/ExchangeGrant provenance is owned by XCH-01; V21 is deliberately self-contained. |
| 8 | Failed safety-critical commands (409/412) produce no audit row | LOW | Deferred — cross-cutting audit-on-failure pattern; not specific to XCH-03 and would widen the change. |

None of the deferred findings is a tenant-isolation, wrong-patient, wrong-revision or revocation-bypass defect; the reviewers confirmed forced RLS, CAS versioning, actor derivation, append-only ledgers, concealment and revocation blocking are sound.

## Not run locally

- Database and integration tests (`bash apps/api/check.sh`, `bash db/check.sh`, full `scripts/check.sh`) — delegated to the integrator per worker rules. The new regression is a pure unit test and needs no database.

## Evidence source

- Tool: Maven/Surefire/JaCoCo command output above (red then green, 100% service coverage).
- Findings: two simulated specialist reviewers (senior backend, software architect) and my own entry-point trace; no human, clinical or unit-pilot evidence.
- Synthetic data only: all UUIDs, checksums, org/subject identifiers and payloads are fabricated fixtures. No patient data or credentials used or stored.
