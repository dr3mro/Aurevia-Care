# X-03 — Upload validation, quarantine, mediated downloads and PDF binding (ARC-06)

## Requirements and acceptance mapping

| Requirement | Acceptance implemented | Evidence (entry point → test) |
|---|---|---|
| ARC-06 | Upload validation (PDF/JPEG/PNG, magic bytes, extension, 1 byte–10 MiB, lowercase SHA-256); private quarantine; verified object key/version/checksum; mediated metadata-only downloads; async PDF jobs bound to exact immutable revisions; release blocked when a required PDF is missing, stale or unverified | `QuarantineService.upload/scan/complete/remove/download/view` and `PdfBindingService.requestRender/pickupRender/finishRender/failRender/bind/release` → `apps/api/src/test/java/com/aurevia/smartclinic/api/x_03/DocumentArtifactTest.java` (38 tests) |
| ARC-01, ARC-02 | Tenant-scoped `(organizationId, id)` keys, two tenants with identical local IDs, cross-tenant 404 concealment, server-seeded owner/context binding, compare-and-set writes | `DocumentStore`/`InMemoryDocumentStore` → `concealsArtifactsAcrossTenantsWithIdenticalLocalIds`, `deniesUnknownInactiveAndForeignAuthorizations`, `competingWritesAreRejectedRatherThanOverwritingCommittedState` |
| IAM-01 | Role-action table and fresh actor on every command; forbidden/inactive/wrong-tenant/wrong-owner denials audited | `DocumentActions`, `DocumentGuard` → `deniesMissingRoleAndNullActorWithoutChangingState`, `actionPolicyGrantsOnlyBoundedRoles` |
| OPS-02 | Append-only facts with actor, resource, outcome, server time; no payload, file name or URL in facts or handles | `DocumentGuard.record` → `downloadHandleAndFactsCarryNoPayloadFileNameOrUrl`, `factsAreAppendOnlyAndTenantScopedAndNeverCarryPayloads` |

Contract: `contracts/x_03/document-artifacts.md`.

## Scope and decisions

- The dispatch environment (`STORY_ID=X-03`, `Scope: contracts/x_03,
  apps/api/src/main/java/com/aurevia/smartclinic/api/x_03`) is authoritative.
  `TODO.md` still lists the older `contracts/upload` / `...api/upload` scope for
  this entry; no other worker uses either path, so there is no collision. The
  integrator should expect the `x_03` path. I did not edit TODO.md.
- CODING_STYLE.md prefers business-domain packages over ticket-ID packages. The
  swarm scope mandates `api/x_03`, so the directory is scope-driven while all
  classes and methods use business-domain names (`QuarantineService`,
  `PdfBindingService`, `DocumentContract`, `DocumentActions`, `DocumentInputs`,
  `DocumentStore`, `DocumentGuard`). Converging this with the existing
  `s_g3_06`/`s_g1_07` packages is a follow-up outside this story.
- Reserved migration V56 was not used: `db/migrations` is outside the declared
  scope and the slice persists through a tenant-scoped store boundary, matching
  the other cross-cutting and swarm stories.
- Stale `If-Match` versions return 412 (`STALE_VERSION`), following ARC-04 and
  the test-engineer review, rather than the 409 used by the older S-G3-06
  attachment slice.
- Idempotency request digests (ARC-03) and real PDF bytes/fonts/RTL rendering
  belong to X-01 and the rendering/document stories respectively; this slice
  records verified object metadata and immutable bindings only.
- Secondary review findings incorporated: explicit render pickup state,
  `DocumentActions` role table, verified object boundary on completion and
  download, compare-and-set writes, and an audit fact on blocked releases.

## Commands and results (all executed locally; synthetic data only)

1. **Red — tests before production code**
   `cd apps/api && mvn -B -Pno-security,fast test` → exit 1, compile errors for
   the missing `com.aurevia.smartclinic.api.x_03` types. After adding the
   implementation, the first behavioral run gave 7 assertion failures
   (1549 tests); each was a real mismatch between the test matrix and the
   service (scan re-scan, role for remove, error codes, CAS setup). All fixed
   by changing the test or the owning rule, never by deleting an assertion.
2. **Green — fast unit tier**
   `cd apps/api && mvn -B -Pno-security,fast test` → exit 0,
   `Tests run: 1550, Failures: 0, Errors: 0, Skipped: 0`; the new
   `DocumentArtifactTest` ran 38 tests with 0 failures.
3. **Coverage**
   `cd apps/api && mvn -B -Pno-security,fast verify` → tests green; JaCoCo
   `Report` and `check` ran. The bundle gate reports 0.81–0.94 because the
   `fast` profile excludes the `@Tag("integration")` database suites; the
   per-class JaCoCo XML shows **zero missed instructions, lines, branches,
   methods and classes for every new `api/x_03` class** (checked by parsing
   `target/site/jacoco/jacoco.xml`). The integrator's full `scripts/check.sh`
   run is the authoritative bundle gate.
4. **Static analysis**
   `cd apps/api && mvn -B -Pfast -DskipTests -Djacoco.skip=true
   -Dspotbugs.skip=false -Dowasp.skip=true spotbugs:check` → exit 1 with six
   pre-existing Medium findings in `g0/s_g0_07`, `g4/s_g4_04`,
   `messaging/s_g3_05`, `messaging/s_g3_08` and `messaging/s_g3_10`; **zero
   findings mentioning `x_03`**.
5. **Repository checks**
   `python3 -I scripts/check-docs.py` → exit 0 (valid links and requirement IDs,
   including the new contract and these notes).
   `python3 -I -m unittest discover -s scripts/tests -q` → 163 tests with 12
   pre-existing failures/errors in `check_pack` (stale `opencode.json`),
   `swarm`, `agent_run`, `workflow_concurrency`, `fast_tier`, and Java
   convention failures in `G3EvidenceService.java` and `PatientFlowContract.java`
   — none reference `x_03`. I did not modify those shared files.
   `python3 -I scripts/check-pack.py` → exit 1: committed `opencode.json` is
   stale on this branch before my changes (git status showed only my new
   directories as untracked); regenerating it is outside my scope.
   `bash scripts/docs.sh` → exit 1: Doxygen is not installed locally.
6. **Database/integration tests** were not run locally: `127.0.0.1:5432`
   refuses connections and `psql`/`pg_isready` are absent. Per the worker
   directive, the integrator runs the database and integration tests.

## Review evidence and defects

- Bounded specialist reviews: `smartclinic-senior-backend` (design gaps) and
  `smartclinic-test-engineer` (test matrix) returned findings; they were
  simulated reviewers, not human or clinical participants. Their findings are
  resolved in the implemented classes and tests as listed under Decisions.
- Defects found and fixed during this story: scan allowed re-scan of a CLEAN
  artifact, remove used a role without that permission, stale-version status
  mismatch, unmocked object verification, idempotency and CAS race branches,
  and missing accessor coverage — each fixed with a focused test and code
  change.
- No real patient data, credentials or clinical feedback were used or recorded.

## Blocking items for the integrator / owner

- The shared-tracker and policy failures in item 5 are pre-existing on this
  branch and outside X-03 scope; the integrator should reconcile them before
  treating `scripts/check.sh` as green.
- No decision or evidence required for X-03 acceptance is missing from this
  note.
