# AUD-20261120-02 — Production authentication part 1: pure claims-to-tenant resolver

Status: complete for integrator merge. Scope: `apps/api/src/main/java/com/aurevia/smartclinic/web` and `apps/api/src/test/java/com/aurevia/smartclinic/web` only. Branch `main` (local, uncommitted until commit), base HEAD `59e3096`. No migration version reserved or used. Synthetic UUID strings only; no token, credential, patient data or real issuer was read or recorded (`issuer.example.test` is a reserved test domain).

## Requirement IDs and acceptance mapping

The TODO entry has no explicit `requirements:` field. The relevant specification IDs for this authentication core:

| ID | How this change serves it |
|---|---|
| IAM-01 | Tenant is derived from the authenticated subject and an explicitly selected organization; a client cannot select a tenant the token does not list. |
| ARC-02 | Tenant context comes from verified claims plus an explicit selection, never from a request body or resource; the resolver is the pure core that part 2 (AUD-20261120-03) calls from the interceptor. |
| WEB-03 | Production authentication foundation: a validated JWT subject and issuer feed the server-side tenant decision (OIDC wiring itself is part 2). |
| OPS-02 | Authentication failures carry machine-readable status/code/message for the uniform API error handler and audit. |

| Story rule / done-when | Acceptance | Where satisfied |
|---|---|---|
| `AuthenticatedPrincipal` Java record with `String subject`, `Set<String> organizationIds`, `String issuer` | Exact components; frozen set | `AuthenticatedPrincipal.java`; compact constructor + overridden accessor |
| `ClaimsTenantResolver` plain class, no Spring annotations, public `resolve(Map<String,Object>, String, String)` returns `TenantContext` | Signature exact; no annotations | `ClaimsTenantResolver.java:35` |
| Rule 1: non-blank `sub` String else 401 `UNAUTHENTICATED` / `authentication required` | status, code and message asserted | `ClaimsTenantResolver.java:40-43`; tests `rejectsClaimsWithoutSubject`, `rejectsBlankSubject` |
| Rule 2: `orgs` is a `List` of Strings each matching the interceptor UUID regex; missing/empty -> 401 | regex copied verbatim; element validation | `ClaimsTenantResolver.java:23-24, 45-54`; tests `rejectsClaimsWithoutOrganizations`, `rejectsOrganizationsClaimThatIsNotAList`, `rejectsEmptyOrganizationsClaim`, `rejectsOrganizationsClaimContainingNonString`, `rejectsOrganizationsClaimContainingMalformedUuid` |
| Rule 3: header must be a UUID in `orgs` else 403 `ORGANIZATION_NOT_PERMITTED`; malformed -> 422 `INVALID_TENANT_SELECTION` | both paths asserted with exact message | `ClaimsTenantResolver.java:36-42`; tests `rejectsOrganizationNotListedInClaims`, `rejectsMalformedActiveOrganizationHeader`, `rejectsMissingActiveOrganizationHeader` |
| Rule 4: return `new TenantContext(requestedOrganization, sub, TenantContext.PURPOSE, correlationId)` | all four fields asserted | `ClaimsTenantResolver.java:43`; test `resolvesTenantForPermittedOrganization` |
| Rule 5: valid UUID not in `orgs` -> 403 | dedicated negative test | `rejectsOrganizationNotListedInClaims` (ORG_OTHER is well-formed and absent) |
| At least 7 unit tests, JUnit 5, no Spring, no database | 12 tests | `ClaimsTenantResolverTest.java`; `tests="12" errors="0" failures="0"` |
| Do not touch interceptor, pom.xml or any other file | Only 3 new files | `git status --short` lists exactly the three new files |
| `bash scripts/worker-check.sh` passes for touched classes | Java tier green; gate blocked by documented pre-existing Python environment failures (see below) | Commands and results below |

## Changes made

1. `AuthenticatedPrincipal.java` — record with a compact constructor that freezes the caller's `Set` with `Set.copyOf`, and an overridden `organizationIds()` that returns another immutable copy. This follows the existing `ChartView` / `NoteView` pattern and satisfies SpotBugs EI_EXPOSE_REP/EI_EXPOSE_REP2 without a suppression. `issuer` is retained for provenance and is empty when the `iss` claim is absent.
2. `ClaimsTenantResolver.java` — `final` plain class (no Spring annotations, no I/O and no token parsing). One public method; three private helpers (`principal`, `issuer`, `unauthenticated`). The UUID regex is copied byte-for-byte from `TenantContextInterceptor.UUID_PATTERN` and not loosened. 401/422/403 use the exact codes and messages from the story.
3. `ClaimsTenantResolverTest.java` — 12 JUnit 5 tests covering the happy path, every rejection rule, the null-header short circuit, the `orgs` element-type and UUID-format branches, and the record's freeze/value semantics.

## Exact commands run and results (tool evidence)

