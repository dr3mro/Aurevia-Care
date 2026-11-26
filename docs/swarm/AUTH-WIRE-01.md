# AUTH-WIRE-01 · Call CommandAuthorization for every POST command (OPS-02)

PR #1747, tested branch tip d6440e4423d1fd500f0712c8d56624fb9e161e9e.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
 typecheck
> eslint . --max-warnings 0
 ^[[32m✓^[[39m tests/responsive/theme/useBreakpoint.coverage.test.tsx ^[[2m(^[[22m^[[2m2 tests^[[22m^[[2m)^[[22m^[[32m 49^[[2mms^[[22m^[[39m
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
> aurevia-patient-mobile@0.1.0 typecheck
> eslint .
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
> typecheck
> eslint . --max-warnings=0
frontend standards: policy and tool commands passed
{"@timestamp":"2026-11-26T06:42:47.837791807Z","log":{"level":"INFO","logger":"org.springframework.test.context.support.AnnotationConfigContextLoaderUtils"},"process":{"pid":148231,"thread":{"name":"main"}},"service":{"name":"aurevia-api"},"message":"Could not detect default configuration classes for test class [com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest]: ValidateSizeSupportedTypeChecksumScanStateTest does not declare any static, non-private, non-final, nested classes annotated with @Configuration.","tags":["COMMONS-LOGGING"],"ecs":{"version":"8.11"}}
{"@timestamp":"2026-11-26T06:42:47.863083835Z","log":{"level":"INFO","logger":"org.springframework.boot.test.context.SpringBootTestContextBootstrapper"},"process":{"pid":148231,"thread":{"name":"main"}},"service":{"name":"aurevia-api"},"message":"Found @SpringBootConfiguration com.aurevia.AureviaApiApplication for test class com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest","tags":["COMMONS-LOGGING"],"ecs":{"version":"8.11"}}
{"@timestamp":"2026-11-26T06:42:48.471740784Z","log":{"level":"INFO","logger":"com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest"},"process":{"pid":148231,"thread":{"name":"main"}},"service":{"name":"aurevia-api"},"message":"Starting ValidateSizeSupportedTypeChecksumScanStateTest using Java 21.0.13.1 with PID 148231 (started by root in /__w/Aurevia-Care/Aurevia-Care/apps/api)","tags":["COMMONS-LOGGING"],"ecs":{"version":"8.11"}}
{"@timestamp":"2026-11-26T06:42:48.471958553Z","log":{"level":"INFO","logger":"com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest"},"process":{"pid":148231,"thread":{"name":"main"}},"service":{"name":"aurevia-api"},"message":"The following 1 profile is active: \"synthetic-auth\"","tags":["COMMONS-LOGGING"],"ecs":{"version":"8.11"}}
{"@timestamp":"2026-11-26T06:42:48.744869985Z","log":{"level":"INFO","logger":"com.aurevia.documents.clauses.ValidateSizeSupportedTypeChecksumScanStateTest"},"process":{"pid":148231,"thread":{"name":"main"}},"service":{"name":"aurevia-api"},"message":"Started ValidateSizeSupportedTypeChecksumScanStateTest in 0.28 seconds (process running for 141.945)","tags":["COMMONS-LOGGING"],"ecs":{"version":"8.11"}}
```

Task: AUTH-WIRE-01

Swarm worker 2. Local checks: failed. Agent result: ok.

# AUTH-WIRE-01 — Call CommandAuthorization for every POST command (OPS-02, ARC-04)

Tested revision: 81844cfdb944580b8f228b23c7b54b5f157b9861

## Story contract

- Task file: `tasks/00094000-AUTH-WIRE-01.md` (`[-]`, orchestrator-owned; worker does not edit it).
- Requirements: OPS-02 (durable authorization audit), ARC-04 (identical semantics for analogous transitions).
- Scope (only these prefixes may change):
  - `apps/api/src/main/java/com/aurevia/api/authorization`
  - `apps/api/src/main/java/com/aurevia/web`
  - `apps/api/src/test/java/com/aurevia/api/authorization`
  - `apps/api/src/test/java/com/aurevia/authz`
- Prerequisite READY-41 is now `[x]` (`tasks/00093000-READY-41.md`, PR #1706); the earlier blocker notes
  (`.agents/notes/AUTH-WIRE-01-20261125-060145.md`, `...-fix-...`) are stale on that point.

## Loaded skills (one line per loaded skill)

- `aurevia-delivery` — every story uses the resumable delivery/swarm loop.
- `aurevia-senior-backend` — Spring controllers, services and REST wiring are in scope.
- `aurevia-senior-java-build` — Java tests, JaCoCo, SpotBugs and wiring checks apply.
- `aurevia-senior-spring-security` — authorization gate, tenant boundary and denial semantics.
- `aurevia-context-optimizer` — bounded discovery and requirement-ID mapping.
- `aurevia-test-engineer` — red-first HTTP tests and 100% branch coverage.
- `aurevia-plan-reviewer` — mandatory pre-code plan review.
- `aurevia-code-reviewer` — mandatory post-code review.
- `aurevia-coverage-auditor` — mandatory post-code branch review.
- `aurevia-devsecops` — mandatory post-code security review.

No additional skill matched beyond those named in the brief.

## Evidence at intake (tool evidence unless labelled otherwise)

- `git branch --show-current` → `swarm/AUTH-WIRE-01-1669`; `git status --short` → clean;
  `git rev-parse HEAD` → `81844cfdb944580b8f228b23c7b54b5f157b9861`.
- `git merge --no-edit origin/main` → `Already up to date.`
- READY-41 delivered: `db/migrations/V65__authorization_decisions.sql`,
  `JdbcDecisionSink.java`, `AuthorizationConfiguration.java` (in `config/`, outside my scope).
  Exactly one `DecisionSink` bean is exposed (`AuthorizationConfiguration.java:31-37`).
- `CommandAuthorization.authorize(...)` (`CommandAuthorization.java:203-255`) records every allow and
  deny through the injected sink before answering, ignores client claims, and fails closed on sink
  failure (`CommandAuthorization.java:272-295`). No production caller invokes `authorize()` today.
- `CommandCatalog.synthetic()` has 24 POST entries (`CommandCatalog.java:139-189`) and only `find(id)`
  (`:101`); there is no route lookup. `grep -rn "@PostMapping" apps/api/src/main/java` → 248 handlers,
  21 in `com/aurevia/web`; only ~13 catalog paths match a live handler. Generated contract/client
  artifacts live under `contracts/openapi/generated` (outside scope) and are drift-checked by
  `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration`.
- No command identifier is read from HTTP requests; the generated client's `command` field is transport
  metadata (`CommandClientGenerator.java:194-201,231-244`).
- `TenantContextInterceptor` binds the request tenant at order 10 on `/api/v1/**`
  (`TenantContextInterceptor.java:195-198`); `IdempotencyFilter` resolves the matched route from
  `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` at order 20 (`IdempotencyFilter.java:55-122`).
- Errors use `ApiException` → `ApiErrorHandler.ErrorBody(code, message, correlationId)`, not RFC 7807
  (`ApiErrorHandler.java:17-104`).

## Requirement mapping

- OPS-02: the gate must produce an append-only decision for each gated command through the delivered
  `DecisionSink`, including denial and sink-failure paths.
- ARC-04: a single interceptor resolves the handler to one catalog command and applies one decision
  algorithm to every analogous POST transition rather than duplicating policy in controllers.
- Acceptance mapping: allowed command → decision row and handler runs; forbidden role/tenant → 403
  and deny row; unknown command → deny row (fail closed); sink failure → deny, never an allow;
  tenant isolation → a decision row is visible only in its own tenant.
- Deliberate scope boundary: the interceptor registers on `CommandCatalog.paths()` (24 POST
  templates). The other ~224 POST handlers keep their existing service-level authorization; they
  cannot be catalogued here because the generated contract/client artifacts are outside scope.
  The unknown-command deny is proven by registering the same interceptor without a path
  restriction (effectively `/**`) in the standalone HTTP harness. This is recorded

