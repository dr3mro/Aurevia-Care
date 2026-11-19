# S-G5-08 — Tenant export manifest, retention, legal hold, deletion and offboarding

## Story and scope

- Story: **S-G5-08** (swarm worker `swarm-9`), branch `swarm/S-G5-08-99`, base SHA `40e1668`.
- Primary requirement: **OPS-07** — tenant export with manifest, schema version,
  checksums, authorized documents, provenance and audit history; retention jobs
  that respect legal holds and track deletion of active data and approved backup
  expiration; representative record-request, export, offboarding and
  disputed-access tests.
- Supporting IDs: OPS-02 (audit), ARC-02 (tenant isolation), ARC-06 (documents),
  PAT-02 (patient downloads), IAM-05 (linkage dispute), ARC-05 (durable events).
- Files changed (only the declared scope):
  - `infra/s_g5_08/tenant_lifecycle_evidence.py` (new)
  - `infra/s_g5_08/check.sh`, `infra/s_g5_08/tests/test_tenant_lifecycle_evidence.py`,
    `infra/s_g5_08/tests/run_coverage.py`
  - `infra/s_g5_08/fixtures/{synthetic,measured,violation}_lifecycle_evidence.json`
  - `docs/operations/s_g5_08/{README,export-manifest,retention-and-offboarding}.md`
  - `.agent/pr-notes.md` (this file)
- No tracker, workflow, script, `db/` file or other module was edited. Reserved
  migration **V50 was not used** — the scope is `infra/` + `docs/` only.
- No push, tag, `gh` call or tracker edit was made.

## What was built

A self-contained, fail-closed Python evaluator of a **synthetic OPS-07 evidence
artifact**, following the established `infra/s_g5_05/capacity_evidence.py`
pattern. It judges an artifact (it does not operate a live system) and emits a
named-check report with `verdict`, `classification`, `final_evidence` and a
`failed` list. Named checks cover: manifest envelope and record-count
consistency; sha256 checksums; policy-allowed document authorizations;
provenance; audit history; unique record IDs; legal-hold-ledger integrity,
active-hold protection for both active-data deletion and backup expiration
(exact, hierarchical `/` and `*` wildcard scopes); approved deletion/backup
tracking; authorized and correctly ordered offboarding; suspension and
re-verification for disputed access; all four required scenarios; and a
PHI-shaped-string scan.

Honesty: `synthetic_rehearsal` is never final evidence; a `measured_operation`
artifact naming a synthetic source fails `evidence_conflict`.

## Acceptance mapping (requirement → evidence)

| Clause | Check(s) | Test evidence |
|---|---|---|
| manifest + schema version | `schema_version`, `export.manifest.*` | `ExportManifestTest`, `StructureTest` |
| checksums | `export.record_checksums`, `export.documents` | `ExportRecordsTest` |
| authorized documents | `export.document_authorizations`, document `policy_version` | `ExportRecordsTest` |
| provenance | `export.record_provenance` | `ExportRecordsTest` |
| audit history | `export.audit_history` | `ExportRecordsTest` |
| retention respects legal holds | `retention.legal_hold_respected`, `.backup_hold_respected` | `RetentionTest`, `ScopeMatchingTest` |
| tracked deletion of active data | `retention.deletion_entries`, `.deletion_approval` | `RetentionTest` |
| approved backup expiration | `retention.backup_expiration_entries` | `RetentionTest` |
| representative scenarios | `scenarios`, `scenarios.no_duplicates` | `ScenariosTest`, `RobustnessTest` |
| tenant offboarding | `offboarding.*` (authorization, scope, ordered steps, audit) | `OffboardingTest`, `OffboardingOrderTest` |
| disputed access (IAM-05 support) | `disputed_access.*` | `DisputedAccessTest` |
| policy-bounded, no PHI | `evidence_conflict`, `kind`, `phi.free` | `StructureTest`, `PhiTest` |
| CLI/exit semantics | exit 0/1/2, reports | `ReportAndCliTest`, `CheckedInFixtureTest` |

Entry-point-to-test trace:
`bash infra/s_g5_08/check.sh` →
`python3 -I -m unittest discover -s infra/s_g5_08/tests -q` →
`test_tenant_lifecycle_evidence.py` → `evaluate()` → named checks → verdict; plus
`python3 -I infra/s_g5_08/tenant_lifecycle_evidence.py <fixture.json>` for the
three checked-in synthetic fixtures (two must pass, the violation fixture must
fail). Coverage runner: `tests/run_coverage.py` runs the suite and the CLI
entry point in one stdlib `trace` session.

## Test-first evidence (red → green)

- RED: first run of the new unit suite with no implementation produced
  `FileNotFoundError: .../infra/s_g5_08/tenant_lifecycle_evidence.py`,
  `Ran 1 test ... FAILED (errors=1)`, exit 1 — the behavior was missing.
- GREEN after implementation: `Ran 78 tests ... OK`, exit 0; `bash check.sh`
  exit 0 with `S-G5-08: OPS-07 evaluator unit tests and fixture checks passed`.

