# AUTH-WIRE-01 · Call CommandAuthorization for every POST command (OPS-02)

PR #1705, tested branch tip eaa6439cc3929a84c68defd6eb8a50ca1b3ed94f.

**Draft: the worker did not finish cleanly** (agent result: timeout; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (failing commands and final lines):

```text
AssertionError: 1 != 130 : cat: .agent/base-sha: No such file or directory
AssertionError: 1 != 143 : cat: .agent/base-sha: No such file or directory
```

Task: AUTH-WIRE-01

Swarm worker 8. Local checks: failed. Agent result: timeout.

# AUTH-WIRE-01 — Call CommandAuthorization for every POST command (OPS-02)

**Outcome: stopped at a blocking prerequisite. No production or test code was changed.**

## Story contract

- Task file: `tasks/00094000-AUTH-WIRE-01.md` (already `[-]`, `agent: swarm-5`, `attempts: 40`).
- Requirements: OPS-02 (durable authorization audit), ARC-04.
- Scope (only these prefixes may change):
  - `apps/api/src/main/java/com/aurevia/api/authorization`
  - `apps/api/src/main/java/com/aurevia/web`
  - `apps/api/src/test/java/com/aurevia/api/authorization`
  - `apps/api/src/test/java/com/aurevia/authz`
- Depends on: READY-41 (`tasks/00093000-READY-41.md`, currently `[-]`, `attempts: 45`).
- Worker rules followed: `tasks/_head.md` "Instructions for every agent"; `.opencode/setup/AGENTS.md`; `.opencode/setup/CODING_STYLE.md`; swarm-worker mode of `aurevia-delivery`.
- Story text explicitly says: "Await these open prerequisites before implementation: READY-41."

## Loaded skills (one line per loaded skill)

- `aurevia-delivery` — delivery loop applies to every story (swarm-worker mode).
- `aurevia-context-optimizer` — story needs a bounded file and requirement map.
- `aurevia-senior-backend` — scope includes Spring `web` controllers and Java services.
- `aurevia-senior-java-build` — Java change needs JaCoCo/SpotBugs/wiring gates.
- `aurevia-senior-spring-security` — authorization gate, denial paths, tenant isolation.
- `aurevia-test-engineer` — write failing HTTP tests first, 100% branch coverage.
- `aurevia-plan-reviewer` — mandatory pre-code plan review.
- `aurevia-code-reviewer` — mandatory post-code review.
- `aurevia-coverage-auditor` — mandatory post-code branch review.
- `aurevia-devsecops` — mandatory post-code security review.

No additional skill matched beyond those named in the brief.

## Requirement mapping (why the story cannot be accepted now)

- OPS-02 (`.opencode/config/PROJECT_V1_SPEC.md:135`) requires a durable audit of every authorization decision with actor, purpose, scope, timestamp, action, resource ID, correlation ID and outcome. The only available `DecisionSink` in this checkout is synthetic and in-process; no durable sink exists.
- ARC-04 (`PROJECT_V1_SPEC.md:122`) requires identical semantics for analogous transitions. There is no command identity on the wire that a single interceptor can resolve today, and the route catalog is stale, so analogous transitions cannot be mapped uniformly.

## Evidence at intake (tool evidence)

- `grep -rn "OPS-02|ARC-04" .opencode/config/PROJECT_V1_SPEC.md` → OPS-02 at line 135, ARC-04 at line 122.
- `find apps/api/src/main/java/com/aurevia/api/authorization ...` → `CommandAuthorization`, `CommandCatalog`, `CommandClientGenerator`, `CommandDefinition`, `InMemoryDecisionSink` exist; no other decision sink.
- `grep -rn "DecisionSink" apps/api/src/main/java` → only the nested interface and `InMemoryDecisionSink`; no production Spring bean.
- `grep -rn "CommandAuthorization|CommandCatalog" apps/api/src/main/java` (excluding their own files) → no production caller or bean.
- `find db/migrations -name '*V65*'` → none; `grep -rn "authorization_decision" db apps/api/src` → none. READY-41's durable `authorization_decision` table is not present in this checkout.
- `python3 -I scripts/check-wiring.py` → exit 0; `scripts/wiring-baseline.json:10` explicitly baselines `InMemoryDecisionSink.java` as in-main debt.
- `grep -rn "@PostMapping" apps/api/src/main/java/com/aurevia/web` → 21 handlers; `CommandCatalog.synthetic()` has 24 entries (`CommandCatalog.java:139-188`).
- `grep -rn "@PostMapping" apps/api/src/main/java` → 248 handlers repo-wide. The story's "about two dozen" is stale by roughly 10×.
- `cat .agent/base-sha` → `b84447295a6b63281bc1b1eea386a5283e570d20`.

## Reviewer evidence (read-only specialists; reviewer evidence, not simulated-user or human feedback)

- `aurevia-context-optimizer`: no `CommandCatalog`/`CommandAuthorization`/`DecisionSink` bean anywhere in main; Spring scan root is `com.aurevia`, so beans would have to be created in the allowed prefixes. Only ~13 of 24 catalog paths exactly match real handler templates; ~230 real POST routes have no catalog entry. A fail-closed `/api/v1/**` interceptor would deny nearly every existing POST and break the full-context suites. Recommended prerequisite-first stop or an owner scope decision.
- `aurevia-test-engineer`: a standalone MockMvc harness can prove interceptor behavior with injected fakes (`RateLimitInterceptorTest.java:50-71` is the pattern), and standalone setup does not auto-register `WebMvcConfigurer` interceptors, so existing controller tests would be unaffected by a new bean in a full context only. Flagged three open decisions: interceptor name/package, how a request resolves to a command id, and whether fail-closed unknown/sink-failure statuses stay as the gate's existing 422/500 or become 403.
- `aurevia-senior-backend`: a complete in-scope design exists in the abstract (configuration beans in `web`, `CommandCatalog.findByPath`, `StaffMembershipResolver` over the tenant-guarded `StaffRepository`, `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` for path templates, `ApiException` propagation to `ApiErrorHandler`). But it concluded: with no durable sink, an honest fail-closed production sink makes **every** command fail with `500 DECISION_NOT_RECORDED`, so the "allowed command" acceptance cannot pass against production beans; and a fail-closed global interceptor breaks the currently green POST suites.

## Findings

1. **Blocking prerequisite READY-41 is not implemented.** `tasks/00093000-READY-41.md` is `[-]` after 45 attempts. There is no V65 migration, no `authorization_decision` table, no `JdbcDecisionSink` and no `DecisionSink` bean. The story asserts the opposite ("READY-41 added the durable `DecisionSink`"). The durable sink and its `config/` bean are explicitly outside AUTH-WIRE-01's `scope`, so this worker cannot supply them without breaking the scope rule.
2. **No honest in-scope production sink exists.** `InMemoryDecisionSink` is documented "synthetic only" (`InMemoryDecisionSink.java:9-15`) and baselined as debt. Binding it as the production bean would fake OPS-02 durability. A fail-closed throwing sink would deny every command with 500, so the required "one allowed command" case cannot pass. Both options fail an explicit acceptance rule.
3. **Catalog is stale by ~10×.** The story says "about two dozen" POST handlers; there are 248. Only about 13 routes match exactly; several catalog routes do not exist (`checkin/{id}/complete`, `orders/{id}/accept`, `critical/{id}/acknowledge`, `invoices`, `instructions/{id}/acknowledge`) and ~230 real POST routes have no entry. The story says to add entries for missing commands, but most have no defined owning action key, and adding ~230 entries would change the generated command contract/client artifacts outside this scope.
4. **Fail-closed registration conflicts with "existing controller tests must still pass".** Registering the interceptor on `/api/v1/**` and denying unknown commands would deny most existing POST endpoints, including full-context `@SpringBootTest` suites. Narrowing registration to catalogued routes would leave ~230 POSTs ungated and removes the HTTP "unknown command" case, because no uniform command identifier is read by the controllers today (the generated client's `command` field is client-side transport metadata, `CommandClientGenerator.java:194-201,231-244`).
5. **No command identity on the wire.
