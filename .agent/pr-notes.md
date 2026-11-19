# S-G4-02 — Therapy episode, evaluation, goals, interventions, outcomes, signed progress and discharge (PT-01)

## Story and requirements

- Story: S-G4-02, branch `swarm/S-G4-02-99`, base SHA `40e1668`. Resumption of a prior attempt (TODO `attempts: 2`); the domain implementation was already present in the base commit. This pass verifies it, closes two integrator-blocking defects, and records evidence.
- Primary requirement: **PT-01** (therapy episode: evaluation, problems, baseline measurements, goals, planned frequency/duration, interventions, outcomes, versioned scales with units, signed session/progress/discharge notes, approved summary to referrer, return acknowledgment).
- Gate evidence: **G4** — "PT episode produces signed discharge and referral closure".
- Supporting requirements exercised: IAM-01 (fresh actor snapshot, server-side authorization on every command), ARC-01 (tenant-owned opaque IDs, versions, provenance), ARC-02 (tenant-scoped store), ARC-03 (version precondition → 412, machine-readable errors), OPS-02 (append-only audit facts with actor, action, resource, outcome).
- Scope honored: only `apps/api/src/main/java/com/aurevia/smartclinic/g4/s_g4_02` and `apps/api/src/test/java/com/aurevia/smartclinic/it/s_g4_02`. No shared tracker, workflow, script, migration or out-of-scope source was edited. Reserved migration **V42** was not needed: this slice is a tenant-scoped domain service over an in-memory store (no schema change); `scripts/check-migrations.py` permits version gaps.

## Acceptance mapping (requirement → code → evidence)

| PT-01 behavior | Production entry point | Test evidence |
|---|---|---|
| Accept referral into an episode | `TherapyService.acceptReferral` → tenant-scoped `Episode` | `TherapyEpisodeFlowTest.acceptsReferralIntoAnActiveOwnedEpisode` |
| Evaluation: problems, baselines with units, goals, frequency/duration | `TherapyService.recordEvaluation` → `Evaluation` | `TherapyEpisodeFlowTest.recordsEvaluationWithProblemsBaselinesGoalsAndPlan`; `TherapyCoverageTest.validationGuardsRejectNullAndBlankMembers` |
| Interventions | `TherapyService.recordIntervention` | `TherapyEpisodeFlowTest.recordsInterventionAndSignsSessionAndProgressNotes` |
| Versioned outcome scales, stored units, approved readings | `defineOutcomeScale` / `approveOutcomeScale` / `recordOutcome` | `OutcomeScaleTest.readingRequiresAnApprovedScaleAndMatchingUnitAndPermittedValue`; `OutcomeScaleTest.readingsBindTheExactScaleVersionAndUnitForTrending` |
| Progress notes (signed) | `recordNote(PROGRESS)` / `signNote` | `TherapyEpisodeFlowTest.recordsInterventionAndSignsSessionAndProgressNotes` |
| Discharge closes the episode | `signNote(DISCHARGE)` → `DISCHARGED` | `TherapyEpisodeFlowTest.signsDischargeTransitionsEpisodeAndSendsApprovedSummaryWithReturnAcknowledgment`; `TherapySafetyTest.dischargeRequiresSignedEvaluationAndASignedSession`; `TherapyCoverageTest.closedEpisodeCannotSignALeftoverDraft` |
| Signer attestation, checksum, signed-note immutability | `TherapyService.signNote` | `TherapySafetyTest.signedNotesAreImmutableAndCannotBeSignedTwice` |
| Approved summary to verified referrer | `TherapyService.sendSummary` | `TherapyCoverageTest.summaryRejectsAnUnverifiedRecipient`; `TherapyCoverageTest.replacementSummaryClearsPriorAcknowledgment` |
| Revision-bound return acknowledgment (referral closure) | `TherapyService.acknowledgeReturn` | `TherapySafetyTest.returnAcknowledgmentMustBindTheExactSentSummaryRevision`; `TherapyCoverageTest.acknowledgmentMustNameTheVerifiedReferrer` |
| Tenant isolation, role authorization, append-only audit | `InMemoryTherapyStore`, `TherapyService.authorize/audit`, `auditTrail` | `TherapySafetyTest.identicalLocalIdentifiersInTwoTenantsStayIsolated`; `TherapyEpisodeFlowTest.auditTrailRecordsActorAndOutcomePerCommandAndIsAppendOnly` |
| Command DTO defensive copies + 422 validation for null lists | `EvaluationRequest` / `ScaleRequest` compact constructors | `TherapyCoverageTest.commandRecordsCopyListsAndPreserveNull` (new, red→green) |

