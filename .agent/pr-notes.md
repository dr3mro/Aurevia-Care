# S-G5-08 — Tenant export manifest, retention, legal hold, deletion and offboarding (OPS-07)

## Scope and requirement IDs

- Primary: **OPS-07** — tenant export with manifest, schema version, checksums,
  authorized documents, provenance and audit history; retention jobs respect
  legal holds, track deletion of active data and approved backup expiration;
  test representative record requests, exports, offboarding and disputed access.
- Supporting: OPS-02 (audit), ARC-02 (tenant isolation), ARC-06 (documents),
  PAT-02 (patient downloads), IAM-05 (linkage dispute), ARC-05 (durable events).
- Scope honored: only `infra/s_g5_08/`, `docs/operations/s_g5_08/` and this
  PR-notes file. No shared tracker edited. V50 reserved but **unused** (no
  migration: this is an infra/docs evidence gate).

## What the story delivers

A fail-closed OPS-07 **evidence-artifact evaluator**
(`infra/s_g5_08/tenant_lifecycle_evidence.py`) with three synthetic fixtures,
94 unit tests, a canonical `check.sh` gate and operator documentation. The
evaluator judges the *shape and internal consistency* of a claimed lifecycle
artifact; it never claims to prove a real export, deletion, restore or human
review happened. Real acceptance remains a named G5 human gate.

## Acceptance mapping (clause → check → test)

| OPS-07 clause | Named check | Executable evidence |
|---|---|---|
| manifest, schema version | `export.manifest.*`, `schema_version`, `export.record_count` | `ExportManifestTest`, `ExportRecordsTest` |
| checksums | `export.record_checksums`, `export.documents` | `test_record_checksum_must_be_lowercase_sha256_hex` |
| authorized documents | `export.document_authorizations` | `test_document_authorization_must_be_policy_allowed` |
| provenance | `export.record_provenance` | `test_record_provenance_must_be_complete` |
| audit history | `export.audit_history` (strictly increasing `seq`) | `AuditHistoryOrderTest` |
| retention respects holds | `retention.legal_hold_respected`, `.backup_hold_respected` | `RetentionTest`, `ScopeMatchingTest` |
| tracked deletion of active data | `retention.deletion_entries`, `.deletion_approval` | `test_active_data_deletion_requires_reason_and_approval` |
| approved backup expiration | `retention.backup_expiration_entries` | `test_backup_expirations_must_be_a_list_of_approved_entries` |
| representative scenarios (declaration completeness) | `scenarios`, `scenarios.no_duplicates` (all four names declared once); the four scenarios are exercised synthetically by `export.*`, `offboarding.*`, `disputed_access.*` | `ScenariosTest` plus `ExportRecordsTest`, `OffboardingTest`, `DisputedAccessTest` |
| tenant offboarding | `offboarding.*` | `OffboardingTest`, `OffboardingOrderTest` |
| disputed access (IAM-05) | `disputed_access.*` | `DisputedAccessTest` |
| no PHI-shaped fixture strings; honest evidence class | `phi.free`, `evidence.source`, `evidence_conflict`, `kind` | `PhiTest`, `StructureTest`, `ConformingArtifactTest` |
| negative control fails shut | gate exit 1 + eight named breaches | `test_check_gate.py` (4 tests) |

Entry point → test trace:
`infra/s_g5_08/check.sh` → `python3 -I -m unittest discover -s tests -q` →
`test_tenant_lifecycle_evidence.py` → `evaluate()` → named checks → verdict;
then the three CLI fixture runs, with the violation fixture pinned to exit 1.
Full trace is in `docs/operations/s_g5_08/README.md`.

## Test-first record (new work this iteration)

The baseline already contained a working evaluator (78 tests). I strengthened
five fail-open paths; each fix was preceded by a red test:

1. **Negative control accepted any non-zero exit** — new `test_check_gate.py`
   failed 3/4 (`set +e`/`status=$?`/exact `-ne 1`, report capture, breach list);
   gate hardened, now 4/4 green.
