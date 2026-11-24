# SPEC-ROLE-CLINICIAN — section-2 role matrix API test: Clinician

## Summary

Adds the API-level clause proof for the Clinician row of SMARTCLINIC_V1_SPEC.md
section 2. One new test class drives the real authorization decisions of the
note, order, prescription and release services, plus the result action policy,
with synthetic fixtures. One new four-heading status doc records what is proven
and what is not. No production file, existing test, migration, contract,
dependency or tracker is changed.

Entry-point-to-test trace: `RxController.attest` reached through MockMvc with
synthetic `X-Synthetic-Subject` / `X-Active-Organization` headers in
`clinicianMayAttestAPrescriptionWithAnEnabledEntitlement`,
`receptionCannotPrescribe`, `careRelationshipLimitsPrescribingToThePrescriber`
and `prescribingRequiresAnEnabledMarketEntitlement`; `ClinicalNoteService.sign`,
`OrderPlacementService.saveDraft/place/get` and `ReleaseService.authorize`
driven through their real domain entry points by the remaining tests.

## Affected requirements

IAM-01, CLN-05. Clause: "Role Clinician: allowed workflow: Document and sign,
order, prescribe where authorized, reconcile and release; scope constraint: Care
relationship, privileges and facility." (section 2, Clinician row.)

## Risk class

Routine (test-only, no production or data change). The underlying behaviours are
safety-relevant, so the negative cases assert status codes, stable codes, audit
outcomes and absence of writes, and synthetic data only is used.

## Review owner

smartclinic-senior-backend, smartclinic-senior-spring-security,
smartclinic-senior-java-build; read-only reviewers listed under Review verdicts.

## Data changes

None. No migration, backfill, RLS change, lock or volume impact, route,
Idempotency-Key, pagination field or OpenAPI entry.

## Authorization boundaries and audit events

No new boundary or audit event. The tests assert existing decisions: note sign
role/membership (`ClinicalNoteService.authorize`), ordering privilege
(`OrderPlacementService.requireOrderingPrivilege`), prescription role and
prescriber ownership (`RxService`), release decider set (`ReleaseService` and
`ReleaseActionPolicy`), and result reconciliation role set
(`ResultActionPolicy`). DENIED and SUCCESS audit rows are asserted where the
owning service emits them; the Rx ownership denial throws a plain ApiException
and is deliberately not asserted as an audit row.

## Migration and rollout considerations

None.

## Test evidence

- `mvn -f apps/api/pom.xml -Dtest=RoleClinicianTest test`
  final: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in com.aurevia.smartclinic.authz.clauses.RoleClinicianTest` (tool, exit 0). Re-run green after the final reviewer-driven assertion and style edits.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
  final: no findings, exit 0 (tool). Compiled before SpotBugs as required.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`
  final: exit 0; Surefire aggregate over `apps/api/target/surefire-reports/TEST-*.xml`: `tests=4618 failures=0 errors=0 skipped=0` (tool).
- `python3 -I scripts/check-docs.py` — final: exit 0 (tool).
- `python3 -I scripts/check-constraints.py` — final: exit 0 (tool). No new suppression.
- `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`,
  `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-migrations.py --require-baseline`,
  `python3 -I scripts/project-name.py displayName` (`Aurevia Care`),
  `python3 -I scripts/project-name.py repositorySlug` (`aurevia-care`),
  and `bash -n` over the shell scripts — each exit 0 (tool). These were run
  individually because worker-check.sh stops earlier at the known environment artifact below.
- `python3 -I -m unittest discover -s scripts/tests`
  final: `Ran 1432 tests ... FAILED (failures=6)` (tool, exit 1). All six
  failures are `test_check_pack.*` and carry the single message
  `opencode.json is stale: run scripts/gen-opencode.py`. This is the documented
  cloud-worker CI-profile artifact: `git ls-files -v opencode.json` reports `S`
  (skip-worktree) and the worktree file is the CI profile, while
  `git show HEAD:opencode.json` is the committed local profile. No protected
  file was regenerated. Running the other 31 tooling test modules directly gave
  `REMAINING: tests=1401 failures=0 errors=0` (tool, exit 0).
- `python3 -I scripts/check-pack.py` — final: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (tool, exit 1). Same artifact.
- `bash scripts/worker-check.sh` — final: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (tool, exit 1). It fails at its first policy step on that artifact; the remaining stages were run individually above.

