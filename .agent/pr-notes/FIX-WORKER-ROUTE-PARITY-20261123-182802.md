# FIX-WORKER-ROUTE-PARITY — block newly introduced API route gaps in swarm integration

## Summary

`worker_gate.decide` accepted a failing `OpenApiRouteParityTest` when a changed file such as
`MailController.java` did not match the test class name (BUGS.md OPS-PARITY-CLASSIFICATION).
This change adds a fail-closed route-parity decision to the worker gate: it compares the
candidate's undocumented routes (parsed from the private Surefire report) and the frozen
`contracts/openapi/missing-routes.txt` against the same files at the pinned base SHA. A new
route, baseline growth, or unreadable base evidence blocks the worker; an exact pre-existing
METHOD + normalized-path exception, a documented candidate, and a shrinking baseline pass.
The Java parity suite now reads the frozen baseline and excludes only those exact entries.

Evidence provenance: executed tool commands (Maven/Surefire, Python unittest and coverage,
policy scripts, shell syntax/lint) and read-only specialist reviews below. No simulated user,
human participant, clinical evidence, GitHub run, `gh` call or Docker run was produced. All
route fixtures are synthetic; no patient data, credential or token is present.

## Affected requirements and acceptance mapping

OPS-06, WEB-05, ARC-04. No spec text was changed; the task's requirement IDs are recorded here.

| Acceptance scenario | Where satisfied |
|---|---|
| Synthetic candidate adds one undocumented route even with existing debt → fails | `check_parity` `new = reported - base`; `RouteParityDecisionTest.test_controller_only_change_with_a_new_route_fails` |
| Adding the route to the baseline still fails | `grown = candidate - base`; `test_baseline_growth_fails_without_any_failing_test` |
| Adding contract and generated client (route gone from report) → passes | green parity report contributes no routes; `test_documented_candidate_passes` models the contract/client paths |
| Documented repair shrinks debt → passes | set subtraction allows `candidate ⊂ base`; `test_baseline_shrink_after_a_documented_repair_passes` |
| Pinned-base evidence absent, empty or unparseable → fails closed (an absent *baseline file* at the base means zero debt, backed by a green base run) | `pinned_base_routes`; `RouteParityHelpersTest.test_pinned_base_routes_*` and `test_check_parity_fails_closed_for_each_unusable_source` |
| Unrelated failing suite with a controller diff → warning, still passes | parity check leaves the original `related`/unrelated path intact; `test_unrelated_failure_with_a_controller_diff_still_passes` |
| Java exact METHOD + normalized-path exception passes; wrong method/path/new route fails | `OpenApiRouteCoverage.evaluate`/`withoutBaselined`; `OpenApiRouteCoverageTest.evaluateExcludesOnlyTheExactMethodAndNormalizedPath` |

## Risk class and scope

Cross-boundary (Python tooling, Java test-only code, contract baseline file). Files changed:
`scripts/worker_gate.py`, `scripts/worker-check.sh`, `scripts/tests/test_worker_gate.py`,
`scripts/tests/test_worker_gate_coverage.py`, the three
`apps/api/src/test/java/com/aurevia/it/contract/*.java` files and the new
`contracts/openapi/missing-routes.txt`. No `src/main` class, route, migration, RLS policy,
workflow, protected tracker or `opencode.json` was edited. The scope's
`apps/api/src/main/java/com/aurevia/it/contract` prefix does not exist; the
shared parser was kept in the test package, which the brief explicitly allows.

## Test evidence

Exact commands and final lines (tool evidence). The red run was captured before production code:

- RED, before implementation: `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py' -q`
  → `Ran 183 tests in 0.076s`, `FAILED (errors=45)`, each `AttributeError: module ... has no attribute 'parse_route_lines'/'pinned_base_routes'/...`.
- RED caller wiring, after the helpers but before the shell edit: same command
  → `Ran 189 tests in 0.088s`, `FAILED (failures=1)`, `test_worker_check_script_passes_the_pinned_base_to_the_gate`.
- GREEN: `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py' -q`
  → `Ran 189 tests in 0.087s`, `OK`.
- Full tooling suite: `python3 -I -m unittest discover -s scripts/tests -q`
  → `Ran 1373 tests in 39.481s`, `FAILED (failures=6)`. All six are `test_check_pack`
  failures (`opencode.json is stale`, committed file differs from the CI profile); the known
  OPS-CW-003-01 worker-image artifact. Verified once: `git ls-files -v opencode.json` → `S`;
  `git show HEAD:opencode.json` shows the committed local profile. No regeneration, no further
  diagnosis and no protected-file edit.
- Java integration (non-vacuous groups, never `-Pfast`):
  `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=OpenApiRouteParityTest test`
  → exit 0; Surefire `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`.
