# S-G5-07 — Separate environments, synthetic fixtures, signed builds, migration checks and progressive rollout (OPS-06)

## Scope and ownership

- Branch: `swarm/S-G5-07-114` (base `4f9bbeb`). Committed locally; **not pushed, tagged or `gh`-invoked**.
- Scope respected: changes only under `infra/s_g5_07`, `docs/operations/s_g5_07`, plus this PR-notes file.
  No tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`) was edited.
- Reserved migration version **V49 intentionally unused**: OPS-06 is a delivery/process control, not a schema
  change. The repository's change-safety gate explicitly allows version gaps.
- Synthetic data only. No real patient data, identifier, credential or deployment detail is present.

This is a resumed attempt (TODO records `attempts: 3`). Earlier attempts had already delivered the full
evaluator, policy manifest, fixtures, tests, canonical gate and operation docs, and those changes were
already present in the base commit. The earlier attempt verified that implementation end to end, engaged a
bounded independent DevSecOps review, and fixed every genuine finding it produced with test-first changes.

This worker re-ran the full story gate from a clean tree and found no further fail-open or doc/code mismatch.
It added one defence-in-depth control with a red→green cycle: a gate-level negative fixture proving that a
synthetic rehearsal relabelled only by `kind` and `source` (with no run provenance) is still rejected — making
the honesty rule executable by the canonical gate, not only by unit tests. No evaluator logic changed.

## Requirement mapping (acceptance)

| OPS-06 control (spec §8) | Enforcement | Evidence |
|---|---|---|
| Separate development/test/staging/production accounts and resources | `environments` exact allowlist, distinct account + resource identifiers (normalized) | `_check_environments`; `EnvironmentTest`; conforming + violation fixtures |
| Synthetic fixtures in non-production, no real patient data | `fixtures.synthetic_only`, `no_real_patient_data`, non-prod env list | `_check_fixtures`; `FixtureTest`; policy manifest |
| CI policy checks, dependency scan, secret scan | three literal-boolean gates | `_check_ci_checks`; `CiChecksTest`; `check.sh` also runs real repo gates |
| Signed builds | allowlisted method, full `sha256:<64 hex>` digest, literal `verified`, signer identity, `signature_verified` promotion gate | `_check_signed_builds`; `SignedBuildsTest` |
| Migration checks | exact `check-migrations --require-baseline` label plus expand/contract and forward-fix declarations; **real checker executed by the gate** | `_check_migration_checks`; `check.sh` runs `scripts/check-migrations.py --require-baseline` |
| Monitored progressive, reversible rollout | progressive strategy, ≥2 distinct normalized stages ending in `full`, health gates, monitoring, tested rollback | `_check_rollout`; `RolloutTest` |
| Reversible deployments / expand-contract | top-level `reversible_deployments` literal true + migration declarations | `evaluate`; `ReversibleDeploymentTest`; decision 0002 |
| Audited, approved, expiring, separated production access | approval, distinct normalized requester/approver, audited, expiring | `_check_production_access`; `ProductionAccessTest` |
| Honesty: a rehearsal is never final evidence | `kind` classification, `evidence_conflict` and a mandatory six-field provenance object for measured claims | `_verdict`, `_check_provenance`, `evaluate`; `ConformingArtifactTest`, `MeasuredProvenanceTest` |

## Exact commands run and results (tool evidence)

All commands run from the repository root unless noted.

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_07/tests -q` | **71 tests, OK** |
| `bash infra/s_g5_07/check.sh` | exit 0 — 71 unit tests, conforming fixture passes, both negative fixtures exit exactly 1, real migration gate green |
| `python3 -m trace --count --coverdir <tmp> infra/s_g5_07/tests/run_coverage.py` | suite OK; evaluator `.cover` shows **0 uncovered lines (100% line coverage)** |
| Direct PoC via `python3 -I -c` against the evaluator | all fail-open PoCs now fail closed (see Defects); conforming measured artifact with provenance passes with `final_evidence=True` |
| `bash -n infra/s_g5_07/check.sh` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 (Markdown links and requirement IDs valid after all edits) |
| `python3 -I scripts/check-secrets.py` | exit 0 (no credential/PHI-shaped strings) |
| `python3 -I scripts/check-pack.py` | exit 1 — `opencode.json is stale` (shared-tree drift, out of scope; see Defects) |
| `python3 -I scripts/gen-opencode.py --check` | exit 1 — `opencode.json is out of date` (same out-of-scope drift) |
| `python3 -I -m unittest discover -s scripts/tests -q` | 163 tests: 5 failures, 4 errors — **pre-existing and out of scope** (see Defects) |

`coverage.py` is not installed here, so line coverage is measured with the stdlib `trace` tool. `trace`
does not measure branches; the suite enumerates both outcomes of every evaluator conditional with paired
synthetic cases (including `subTest` matrices), documented in `evidence-contract.md`.

### This attempt's test-first change (red → green)

- **Gap:** the honesty rule "a relabelled synthetic rehearsal is never final evidence" was asserted only by
  unit tests; the canonical gate itself ran just one positive and one control-breach fixture.
- **RED:** added `RelabelledRehearsalFixtureTest` (3 cases) referencing
  `fixtures/relabelled_violation_evidence.json` before creating it →
  `python3 -I -m unittest discover -s infra/s_g5_07/tests -q` reported `FAILED (errors=1)` with
  `FileNotFoundError` for the missing fixture.
- **GREEN:** created the negative fixture (conforming rehearsal with only `kind=measured_deployment` and a
  neutral `source`, no `provenance`) and wired `check.sh` to require it exits exactly 1. Result: 71 tests OK,
  gate exit 0, docs/secrets/migration gates exit 0, 100% evaluator line coverage retained.