No full `mvn verify`, no integration-test-only run and no `scripts/check.sh`
were used, per worker scope.

## Coverage

No production code changed, so no production line, branch, method or class was
added or modified; there is no production coverage to measure for this change.
The test class adds only tests. All 17 tests assert an outcome (value, status,
stable code, audit row or absence of a write); none is skipped, and the full
unit suite reports `skipped=0`. The worker gate does not run JaCoCo; the
integrator and Delivery own that gate.

ACTIVE gap-tripwire tests, each with its reason and the assertion that must be
flipped by the enabling task (these are absence proofs, not proofs of the clause):

- `reconcileIsCurrentlyDeniedToClinicianGap` — `ResultActionPolicy` currently
  excludes `clinician` from `RECONCILIATION_READ` and `RECONCILIATION_RESOLVE`.
  The test asserts those two `false` values and the two built permissions
  (`RESULT_READ`, `REVIEW_ACK`). The enabling task flips both to `true` and
  renames the method as the positive proof.
- `facilityScopeIsNotConsultedByClinicalOrderingGap` — the placement kernel
  stores the caller's `facilityId` verbatim and never consults an assigned
  facility or encounter care relationship, so an out-of-scope order still
  saves. The enabling task flips this to a 403 with no draft persisted.

The complete would-prove assertion shapes are recorded verbatim in
`docs/spec/clauses/authz/role-clinician.md` under `## Gap`, which is why no
`@Disabled` test is needed.

## Clinical and architecture review

No clinical decision, data flow or interface changed. The clause doc records
the three missing behaviours (reconciliation role grant; facility/care
relationship on clinical verbs; labflow release actor check) and their task
estimates. The stale facility-scope clause doc is flagged below, not edited.

## Review verdicts

- smartclinic-plan-reviewer: GO (after the @Disabled plan was replaced with ACTIVE gap tripwires; before coding)
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO
- smartclinic-senior-backend: GO
- smartclinic-senior-java-build: GO

Optional reviewer findings addressed before the final run: added
`verify(releases).insertNotification(...)` so the doc's "patient notification"
claim matches an assertion; used a distinct `RECEPTION` subject in
`receptionCannotAuthorizeARelease`; imported `ResultActions` instead of fully
qualifying it. `smartclinic-test-engineer` and `smartclinic-senior-spring-security`
returned NO-GO on the first draft and their Required findings were incorporated
(details under Findings).

## Findings

- **Story/gate conflict on `@Disabled`.** Story step 3 asks for unbuilt
  behaviour to be committed as `@Disabled("GAP: ...")`; `scripts/check-constraints.py:56`
  rejects every added Java `@Disabled` line and is run by both worker-check.sh
  and the integrator quality gate. I verified this once with a scratch probe
  file: `python3 -I scripts/check-constraints.py` exited 1 with
  `disabled test: @Disabled("GAP: probe")`; the probe was removed and the tree
  rechecked clean. To keep every gate green I used ACTIVE absence-pinning
  tripwires and carried the complete would-prove assertions in the clause doc
  (Markdown is exempt). This is a deliberate deviation from the story wording,
  not an added skip; no test is disabled anywhere in the change.
- **Stale record (not edited).** `docs/spec/clauses/authz/facility-scope-further-narrow-access.md`
  says "Not built" and describes its API test as `@Disabled`, but
  `FacilityScopeFurtherNarrowAccessTest` has five active `@Test` methods and
  asserts 403 for cross-facility worklist/intake. Facility narrowing is built
  for check-in/intake/emergency/messaging but not for the five clinician verbs.
  Correcting that doc is outside this task's scope.
- **Lab release caveat.** `labflow.release.LabReportReleaseService` has no actor
  role decision; its `CLINICIAN` constant is a notification recipient. The
  clinician release authority is proven at `ReleaseService`/`ReleaseActionPolicy`
  (XCH-06) instead. Recorded in the clause doc Gap, with one implementation task.
- **Care relationship is partial.** `RxService.requirePrescriber` enforces
  prescribing ownership; `ClinicalNoteService.requirePatient` only checks that
  the patient exists in the active tenant. There is no encounter-assignment or
  care-relationship check for note sign, order or release.