**Entry-point-to-test trace:** command → `TherapyService.<command>(Actor, …)` → `authorize` (fresh actor roles, 403) → guarded `InMemoryTherapyStore.find` (404) → `requireVersion` (412) / `requirePatient` (422) / `requireState` (409) → `store.save` → `appendAudit(SUCCESS)` → value record. Each arrow is exercised by the tests above, including the failure paths (`TherapyCoverageTest.guardFailuresAreAudited`).

## Defects found and fixed (this pass)

1. **DEF-1 (integrator-blocking, SpotBugs):** `EvaluationRequest` and `ScaleRequest` exposed caller-owned mutable lists — 9 `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` findings in `TherapyContract.java`. Fixed with null-preserving `List.copyOf` in the compact constructors and overridden accessors. Null is deliberately preserved so the service still returns a structured 422 for an omitted list (existing validation tests depend on this).
2. **DEF-2 (integrator-blocking, docs gate):** `TherapyContract.Actor.of()` repeated the record's component `@param` tags on a static factory. Doxygen folds a record's factory methods into the canonical constructor entity, producing "multiple @param documentation sections" (same root cause as the merged S-G3-06 fix). Removed those `@param` tags; component semantics remain on the record declaration.
3. **DEF-3 (clinical integrity, reviewer PT-H3 / F2):** signed-note `contentChecksum` was a 32-bit Java `hashCode` (8 hex chars, trivially collidable), mislabelled "content fingerprint" and short of CLN-05's content-checksum requirement. Replaced with the platform's SHA-256 helper `NoteChecksum.sha256(...)` (64 lowercase hex chars) over the NUL-joined bound fields (kind, revision, patient, content, effective time, author, template version, signer, attestation, signed time). Red test `signedNoteChecksumIsSha256` (asserted 64 hex chars) failed before, passed after. Cross-story import is established here (s_g3_09 → s_g3_04) and avoids duplicating crypto.
4. **DEF-4 (authorization, reviewer F1):** `authorize()` had no case for `ACTION_READ`, which fell through to `default -> null` and skipped role checking — any active role (e.g. finance, reception) could read the full signed clinical aggregate. Split into `ACTION_READ_EPISODE` (PT clinician/coordinator) and `ACTION_READ_SCALE` (admin, governance, PT clinician), replaced the switch with a declarative `ACTION_ROLES` policy map whose `getOrDefault(action, Set.of())` fails closed with no unreachable branch, and added red test `wrongActiveRoleCannotReadClinicalAggregateOrScales`. The service class Javadoc now accurately claims every command is authorized.

DEF-3 and DEF-4 change integrity/authorization semantics but no external contract; all prior tests still pass.

## Exact commands run and results

| Command | Result |
|---|---|
| `cd apps/api && mvn -B -Pno-security,fast test` (baseline) | 1491 tests, 0 failures/errors/skipped, BUILD SUCCESS |
| `mvn -B -Pno-security,fast -Dtest=TherapyCoverageTest test` (RED, before DEF-1 fix) | 15 tests, 1 failure: `commandRecordsCopyListsAndPreserveNull` expected `<[p1]>` but was `<[]>`; BUILD FAILURE |
| `mvn -B -Pno-security,fast -Dtest=TherapyCoverageTest test` (GREEN, after fix) | 15 tests, 0 failures/errors, BUILD SUCCESS |
| `cd apps/api && mvn -B -Pno-security,fast test` (final) | **1494 tests, 0 failures/errors/skipped, BUILD SUCCESS** (40 tests in s_g4_02: 5 + 15 + 6 + 14) |
| `cd apps/api && mvn -q -B jacoco:report` → parse `target/site/jacoco/jacoco.csv` for package `com.aurevia.smartclinic.g4.s_g4_02` | **MISSED = 0, COVERED = 3480** across instruction/branch/line/complexity/method; i.e. 100% coverage of every s_g4_02 class from the fast tier |
| `cd apps/api && mvn -B -DskipTests -Dspotbugs.skip=false com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0:check` (before) | 14 bugs; 9 in s_g4_02 (DEF-1) |
| same command (after) | 6 bugs, **0 in s_g4_02** |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests, 1 failure: `record='PatientFlowContract.java'` (s_g3_11, out of scope); `test_javadoc_links_are_fully_qualified` PASS |
| `bash scripts/quick-check.sh` | exit 1 at first gate: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — documented cloud-worker `skip-worktree` CI profile artifact (`git ls-files -v opencode.json` → `S`); not caused by this change |

