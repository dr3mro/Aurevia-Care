# READY-49 · Add HTTP tests for the remaining controllers named in no test

PR #217, tested branch tip 25d203a4527f889c9f805604a2a439feec8b2d8e.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

Task: READY-49

Swarm worker 3. Local checks: failed. Agent result: ok.

<!-- Merge resolution: this shared notes file previously held a single story note.
Both sides replaced the merge-base note; both sections are preserved below.
Canonical per-story copies: docs/pr/notes/READY-49-20261120-091850.md and docs/pr/notes/READY-15-20261120-090755.md. -->

# READY-49 — HTTP tests for the remaining controllers named in no test

## Requirement IDs and acceptance mapping

| Requirement | What this change proves |
|---|---|
| ARC-03 | Every protected route of the four remaining controllers rejects an unauthenticated request (401) and an invalid tenant selection (422), and returns the documented success status for an authorized subject. |
| ARC-04 | Irreversible commands expose their version precondition (`If-Match`) at the HTTP boundary; list/boundary validation failures return 422 with a machine-readable error code. |

## Scope

Test-only change. Four new integration test classes under
`apps/api/src/test/java/com/aurevia/it`. No production code,
migration, OpenAPI file or shared tracker was modified. All fixtures are
synthetic; no patient data, credentials or real identifiers are used.

## Controller discovery (story step 1)

Command run for every `*Controller.java` under `apps/api/src/main/java`:

```
for f in $(find apps/api/src/main/java -name '*Controller.java' | sort); do
  name=$(basename "$f" .java)
  hits=$(grep -rl "$name" apps/api/src/test 2>/dev/null | wc -l)
  printf '%-45s %s\n' "$name" "$hits"
done
```

Controllers with no test hit: `CriticalResultController`, `PatientReleaseController`,
`PatientReleasedRevisionsController`, `ResultController`, `AppointmentController`,
`AuditController`, `SchedulingAdminController`.

Excluded per the story (already covered by AUD-20261120-08 / -09):
`AppointmentController`, `AuditController`, `SchedulingAdminController` (AUD-08),
and finance, prescription, specialty and conversation controllers (AUD-09).

**Remaining controllers covered by this change (one `<Name>HttpTest` each):**

| Controller | Business-domain test package | New test class | Tests |
|---|---|---|---|
| `exchange/critical/CriticalResultController` | `it.exchangeresult` | `CriticalResultControllerHttpTest` | 32 |
| `exchange/result/ResultController` | `it.exchangeresult` | `ResultControllerHttpTest` | 18 |
| `exchange/release/PatientReleaseController` | `it.exchangerelease` | `PatientReleaseControllerHttpTest` | 20 |
| `exchange/release/PatientReleasedRevisionsController` | `it.exchangerelease` | `PatientReleasedRevisionsControllerHttpTest` | 5 |

### URL-level observation (carried forward from AUD-08/-09)

A URL-level grep confirms these controllers' routes are also called by existing
flow tests (`CriticalResultFlowTest`, `ResultRoutingIntegrationTest`,
`PatientReleaseIsolationTest`). As in AUD-09, the class-name audit is a false
positive at the URL level, but the four-case HTTP **security** matrix was not
systematically asserted for these routes. This change adds that matrix without
duplicating the business-flow assertions.

## Acceptance matrix implemented per class

1. **401** — request with no `X-Synthetic-Subject` header.
2. **403** — authenticated subject with no active membership (`ghost-1111`);
   the patient revision list also proves a `reception` subject is denied.
3. **422** — malformed `X-Active-Organization` (`not-a-uuid`), with error code.
4. **Success** — allowed subject reaches the controller's documented status
   (201 for creates, 200 otherwise), driven through a real workflow.
5. **Invalid body** — a syntactically complete, domain-invalid command body
   returns 422 with a machine-readable error code (`INVALID_PATIENTID`,
   `INVALID_INPUT`, `INVALID_REVISION_ID`).
6. **List boundary** — `ResultController` rejects an unknown reconciliation
   `status` with 422/`INVALID_STATUS`; `PatientReleasedRevisionsController`
   rejects a non-UUID patient id with 422/`INVALID_PATIENT_ID`.

Every test uses `@Tag("integration")`, `@ActiveProfiles("synthetic-auth")`,
`@SpringBootTest` on a random port with a dedicated synthetic PostgreSQL
database, and the `headers(...)`/`send(...)` request pattern copied from
`it/exchangeresult/ResultRoutingIntegrationTest.java` and the AUD-09
`*HttpSecurityTest` classes. Each class drops its database in `@AfterAll`.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=CriticalResultControllerHttpTest,ResultControllerHttpTest,PatientReleaseControllerHttpTest,PatientReleasedRevisionsControllerHttpTest test` | 75 tests, 0 failures, 0 errors, 0 skipped (first run: 1 failure, corrected by using a domain-invalid body — see Defects) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` (whole integration tier) | exit 0; surefire aggregate 205 suites / 2331 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (unit tier) | exit 0 |
| `AUREVIA_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history, versioned config verified |
| `bash apps/api/check.sh -Pno-security` (JaCoCo gate; `no-security` skips OWASP/SpotBugs only) | exit 0 — full `verify` with build-failing JaCoCo rules |
| `bash scripts/worker-check.sh` | blocked at `check-pack.py`: "opencode.json is stale" — documented cloud-worker false positive OPS-CW-003-01; all later gates run directly (rows above) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`, `project-name.py`, `bash -n` and `shellcheck` over shell scripts | all exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` | 271 tests, 8 failures / 10 errors — all in `test_agent_run`, `test_check_pack` and YAML-dependent workflow tests (`ModuleNotFoundError: No module named 'yaml'`), i.e. local git/gh behaviour, the skip-worktree `opencode.json` profile and a missing Python module. None touches Java tests or this change; the integrator image has PyYAML and the correct profile. |
| `bash apps/api/check.sh` (default, with OWASP) | failed before OWASP analysis: `NvdApiException: NVD Returned Status Code: 524`, `NoDataException: No documents exist`. External feed outage, not a finding. Coverage was verified with the `-Pno-security` run above; the integrator/Delivery supplies cached NVD data. |

### `worker-check.sh` skip-worktree diagnosis (tool evidence)

```
git ls-files -v opencode.json            -> S opencode.json
git status --short opencode.json         -> (empty)
git show HEAD:opencode.json vs worktree  -> different (CI profile)
```

The worktree `opencode.json` is the CI profile copied under `skip-worktree` by
`agent-run.sh`; the committed local profile at `HEAD` is correct. This is the
documented OPS-CW-003-01 artifact. `opencode.json` was not touched (out of scope).

## Coverage

`bash apps/api/check.sh -Pno-security` (exit 0) enforces the 100% JaCoCo rules.
`apps/api/target/site/jacoco/jacoco.csv`:

| Metric | Covered | Missed | Percent |
|---|---|---|---|
| Instruction | 90407 | 0 | 100.00% |
| Branch | 6377 | 0 | 100.00% |
| Line | 15680 | 0 | 100.00% |
| Complexity | 7042 | 0 | 100.00% |
| Method | 3802 | 0 | 100.00% |

Class coverage is also 100%: every class in the report has zero missed lines,
and the build-failing class rule passed. **No production code was added or
changed, so there are no new production branches to cover.** The new branches
are all in test code: the parameterized 401/403/422 cases, the invalid-body and
invalid-list-filter cases, and the success workflow branches of the four new
test classes. Each is exercised by its own assertion in the same class, and the
75 test i
