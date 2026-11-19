# S-G5-09 — Onboarding dataset manifest, dry run, controlled batches and authorized sign-off (OPS-08)

## Story and requirements

- Story: S-G5-09 (swarm worker `swarm-10`), branch `swarm/S-G5-09-99`, base SHA `40e1668`.
- Primary requirement: **OPS-08** — approved onboarding datasets imported through a versioned manifest and deterministic mapping; dry-run counts, unsupported fields, duplicate risk, missing attachments and errors; preserve import provenance and historical signature evidence without creating a new attestation; commit in controlled batches; reconcile counts and hashes; require authorized sign-off before the source of truth changes.
- No other requirement ID is introduced. Clinical approval, real import and real sign-off remain human G5 gates.
- Reserved migration version **V51 was not used**: the slice is a deterministic evaluator and documentation, with no schema change. Version gaps are allowed by the repository's change-safety gate.
- Scope honored: only `infra/s_g5_09/` and `docs/operations/s_g5_09/` (plus the tracked PR-notes artifact). No tracker, workflow, script, `.opencode`, API or database file was touched.

## Implementation summary

- `infra/s_g5_09/onboarding.py` — pure, fail-closed evaluator. `evaluate(manifest)` returns a verdict with every named check, the five dry-run findings, an ordered reconciled `batch_plan`, `commit_allowed`, `source_of_truth_action` and `new_attestations_created: 0`. CLI: `python3 -I onboarding.py <manifest.json>` (0 pass / 1 fail / 2 unreadable).
- `infra/s_g5_09/fixtures/synthetic_onboarding_manifest.json` — conforming synthetic manifest (3 records, 2 controlled batches, named sign-off, preserved per-record historical signatures).
- `infra/s_g5_09/fixtures/violation_onboarding_manifest.json` — a synthetic manifest with six independent breaches (wrong requirement id, new attestation allowed, unsupported field, missing attachment, tampered batch hash, pending sign-off).
- `infra/s_g5_09/check.sh` — canonical fast gate: unit tests + conforming fixture passes + violation fixture fails.
- `infra/s_g5_09/tests/test_onboarding.py` (60 tests) and `tests/run_coverage.py` — executable acceptance matrix and stdlib-trace coverage runner. Expected hashes are computed independently with the standard library in the tests, so the evaluator's hashing is genuinely verified.
- `docs/operations/s_g5_09/` — README plus the manifest/mapping contract, dry-run/batch contract and sign-off/provenance contract.

## Acceptance mapping (OPS-08 clause -> evidence)

| Clause | Implementation | Test evidence |
|---|---|---|
| Versioned manifest | `schema_version`, `requirement`, positive `manifest_version`, `source`, `synthetic_only` checks in `_check_envelope` | `EnvelopeTest` (6 tests) |
| Deterministic mapping | `mapping.deterministic`, `canonical_sha256` strategy, required target fields, mapped identity fields, declared `ignored_fields` in `_check_mapping`; canonical sorted-key compact JSON hashing | `MappingTest` (8 tests), `ManifestFixtureTest` |
| Dry-run counts | `dry_run.counts` with total/batch/finding counts | `DryRunTest.test_counts_report_every_category`, `ConformingManifestTest.test_dry_run_reports_all_required_categories` |
| Unsupported fields | `_dry_run` compares every source field to the mapping and declared ignores | `DryRunTest.test_unsupported_fields_are_reported`, `test_declared_ignored_fields_are_not_unsupported` |
| Duplicate risk | Deterministic identity key over mapped identity fields; every member of a duplicate group reported | `DryRunTest.test_duplicate_risk_is_reported` |
| Missing attachments | `present: false` attachments reported and failed | `DryRunTest.test_missing_attachments_are_reported` |
| Errors | Invalid source id, non-object fields, missing/blank required field, non-list/malformed attachments | `DryRunTest` (5 dedicated error tests), `DatasetTest` |
| Preserve provenance / historical signatures, no new attestation | `_check_provenance` requires source system/version, `preserve_historical_signatures: true`, `creates_new_attestation: false` and per-record signature checksum; `new_attestations_created` is `0` only when provenance is conforming | `ProvenanceTest` (6 tests), `ConformingManifestTest`, `SignOffTest.test_new_attestation_claim...` |
| Controlled batches | `_check_batches` enforces contiguous integer (non-boolean) sequence, sorted/unique ids, exact coverage, bounded count (<= 100) and recomputed SHA-256 | `BatchesTest` (11 tests) |
| Reconcile counts and hashes | Sum of `batch_plan` counts equals `total_records`; every declared batch digest recomputed from canonical record bytes | `ConformingManifestTest.test_batch_plan_is_ordered_and_reconciles`, `BatchesTest.test_batch_hash_must_reconcile` |
| Authorized sign-off before source-of-truth change | `_check_sign_off` requires named approver, authorized role, `approved`, a digest binding to manifest version and ordered batch hashes, matching version and full batch set, and approval time; `commit_allowed` is false and action `unchanged` until all checks pass | `SignOffTest` (12 tests), `ManifestFixtureTest.test_without_sign_off...` |