- Java unit: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=OpenApiRouteCoverageTest test`
  → exit 0; Surefire `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`.
- Whole Java unit tier (unit only, `excludedGroups=integration`): `mvn -q -B -f apps/api/pom.xml -Pno-security test`
  → exit 0; Surefire totals `tests=5942 failures=0 errors=0 skipped=4` (the four skips pre-date this change).
- Coverage driver: `COVERAGE_FILE=/tmp/opencode/all.coverage python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q`
  then `python3 -m coverage report -m --include='scripts/worker_gate.py'` → `100%` (below).
- `bash scripts/python-coverage.sh --full` stops at the same six OPS-CW-003-01 pack failures
  in the shared unittest step; the branch/line result above was read from the collected data.
- `python3 -I scripts/check-wiring.py` → exit 0, no output.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q`
  → `Ran 2 tests`, `OK`.
- `npm --prefix apps/staff-web run gen:api:check` → `gen:api: generated client matches the contract`.
- Individually green after the pack artifact: `check-docs.py`, `check-secrets.py`,
  `check-names.py`, `check-constraints.py --base origin/main --require-base`,
  `check-migrations.py --require-baseline`, `bash scripts/frontend-check.sh`,
  `shellcheck scripts/worker-check.sh`, `git diff --check origin/main` → all exit 0.
- `bash scripts/check.sh` → exit 1 with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`
  (the one confirmed OPS-CW-003-01 artifact); the remaining stages were run individually above.
  The module `apps/api/check.sh` (full `mvn verify`) was deliberately not run by this worker.

Entry point to test trace: `scripts/worker-check.sh` passes the pinned base SHA and baseline
path to `scripts/worker_gate.py main`, which builds the parity context for `decide` →
`check_parity` → `pinned_base_routes`, `candidate_baseline`, `reported_parity_routes`. The
Java entry point `OpenApiRouteParityTest.everyControllerRouteIsDocumentedOrBaselined` calls
`OpenApiRouteCoverage.evaluate`, exercised by the 16-test `OpenApiRouteCoverageTest` suite and
the 2-test Spring integration suite.

## Coverage

`python3 -m coverage report -m --include='scripts/worker_gate.py'` after the full branch-measured
tooling suite: `scripts/worker_gate.py 291 stmts, 0 miss, 133 branches, 0 partial, 100%` (final measurement after hardening).
No threshold, exclusion, pragma, generated marker or test skip was added. Every new branch and
its test:

| Branch | Test |
|---|---|
| `parse_route_lines` blank, `#` comment, valid, malformed, empty text | `test_parse_route_lines_skips_comments_blanks_and_normalizes`, `_returns_empty_set_for_empty_text`, `_rejects_a_malformed_line` |
| `pinned_base_routes`: no ref, unresolved ref, `OSError`, absent base file, generic `git show` error, blank file, malformed file, valid file | eight `test_pinned_base_routes_*` cases in `RouteParityHelpersTest` |
| `candidate_baseline`: unreadable/missing, valid, malformed | `test_candidate_baseline_missing_file_fails_closed`, `_parses_the_working_tree_file`, `_rejects_a_malformed_file` |
| `parse_parity_message`: heading present/absent, ghost heading, non-route line, route before heading | `test_parse_parity_message_*` and `test_real_surefire_message_shape_normalizes_path_variables` |
| `reported_parity_routes`: XML parse error, `seen` false (no report / other suite only), `seen` true (green suite), red with/without heading, null message/text, text-only message | eight `test_reported_parity_routes_*` cases, including `test_reported_parity_routes_without_a_parity_report_is_unusable` |
| `check_parity`: each None source, growth only, new route only, both, neither/shrink | `test_check_parity_fails_closed_for_each_unusable_source`, `_blocks_growth_a_new_route_and_both`, `_allows_a_clean_or_shrinking_baseline` |
| `decide`: parity checked before early return and before related/unrelated verdict; clean parity preserves repair logic | `RouteParityDecisionTest` ten cases |
| `main`: args 2/3 present, default baseline, absent and empty args, parity forwarded to `decide`, blocked exit | five `ParityMainTest` cases |

