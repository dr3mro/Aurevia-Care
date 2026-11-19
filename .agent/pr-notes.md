# S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering

## Requirement IDs and acceptance mapping

Primary: **CLN-07**. Supporting: **ARC-01, ARC-02, ARC-03, OPS-02, IAM-01, UX-01** (locale/direction).
Specification: `SMARTCLINIC_V1_SPEC.md` §3 CLN-07, §7 ARC-01..03, §8 OPS-02.

| Acceptance behaviour (CLN-07) | Implementation | Test evidence (synthetic only) |
|---|---|---|
| Link original PDF/JPEG/PNG to chart, order or result; retain original bytes + checksum | `DocumentService.link`, `DocumentRepository.insert`; V17 `document_association` + `set_document_original_digest` trigger (sha256 derived from stored bytes, never caller-supplied) | `DocumentServiceTest`, `DocumentInputsTest`, `DocumentValuesTest`, `DocumentIsolationTest` (integrator) |
| Rotation is presentation metadata only; voiding preserves history with a reason | `rotation_degrees` column pinned by `guard_document_association_update` trigger; `DocumentService.voidAssociation` + append-only `document_association_event` | `DocumentServiceTest`, `DocumentPolicyTest`, `DocumentIsolationTest` (integrator) |
| Immutable, versioned PDFs; stale version / illegal transition rejected | `rendered_document` UNIQUE(org,type,id,locale,version), append-only triggers, `next_render_version` advisory-lock fold; `If-Match` on void (412) | `DocumentServiceTest`, `DocumentControllerTest`, `PdfRendererTest`, `DocumentIsolationTest` (integrator) |
| English/Arabic, RTL direction, page numbers, provenance, print profile | `PdfRenderer` direction-aware layout, embedded font placeholder, `/PrintScaling /None`, provenance keys, `Page 1 / 1`; locale->direction checked by V17 CHECK constraint | `PdfRendererTest`, `DocumentValuesTest`, `DocumentIsolationTest` (integrator) |
| Tenant isolation, fail-closed authorization, audit | Forced RLS on all three V17 tables; role matrix in `DocumentPolicy`; `DocumentService.authorize` re-derives membership/roles per transaction and audits denials | `DocumentPolicyTest`, `DocumentIsolationTest` (integrator) |

Entry-point-to-test trace: `POST /api/v1/documents/associations` → `DocumentController.link` → `DocumentService.link` → guarded `TransactionTemplate` (tenant + membership + action) → `DocumentRepository.insert` (forced RLS + DB-derived digest) → `appendEvent` + `appendAudit` → 201. `POST /api/v1/documents/rendered` → `DocumentController.render` → `DocumentService.render` → `PdfRenderer.render` → `DocumentRepository.insertRendered` (immutable, versioned, RTL/LTR) → 201. Verified by `DocumentControllerTest` (unit) and `DocumentIsolationTest` (integration, two synthetic tenants).

## Commands run and results (evidence source: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. Surefire: **244 tests, 0 failures, 0 errors, 0 skipped**; 54 are in `com.aurevia.smartclinic.it.s_g1_07` (DocumentControllerTest 5, DocumentInputsTest 11, DocumentPolicyTest 4, DocumentServiceTest 20, DocumentValuesTest 5, PdfRendererTest 9). This is the exact Java command in `scripts/quick-check.sh`.
- `bash scripts/frontend-check.sh` → **EXIT 0** (`frontend standards: policy and tool commands passed`). Includes staff-web `npm run typecheck`, `lint`, `npm test` (vitest) and patient-mobile plus root toolchain checks.
- `python3 -I scripts/project-name.py displayName|repositorySlug` → `Aurevia Care` / `aurevia-care`, EXIT 0.
- `python3 -I scripts/check-docs.py` → EXIT 0. `python3 -I scripts/check-secrets.py` → EXIT 0. `bash -n` on all shell scripts → EXIT 0.