## Out-of-scope, pre-existing failures (observed, not modified)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/s_g3_11/PatientFlowContract.java` — Java-conventions defensive-copy guard (owned by S-G3-11).
- SpotBugs: `g0.s_g0_07` (1), `g4.s_g4_04` (2), `messaging.s_g3_05` (1), `messaging.s_g3_08` (1), `messaging.s_g3_10` (1) — other stories.
- Stale skip-worktree `opencode.json` in this cloud worker.
- The full `docs.sh` / Doxygen gate could not be run locally: `doxygen` is not installed and `apt-get install doxygen` failed ("Unable to locate package"). DEF-2 was fixed from the documented known-good convention and merged precedent (S-G3-06); a local Doxygen run remains an evidence gap.

## Decisions

- Keep request DTOs null-preserving: the service owns structured 422 validation for missing lists; the records only freeze non-null caller lists.
- No `@param` tags on record compact constructors or static factory methods; components are documented once on the record declaration (Doxygen merge rule). Instance `with*` copy methods keep their `@param` (regular methods, not constructor entities; merged G1 precedent).
- Reserved V42 migration intentionally unused — no schema/Flyway change in this domain-only slice; scope forbids `db/migrations` edits.

## Specialist reviews (simulated, read-only)

- **Senior backend (bounded review):** confirmed the DEF-1 fix correct with no residual SpotBugs/Doxygen issue; found F1 (fail-open READ, fixed as DEF-4) and F2 (weak checksum, fixed as DEF-3). Noted informational concerns: audit append is not transactional with state in the in-memory store; return-acknowledgment origin is asserted by a caller-supplied string, not verified through a cross-org identity.
- **Physician / clinical safety (bounded review):** core state machine accepted as synthetic slice evidence — evaluation gates treatment, signed non-discharge note gates discharge, discharge is terminal, signed notes immutable, readings bind approved scale version + unit, replacement summary clears the old acknowledgment, two-tenant value isolation. Flagged PT-H3 (fixed as DEF-3) and the following limitations that need surfaces outside this domain-only slice.

## Routed limitations (not fixed here — need controller/DB/exchange surfaces, outside declared scope)

These are real gaps for **live enablement**, not defects in this synthetic domain slice. None is represented as verified; each needs its own story and qualified site sign-off.

- **PT-H1 / F1-secondary — return-acknowledgment provenance:** `acknowledgeReturn` checks a caller-supplied `receivedBy` against the stored referrer string; it does not authenticate the external referrer. With no REST controller or cross-org exchange channel in scope, G4 "referral closure" is **not** claimed from this slice. Owner: Product Owner + clinical safety lead + architect (XCH partner identity).
- **PT-H2 — summary disclosure:** `sendSummary` is self-approved and free-text with no content snapshot/checksum/purpose/release-policy version and no verified referrer identity (the `referrerOrg/Subject` strings are captured unverified at acceptance). Owner: clinical safety lead + privacy owner.
- **PT-H4 — atomic version precondition:** `requireVersion` and `InMemoryTherapyStore.save` are separate operations, so two concurrent commands can both pass the check and the last blind put can lose a clinical fact. Real atomicity belongs to a transactional `UPDATE ... WHERE version = ?` in the durable store; the in-memory store is synthetic scaffolding. Owner: senior backend + database specialist (integration story).
- **PT-H5 — acceptance-time verification:** `acceptReferral` does not verify the patient exists or that the referrer is an authorized party (no registry in scope). Explicit patient-binding overloads exist on the patient-bearing commands but the common overloads rely on the episode patient. Owner: clinical safety + IAM/architecture.
- **PT-H6 — discharge does not enforce closure:** summary send and acknowledgment are independent optional commands; the state model does not require them for referral closure. Owner: product + clinical safety.
- **L1/L2/L3/L4 —** clinical timestamps are non-blank strings rather than parsed instants with zone context; `templateVersion` is optional on signed notes; empty baselines/goals are allowed; in-memory audit has no hash chain and some validation failures are unaudited. Owner: relevant domain + integration stories.
- **Architectural context:** no controller, PostgreSQL/Flyway/RLS, OIDC/membership enforcement, or durable audit exists in this module. Tenant isolation was proven at the value layer only; it is not a substitute for the G0 forced-RLS guarantee. All of the above must be re-evaluated when this slice is wired to real persistence and identity.

## Evidence provenance

- Tool-generated: Maven/Surefire (fast tier, 1492 tests), JaCoCo CSV (100% scope coverage), SpotBugs 4.10.5.0 (before/after), Python unittest (Java conventions guard), `git ls-files` / `git status`.
- Simulated reviewers: bounded read-only reviews from the senior-backend and physician-clinical-safety specialists (see appended findings below, if any).
- No DB/integration tests were run locally: per worker rules the integrator runs the database and integration suites. No human or authorized clinical participant feedback; all fixtures are synthetic. No patient data, credentials or raw logs are recorded.
