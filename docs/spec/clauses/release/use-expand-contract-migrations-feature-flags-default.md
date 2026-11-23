# WEB-11 clause 4 — use expand and contract migrations and feature flags that default off

## Clause

Use expand and contract migrations and feature flags that default off.

## Status

Not built

## Evidence

This is a repository-survey evidence note. Every file, test and command below was found or re-run locally; no CI run, real deployment, signed artifact, clinical review or human decision is claimed. `Not built` follows rule 3 of this task: WEB-11 evidence is produced with the infrastructure owner before G5, and the repository does not hold that evidence (see `## Gap`). The offline expand/contract checker described below is a supporting control, not evidence that the clause has been used in a release. The expand/contract half alone would be "Built and verified" as offline tooling; the clause as a release practice is not.

The clause is the third sentence of WEB-11 in [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md) (line 159). Section 8.1 requires WEB-01–WEB-13 evidence before G5 and states WEB-11 evidence is produced with the infrastructure owner. Section 11 names "Service fails during rollout" as a testable outcome — operators stop traffic to the affected feature — but that is a required outcome, not an implemented mechanism here. The tracked checklist in [docs/operations/production-readiness/README.md](../../../../docs/operations/production-readiness/README.md) (line 19) still records the WEB-11 evidence as "not started", owner "Infrastructure owner", with an empty evidence link.

Expand and contract — an offline checker exists and passes:

- [scripts/check-migrations.py](../../../../scripts/check-migrations.py) is the OPS-06 change-safety gate. It parses `Change: expand|contract`, `Forward-fix:` and `Requires-expand:` headers, requires a format-valid forward fix and an existing earlier `Change: expand` for every contract, rejects duplicate versions, and fails closed under `--require-baseline`. Its module docstring says the header is a developer declaration: the checker proves its format and reference, not that SQL matches the label; human review of every contract remains required.
- [scripts/tests/test_check_migrations.py](../../../../scripts/tests/test_check_migrations.py) is the nearest honest expand/contract test. It uses synthetic migration fixtures in a scratch repository; `ContractTest.test_valid_contract_passes`, `ContractTest.test_documented_contract_example_passes` and `SequenceTest.test_clean_expand_migration_passes` cover the accepting paths, and the remaining cases cover destructive DDL forms, comment/string/dollar-body evasion, duplicates, gaps, forward-fix and expand-reference branches, and CLI exit codes. The observed full run is `Ran 35 tests in 0.050s / OK`.
- [db/migrations/README.md](../../../../db/migrations/README.md) (lines 15–35) documents the gate, its canonical command and its wiring into `scripts/check.sh`, `scripts/quick-check.sh` and the Delivery policy job; it also records that the header is self-declared and `db/check.sh` remains the authoritative apply-and-assert gate on real PostgreSQL.
- [docs/decisions/0002-ops06-migration-change-safety.md](../../../../docs/decisions/0002-ops06-migration-change-safety.md) is the accepted decision. It adds the deterministic checker and explicitly defers environment-account separation, signed builds, monitored progressive rollout and audited production access to cloud/human-gated work; it rejected a self-attesting environment manifest as vacuous. Its evidence line records 34 cases and 100% line coverage measured with stdlib `trace` at decision time; the current suite has 35 cases.
- [docs/decisions/0003-persistence-of-in-memory-stores.md](../../../../docs/decisions/0003-persistence-of-in-memory-stores.md) (line 45) records that one migration per store, with its own reserved version, is required to keep expand/contract changes reviewable under OPS-06.
- [docs/archive/TODO-DONE.md](../../../../docs/archive/TODO-DONE.md) (line 644) records S-G0-03d delivering the gate with rollout deferred to S-G5-07; line 1073 records S-G5-07 done with PR #129 in `infra/delivery-safety` and `docs/operations/delivery-safety`.
- [infra/delivery-safety/check.sh](../../../../infra/delivery-safety/check.sh) executes the evaluator's unit tests, three synthetic fixture runs and the real migration checker. A local run reported `Ran 71 tests ... OK` and `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`, exit 0. [docs/operations/delivery-safety/README.md](../../../../docs/operations/delivery-safety/README.md) states the contract, evaluator and handoff are delivered while real deployment evidence and per-site launch approval are **not** produced there and remain open G5 gates; synthetic rehearsals are explicitly `final_evidence: false`. [docs/operations/delivery-safety/build-and-migration-gates.md](../../../../docs/operations/delivery-safety/build-and-migration-gates.md) names the existing checker in the artifact contract and states what it does not prove.

Feature flags that default off — no release mechanism found:

- Exact search: `grep -rniE 'feature.?flag|feature.?toggle|default.?off|kill.?switch|launch.?darkly|unleash' .github/workflows/ infra/ apps/api/src/main/resources/ db/migrations/` returned one hit, `.github/workflows/swarm-integrator.yml:23`, a `SWARM_ALLOW_PROTECTED` kill switch for merging protected files in swarm CI. It is not an application or release flag and does not gate WEB-11 rollout.
- The closest first-party default-off switch is [apps/api/src/main/java/com/aurevia/smartclinic/labflow/rejection/RejectionNotificationPolicy.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/labflow/rejection/RejectionNotificationPolicy.java) (line 25): `@Value("${smartclinic.lab.rejection.notify-patient:false}")` makes one patient-notification behaviour require explicit site enablement. It is a single LAB-01/PAT-02 domain policy default, not a feature-flag registry, rollout percentage or a gate on a promoted release.
- No feature-flag registry, configuration key, runtime toggle, default-off release flag or 5%/25%/100% traffic mechanism exists in the four searched directories or in the delivery-safety evidence contract. No workflow run or deployment artifact in this repository demonstrates flags defaulting off.

## Gap

Missing before G5:

1. A default-off release feature-flag mechanism for the application: a named flag configuration whose unset value is off, with tests proving the off default and the explicit enable path. The one domain switch above does not provide it.
2. Evidence that expand and contract migrations are actually used in a release, not just checked offline: a migration run log and human review of each contract's self-declared header (decision 0002 names human review as the compensating control).
3. Real deployment evidence all left open by decision 0002 and the S-G5-07 handoff: separated cloud accounts with verified isolation, clean dependency and secret scan records, a signed artifact with an SBOM and verified signer identity, monitored progressive rollout with health gates and a rehearsed rollback, and approved, audited, expiring production access.
4. The WEB-11 evidence row in [docs/operations/production-readiness/README.md](../../../../docs/operations/production-readiness/README.md) is "not started" with no evidence link, and per-site G5 acceptance is absent.

Files that would change: a new application-side flag configuration and its tests under the owning domain package (no such file exists yet); [infra/delivery-safety/delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py), `infra/delivery-safety/delivery-safety-policy.json`, [docs/operations/delivery-safety/evidence-contract.md](../../../../docs/operations/delivery-safety/evidence-contract.md) and [docs/operations/delivery-safety/rollout-and-production-access.md](../../../../docs/operations/delivery-safety/rollout-and-production-access.md) to judge a default-off flag contract; `docs/operations/production-readiness/launch-checklist.md` and the G5 evidence pack to record the rehearsal and named approvers. This note only lists those files; it changes none of them.

Number of separate tasks: at least three — implement and test the default-off flag mechanism (code); extend the delivery-safety contract, evaluator, policy manifest and documentation (offline tooling); and supply the cloud/human evidence with per-site G5 sign-off (people, not code). A maintainer correction of the stale 34-case figure and the stale archive plan entry is separate cleanup, not clause evidence.

Suppliers before G5: the infrastructure owner with DevSecOps supplies account separation and scan records; release engineering with the signing authority supplies the signed artifact, SBOM and signer identity; operations with the system designer supplies the monitored rollout and rollback rehearsal; a named approver and privacy reviewer supply audited, expiring access; the Product, clinical, engineering and privacy owners supply the signed acceptance matrix with roll-forward/rollback criteria (specification section 10), and the G5 acceptance owners supply final per-site sign-off.

## How to verify

Repository checks a reviewer can run now. These prove only the offline tooling, never a deployment or a flag default:

- `python3 -I scripts/check-docs.py` — exit 0: this note's relative links and requirement IDs are valid.
- `python3 -I scripts/check-migrations.py --require-baseline` — exit 0 on the current migration set.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q` — `Ran 35 tests ... OK`: the expand/contract checker suite.
- `bash infra/delivery-safety/check.sh` — `Ran 71 tests ... OK`, final line `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`, exit 0: the synthetic evaluator and real checker, not a deployment.
- The `grep -rniE 'feature.?flag|feature.?toggle|default.?off|kill.?switch|launch.?darkly|unleash' .github/workflows/ infra/ apps/api/src/main/resources/ db/migrations/` command above — returns only the swarm CI kill switch, confirming no WEB-11 release flag mechanism.
- `grep -n '^## ' docs/spec/clauses/release/use-expand-contract-migrations-feature-flags-default.md` — shows the five headings in the required order; `test -f` on each linked path confirms every relative link resolves.

Human evidence a site must produce before G5, which no command or workflow run can substitute for:

- Four separated cloud accounts with distinct resources, isolation results and clean dependency/secret scan records, from the infrastructure owner with DevSecOps.
- One signed artifact with an SBOM, a `sha256:` digest and a verified trusted signer identity, from release engineering with the signing authority.
- A monitored, reversible 5%/25%/100% rollout with health gates, live metrics and a recorded rollback rehearsal, from operations with the system designer.
- Approved, attributable and expiring production access with requester/approver separation, from a named approver and privacy reviewer.
- A signed acceptance matrix naming the approvers and the roll-forward/rollback criteria, including which flags default off at that site, from the Product, clinical, engineering and privacy owners, followed by G5 per-site acceptance. Until that evidence exists, provider or partner traffic must not be enabled.