- **Synthetic-auth nuance.** `RxService.create` reads `prescriberSubject` from
  the JSON body; a MockMvc mock test cannot prove header binding for that one
  field. The real binding is the DB trigger in `V43__prescription_drafts.sql`
  (`NEW.prescriber_subject := app.actor`). This is cited as the compensating
  control rather than asserted through a mock that would prove nothing.
- **Reviewer rounds.** `smartclinic-plan-reviewer` first returned NO-GO on the
  `@Disabled` plan; after replacing both disabled cases with active tripwires it
  returned GO before any code was written. `smartclinic-test-engineer` and
  `smartclinic-senior-spring-security` returned NO-GO with concrete findings,
  all incorporated: real-path positive attest and release assertions, audit
  DENIED assertions, distinct role-gate versus ownership denials, `place` (not
  `saveDraft`) for the tenant-admin negative, clinician roles on the other-tenant
  call so 404 is reached, market setting present for the entitlement negative,
  and cross-references rather than duplicated existing coverage.
- **Environment artifact only.** The six `test_check_pack` failures and the
  check-pack/worker-check failures are the documented skip-worktree profile
  artifact, not a defect of this change; evidence is under Test evidence.

## Lessons for workers

- Survey the named domain packages and `git log` before coding: all five
  clinician verbs except reconciliation already had production decisions and
  tests on `main`; this task contributes only the missing clause-level proof.
- A new Java `@Disabled` fails `check-constraints.py` even with a `GAP:` reason.
  Pin today's absence with an ACTIVE, clearly named tripwire and record the
  complete would-prove assertions in the Markdown gap instead.
- The Rx attest path needs both `loadForUpdate` and the post-write `find` stub;
  without `find` the controller returns 500 via `NoSuchElementException`.
- For a tenant-concealment negative, pass the permitted role on the foreign-org
  call, otherwise the role gate returns 403 and the test proves the wrong branch.
- Assert the DENIED audit for role-gate denials but not for the plain-ApiException
  prescriber-ownership denial; the distinction documents which branch ran.

## task-requests

```task-requests
[
  {
    "title": "Grant clinicians the result reconciliation actions",
    "details": "ResultActionPolicy excludes the clinician role from RECONCILIATION_READ and RECONCILIATION_RESOLVE although the section-2 Clinician row grants reconcile. Decide the clinical safety boundary, grant the role if approved, add tests for the resolution route and OpenAPI entry, and flip reconcileIsCurrentlyDeniedToClinicianGap to a positive proof. See docs/spec/clauses/authz/role-clinician.md Gap 1.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/exchange/result", "apps/api/src/test/java/com/aurevia/smartclinic/exchange/result", "contracts/openapi.yaml"],
    "depends": []
  },
  {
    "title": "Enforce facility and care-relationship scope on clinical verbs",
    "details": "Facility narrowing is enforced only for check-in, intake, emergency access and messaging. Add a tenant-owned, forced-RLS association for facility assignment and encounter care relationship, propagate it through staff invitation acceptance, consult it in ClinicalNoteService, OrderPlacementService, RxService and the release path, update the action-policy contract and OpenAPI, and flip facilityScopeIsNotConsultedByClinicalOrderingGap. See docs/spec/clauses/authz/role-clinician.md Gap 2; needs persistence then enforcement then test enablement.",
    "scope": ["db/migrations", "apps/api/src/main/java/com/aurevia/smartclinic/authz", "apps/api/src/main/java/com/aurevia/smartclinic/clinical", "apps/api/src/main/java/com/aurevia/smartclinic/orderentry", "apps/api/src/main/java/com/aurevia/smartclinic/prescription", "contracts/openapi.yaml"],
    "depends": []
  },
  {
    "title": "Add an actor role decision to lab report release",
    "details": "LabReportReleaseService.release has no actor role check; the CLINICIAN constant is a notification recipient. Add the real authorization decision (reconciling it with ReleaseActionPolicy and the lab_verifier specialty duty), cover it with unit and HTTP tests, update the OpenAPI entry and record the clause evidence. See docs/spec/clauses/authz/role-clinician.md Gap 3.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/labflow/release", "apps/api/src/test/java/com/aurevia/smartclinic/labflow/release", "contracts/openapi.yaml"],
    "depends": []
  }
]
```