2. **Audit ordering not checked** — `test_audit_seq_must_be_strictly_increasing`
   failed for `[2,1]`, `[1,1]`, `[3,1,2]`; `_valid_audit` now enforces strictly
   increasing `seq`.
3. **Backup-set / broad-scope hold bypass** — three tests failed (backup-set
   hold, broad backup scope, ancestor deletion); `_covered` is now
   bidirectional and checks `backup_set` as well as `scope`.
4. **Inactive hold could be flipped off without authority** —
   `test_inactive_hold_without_a_recorded_lift_is_rejected` failed; inactive
   holds now require `lifted_by`, `lifted_at`, `lift_reason`, and an unlifted
   inactive hold still covers its scope.
5. **PHN/SSN boundary, whitespace and missing source** — four tests failed
   (embedded SSN, padded scope, blank source variants); regex now uses
   `(?<!\d)…(?!\d)`, identifiers are canonical, matching strips whitespace,
   and `evidence.source` is required.

Red command: `python3 -I -m unittest discover -s infra/s_g5_08/tests -q`
→ `FAILED (failures=13)` before production edits (plus 3 gate-guard failures).
Green: same command → `Ran 92 tests … OK`.

### Resume iteration (branch `swarm/S-G5-08-114`)

Two further bounded simulated reviews and one red-green fix were added on this
branch (base `4f9bbeb`), which already carried the verified baseline:

1. **Duplicate document ids (red → green).** `smartclinic-senior-python` found
   that a record could list the same `documents[].id` twice with different
   checksums and still pass `export.documents`, leaving the manifest ambiguous.
   New `ExportIntegrityTest.test_duplicate_document_ids_are_rejected` failed
   (`'export.documents' not in failures()`); `_valid_documents` now rejects
   duplicate ids after structural validation (so an unhashable id still cannot
   reach `set`). `test_distinct_document_ids_are_accepted` locks the positive
   path. 92 → 94 tests; 100% line coverage re-verified at 338 executable lines.
2. **Audit docstring mismatch.** Same reviewer noted `_valid_audit`'s docstring
   claimed "without gaps" while the code checks only strictly increasing `seq`.
   Corrected the docstring to state ordering, not contiguity, is enforced.
3. **Honesty of the acceptance matrix.** `smartclinic-qa-reviewer` found that
   mapping "representative scenarios tested" to the `scenarios` name-list
   overstated the evidence. The PR-notes and README matrices now say
   "declared" and name the check families that actually exercise each scenario;
   explicit limitations now state that scenario execution, live policy-pack
   binding and real measured runs are human G5 gates, and that the checked-in
   `measured_operation` fixture is synthetic.

Red command this iteration:
`python3 -I -m unittest discover -s infra/s_g5_08/tests -p test_tenant_lifecycle_evidence.py -q`
→ `FAILED (failures=1)` before the `_valid_documents` edit; green afterwards.

## Exact commands and results (tool evidence)