## Exact commands run and results (synthetic, tool-generated)

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_09/tests -q` (RED, before implementation) | 1 error: `FileNotFoundError ... onboarding.py` — confirms the failing-test-first state |
| `python3 -I -m unittest discover -s infra/s_g5_09/tests -q` (GREEN) | `Ran 68 tests ... OK`, exit 0 |
| `bash infra/s_g5_09/check.sh` | 68 tests OK; conforming fixture exit 0; violation fixture correctly rejected; printed `S-G5-09: OPS-08 evaluator unit tests and fixture checks passed`, exit 0 |
| `python3 -m trace --count --coverdir /tmp/opencode/s_g5_09_cov3 infra/s_g5_09/tests/run_coverage.py` | 68 tests OK; `grep -c '>>>>>>' ...onboarding.cover` = **0 missed lines** → 100% line coverage |
| `python3 -I infra/s_g5_09/onboarding.py infra/s_g5_09/fixtures/synthetic_onboarding_manifest.json` | exit 0; `OPS-08 onboarding: PASS`, `commit allowed: True`, `source of truth: commit_in_batches`, `new attestations created: 0` |
| `python3 -I infra/s_g5_09/onboarding.py infra/s_g5_09/fixtures/violation_onboarding_manifest.json` | exit 1; `FAIL`, `commit allowed: False`, `source of truth: unchanged`, with 6 named `[FAIL]` checks |
| `python3 -I scripts/check-docs.py` | exit 0 — all relative links resolve; all requirement IDs defined |
| `python3 -I scripts/check-secrets.py` | exit 0 — no credential or PHI-shaped value introduced |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `bash -n infra/s_g5_09/check.sh` | exit 0 (shellcheck is not installed locally; CI runs it; the script mirrors the already-green S-G5-05 gate) |
| `python3 -m py_compile infra/s_g5_09/onboarding.py infra/s_g5_09/tests/test_onboarding.py infra/s_g5_09/tests/run_coverage.py` | exit 0 |
| `git status --short` | only `docs/operations/s_g5_09/` and `infra/s_g5_09/` untracked, plus this tracked PR-notes file |

## Defects found and fixed during this story

1. **Fail-closed gap in batch hashing (implementation)**: when a dataset record was malformed and filtered out, a batch still referenced its source id and the digest loop raised `KeyError` instead of returning a failed check (8 test errors). Fixed by reconciling only ids present in the record map and failing `batches.sha256` when any declared id is missing. Covered by `DatasetTest` and the fixture rehearsal.
2. **Test-helper defects (tests only)**: `with_records` passed a duplicate `batches` keyword, and a sequence test indexed a second batch that did not exist at size 2. Fixed in the test file; no production behavior was weakened.
3. **Stale PR-notes artifact**: the pre-existing `.agent/pr-notes.md` described an unrelated story (S-G3-06). Overwritten with this story's notes.

No repository defect outside the declared scope was found. Database and integration checks are the integrator's responsibility and were not run here.

## Bounded specialist review and resolutions

A Senior Python Tooling specialist performed a read-only review (no shell access in that session, so the reported commands were not re-run by the reviewer). All findings with impact were fixed in scope and re-verified with the commands above:

| Finding | Severity | Resolution |
|---|---|---|
| F1 — sign-off bound only to a self-declared version and sequence numbers; content could be changed and batch hashes recomputed under the old approval | High | Added `sign_off.content_sha256` over the manifest version and ordered batch digests, checked at `sign_off.content`; focused test `test_content_change_under_unchanged_sign_off_is_rejected` mutates a record, recomputes batches and proves rejection. The violation fixture also carries a stale content digest. |
| F2 — required target fields checked only in the mapping declaration, not per record; blank MRN could pass | High | `_dry_run` now reports `missing required field <name>` for every record lacking a non-blank value for each required target; tests cover missing and blank MRN. |
| F3 — `identity_fields` could omit MRN/issuer and hide issuer-scoped duplicates | Medium | Mapping now requires `mrn` and `mrnIssuer` in `identity_fields`; added `test_identity_fields_must_include_issuer_scoped_mrn`. |
| F4 — duplicate target values in `source_to_target` made the reverse map depend on JSON key order | Medium | Mapping now requires unique string target names; added duplicate-value and non-string-value tests. |
| F5 — no focused negative test for F1/F2 | Medium | Focused tests added for both (see above) in addition to the bundled violation fixture. |
| F6 — `True == 1` accepted as a batch sequence or sign-off version; `new_attestations_created: 0` reported even when provenance was non-conforming; two doc inconsistencies | Low | Added explicit integer/non-boolean guards; attestation claim is `null` unless `creates_new_attestation` passed; docs now state batch digests cover full records and report 68 tests. |

## Decisions

- D1–D9 are recorded in `docs/operations/s_g5_09/README.md`; in short: judge a manifest artifact rather than claim an import happened; accept only deterministic canonical-SHA-256 mapping; gate on all five dry-run findings and on per-record required values; require explicit ignored fields; enforce ordered, bounded, hash-reconciled batches; bind sign-off to a content digest as well as version and batch scope; preserve per-record historical signatures and never mint an attestation; use issuer-scoped MRN identity and unique mapping targets; leave V51 unused.
- Python trace has no branch metric; `test_onboarding.py` enumerates both outcomes of each conditional, including fail-closed paths (missing sections, malformed ids, non-string batch ids, empty/duplicate ids, non-integer sign-off scope). Coverage claim is **100% lines** with branch enumeration by tests, matching the sibling S-G5-05 evidence standard.
- No skill/agent instruction was edited: `.opencode` is outside a swarm worker's scope and protected from automatic merge.

## Evidence provenance

All results above are from executed local tools on synthetic fixtures. The Senior Python Tooling specialist was asked for a bounded read-only review of fail-closed correctness; its findings, if any, are recorded in the review section below. No human participant, patient data, credential or clinical evidence was used. A passing synthetic rehearsal is not proof of a real onboarding import or a real approval.
