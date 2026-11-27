# START-182 · API: edge-case tests for `InstructionFactController`

PR #1810, tested branch tip c2f2396e2bd308ec530092cfaf415f0940b29c58.

Task: START-182

Swarm worker 2. Checks: passed. Local checks: failed. Agent result: ok.

# START-182 — API: edge-case tests for `InstructionFactController`

Story: `tasks/01070000-START-182.md`. Requirements: MSG-01, ARC-02. Scope: one new test file only — `apps/api/src/test/java/com/aurevia/messaging/acknowledgment/InstructionFactControllerEdgeCasesTest.java`. Branch: `swarm/START-182-1740`. No production, contract, migration, script, tracker or workflow file is edited. Synthetic fixtures only.

## Summary

Adds one HTTP edge-case acceptance class for the six MSG-05 instruction-fact routes. No production code, contract, migration, script or tracker changed. The class proves validation fails closed, roles and tenants are concealed correctly, revisions and client keys are immutable/idempotent, and hostile Unicode text is bounded without echoing a clinical payload.

## Affected requirements

MSG-01 (server validates patient, destination and context on every protected action) and ARC-02 (tenant isolation, fail closed). MSG-05 routes are the exercise surface; no MSG-05 contract or production behavior is changed.

## Risk class

Cross-boundary evidence slice: HTTP adapter, domain service, tenant interceptor and error handler are exercised together, but only a test file changes. Clinical safety is indirect (receipt-only facts and concealment), so no clinical sign-off is introduced.

## Review owner

Read-only specialists named by the brief: plan-reviewer, test-engineer, senior-backend, senior-java-build, devsecops, code-reviewer, coverage-auditor; qa-reviewer per worker rules. The primary agent owns the single test file and this notes file.

## Data changes

none — no migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Exercises existing checks only: active membership and role in the active organization, verified patient chart plus active connection for patient actions, owning-unit assignment for staff actions, and per-organization fact lookups. No audit event is added or changed; the new tests assert existing `deny()` audit outcomes (`DENIED`, `NOT_FOUND`, `CONFLICT`) stay in the correct organization.

## Migration and rollout considerations

none — no schema or deployment change.

## Clinical and architecture review

none requested beyond the read-only specialist reviews; no signed data, cross-organization sharing change, patient association change, prescribing or critical-result release is edited.

## Skills loaded

- aurevia-delivery — every story; drives the swarm test-first delivery loop and evidence hand-off.
- aurevia-senior-backend — change touches the Spring MVC controller/service slice (`InstructionFactController`, `InstructionFactService`).
- aurevia-senior-java-build — targeted Maven test/SpotBugs commands and JaCoCo coverage evidence for Java.
- aurevia-test-engineer — new edge-case acceptance tests written first, every branch mapped to a named test.
- aurevia-plan-reviewer — pre-code plan review required by the brief.
- aurevia-code-reviewer — post-code correctness/contract review required by the gate.
- aurevia-coverage-auditor — post-code branch-to-test completeness review required by the gate.
- aurevia-devsecops — tenant isolation, concealment and PHI-free test evidence (ARC-02/MSG-01).
- context-optimizer — dropped by the brief: scope and paths are exact, no context discovery needed.

## Plan