All commands run from the repository root on local `main`, base `59e3096`.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ClaimsTenantResolverTest test` (before production classes existed) | exit 1 — compile errors `cannot find symbol: class ClaimsTenantResolver` / `class AuthenticatedPrincipal` (RED) |
| Same command after adding the two production classes | exit 0 (GREEN); Surefire: `tests="12" errors="0" skipped="0" failures="0"` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (full Java unit tier) | exit 0; aggregated Surefire XML: 1871 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` + read `target/site/jacoco/jacoco.xml` | `ClaimsTenantResolver`: INSTRUCTION 0/140 missed, LINE 0/25 missed, BRANCH 0/22 missed, METHOD 0/6 missed, CLASS 0/1 missed. `AuthenticatedPrincipal`: INSTRUCTION 0/19 missed, LINE 0/4 missed, METHOD 0/2 missed, CLASS 0/1 missed. Both 100% on every counter. |
| `mvn -q -B -f apps/api/pom.xml -DskipTests -Dowasp.skip=true spotbugs:check` | exit 0 — no SpotBugs findings on the new classes (including no EI_EXPOSE_REP) |
| `python3 -I scripts/check-names.py` | exit 0 — no ID-based package or class name |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests … OK` — no unqualified Javadoc link |
| `python3 -I scripts/check-pack.py` (worktree as-is) | exit 1 `opencode.json is stale` — documented OPS-CW-003-01 artifact: `git ls-files -v opencode.json` is `S`, the worktree file equals `.github/opencode.ci.json`, and `git show HEAD:opencode.json` equals `gen_opencode.render(ROOT, ci=False)` exactly (HEAD sha256 `7ceb8b30…`). With the HEAD file temporarily swapped in, `check-pack.py` printed `Delivery tooling validated: 33 agents, 33 skills` (exit 0). Byte-for-byte restored afterwards. |
| `bash scripts/worker-check.sh` (HEAD `opencode.json` temporarily swapped in via trap) | Reached the Python tooling suite and stopped there: `Ran 268 tests … FAILED (failures=5, errors=10)`; all policy steps before it passed (pack, docs, secrets, names, migrations) and SpotBugs/JaCoCo are owned by the integrator. See "Worker gate" below. |

## Worker gate — pre-existing environment failures, reproduced on an unchanged tree

`bash scripts/worker-check.sh` did not finish in this worker. The blocker is in `python3 -I -m unittest discover -s scripts/tests -q`, which runs before the Java tier in `quick-check.sh` (the Java tier was therefore run directly and is green above). With the committed HEAD `opencode.json` in place:

- Baseline (my three Java files moved aside): `Ran 268 tests`, `FAILED (failures=5, errors=10)`.
- With my change present: `Ran 268 tests`, `FAILED (failures=5, errors=10)`.
- `diff` of the sorted `ERROR:`/`FAIL:` test names: **IDENTICAL failure sets**.

The failures are all in `test_agent_run` (fake repo git/gh state: `gh pr create: permission denied`, push rejected to a temp bare remote, `git branch -D` failure) and import errors `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_swarm` and `test_workflow_concurrency`. None of these modules reads `apps/api/src/**`. No PyYAML install, `gh` auth or shared tooling change was attempted (out of scope and unavailable). `shellcheck` is guarded in `quick-check.sh`; `bash -n` of all scripts passed inside the gate. The integrator runs the full gate in the prepared image and judges the merge result.

## Entry-point-to-test trace

Story `AUD-20261120-02` → `ClaimsTenantResolver.resolve(claims, requestedOrganization, correlationId)` (the exact API part 2 `AUD-20261120-03` will call from `TenantContextInterceptor` with `JwtAuthenticationToken.getToken().getClaims()`) → private `principal(Map)` validates `sub` and `orgs` → private `issuer(Map)` → `AuthenticatedPrincipal` frozen value object → `resolve` validates the header and returns `TenantContext` → `ClaimsTenantResolverTest` (12 JUnit 5 tests, no Spring, no DB) asserts each rule and the returned fields. JaCoCo XML from that test run proves 100% of every counter on both new production classes.

## Defects and decisions

- Defects found in the inspected code: none. The resolver has no Spring dependency and performs no token parsing, matching the story's "already-validated claims" boundary.
- Decision: model the validated identity as `AuthenticatedPrincipal` and build it inside `resolve`; this reuses one domain type instead of duplicating subject/organization validation, keeps the public API to the single required method, and gives the record's constructor and accessors real callers for the 100% gate.
- Decision: a malformed `orgs` element (non-String or non-UUID) is treated as a violation of rule 2 and therefore returns 401, the same as a missing or empty list.
- Decision: absent `iss` yields an empty issuer string. The issuer is provenance only and never authorizes a tenant selection.
- Pre-existing, not fixed here: the worker-image Python environment failures and the `opencode.json` skip-worktree artifact above; both are documented in BUGS (OPS-CW-003-01) and in the two immediately preceding AUD pr-notes.

## Evidence provenance

- **Tool evidence**: every command and result above, executed by the primary agent on this checkout (`mvn`, Surefire XML, JaCoCo XML, SpotBugs, `python3 -I`, `grep`, `git`).
- **Reviewer evidence**: one bounded read-only review from `smartclinic-senior-spring-security` (simulated reviewer). Verdict: **no actionable findings**. It independently re-read the three files plus `TenantContextInterceptor`, `TenantContext` and `ApiException`, confirmed all five story rules and the byte-identical UUID regex, and enumerated a covering test for each of the six branch points (`:38`, `:41`, `:50`, `:54`, `:59`, `:69`), concluding 100% branch coverage is achievable. Two non-blocking observations, not actioned: case-sensitive UUID membership is consistent with the text-key/RLS design (a hex-case-only change yields 403), and the double `Set.copyOf` is redundant but harmless. The reviewer executed no build or tests; its verdict is static file-read evidence, while the tool evidence above supplies the executed results.
- **Simulated user / human participant evidence**: none. This is a pure server-side authentication primitive with no clinical or patient-facing surface; no real patient data, credential or token was used.
