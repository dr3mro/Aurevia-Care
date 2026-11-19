# S-G4-06 — Specialty role permissions and end-to-end flow evidence (PRD-06)

## Story, scope and requirements

- Story: **S-G4-06**, swarm worker branch `swarm/S-G4-06-99`, base SHA
  `40e1668447f4229641c44b99078e8ff6953aeefe` (orchestrator claim commit).
  This worker is retry attempt 3 (TODO.md carried `attempts: 2` at resume).
- Scope honoured: only
  `apps/api/src/main/java/com/aurevia/smartclinic/g4/s_g4_06` and
  `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g4_06` were available for
  edits; reserved migration **V46** (`db/migrations/V46__specialty_attestation.sql`)
  was inspected but not modified. No shared tracker (`TODO.md`, `MEMORY.md`,
  `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`) and no other migration
  version was touched. No push, tag or `gh` call was made. Synthetic fixtures only.
- Primary requirement: **PRD-06** — clinical users decide and attest to
  diagnosis, treatment and release; the platform makes no autonomous care
  decision. Supporting requirements exercised: **IAM-01, IAM-02** (active
  membership/role re-derived per protected request, deny by default),
  **ARC-01, ARC-02, ARC-03, ARC-04** (tenant-owned keys, forced RLS, version
  precondition, bounded errors) and **OPS-02** (transactional append-only audit).
  Specification references: §1 PRD-06, §2 role table, §7 ARC-01..04, §8 OPS-02.

## Acceptance mapping (requirement → implementation → test evidence)

| PRD-06 / supporting behaviour | Implementation (in scope) | Evidence (synthetic) |
|---|---|---|
| Only authorized clinical roles decide and attest; structural separation of duties (lab collector cannot verify/release, radiology staff cannot sign, pharmacy records evidence but does not diagnose) | `SpecialtyRoles` (10 stable keys), `SpecialtyActions.allows/allowedActions` action matrix, `SpecialtyActions.CLINICAL_ATTESTERS` | `SpecialtyPolicyTest.roleKeysAreDistinctAndSpecialtyRolesAreNamed`, `actionMatrixSeparatesEverySpecialtyDuty`, `attestationIsReservedForClinicalDecisionRoles`, `readPermissionsKeepClinicalContentOutOfAdministrativeRoles`, `allowedActionsIsTheStableUnionOfActiveRoles` |
| Decision class (DIAGNOSIS / TREATMENT / RELEASE) is derived from the human's action, never caller-supplied; no autonomous decision | `SpecialtyDecision` enum + `SpecialtyActions.ATTESTABLE` / `isAttestable` / `decisionFor`; `SpecialtyAttestationService.attest` derives the decision from the validated action | `SpecialtyPolicyTest.onlyClinicalDecisionActionsAreAttestableAndMapToPrd06Decisions`, `decisionWireAndParseRoundTrip`; `SpecialtyAuthorizationFlowTest.fullAttestationFlowIsHumanAuthorizedDerivableAndAudited` (asserts `RELEASE` for a lab verification) |
| Attestation is an immutable, attributable fact: human text + server actor + revision + resource reference; replacement is a new row | V46 append-only `specialty_attestation` ledger (actor/subject overwritten by insert trigger from `app.actor`), `SpecialtyAttestationRepository.insert/find/list`, `SpecialtyContract.Attestation` | `SpecialtyAuthorizationFlowTest.attestationLedgerIsAppendOnlyActorDerivedAndTenantScoped`, `fullAttestationFlow...` (asserts subject, actor id, revision, resource ref, text, server timestamp) |
| Authorization fails closed on the next protected request (IAM-01/02) and tenant context is scoped | `SpecialtyAttestationService.membershipDenial/readDenial` re-read inside the guarded transaction; V46 forced RLS; role spoofing checked against the active-role fold | `SpecialtyAuthorizationFlowTest.revokedMembershipFailsClosedOnTheNextRequest`, `separationOfDutiesAndRoleSpoofingAreDenied`, `crossTenantResourcesAndSubjectsAreConcealed` |
| Clinical content is not readable by administrative/clerical roles; sensitive actions are audited | `ATTESTATION_READ` limited to clinical readers in `SpecialtyActions`; `deny/success` append `AUDIT_READ`/`AUDIT_ATTEST` to the append-only chain | `SpecialtyAuthorizationFlowTest.administrativeAndClericalRolesCannotReadClinicalAttestations`, `fullAttestationFlow...` (SUCCESS count), `separationOfDuties...` (DENIED count) |
| Bounded API contract: 401/403/404/422/428, UUID validation, `If-Match` positive-version precondition, bounded list limit | `SpecialtyInputs` (uuid/action/role/text/newId), `SpecialtyController` (parse `If-Match`, bounded `limit`), `SpecialtyContract.PermissionView` defensive copies | `SpecialtyPolicyTest.uuidValidationIsBounded`, `actionAndRoleValidationAreBounded`, `boundedTextValidationRejectsBlankAndOversizedFields`, `newIdIsOpaque`, `recordValueSemanticsAndDefensiveCopiesAreStable`; `SpecialtyAuthorizationFlowTest.invalidInputsAndPreconditionsAreRejected` |

