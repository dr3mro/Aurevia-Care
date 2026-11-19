# S-G5-09 — Onboarding dataset manifest, dry run, controlled batches and authorized sign-off

## Requirement

- **OPS-08** (primary): "Import approved onboarding datasets through a versioned
  manifest and deterministic mapping. Dry-run counts, unsupported fields,
  duplicate risk, missing attachments and errors. Preserve import provenance and
  historical signature evidence without creating a new attestation. Commit in
  controlled batches, reconcile counts and hashes, and require authorized
  sign-off before changing the source of truth."
- Supporting context only (no new requirement IDs introduced): IAM-03
  (issuer-scoped MRN uniqueness drives duplicate risk), ARC-01 (provenance),
  G5 launch gate. The approved clause is in `SMARTCLINIC_V1_SPEC.md`.

## Classification and scope

- Workflow class: **cross-boundary / operational tooling** (docs + synthetic
  evaluator). No clinical data, no application runtime, no schema.
- Changed paths (only): `infra/s_g5_09/`, `docs/operations/s_g5_09/`, and this
  PR-note file. No shared tracker, API, schema, CI or workflow file touched.
- Reserved migration **V51 not used**: no schema change is required; version
  gaps are permitted by the repository's change-safety gate.
- Java package/class naming rule: not applicable (no Java in scope).

## Acceptance mapping (OPS-08 clause → enforcement → evidence)

| OPS-08 clause | Enforced by | Test evidence |
|---|---|---|
| Approved dataset via **versioned manifest** | `_check_envelope`: `schema_version`, `requirement=OPS-08`, positive-int `manifest_version`, `source`, `synthetic_only` | `EnvelopeTest` (5 tests) |
| **Deterministic mapping** | `_check_mapping`: `deterministic=true`, `strategy=canonical_sha256`, unique string targets, issuer-scoped identity fields, declared `ignored_fields` | `MappingTest` (11 tests) |
| Dry-run **counts / unsupported fields / duplicate risk / missing attachments / errors** | `_dry_run` returns all five named findings; each gates a named check | `DryRunTest` (10 tests), `ConformingManifestTest.test_dry_run_reports_all_required_categories` |
| Preserve **provenance + historical signature evidence**, **no new attestation** | `_check_provenance` plus per-record `historical_signature`; `creates_new_attestation` must be false; verdict reports `new_attestations_created: 0` | `ProvenanceTest` (6 tests) |
| **Controlled batches; reconcile counts and hashes** | `_check_batches`: contiguous sequences, sorted/unique ids, exact coverage, bounded size (`MAX_BATCH_SIZE=100`), hash reconciliation; ordered `batch_plan` | `BatchesTest` (10 tests), `ConformingManifestTest.test_batch_plan_is_ordered_and_reconciles` |
| **Authorized sign-off before source of truth changes** | `_check_sign_off`: named approver, authorized role, `decision=approved`, version + all-batch scope, content digest binding, `approved_at`; gates `commit_allowed` / `source_of_truth_action` | `SignOffTest` (11 tests) |
| Fail-closed reporting of every breach | `evaluate` collects all named checks; `format_report`; CLI exit 0/1/2 | `ReportAndCliTest` (7 tests), `ManifestFixtureTest` (3 tests) |

Full entry-point-to-test trace:
`bash infra/s_g5_09/check.sh` → `python3 -I -m unittest discover -s infra/s_g5_09/tests` → `test_onboarding.py` imports `infra/s_g5_09/onboarding.py` → `evaluate()` runs the envelope, dataset, mapping, provenance, dry-run, batch and sign-off checks → CLI runs on the conforming and violation fixtures.

## Work performed in this take

The base commit already carried a complete, committed implementation from an
earlier attempt. I re-validated it, then closed one genuine fidelity gap with a
test-first change:

- **Defect/gap D-1 (docs ↔ code mismatch):** `onboarding-manifest.md` states a
  record's `source_id` must be "unique within the dataset", but the evaluator
  had no explicit check. Duplicate IDs were only caught indirectly by batch
  coverage/order, and a blank or non-string ID was reported only as a generic
  dataset.records failure — not as a named finding.
  - Red first: added `DatasetTest.test_source_ids_must_be_unique_within_the_dataset`
    and `test_blank_or_non_string_source_id_is_rejected` → 5 failures
    (`'dataset.source_ids' not found`), confirming the missing behavior.
  - Green: `_check_dataset` now emits a named `dataset.source_ids` check that
    requires every source id to be a non-blank string unique within the dataset,
    failing closed before any `set()` on malformed input. 70/70 tests pass.
  - Doc updated so the named check is discoverable in `onboarding-manifest.md`.