## Exact commands run and results (all evidence from executed tools)

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_08/tests -q` | 78 tests, OK, exit 0 |
| `bash infra/s_g5_08/check.sh` | exit 0; conforming + measured fixtures pass, violation fixture fails as required |
| `python3 -I -m trace --count --missing --coverdir <tmp> infra/s_g5_08/tests/run_coverage.py` | production module **313/313 lines = 100.0%**, no missing lines in the evaluator; only `unittest.main()` in the test file is unhit (test tooling) |
| `python3 -I scripts/check-docs.py` | exit 0 (links and requirement IDs valid) |
| `python3 -I scripts/check-secrets.py` | exit 0 (no credential/PHI-shaped values) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (no migration added) |
| `bash -n infra/s_g5_08/check.sh` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` | 163 tests, 3 failures / 4 errors — identical to clean `HEAD` (verified in a detached worktree), plus 3 extra `check_pack` failures caused solely by the CI `opencode.json` copied under `skip-worktree`; **no failure attributable to this change** |
| `python3 -I scripts/gen-opencode.py --check` | exit 1, documented cloud-worker artifact: `git ls-files -v opencode.json` = `S` (skip-worktree), `git show HEAD:opencode.json` equals `gen-opencode.py`'s local render, worktree file is the CI profile. Per MEMORY/OPS-CW-003-01 this is not HEAD truth; file left untouched |
| `bash scripts/quick-check.sh` | not used as the verdict: it wraps the known-failing `check_pack`/tooling checks above; its deterministic components were run individually instead |

Shellcheck is not installed locally (`shellcheck unavailable`); the module gate
passes `bash -n`. Java/DB/integration checks are the integrator's responsibility;
this slice has no Java or migration changes.

## Defects found and fixed during this story

1. Malformed or missing retention ledgers were vacuously "no events" because
   `all([])` is true. Fixed by making the ledger-structure a precondition of
   `retention.legal_hold_respected`, `.backup_hold_respected`, `.deletion_approval`
   and the entry checks. Regression tests: `test_malformed_ledgers_fail_the_cross_checks`
   and the `*_entries` tests with `None`/string ledgers.
2. An unhashable `kind` could raise in the classification lookup; an unhashable
   record id could raise in the uniqueness set. Both now fail closed without
   raising (`test_unhashable_kind_fails_closed_without_raising`,
   `test_unhashable_record_id_fails_closed_without_raising`).
3. Non-dict records could pass `export.record_ids_unique` vacuously. Fixed by
   requiring one id per record (`test_non_dict_record_fails_every_record_check`).
4. `main()` did not catch `UnicodeDecodeError`; `_check_phi` could raise on a
   non-serializable artifact. Both now fail closed (`RobustnessTest`).
5. Stale `.agent/pr-notes.md` from a different story (S-G3-06) was replaced.

## Decisions

- Scope: a fail-closed **artifact evaluator** plus synthetic fixtures and docs,
  mirroring S-G5-05. It deliberately does not verify live object storage,
  policy signatures or real event logs; those are G5 human-gated items. This is
  documented in the docs' "Limitations — human-gated" sections.
- Legal-hold scope semantics: exact id, hierarchical `parent/child` and `*`
  wildcard; a hold blocks both active-data deletion and backup expiration.
- Offboarding steps must appear in the governed order; extra steps are allowed.
- Disputed access requires recorded suspension and re-review, and restoration
  additionally requires `identity_reverified is true`.
- Reserved V50 unused (no database scope).
- Specialist input was advisory and read-only: `smartclinic-senior-python`
  (check/branch inventory and false-pass edge cases) and
  `smartclinic-physician-reviewer` (legal-hold, disputed-access and honesty
  hazards). Their applicable findings are implemented above; deeper
  provenance fields (issuing organization, effective date, correction context)
  and content-level checksum verification are recorded as documented
  limitations, not silently claimed as covered.

## Evidence provenance

- Tool evidence: all commands in the table above were executed locally on
  synthetic fixtures; no raw logs, PHI or credentials are stored here.
- Reviewer evidence: two simulated specialist reports (senior Python and
  clinical safety); the primary agent verified and implemented the applicable
  findings with executed tests.
- No simulated-user, human-participant or authorized clinical feedback was
  used; no real-patient claim is made.

## Open items / missing evidence

- No blocking decision or evidence is missing for this **synthetic evaluator**
  slice. Real measured operation evidence, site legal-hold authority, release
  policy approval, dispute re-review authority and offboarding sign-off are
  separate G5 human gates explicitly listed in the docs.
- Follow-up (not in scope): `scripts/check.sh` does not auto-discover
  `infra/*/check.sh` (same as S-G5-05), so the integrator should run
  `bash infra/s_g5_08/check.sh` or rely on this recorded evidence; wiring it
  into the root gate would require editing `scripts/check.sh`, which is outside
  this story's scope.