Entry-point-to-test trace (PRD-06 command):
`POST /api/v1/specialty/attestations` → `SpecialtyController.attest` (parses
`If-Match` via `IfMatch.parse`, 428/422 on bad precondition) →
`SpecialtyInputs` field validation (422 before SQL) →
`SpecialtyAttestationService.attest` → tenant-guarded `TransactionTemplate`
(`TenantGuardTransactionManager.doBegin` binds `app.current_org`/`app.actor`/
`app.purpose`/`app.correlation_id`) → active membership + active-role fold +
declared-role/action/ATTEST permission checks → `SpecialtyActions.decisionFor`
(derives PRD-06 decision) → patient visibility check (404 concealed) →
`SpecialtyAttestationRepository.insert` (forced RLS + V46 actor trigger) →
`AuditAppender.append` (append-only chain, same transaction) → 201.
`GET /api/v1/specialty/permissions`, `GET /attestations/{id}` and
`GET /attestations` follow the same guarded path and are exercised by
`SpecialtyAuthorizationFlowTest.permissionsReflectOnlyActiveSpecialtyRoles`,
`fullAttestationFlow...` and `administrativeAndClericalRolesCannotReadClinicalAttestations`.

## Exact commands run and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **EXIT 0**. Full fast tier: Surefire **1491 tests, 0 failures, 0 errors, 0 skipped**. `com.aurevia.smartclinic.it.s_g4_06.SpecialtyPolicyTest`: **12 tests, 0 failures**. `SpecialtyAuthorizationFlowTest` is `@Tag("integration")` and correctly excluded from the fast tier. |
| `mvn -q -B -f apps/api/pom.xml test-compile` | **EXIT 0**. All main and test sources, including the DB integration test, compile cleanly. |
| Focused `doxygen` on `.../g4/s_g4_06` (repo `docs/Doxyfile`, `WARN_NO_PARAMDOC=YES`, `WARN_AS_ERROR=FAIL_ON_WARNINGS`, doxygen 1.9.4) | **EXIT 0**, zero warnings/errors for the scope. (Installed doxygen via `apt-get update && apt-get install -y doxygen` in this worker.) |
| `python3 -I scripts/check-migrations.py --require-baseline` | **EXIT 0** — V46 filename/header and expand/contract discipline accepted. |
| `python3 -I scripts/check-docs.py` | **EXIT 0**. |
| `python3 -I scripts/check-secrets.py` | **EXIT 0**. |
| `mvn -q -B -f apps/api/pom.xml spotbugs:check -Dspotbugs.skip=false` | EXIT 1 with 14 Medium findings — **none in `s_g4_06`** (they are in `s_g0_07`, `s_g4_02`, `s_g4_04`, `s_g3_05`, `s_g3_08`, `s_g3_10`, other workers' scopes). |
| `python3 -I -m unittest discover -s scripts/tests -q` | EXIT 1, 6 failures / 4 errors — all out of scope or environmental: `check_pack.*` (documented cloud-worker `opencode.json` CI-profile/skip-worktree trap), `agent_run.*` (git-ref environment), `test_fast_tier`/`test_workflow_concurrency` (import errors), `test_java_conventions.*` for `PatientFlowContract.java` (other scope). None references `s_g4_06`. |
| `bash scripts/docs.sh` | EXIT 1 — Doxygen errors are all in `messaging/s_g3_09/ServiceProfileStore.java` and `messaging/s_g3_12/GateEvidenceContract.java` (other workers' scopes); the focused s_g4_06 build above is clean. |
| `python3 -I scripts/check-pack.py` | EXIT 1 — `opencode.json is stale`: the documented cloud-worker CI-profile/skip-worktree artifact, not a branch defect. |

## State found on resume / defect assessment

The branch already contained the complete S-G4-06 implementation: 8 production
files (`SpecialtyActions`, `SpecialtyRoles`, `SpecialtyDecision`,
`SpecialtyContract`, `SpecialtyInputs`, `SpecialtyAttestationRepository`,
`SpecialtyAttestationService`, `SpecialtyController`), 2 test files
(`SpecialtyPolicyTest` 12 fast tests, `SpecialtyAuthorizationFlowTest` 11
integration tests), and reserved migration V46. I performed a test-first
assessment (ran the fast tier and every locally runnable gate before considering
any edit) and a line-by-line review against PRD-06, the IAM-01/02 role table and
ARC-01..04/OPS-02.

**No in-scope defect was found.** Every locally runnable in-scope gate passes:
fast tests, test compilation, focused Doxygen, migration policy, doc links and
secret scanning; SpotBugs reports no s_g4_06 finding. The reported global
failures (docs.sh, SpotBugs, Python tooling, check-pack) are all in other
workers' scopes or documented cloud-worker environment artifacts and were left
untouched, per the scope rule.

I also evaluated whether the `clinician` role, listed as an attester in
`SpecialtyActions.CLINICAL_ATTESTERS`, lacks an attestable action in this slice
(all attestable actions map to lab-verifier/radiology-interpreter/PT-clinician).
I made **no change** for this: the point of this slice is the *specialty* role
matrix and the clinician is used here as an authorized clinical reader; general
clinical documentation/signing actions belong to other modules (e.g. S-G1-05).
Adding a new wire action here would be speculative and out of scope. Recorded as
a decision below, not as a defect.

## Evidence provenance and gaps

- All results above are from executed tool commands on synthetic fixtures. No
  real patient data, credentials or raw logs are recorded. No human participant
  or authorized clinical feedback was used; there was no simulated reviewer
  report for this attempt either.
- **Evidence gap:** no integrator failure report or "requested change" text was
  supplied with this retry, and this worker has **no local PostgreSQL**
  (`psql`/`pg_isready` absent, `SMARTCLINIC_PG_ADMIN` unset). Per the worker
  contract the real-PostgreSQL `SpecialtyAuthorizationFlowTest`, `db/check.sh`
  and the JaCoCo 100%-coverage gate are run by the integrator on the merge; I
  did not fabricate those results. The exact DB integration assertions and
  coverage threshold remain integrator evidence.
- **Decision / stop notice:** with no in-scope defect to fix and no reproducible
  in-scope failure, I made no production or test source change. This branch's
  contribution is the corrected PR description (the file previously held stale
  notes from unrelated stories S-G3-06, S-G0-08 and S-G1-07). If the integrator
  still observes a non-zero gate on the merge, the failing step, exit code and
  log tail must be supplied; if it is in `s_g4_06`, a follow-up worker can act
  on it with the exact evidence.