1. Pre-code specialist proposals (plan-reviewer, test-engineer, senior-backend, senior-java-build, devsecops); fix every Required gap before coding. Plan-reviewer Required gaps closed: (a) ARC-02 DB-layer evidence is credited to the existing out-of-scope `db/migrations/V63__instruction_facts.sql` (ENABLE + FORCE RLS at lines 160-161) and `it/JdbcInstructionFactStoreIntegrationTest.java` (two tenants, identical local IDs/subjects); this new InMemory+MockMvc slice proves only service/HTTP concealment and must say so in the notes. (b) "No clinical payload" is asserted explicitly on the uniform error body: `$.code == NOT_FOUND`, the fixed `$.message`, and `$.instructionId`/`$.patientId`/`$.contentRef`/`$.unitId`/`$.actor` `.doesNotExist()`; assert codes, not just status class (a 429 rate-limit must not be mistaken for a denial). Body shape confirmed in `ApiErrorHandler.ErrorBody`: `{code,message,correlationId}` only.
2. Write one new JUnit test class using the neighbouring `MockMvc` + `ApiErrorHandler` + `TenantContextInterceptor` pipeline and `InMemoryInstructionFactStore` seeding helpers; assert only behaviour that exists today. Unicode pass-through is asserted where it is actually observable: `$.reason` on a successful resolve and `store.latestInstruction(ORG, id).contentRef()` for publish; `clientId` is observable only through digest behaviour. Concurrency is one key with atomic digest reservation, asserting exact codes 200/409 plus one stored fact.
3. Run targeted Maven tests (four classes), compile-then-SpotBugs, `worker-check.sh` and the Python tooling suite; record Surefire counts, branch-to-test map and tested SHA.
4. Post-code reviewers (code-reviewer, coverage-auditor, devsecops, qa-reviewer); fix every Critical/Required finding; copy final verdict lines.

## Findings

Pre-code specialist proposals (recorded before implementation, as the brief requires):

- **senior-backend: GO.** Full branch→status→code map traced through all six routes: raw `InstructionFactInputs` 422s write no audit fact; 404→audit `NOT_FOUND`, 409→`CONFLICT`, 401/403→`DENIED`, 422 from `deny()`→the code. Validation always precedes authorization, so wrong-role cases need otherwise-valid input. Wrong tenant: interceptor only checks org UUID shape; without `addMember` in OTHER_ORG the result is 403, with membership the object lookup yields 404; duplicating the instruction/chart/connection/assignment in OTHER_ORG would yield 200 (isolation is per-org data, not a global ID blacklist). Impossible/problematic expectations resolved: first-view same key with changed payload returns 200 both times (clientId validated but unused, no digest); first-view of a superseded revision returns 200 (no stale check); resolve has no clientId; acknowledge of a revision with `requiresAcknowledgment=false` returns 422 `ACKNOWLEDGMENT_NOT_REQUESTED` before staleness; stale publish/ack reusing the earlier key returns IDEMPOTENCY_CONFLICT/committed fact, so stale tests need fresh keys; RLM-only or NBSP-only reason is accepted because `trim()` does not remove those code points; `acknowledgmentDueAt` equal to the clock instant is not overdue (`isBefore`); missing `unitId` query parameter is Spring's 400 with no stable code. Contract drift observed and deliberately NOT changed: POST routes return 200 while OpenAPI declares 201.
- **senior-java-build: GO.** Commands 1 and 2 from the brief are correct for a test-only change: `-Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` is byte-identical to `scripts/worker-check.sh:15`, so SpotBugs really runs after compile and OWASP stays skipped. Must NOT run `mvn verify`/`apps/api/check.sh`, `scripts/check.sh`, `scripts/coverage-check.sh`, `-Pfast`/`-Pno-security,fast -Dgroups=integration` (false green: `fast` sets `excludedGroups=integration` in `apps/api/pom.xml:225`), bare `spotbugs:check` (vacuously green), or local OWASP (needs NVD key). Capture Surefire counts from `apps/api/target/surefire-reports/TEST-*.xml` **before** `worker-check.sh` deletes them. Test-only coverage means preserving the existing 100% baseline: no thresholds/exclusions changed, no skipped tests, each new test asserts an outcome. Toolchain observed: Java 21.0.13.1 / Maven 3.10.0 (tool evidence). Baseline `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=InstructionFactControllerTest,InstructionFactControllerHttpTest,InstructionFactServiceTest test` exit 0, Surefire 56 tests / 0 failures / 0 errors / 0 skipped (2 + 7 + 47).
- **plan-reviewer: NO-GO then GO.** First pass Required: (a) credit exis
