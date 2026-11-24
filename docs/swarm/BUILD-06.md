# BUILD-06 · Documents: checksum, retained original bytes and void with reason

PR #303, tested branch tip e31cbbdda59aebfba82f16be95f63bb9db233d24.

Task: BUILD-06

Swarm worker 9. Local checks: passed. Agent result: ok.

# BUILD-06 — Documents: checksum, retained original bytes and void with reason

**Status: COMPLETE within task scope. All implementation gates pass; the only failing check is the known `opencode.json` skip-worktree environment artifact (see Findings).** A bounded simulated `orevia-senior-backend` review raised a HIGH defect in the first implementation; it was fixed and re-verified, including a real-HTTP regression.

- Worker: `swarm-9`; branch `swarm/BUILD-06-417`; base HEAD `c45f9b1`. Evidence sources: **tool** for every command quoted under Test evidence/Coverage; **simulated reviewer** (`orevia-senior-backend`, session `ses_e02d07adcffe9PHuj0nnnXjzFG`) for the bounded diff review summarized under Clinical and architecture review. No simulated patient/user workflow and no human participant was exercised; no clinical or real-patient acceptance is claimed. Skills loaded: `orevia-delivery`, `orevia-senior-backend`, `orevia-senior-database`, `orevia-senior-java-build`, `orevia-test-engineer`. All fixtures are synthetic; no patient data, credential or real domain was used.

## Summary

CLN-07's association side already stored the upload SHA-256 (V17 trigger over the retained bytes), kept rotation presentation-only, and voided with a mandatory reason plus an append-only history guarded by an immutability trigger. The missing guarantee was **re-verifying the checksum on download**. This change adds: a V75 PostgreSQL function that recomputes `sha256(content)` from the exact retained bytes and returns an `intact` flag alongside the bytes; a repository call, an audited domain command and a thin controller endpoint (`GET /api/v1/documents/associations/{id}/content`). A mismatch is refused with 409 `CHECKSUM_MISMATCH` and a durable `TAMPERED` audit row. Tests prove tampered bytes are detected (including through the real HTTP controller and real PostgreSQL), a void keeps the original bytes and history, and another tenant cannot read the association.

A first implementation raised SQLSTATE 23514 from the function on tamper. The simulated reviewer correctly identified that this aborts the transaction, so the `TAMPERED` audit write fails with 25P02 and the client receives 500 instead of 409. The function now returns a structured result instead of raising; the service decides on the flag inside a valid transaction. The corrected behavior is proven by an HTTP-level integration test.

## Affected requirements

| ID | How this change serves it |
|---|---|
| CLN-07 | SHA-256 captured on upload (existing V17 trigger) is re-verified from the retained bytes on download (V75); original bytes never change on rotation (presentation column) or void; void carries a reason and preserves the append-only history. |
| OPS-02 | Verified-download successes, concealments and tamper denials append sanitized, durable audit facts; the tamper audit survives because verification returns a flag rather than aborting the transaction. |

## Risk class

Cross-boundary (`docs/WORKFLOW.md`): a new tenant-scoped SQL read path, a new domain command and a new HTTP endpoint. Not safety-critical in the clinical sense — no signed record, cross-organization sharing, patient-account link, prescribing or critical-result workflow is changed. No real-patient approval is implied.

## Review owner

Senior Java and Spring Boot (domain command, controller, audit), Senior PostgreSQL/Flyway (verified-download function, forced RLS, grants), Senior Java Build and Test Tooling (JaCoCo 100%, SpotBugs, Javadoc), Test Engineer (branch-to-test mapping). A bounded simulated `orevia-senior-backend` review was performed; no independent human reviewer was available.

## Data changes

New `db/migrations/V75__build_06.sql` (reserved version V75, `-- Change: expand`) creates **one function and no table**: `public.document_association_verified_content(uuid) RETURNS TABLE (content bytea, intact boolean)`. It is additive and changes no existing row. It runs as the invoker (`SECURITY INVOKER` default), so forced RLS on `document_association` (V17) bounds it to `public.app_org()`; a foreign or absent row yields no row. `EXECUTE` is revoked from `PUBLIC` and granted only to `orevia_app`. No existing migration was edited; no backfill; negligible lock/volume impact. Flyway applied it in every integration run: `Successfully applied 33 migrations to schema "public", now at version v75`.

## Authorization boundaries and audit events

The new command re-derives membership and the document action matrix through the existing `DocumentService.authorize` (`DOCUMENT_READ`) on every call; a denial is audited (`DENIED`) before the exception leaves the transaction. A successful verified download appends `DOCUMENT_READ` / `DOCUMENT_CONTENT` with outcome `SUCCESS`; a missing association or missing content appends `NOT_FOUND`; a tampered original appends `TAMPERED` and surfaces as 409 `CHECKSUM_MISMATCH`. `DocumentDownloadHttpIntegrationTest` asserts the `TAMPERED` row is actually durable (`audit_event` count rises by one across the tampered request). No payload, file name or URL is logged. Tenant context comes only from `TenantContext`/`X-Active-Organization`, never from the request body. Void continues to require `DOCUMENT_VOID` and a bounded, trimmed reason and to append a `void` history event.

## Migration and rollout considerations

V75 sorts after V17 (which owns `document_association`, its forced-RLS policy, the insert-time digest trigger and the void guard) and is applied as a normal Flyway step; the function depends only on that table and built-in `sha256(bytea)`. Deploy is additive: existing rows already carry a DB-derived digest, so no dual-write or backfill is needed and the new endpoint is available immediately. Because a tamper returns a flag rather than raising, the OPS-02 audit write and the 409 share one usable transaction. Rollback is a later contract migration that drops the function (pointing at V75 as its `Requires-expand`), per `docs/decisions/0002`. `scripts/check-migrations.py --require-baseline` and `db/check.sh` both pass with V75 present.

## Test evidence

All commands run from the repo root on `swarm/BUILD-06-417`. Exit codes and final lines (evidence source: **tool**):

| Command | Exit | Result / final line |
|---|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='DocumentVerifiedDownloadTest,DocumentContentRepositoryTest,DocumentContentControllerTest' test` (red, before implementation) | 1 | `cannot find symbol: method verifiedAssociationContent(java.lang.String)` / `associationContent(java.lang.String)` |
| same command after implementation (both before and after the reviewer fix) | 0 | `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | 0 | full unit tier green |
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='DocumentDigestIntegrationTest,Document*Test,PdfRendererTest' test` | 0 | real PostgreSQL; `Successfully applied 33 migrations ... now at version v75` |
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DocumentDownloadHttpIntegrationTest test` | 0 | HTTP 200 + octet-stream + original bytes, then 409 `CHECKSUM_MISMATCH` + durable `TAMPERED` audit, then 404 for the other tenant |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | 0 | no findings |
| `python3 -I scripts/check-migrations.py --require-baseline` | 0 | clean |
| `python3 -I scripts/check-names.py` | 0 | clean (no story-ID folder/package) |
| `python3 -I scripts/check-wiring.py` | 0 | clean |
| `python3 -I scripts/check-docs.py` | 0 | clean |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 0 | `Ran 2 tests ... OK` |
| `OREVIA_PG_ADMIN=postgres bash db/check.sh` | 0 | `db: migrations, force