- No other production behavior changed. 100% line coverage preserved.

## Exact commands run and results (evidence source: executed tool commands)

From repository root:

| Command | Result |
|---|---|
| `bash infra/s_g5_09/check.sh` | exit 0 — 70 tests OK; conforming fixture passes; violation fixture fails as required |
| `python3 -I -m unittest discover -s infra/s_g5_09/tests -q` | exit 0 — `Ran 70 tests ... OK` |
| `python3 -m trace --count --coverdir /tmp/opencode/s_g5_09_cov2 infra/s_g5_09/tests/run_coverage.py` | exit 0; evaluator `.cover` shows **no `>>>>>>` lines = 100% line coverage** of 359 executable lines |
| `python3 -I scripts/check-docs.py` | exit 0 — links and requirement IDs valid |
| `python3 -I scripts/check-secrets.py` | exit 0 — no credential/PHI-shaped values in fixtures or docs |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 — V1–V51 baseline intact (V51 unused) |
| `python3 -I -m unittest discover -s scripts/tests -q` | **pre-existing failures unrelated to this story** (see below) |

Red-state evidence (before the fix): `python3 -I -m unittest discover -s infra/s_g5_09/tests -q` → `FAILED (failures=5)` all asserting the absent `dataset.source_ids` check.

### Note on the broader tooling suite

`python3 -I -m unittest discover -s scripts/tests -q` reports pre-existing
failures on the clean base commit, none touching `infra/s_g5_09` or
`docs/operations/s_g5_09`:

- `test_fast_tier` / `test_workflow_concurrency`: `ModuleNotFoundError: yaml`
  (PyYAML not installed in this worker image — environmental).
- `test_swarm.PlanTests.*`: parallel workers have claimed most tasks, so
  `test_open_spec_tasks_are_dispatchable` sees 3 open tasks instead of ≥10;
  related label/permission tests reflect in-flight orchestration state.
- `test_check_pack.*` and `gen-opencode.py --check`: `opencode.json is out of
  date` from a parallel worker's agent edits (shared-tree drift; out of scope).
- `test_java_conventions.*`: findings in `G3EvidenceService.java` and
  `PatientFlowContract.java` from other workers' in-flight Java stories.

The working tree was clean at the base commit `ddbd816` before my scoped edits,
so these are inherited from the shared branch, not introduced here. Per the task
rules I did **not** modify shared tooling, trackers, `opencode.json` or Java
files to "fix" them.

The integrator runs the database and integration gates; this story has no
database, API or end-to-end surface, so there are none to run. The story's own
`check.sh` is the canonical gate (consistent with sibling G5 infra stories,
which are likewise not invoked by root `scripts/check.sh`).

## Decisions and limitations (durable, synthetic-only)

- **D1:** The evaluator judges a manifest artifact, not a running importer. A
  script cannot prove a real import, a virus-scan verdict or a human approval
  happened; claiming otherwise would fabricate evidence.
- **D2:** Mapping must declare `canonical_sha256` and `deterministic: true`.
- **D3:** Duplicate identity and missing attachments fail the dry run (a risk
  reported without gating would pass through).
- **D4:** Unsupported fields must be declared in `ignored_fields`; blank
  required fields are errors; source ids must be non-blank and unique (D-1).
- **D5:** Batches must be ordered, unique, bounded and hash-reconciled.
- **D6:** Sign-off names an authorized role and binds to a content digest plus
  version and every batch, so re-hashing tampered content cannot carry approval.
- **D7:** Historical signature evidence is required per record and no new
  attestation is created.
- **D8:** V51 unused — no schema change.
- **D9:** Identity duplicates use the issuer-scoped MRN (IAM-03); mapping
  targets must be unique strings so reverse lookup is JSON-key-order independent.

**Open (human/G5) items:** a real approved dataset and manifest, a real named
sign-off event, verification that a real importer preserved source hashes, and
G5 product-owner acceptance. None can be discharged by a synthetic rehearsal.

## Evidence-source disclosure

- Gate results (70 tests, 100% line coverage, docs/secrets/migration checks):
  **executed tool commands** in this repository.
- Review findings: **simulated specialist review** carried forward from the
  earlier attempt plus this worker's code/doc inspection. **No human,
  clinical, real-user or real-import evidence** was used or claimed.
- No patient data, identifiers or credentials appear in fixtures, docs or notes.

## Delivery

Committed to the current branch `swarm/S-G5-09-112`. Per the worker rules: **no
push, no tag, no `gh` calls**. Tracker files were left untouched for the
orchestrator/integrator.
