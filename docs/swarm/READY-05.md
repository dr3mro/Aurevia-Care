# READY-05 · Structured JSON logs with correlation ID and a redaction test (WEB-06)

PR #274, tested branch tip ff360cdc18207dff1eb366baaa6f0201c9aee33a.

Task: READY-05

Swarm worker 1. Local checks: passed. Agent result: ok.

# READY-05 — Structured JSON logs with correlation ID and a redaction test (WEB-06)

## Summary

Completes the WEB-06 wiring on the API side: switches the console to ECS structured
JSON, publishes the validated correlation id to SLF4J MDC from the tenant interceptor
and clears it at request completion, and adds an HTTP-level redaction acceptance test.
No logging library or dependency was added. No request/response body logging was
turned on, and no subject or organization is written to every log line. All data is
synthetic; no patient data, token or real identifier appears in code, tests or logs.

## Affected requirements

| Acceptance | Where implemented | Where proven |
|---|---|---|
| WEB-06: `logging.structured.format.console: ecs` | `apps/api/src/main/resources/application.yml` | File content (tool evidence, this note) |
| WEB-06: interceptor publishes correlation id to MDC after validation | `TenantContextInterceptor.preHandle` (`MDC.put("correlationId", correlation)`) | `TenantContextInterceptorTest.publishesAndClearsCorrelationIdInMdc`; `LogRedactionTest` (log line carries id) |
| WEB-06: MDC cleared at completion | `TenantContextInterceptor.afterCompletion` (`MDC.remove("correlationId")`) | `TenantContextInterceptorTest.publishesAndClearsCorrelationIdInMdc` |
| WEB-06: no body/token/header/patient field logged | `grep` result below; sole production log call is the fixed-field `CorrelationIdFilter`/`RequestLogFormatter` | `LogRedactionTest.errorRequestLogsCorrelationIdButNeitherBodyNorTokenMarker` |
| WEB-06: prove redaction with a test | new `LogRedactionTest` (OutputCaptureExtension, error route, both markers) | Same test; red + green runs recorded below |

Risk class: routine/cross-boundary (a thin interceptor and a config key on the request
path); no clinical decision, tenant selection change, prescribing or critical-result
logic. No data, migration or OpenAPI contract change.

## Review owner

`orevia-senior-backend` (bounded, review-only) plus the `orevia-senior-spring-security`,
`orevia-test-engineer` and `orevia-senior-java-build` skills. Evidence sources:
executed tool commands (tool evidence) and a bounded simulated specialist review (static
read of the four scoped files plus `CorrelationIdFilter`, `RequestLogFormatter` and
`ApiErrorHandler`). No reviewer ran commands; no simulated user, clinician or human
participant evidence. The review confirmed the config key and MDC placement are correct,
found no new branch lacking a test, and did not block; its three observations are
addressed in Findings 2, 5 and 6.

## Data changes

None. No migration, table, backfill, lock or volume impact. No patient data, real
identifiers or credentials in any fixture.

## Authorization boundaries and audit events

None changed. The interceptor still derives tenant context exactly as before; the only
new effect is writing the already-validated correlation id to MDC and clearing it.
No audit event added or changed.

## Migration and rollout considerations

None. The ECS console format is a declarative config key read at startup. The MDC
put/remove runs inside the existing interceptor lifecycle; no feature flag, no
configuration input beyond the format key, no deployment ordering change.

## Test evidence

Entry point to test trace: `POST /api/v1/probe` through standalone MockMvc → real
`CorrelationIdFilter` (emits the structured JSON line) → `TenantContextInterceptor.preHandle`
(validates id, binds tenant, `MDC.put`) → `FailingController` throws `ApiException(422)`
→ `ApiErrorHandler` renders the error with the correlation id → `LogRedactionTest`
asserts captured console output via `OutputCaptureExtension`.