| Command | Result |
|---|---|
| `bash infra/s_g5_08/check.sh` | `Ran 94 tests … OK`; `S-G5-08: OPS-07 evaluator unit tests and fixture checks passed`; exit 0 |
| `python3 -I -m unittest discover -s infra/s_g5_08/tests -q` | 94 tests, OK, exit 0 |
| `python3 -I -m unittest discover -s infra/s_g5_08/tests -p test_check_gate.py -q` | 4 tests, OK |
| `python3 -m trace --count --coverdir /tmp/opencode/g5_08_final infra/s_g5_08/tests/run_coverage.py` | 0 `>>>>>>` lines; 334 executable evaluator lines → 100% line coverage |
| `python3 -I infra/s_g5_08/tenant_lifecycle_evidence.py …/violation_lifecycle_evidence.json` | `FAIL`, class `measured-operation (final: False)`, exit 1; eight named breaches confirmed |
| `python3 -I infra/s_g5_08/tenant_lifecycle_evidence.py …/measured_lifecycle_evidence.json` | `PASS`, `final: True`, exit 0 |
| `python3 -I scripts/check-docs.py` | exit 0 (links and requirement IDs valid) |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -m py_compile …` (evaluator, runner, tests) | exit 0 |

Coverage note: `coverage.py` is unavailable; stdlib `trace` is the closest
supported line measure. Branch coverage has no mechanical measure here; the
94 tests enumerate each decision branch, including malformed/unhashable input.

## Defects found and fixed

- `check.sh` negative control silently passed on exit 2 (unreadable fixture)
  and recorded no breach evidence. Fixed with exact exit code + named breaches
  (S-G5-05 precedent).
- Audit docstring promised "ordered" history but ordering was never validated.
- Legal holds were matched one-directionally and only against `scope`; a
  narrow hold could not block an org-wide backup expiry, and `backup_set` was
  validated but unused.
- An inactive hold needed no lift authority and stopped covering immediately.
- SSN detection missed a digit-adjacent embedded shape; identifiers with
  surrounding whitespace could evade match; `source` was optional.

## Reviewer findings and dispositions (simulated reviewers, not human evidence)

- `smartclinic-senior-shell`: one material finding (negative control) — fixed.
- `smartclinic-senior-python`: F1 audit ordering, F3 backup-set hold, F4 SSN —
  all fixed. F2 (self-declared evidence class) — **declined as a code change**:
  every fixture must be synthetic by task directive, and sibling G5 evaluators
  use the same "claimed measured" envelope convention. Addressed by documenting
  explicitly that `final_evidence` is a claim, never independent verification.
- `smartclinic-devsecops`: F-1 (bidirectional scope), F-2 (lift authority),
  F-3 (whitespace), F-5/F-7 (SSN/audit), F-8 (scenarios "exactly") — fixed or
  documented. F-4 (honesty) resolved as above with `evidence.source` plus
  corrected documentation. F-6 (per-record tenant field) — **declined**:
  added documentation states this is an envelope check only; cross-tenant record
  isolation belongs to the database/API gates. PHI scope is documented as
  email/SSN shapes only rather than overstating the check.

- Resume iteration `smartclinic-senior-python`: duplicate document ids could
  pass `export.documents` (fixed red-green); audit docstring over-claimed gap
  detection (corrected). No high-severity fail-open found.
- Resume iteration `smartclinic-qa-reviewer`: acceptance matrices overstated
  scenario execution and policy binding (relabelled and limited in docs); the
  synthetic `measured_operation` fixture was clarified so it cannot be read as
  operational acceptance. All other OPS-07 clauses and negative paths were
  found fully covered.

## Evidence provenance

- Tool/executed commands: all gates above, run locally on branch
  `swarm/S-G5-08-114` at base `4f9bbeb` (the verified baseline from
  `swarm/S-G5-08-112`, base `ddbd816`, was already present on `main`);
  synthetic fixtures only.
- Reviewers: five bounded simulated specialist reviews across both iterations
  (shell, Python, DevSecOps, Python again, QA); their findings were independently
  re-verified before acting. No human or clinical participant.
- Simulated users / human participants: none. Real clinical, privacy and site
  acceptance remain G5 human gates and are explicitly **not** claimed here.

## Environment notes / missing evidence

- No decision or evidence is missing for this story's synthetic scope.
- `shellcheck` is not installed in this worker (WORKSPACE.md lists it); the
  gate change follows the shellcheck-clean S-G5-05 pattern and uses only POSIX
  constructs. Repo-wide `python3 -I -m unittest discover -s scripts/tests -q`
  has 8 pre-existing failures and 4 import errors on the untouched base
  (missing `yaml` in two modules plus in-flight parallel-worker changes); none
  touch this scope. The integrator runs the database/integration gates.