- No production evaluator code changed; documentation (`README.md`, `evidence-contract.md`) updated so the
  named check, test count and fixture contract stay discoverable.

## Defects found and disposition

### Genuine findings from the bounded DevSecOps review — all confirmed by executed PoC and fixed

1. **MEDIUM fail-open — whitespace self-approval.** `_check_production_access` compared raw
   `requester != approver`, so `requester="release-manager "`, `approver="release-manager"` passed
   `separation_of_duties` (PoC: `verdict=pass`). Fixed by comparing stripped values; added
   `test_whitespace_padded_self_approval_is_rejected`.
2. **MEDIUM fail-open — big-bang relabelling.** `rollout.stages` checked distinctness and the final `full`
   stage on raw strings, so `["full ", "full"]` passed as "two distinct stages" (PoC: `verdict=pass`),
   contradicting the documented control. Fixed by normalizing stage names before distinctness and
   final-stage checks; added `test_whitespace_padded_duplicate_stages_are_rejected`.
3. **MEDIUM honesty gap / false documentation claim.** `evidence-contract.md` claimed the sibling OPS-03
   evaluator used "the same classification keys ... so a rehearsal cannot be promoted by relabelling".
   Both parts were false: keys differ (`measured-deployment` vs `measured-pilot`), and changing only
   `kind`/`source` on the rehearsal fixture yielded `verdict=pass`, `final_evidence=True` (PoC C). Fixed in
   two ways: (a) added a mandatory six-field `provenance` object for `measured_deployment` (tool,
   tool_version, command, utc_timestamp, environment, source_sha), matching the sibling gate, with
   `_check_provenance`, `MeasuredProvenanceTest` and manifest constant; (b) corrected the documentation to
   state the actual keys, the provenance requirement and the human/cloud boundary.
4. **LOW weak negative control.** `check.sh` treated any non-zero exit of the violation fixture as
   rejection, while the contract documents exit 1. Fixed to assert exactly status 1, so a parse/IO
   regression (exit 2) cannot pass the gate.

### Earlier finding from this attempt's own verification — fixed

5. **Documentation/code mismatch.** `environment-and-fixtures.md` claimed no whitespace trimming was
   applied to identifier comparison, while the evaluator strips values before distinctness (the safer,
   fail-closed behavior). Kept the code, corrected the doc, and added
   `test_whitespace_variant_account_is_treated_as_a_duplicate` and
   `test_whitespace_variant_resource_is_treated_as_a_duplicate`.

### Pre-existing repository tooling failures — out of scope, not modified

`python3 -I -m unittest discover -s scripts/tests -q` reports 5 failures / 4 errors on the base commit:
- `test_fast_tier` and `test_workflow_concurrency` cannot import `yaml` (`ModuleNotFoundError`) — local
  Python lacks PyYAML; the CI image installs it.
- `test_swarm.PlanTests` (3), `test_agent_run.AgentRunTests` (2) and
  `test_java_conventions.JavaConventionTest` (2) assert on live repository/tracker state or other modules;
  the `3 not >= 10` assertion reflects the current swarm tracker state.

These are outside this story's scope and are the integrator's / owning workers' concern. None touches this
story's code. The story's own gate and all scoped repo checks are green. Additionally, this worker observed
`python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check` both fail with
`opencode.json is out of date / stale` — shared-tree drift from parallel workers' agent edits, present on the
clean base commit `4f9bbeb` and unrelated to this story's `infra/s_g5_07` / `docs/operations/s_g5_07` files.
Per the scope rules this worker did not regenerate or commit `opencode.json`.

## Decisions

- **D1 (retained):** The evaluator judges declaration *shape and completeness*, not reality. It cannot
  verify cloud IAM isolation, a real signature, executed scans, live health signals, an approver's identity
  or audit immutability. This follows `docs/decisions/0002-ops06-migration-change-safety.md`, which
  rejected a self-attesting environment manifest as vacuous; no vacuous manifest was introduced.
- **D2:** All distinctness comparisons use stripped values (fail-closed against whitespace aliases);
  documentation follows the code rather than weakening the checks.
- **D3:** Measured claims require run provenance so relabelling a rehearsal cannot grant `final_evidence`;
  presence/shape only, with human and cloud verification retained.
- **D4:** V49 left unused; no schema change is needed for an OPS-06 process control.
- **D5:** No CI/workflow or shared-script wiring: outside scope; the canonical gate runs the authoritative
  migration checker directly instead of trusting an artifact label, and now asserts the exact negative
  exit status.
- **D6:** The canonical gate enforces a relabelled-rehearsal negative fixture (exit exactly 1). This makes
  the "relabelling alone cannot grant final evidence" honesty rule a gate-level regression guard, not just
  a unit assertion. It adds no heuristic to the evaluator: the fixture is the conforming rehearsal with
  only `kind` and `source` changed, so the sole breach is missing provenance.

## Evidence source classification

- **Tool / executed commands:** all unit-test, gate, coverage, docs, secrets, migration and shell-syntax
  results above were executed by this worker (synthetic fixtures only; scoped checks green). The PoC rows
  and the two stale-`opencode.json` failures are carried from the base commit / shared-tree drift and are
  classified in Defects.
- **Reviewer:** one bounded independent DefSecOps (defensive-security) review of this exact scope; all four
  findings were reproduced by executed PoC before being fixed. The reviewer could not execute commands
  itself, so its PoCs were re-run by the orchestrator (tool evidence).
- **Simulated user / human / clinical:** none. There is no real deployment, clinical or human pilot
  evidence in this story. G5 launch acceptance and real deployment evidence remain open for the accountable
  owners, as listed in `docs/operations/s_g5_07/README.md`.
