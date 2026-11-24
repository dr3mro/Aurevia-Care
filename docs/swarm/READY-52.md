# READY-52 · API: structured JSON request log with a correlation ID and a redaction test (WEB-06)

PR #270, tested branch tip 6d3c946724b9594df33eb3c023eedab037788037.

Task: READY-52

Swarm worker 3. Local checks: passed. Agent result: ok.

# READY-52 — API: structured JSON request log with a correlation ID and a redaction test (WEB-06)

## Summary

Adds a servlet filter in the new `com.aurevia.observability` package that
gives every HTTP request an opaque correlation id, publishes it to SLF4J MDC and the
response header, and emits one fixed-field JSON log line after the request. No file in
`web/` was changed and no dependency was added. All fixtures are synthetic; no patient
data, token or real identifier appears in code, tests or logs.

## Affected requirements / acceptance mapping

| WEB-06 acceptance | Where it is implemented | Where it is proven |
|---|---|---|
| Correlation id taken from `X-Correlation-Id` only when valid UUID, else generated | `CorrelationIdFilter.resolveCorrelationId` / `isUuid` | `CorrelationIdFilterTest.usesValidHeaderAndEchoesIt`, `generatesIdWhenHeaderIsMissing`, `generatesIdWhenHeaderIsNotAUuid` |
| MDC publication and removal, header echo | `CorrelationIdFilter.doFilterInternal` (`MDC.put`/`MDC.remove`, `response.setHeader`) | `usesValidHeaderAndEchoesIt` (chain reads MDC), `clearsMdcAfterASuccessfulRequest`, `clearsMdcAndLogsWhenTheChainThrows` |
| One JSON line with `correlationId`, `method`, `route`, `status`, `latencyMs` | `RequestLogFormatter.toJson` (5 writes, 5-arg signature) | `logsMethodMatchedRouteStatusAndLatency` (exact field set, 1 line), `RequestLogFormatterTest.emitsExactlyTheFiveAllowedFields` |
| `route` is the matched pattern, not the raw path | `CorrelationIdFilter.resolveRoute` reads `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, `UNMATCHED` fallback | `logsMethodMatchedRouteStatusAndLatency`, `usesConstantRouteWhenNoHandlerPatternWasMatched` |
| Never query string, body, headers, tokens or patient fields | Fixed-field helper has no parameter for them; filter never reads them | `neverLogsQueryBodyHeaderOrRawPathValues` (markers in query, body, bearer header and raw path all absent) |
| Strings cannot break out of the JSON line | JSON node escaping in `RequestLogFormatter` | `RequestLogFormatterTest.escapesCallerControlledStringsSoTheLineStaysOneJsonObject` |
| Failure path still logs and clears MDC | `finally` block | `clearsMdcAndLogsWhenTheChainThrows` |

Risk class: cross-boundary (a servlet filter on the request path) but no clinical decision,
tenant selection, prescribing or critical-result logic. No data, migration or OpenAPI
contract change. Full-chain correlation interaction is recorded under **Findings**.

## Review owner and evidence sources

`orevia-senior-backend` (bounded, review-only) plus `orevia-senior-java-build`
and `orevia-test-engineer` skills. Evidence sources: executed tool commands (tool
evidence) and a simulated specialist review. No reviewer ran commands; no simulated user,
clinician or human participant evidence in this story.

## Data changes

None. No migration, table, backfill, lock or volume impact. No patient data, real
identifiers or credentials in any fixture.

## Authorization boundaries and audit events

None changed. The filter does not read authentication, tenant context or audit state.
It does not reject any request.

## Migration and rollout considerations

None. The filter is a `@Component` servlet filter at `@Order(HIGHEST_PRECEDENCE + 1)`;
Spring Boot registers it automatically. No feature flag, no configuration key.

## Test evidence

Red (before production classes existed):

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='CorrelationIdFilterTest,RequestLogFormatterTest' test
[ERROR] ... cannot find symbol: class CorrelationIdFilter
[ERROR] ... cannot find symbol: variable RequestLogFormatter
EXIT=1
```