Red (before the production change):

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='LogRedactionTest,TenantContextInterceptorTest' test
[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0
[ERROR] com.aurevia.web.TenantContextInterceptorTest.publishesAndClearsCorrelationIdInMdc -- expected: <corr-mdc> but was: <null>
EXIT=1
```

Green after implementation (targeted):

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='LogRedactionTest,TenantContextInterceptorTest,RateLimitInterceptorTest' test
... INFO com.aurevia.observability.CorrelationIdFilter -- {"correlationId":"222e2222-2222-2222-2222-222222222222","method":"POST","route":"/api/v1/probe","status":422,"latencyMs":0}
EXIT=0
```

Bounded regression set (web interceptor + observability filter, all green):

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='LogRedactionTest,TenantContextInterceptorTest,TenantContextInterceptorJwtTest,RateLimitInterceptorTest,CorrelationIdFilterTest,RequestLogFormatterTest' test
EXIT=0
```

Static analysis and policy:

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check
EXIT=0  (SpotBugs 4.10.5.0, no findings; no new @SuppressFBWarnings)

$ python3 -I scripts/check-names.py                    EXIT=0
$ python3 -I scripts/check-wiring.py                   EXIT=0
$ python3 -I -m unittest discover -s scripts/tests -q  436 tests, 3 failures, all in test_check_pack (see Findings)
```

Named worker gates:

```text
$ bash scripts/worker-check.sh     EXIT=1  check-pack: opencode.json is stale: run scripts/gen-opencode.py
$ bash scripts/check.sh            EXIT=1  check-pack: opencode.json is stale: run scripts/gen-opencode.py
```

Both stop before any Java check on the known cloud-worker CI-profile artifact (see
Findings). Verified HEAD truth with the generator: `git show HEAD:opencode.json ==
render(local profile)` is **true**; the worktree copy (CI profile under `skip-worktree`,
`git ls-files -v opencode.json` = `S`) does not match. The committed profile is correct.

Step 3 logging survey (precise grep of production sources):

```text
$ grep -rnE '\b(LOG|log|logger|LOGGER)\.(info|debug|warn|error|trace)\b' apps/api/src/main --include='*.java'
apps/api/src/main/java/com/aurevia/observability/CorrelationIdFilter.java:75:
  LOG.info(RequestLogFormatter.toJson(correlationId, request.getMethod(), route, status, latencyMs));
```

That single call emits exactly five fixed fields (`correlationId`, `method`, matched-handler
`route`, `status`, `latencyMs`) via `RequestLogFormatter`; it has no parameter for a request
or response body, header, token, query string or patient field, and `route` is the matched
pattern, never the raw path. A broader grep for `System.out/err`, `printStackTrace`,
`getHeader/getQueryString/getInputStream/getReader` near logging found no other log of
sensitive data. **Result: no production log statement needed removal or reduction; none
was found that prints a body, token, header value or patient field.** The observability
package is outside this story's scope and was not edited.

## Coverage

Touched production files: `TenantContextInterceptor.java` (Java) and `application.yml`
(declarative config, no executable branches). The new/modified test files need no
coverage of their own.

Targeted JaCoCo evidence (full `verify` left to the integrator per worker rules; this run
covers every method and branch of the touched class):

```text
$ rm -f apps/api/target/jacoco.exec \
  && mvn -q -B -f apps/api/pom.xml -Pno-security,fast \
       -Dtest='LogRedactionTest,TenantContextInterceptorTest,TenantContextInterceptorJwtTest,RateLimitInterceptorTest' test \
  && mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report
EXIT=0
```

`apps/api/target/site/jacoco/jacoco.csv` — `TenantContextInterceptor`:

| Counter | Missed | Covered |
|---|---|---|
| Instruction | 0 | 202 |
| Branch | 0 | 20 |
| Line | 0 | 47 |
| Complexity | 0 | 17 |
| Method | 0 | 7 |
| Class | 0 | 1 |

100% on every counter for the only touched production class. No threshold, exclusion,
`@Generated`, ignore marker or skip was added; no test was disabled or rem