Java branch → test (honest manual mapping; `OpenApiRouteCoverage` lives under `src/test`, so
JaCoCo's production bundle does not measure it):

| Java branch | Test |
|---|---|
| `readBaseline` blank line, `#` comment, real route, indented route | `baselineReaderSkipsCommentsAndNormalizesPathVariables` |
| `readBaseline` malformed line → `IllegalArgumentException` | `baselineReaderRejectsAMalformedLine` |
| `readBaseline` missing file → `IOException` | `baselineReaderFailsWhenTheFileIsMissing` |
| `withoutBaselined` exact normalized match removed; wrong method and wrong path retained | `evaluateExcludesOnlyTheExactMethodAndNormalizedPath` |
| `withoutBaselined` ghost set preserved | `evaluateNeverSuppressesGhostRoutes` |
| `evaluate` clear report when the route is baselined and documented | `aFrozenBaselineCanClearAnOtherwiseUndocumentedRoute` |
| Spring wiring reaches `evaluate` with the real baseline | `OpenApiRouteParityTest.everyControllerRouteIsDocumentedOrBaselined` (2 tests green) |

The integrator's full `verify` remains the bundle-level gate.

## Findings

- **Owner conflict recorded, not silently resolved.** Merged FIX-OPENAPI-04 deliberately deleted
  the missing-route baseline and made parity strict. The newer task for this story explicitly
  requires reintroducing a frozen baseline and the renamed Java method, so that is the owner
  decision followed here. The older worker-filed reconciliation task-request remains valid and
  is requested again below.
- **Integrator-gate gap (out of scope).** `worker_gate.py` is invoked by `worker-check.sh`, not by
  `integrator-gate.sh`. A worker whose branch reaches the merge gate with a grown baseline and
  a green Java parity suite is therefore covered by the worker gate only. This is mitigated
  because the unchanged `everyApplicationRouteHasAnOperationWithASuccessResponse` test still
  fails any baselined-but-undocumented route in full `verify`; a task-request asks for explicit
  merge-gate enforcement.
- **Empty base is verified, not assumed.** The pinned base's parity suite was run before the
  change: 2 tests, 0 failures. The seeded baseline therefore has zero route lines (header only).
  A stale entry left in a future baseline is no longer rejected by the Java test; the Python
  gate rejects growth but not stale debt, which is harmless with an empty seed.
- **Base-ref divergence (optional).** `changed_paths()` still hard-codes `origin/main` while the
  new gate uses `.agent/base-sha`. Both pointed at `c195307e...` in this run.
- **PostgreSQL unavailable path (optional).** `worker-check.sh` exits 0 before calling
  `worker_gate.py` when PostgreSQL is missing; that path skips the new gate and relies on the
  integrator, as before.
- **Reviewer hardening applied.** A missing parity report now fails closed (the suite never ran
  cannot prove the candidate clean), and an empty pinned base ref is a hard failure in
  `worker-check.sh`. The worker-settable `BASE_REF` override was removed so the pin comes only
  from `.agent/base-sha` (fallback `origin/${BASE_BRANCH:-main}` for manual runs). Java and notes
  wording now attribute baseline-growth enforcement to the worker gate, not to the Java suite.
- **Environment artifact.** The six `test_check_pack` failures and the single `check.sh` failure
  are OPS-CW-003-01 (worktree CI profile under `skip-worktree`), not caused by this change.
- Protected tooling was changed (`scripts/worker_gate.py`, `scripts/worker-check.sh`); this needs
  maintainer review. No suppression, exclusion, skipped test or lowered threshold was added.

## Lessons for workers

- Seed a frozen baseline only from a pinned-base report you actually ran; an empty-but-present
  baseline needs a comment header so "blank evidence" and "zero debt" stay distinguishable.
- A gate that reads Surefire XML must treat a failing suite with no route section as unusable;
  otherwise a ghost route or an unexpected assertion silently clears the candidate.
- Check whether the integrator invokes the gate you changed. A worker-only gate cannot protect
  the merge path, so record the gap and request a scoped follow-up instead of claiming coverage
  of a path you did not wire.

## Review verdicts

- orevia-plan-reviewer: GO (re-review after all Required plan gaps were addressed)
- orevia-code-reviewer: GO (re-review after the hardening deltas)
- orevia-coverage-auditor: GO (re-audit after the hardening deltas)
- orevia-devsecops: GO (re-review after the hardening deltas)

`orevia-context-optimizer` supplied the authoritative base-ref and route-evidence map.
`orevia-test-engineer` reviewed the planned test matrix before coding and raised nine
Required gaps; all were addressed in the RED suite or recorded in Findings. Where it asked
for an injectable Java baseline path, the change uses a shared package-visible
`OpenApiRouteCoverage.evaluate` seam instead, exercised by six synthetic tests.

## Maintainer review note

This pull request edits protected tooling (`scripts/worker_gate.py`, `scripts/worker-check.sh`)
and is intentionally left for maintainer review. `opencode.json` was not regenerated: the worktree
copy is the CI profile installed under `skip-worktree` (OPS-CW-003-01) and `git show HEAD:opencode.json`
is the committed, correct local profile.

```task-requests
[
  {
    "title": "Enforce missing-route baseline growth in the merge gate",
    "details": "Make a merge-time gate compare contracts/openapi/missing-routes.txt with the pinned base so a grown baseline cannot pass solely because the Java parity suite excludes frozen entries.",
    "scope": ["apps/api/src/test/java/com/aurevia/it/contract"],
    "depends": ["FIX-WORKER-ROUTE-PARITY"]
  },
  {
    "title": "Reconcile the frozen baseline policy with strict route parity",
    "details": "FIX-OPENAPI-04 removed the baseline; FIX-WORKER-ROUTE-PARITY reintroduced a frozen one. Record the owner decision and decide whether the baseline file is temporary debt or permanent policy.",
    "scope": ["contracts/openapi"],
    "depends": ["FIX-WORKER-ROUTE-PARITY"]
  }
]
```
