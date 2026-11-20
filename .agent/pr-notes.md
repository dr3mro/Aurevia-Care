# AUD-20261120-09 — HTTP security-contract tests for finance, prescription, specialty and conversation endpoints

## Requirement IDs

FIN-01, RX-01, RX-02, PRD-06, MSG-02, MSG-03, IAM-01, ARC-03, ARC-04, OPS-02.

## Scope and changes

Test-only change. Five new integration test classes; no production code, migration,
OpenAPI file or shared tracker was modified.

| New file (package follows business domain) | Controller(s) covered | Endpoints |
|---|---|---|
| `it/finance/FinanceHttpSecurityTest.java` | `FinanceController` | 8 |
| `it/authz/SpecialtyHttpSecurityTest.java` | `SpecialtyController` | 4 |
| `it/prescription/PrescriptionHttpSecurityTest.java` | `PrescriptionController`, `DrugCatalogController` | 5 |
| `it/prescription/RxHttpSecurityTest.java` | `RxController` | 11 |
| `it/messaging/ConversationHttpSecurityTest.java` | `ConversationController` | 10 |

All classes are `@Tag("integration")`, `@ActiveProfiles("synthetic-auth")`,
`@SpringBootTest` with a random port and a dedicated synthetic PostgreSQL database.
They copy the `headers(...)`/`send(...)` request-building pattern and the
`@BeforeAll`/`@BeforeEach`/`@AfterAll` Flyway setup from
`it/exchangeresult/ResultRoutingIntegrationTest.java`, with all fixtures synthetic.

## Step 1 — URL-level coverage survey (not skipped)

Grepped the full `apps/api/src/test` tree for every controller path fragment.
Result: **all 38 endpoints are already called by at least one existing integration
test**, mainly `FinanceFlowTest`, `PrescriptionFlowTest`, `RxTransmissionFlowTest`,
`SpecialtyAuthorizationFlowTest` and `it/messagingconversations/ConversationFlowTest`.
The literal finance fragments `/payments`, `/credits` and `/refunds` return zero
hits only because `FinanceFlowTest` builds them via `"/invoices/" + id + "/" + kind`
with the `kind` values `payments`, `credits`, `refunds` (verified in that file).
Rx command paths are built as `id + "/attest"`, `id + "/renew"`, etc. and all
helper methods are invoked. Every controller flow test is active (no `@Disabled`,
`@Tag("integration")` present).

Conclusion: the audit's premise — "controller class name appears in no test" — is
a false positive at the URL level: the controllers are exercised through HTTP by
flow tests, not by class name. However, the four-case HTTP **security** matrix
required by step 3 was not systematically asserted for these routes (for example
`RxTransmissionFlowTest` has zero 401 and zero 422 assertions and
`ConversationFlowTest` has zero 401 assertions). Per step 3 this story therefore
adds dedicated security-contract tests; it does not duplicate the business-flow
assertions.

## Step 3 — acceptance mapping (four cases per endpoint)

For each of the 38 endpoints the new classes assert:

1. **(a) 401** — request with no `X-Synthetic-Subject` header.
2. **(b) 422** — request with `X-Active-Organization: not-a-uuid`.
3. **(c) 403** — authenticated subject that may not perform the action.
4. **(d) success** — allowed subject, asserted in a chained happy-path test using
   the status implemented by the controller and observed in the existing flow
   tests: `201` for the eight create/open routes, `200` for the rest.

Cases (a)–(c) are `@ParameterizedTest` methods with one invocation per endpoint;
case (d) is one chained test per class that walks the real workflow. Total new
test invocations: 25 + 13 + 16 + 34 + 31 = **119**, covering 38 × 4 = 152 case
assertions.

`403` subject choice is endpoint-appropriate: `reception-1111` for finance and
prescription; `ghost-1111` (no active membership) for Rx and conversations; and
`ghost-1111` for specialty because `/api/v1/specialty/permissions` is readable by
every active member, so a non-member is the deterministic denial there. This is a
test-fixture decision, not a production change.

No test revealed a wrong status or missing authorization, so **no `@Disabled`
test was needed and no follow-up defect was found** in the covered endpoints.

## Commands and results (tool evidence)

Local PostgreSQL was available (`pg_isready` accepting connections, `SMARTCLINIC_PG_ADMIN=postgres`), so the integration tier was run directly rather than left to the integrator.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=FinanceHttpSecurityTest test` | 25 tests, 0 failures, 0 errors |
| `... -Dtest=SpecialtyHttpSecurityTest test` | 13 tests, 0 failures, 0 errors |
| `... -Dtest=PrescriptionHttpSecurityTest test` | 16 tests, 0 failures, 0 errors |
| `... -Dtest=RxHttpSecurityTest test` | 34 tests, 0 failures, 0 errors |
| `... -Dtest=ConversationHttpSecurityTest test` | 31 tests, 0 failures, 0 errors |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` (whole tier) | exit 0 |
| `python3` surefire report aggregation | 196 suites, 2221 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (unit tier) | exit 0 |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history, versioned config verified |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`, `bash -n` over shell scripts | all exit 0 |

Evidence source: executed tool commands only. No reviewer, human or clinical
participant was involved; all fixtures are synthetic.

### `bash scripts/worker-check.sh` could not complete in this worker

`worker-check.sh` stops at `check-pack.py` with `opencode.json is stale`. This is
the documented cloud-worker `skip-worktree` artifact: `git ls-files -v opencode.json`
returns `S`, and `agent-run.sh` has copied the CI profile into the worktree, so
the pack check measures the CI profile rather than the committed local profile at
`HEAD`. This is unrelated to the test-only change and is recorded as a false
positive in `MEMORY.md` (OPS-CW-003-01). `opencode.json` was not touched.

After that gate, this environment also cannot run the whole Python tooling suite:
`test_fast_tier`, `test_swarm` and `test_workflow_concurrency` error with
`ModuleNotFoundError: No module named 'yaml'`; `test_agent_run` and `test_check_pack`
fail on local git/gh behaviour and the same `opencode.json` profile artifact.
These are environment limitations, not defects in this change. The Java unit tier,
the full Java integration tier and `db/check.sh` — the gates relevant to the
classes touched — were run directly and all pass (table above). The integrator's
prepared image has PyYAML and the correct profile and runs the complete gate.

## Defects and decisions

- No production defect found; no test disabled; no follow-up needed for the covered endpoints.
- **Observation for a separate story (not fixed here):** `contracts/openapi/generated/openapi.yaml`
  documents only 24 paths. None of the finance, prescription-drafts, drug-catalog,
  prescriptions or conversations routes are present; only `/api/v1/specialty/attestations`
  is (with `201`). Step 3's "success status from the generated contract" was therefore
  unavailable for 37 of 38 endpoints, so success statuses were taken from the
  controllers and the existing flow tests. Contract publication for these routes is
  out of scope for this test-only task.
- Test DB names are unique per class (`smartclinic_*_http_it`) and each class drops
  its database in `@AfterAll`, so the new classes do not collide with existing
  integration suites; confirmed by the whole-tier run.