Green after implementation (targeted):

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='CorrelationIdFilterTest,RequestLogFormatterTest' test
... INFO com.aurevia.observability.CorrelationIdFilter -- {"correlationId":"...","method":"GET","route":"UNMATCHED","status":200,"latencyMs":0}
EXIT=0
```

Canonical coverage gate and SpotBugs:

```text
$ mvn -q -B -f apps/api/pom.xml -Pno-security verify          # EXIT=0
  2527 tests, 0 failures, 0 errors, 0 skipped (surefire report totals)

$ mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false verify   # EXIT=0
  SpotBugs 4.10.5.0, no findings on the new classes (no @SuppressFBWarnings added)
```

Named story check (step 6 of TODO's "Instructions for every agent"):

```text
$ bash scripts/worker-check.sh                                 # EXIT=1
check-pack: opencode.json is stale: run scripts/gen-opencode.py
```

This fails before reaching any Java check: `opencode.json` in this worktree is the CI
profile installed under `skip-worktree` (`git ls-files -v opencode.json` shows `S`).
Verified with the generator: `git show HEAD:opencode.json` equals `gen_opencode.render(.)`
(**true**), while the worktree copy does not. The committed local profile is correct;
the failure is the documented OPS-CW-003-01 measurement artifact, not this change.

Policy/tooling:

```text
$ python3 -I scripts/check-names.py                     # EXIT=0
$ python3 -I scripts/check-wiring.py                    # EXIT=0
$ python3 -I -m unittest discover -s scripts/tests -q   # EXIT=1: 3 test_check_pack failures, same opencode.json CI-profile artifact; 436 tests, 3 failures
```

Static dependency scan attempt:

```text
$ timeout 240 mvn -q -B -f apps/api/pom.xml -Psecurity -Ddependency-check.autoUpdate=false dependency-check:check
  EXIT=124 (timed out in NvdCveClient API poll; NVD_API_KEY absent locally).
  OWASP 12.1.0 could not complete offline here; the integrator/CI supplies NVD_API_KEY.
  No finding count available locally. SpotBugs (the other static analysis) passed above.
```

## Coverage

Command and result: `mvn -q -B -f apps/api/pom.xml -Pno-security verify` → EXIT=0,
JaCoCo 0.8.16 build-failing rule (INSTRUCTION/LINE/BRANCH/METHOD/CLASS all ≥ 1.00)
passed. `apps/api/target/site/jacoco/jacoco.csv` for the touched production files:

| Class | Instructions missed/covered | Branches missed/covered | Lines missed/covered | Methods missed/covered |
|---|---|---|---|---|
| `CorrelationIdFilter` | 0 / 86 | 0 / 6 | 0 / 24 | 0 / 6 |
| `RequestLogFormatter` | 0 / 31 | 0 / 0 | 0 / 7 | 0 / 1 |

Every touched file is at 100% on all counters; the private utility constructor is
filtered by JaCoCo (as for `NoteInputs`), so no reflection test was needed.

New branches and their tests:

| New branch | Test |
|---|---|
| `header != null && isUuid(header)` true | `usesValidHeaderAndEchoesIt` |
| `header == null` short circuit | `generatesIdWhenHeaderIsMissing` |
| `header != null && isUuid(header)` false via `isUuid` | `generatesIdWhenHeaderIsNotAUuid` |
| `UUID.fromString` try success / `IllegalArgumentException` catch | valid-header tests / `generatesIdWhenHeaderIsNotAUuid` |
| `pattern instanceof String` true | `logsMethodMatchedRouteStatusAndLatency`, `neverLogsQueryBodyHeaderOrRawPathValues` |
| `pattern instanceof String` false | `usesConstantRouteWhenNoHandlerPatternWasMatched` |
| `try` success / `finally` on thrown chain | all success tests / `clearsMdcAndLogsWhenTheChainThrows` |

No threshold, exclusion or skip was added; no test was removed. No test is `@Disabled`.

## Clinical and architecture review

Not required: no signed data, patient association, tenant selection, prescribing or
critical-result logic. No human clinical acceptance is claimed.

## Findings

1. **Full-chain correlation divergence (review finding, not changed).** A bounded
   `orevia-senior-backend` review observed that the pre-existing
   `web/TenantContextInterceptor` (registered on `/api/v1/**`, `preHandle`) reads and
   overwrites `X-Correlation-Id` with its own value using a *looser* allowlist
   (`[A-Za-z0-9._-]{1,128}`, no UUID requirement) and binds that value into
   `TenantContext` and the audit trail. The new filter therefore logs i