I did **not** run the database/integration gate (`db/check.sh`, `mvn verify`, `DocumentIsolationTest`) — per the worker contract those belong to the integrator. Reserved version **V17** is present as `db/migrations/V17__document_associations.sql`; I did not touch any other migration version.

## Defects found

No in-scope defect was found. The S-G1-07 implementation already committed on this branch is complete and its in-scope fast unit tests pass. No in-scope source or test change was necessary; this worker's scoped contribution is verification evidence and this PR description.

## Reported merge-gate failures — triaged, all out of scope / environmental

The untrusted "Requested change" text reported a fast-merge-gate failure whose visible stack trace is a jsdom `reportException` at `apps/staff-web/tests/theme/ThemeProvider.test.tsx:6`. Findings:

1. **That trace is an intentional negative test, not a failure.** `ThemeProvider.test.tsx:31-35` renders `useTheme()` outside its provider and asserts `toThrow(/useTheme/)` (`ThemeProvider.tsx:36-40` throws exactly that message), with `console.error` spied. Locally `bash scripts/frontend-check.sh` runs the full staff-web vitest suite and exits 0. The path is in `apps/staff-web`, outside my declared scope (`apps/api/.../g1/s_g1_07` and `apps/api/.../it/s_g1_07`). I did **not** modify it.
2. **`scripts/quick-check.sh` fails at `check-pack.py` — "opencode.json is stale".** Verified as the documented cloud-worker CI-profile trap: worktree `opencode.json` SHA `279aa1e7…` equals `.github/opencode.ci.json`, carries the skip-worktree flag (`git ls-files -v` → `S`), while `git show HEAD:opencode.json` SHA is `14bba0da…`. `check-pack.py` judges the worktree (CI) profile, not the committed local profile — the known false positive recorded as OPS-CW-003-01. I did **not** regenerate or overwrite `opencode.json` (root-level, out of scope, and it would replace the CI profile with the local one).
3. **`python3 -I scripts/check-migrations.py --require-baseline` fails — "version gap: expected V9, found V17".** This shard contains V1–V8 plus reserved V17; V9–V16 belong to other parallel workers' stories and are absent here. On the integrator's merge with `main` those earlier versions are present, so the contiguity gate applies to the merged tree. I did **not** fabricate V9–V16.
4. **Python tooling tests report 7 failures / 4 errors**, all explained by the above: four `test_check_pack.*` failures + `test_repository_migrations_are_valid` (items 2 and 3); `test_fast_tier`, `test_workflow_concurrency` and two `test_swarm.PlanTests` errors are `ModuleNotFoundError: No module named 'yaml'` (pyyaml absent in this worker image); three `test_agent_run.*` failures are environment-dependent. None is in the S-G1-07 scope or caused by this story.

Because the reported failure is outside my scope and not reproducible as a failure locally, and because resolving items 2–4 requires files/dependencies outside my scope, I stopped at verified in-scope evidence rather than making out-of-scope edits.

## Decisions / notes for the integrator

- No production or test source was changed by this worker; in-scope code was already complete and green.
- Confirm on the **merge result**: V9–V16 present (migration contiguity), the correct `opencode.json` profile applies for `check-pack.py`, and pyyaml is installed for the workflow tests. These are merge/environment conditions, not S-G1-07 changes.
- Coverage (JaCoCo 100% line/branch/method/class) and the real-PostgreSQL `DocumentIsolationTest` are enforced by `mvn verify` / `db/check.sh`, which the integrator runs; I did not fabricate a coverage result.
- Synthetic fixtures only. No patient data or credentials were used or recorded. No human or clinical evidence; all evidence above is tool-generated.
- Commit: no in-scope production/test source change was needed, so this branch's only new commit force-adds this ignored `.agent/pr-notes.md` file (the publish job reads the same file from the worktree artifact). The S-G1-07 implementation itself was already committed at HEAD `a9fdb91`.
