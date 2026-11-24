# BUILD-11 · Radiology: verify patient and accession before linking a study

PR #302, tested branch tip e8a5a11aa641a21e1df293fbdaf10235a0351ac6.

Task: BUILD-11

Swarm worker 2. Local checks: passed. Agent result: ok.

## Summary

RAD-01 study-link verification. An external Study Instance UID is linked only when the supplied patient **and** accession labels match the order. A mismatch now opens an item in a tenant-scoped study-link review queue, emits a review fact and leaves the order revision untouched; resolving the review does not itself link the study. A later correct `associateStudy` appends a new revision so the full order history is preserved. Modality, urgency and clinical question are recorded during the intake workflow; modality may be supplied at intake (only if approved) or at scheduling as before. All changes stay inside the two radiology paths in the story scope. Synthetic fixtures only.

## Affected requirements

- RAD-01 — imaging intake, patient/accession verification before study association, review of mismatches, modality/urgency/clinical question capture.
- ARC-01 / ARC-02 — immutable per-tenant revisions and events; review queue keyed by active tenant.
- OPS-02 — append-only review revisions and `STUDY_LINK_REVIEW_OPENED` / `STUDY_LINK_REVIEW_RESOLVED` facts.
- CLN-05 / XCH-05 — downstream signing and critical communication are unchanged; they still require a linked study.

## Risk class

Safety-critical (patient-to-study association). Clinical and architecture review here is **simulated specialist review only**; no human clinical sign-off is claimed.

## Review owner

- Orevia Senior Java/Spring Boot (advisory report received, static review).
- Orevia Test Engineer (advisory test matrix received, static review).
- QA / clinical reviewer: pending integrator; no human participant in this run.

## Data changes

None. No migration was added or changed (no reserved version in this task). The radiology kernel remains deliberately in-memory with append-only revisions and events, as documented in `ImagingService`; durable persistence is a later story. New domain types: `StudyLinkReview` (immutable record), `RadTypes.StudyLinkReviewStatus`, `RadCommands.StudyReviewCommand`, two `RadEventType` constants, and three request records on `ImagingController`. Backward-compatible overloads preserve the existing 14-arg `IntakeCommand` and 11-arg `IntakeRequest` constructors used by out-of-scope tests.

## Authorization boundaries and audit events

Organization, actor and roles always come from `TenantContext` and freshly resolved membership; no identity is taken from the link/resolve request body. Linking and review resolution both require the existing `radiology_staff` role (`RadRules.mayAssociate`); a deactivated grant is denied on the next command. A mismatch is visible only in the active tenant's queue. Events carry only identifiers (no clinical narrative, no image pixels): `STUDY_LINK_REVIEW_OPENED` (destination = Study Instance UID, actor = caller) and `STUDY_LINK_REVIEW_RESOLVED` (same identifiers, resolution text is not put in the event). Resolved reviews are retained, not deleted.

## Migration and rollout considerations

No schema or backfill. New HTTP routes are added to an existing controller; no OpenAPI entry or regenerated client is included because `contracts/` is outside this task's scope. Per the senior-backend review, this change is domain-kernel-complete but **not releasable on its own**: OpenAPI/client/UI and durable persistence must land in follow-up work before launch.

## Test evidence

Evidence source labels: all commands below were **executed by the primary agent tool**; the two specialist reports are **simulated reviewer** evidence; no human participant.

Red first (expected compile failure before production code existed):

- `mvn -q -B -Pno-security -f apps/api/pom.xml -Dtest=StudyLinkReviewTest,ImagingControllerHttpTest test`
  - Result: `cannot find symbol: class StudyLinkReview / variable StudyLinkReviewStatus` — expected red.

Green, focused (in-scope plus the out-of-scope radiology tests that must keep passing):

- `mvn -q -B -Pno-security -f apps/api/pom.xml -Dtest='StudyLinkReviewTest,ImagingControllerHttpTest,ImagingServiceTest,RadDomainRecordsTest,RadTypesTest,RadPolicyTest' test jacoco:report`
  - Final line: exit `0`. Surefire XML: `StudyLinkReviewTest` 5/5, `ImagingControllerHttpTest` 9/9, `ImagingServiceTest` 11/11, `RadDomainRecordsTest` 6/6, `RadTypesTest` 4/4, `RadPolicyTest` 5/5 — 40 tests, 0 failures, 0 errors.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — final line exit `0` (whole non-integration Java unit suite).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — final line exit `0` (SpotBugs static analysis, 0 findings).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` — `Ran 2 tests … OK` (Javadoc links fully qualified; business-domain names).
- `python3 -I -m unittest discover -s scripts/tests` with the committed `opencode.json` — `Ran 436 tests … OK`.
- `python3 -I scripts/check-pack.py` (committed profile) — `Delivery tooling validated: 33 agents, 33 skills`; `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` — all exit `0`.
- `timeout 290 bash scripts/check.sh` — stopped at the mandated 5-minute worker cap (exit `124`) while `mvn verify` was still running the full suite; no `[ERROR]` or `BUILD FAILURE` appeared in the log before the cap. Full OWASP/JaCoCo-gate/full-integration/db evidence is the integrator's gate.

Entry-point-to-test trace:

- `POST /api/v1/imaging/orders` → `ImagingControllerHttpTest.intakesAndReadsAnOrderThroughHttp`, `intakeWithoutModalityStillWorksThroughHttp`, `autowiredConstructorBuildsAControllerWithAnApprovedModalityCatalog`.
- `POST /api/v1/imaging/orders/{orderId}/studies` → `mismatchQueuesAReviewAndReLinkAfterReviewThroughHttp`, `linkingValidatesInputRoleAndTenantThroughHttp`.
- `GET /api/v1/imaging/orders/study-link-reviews` → `mismatchQueuesAReviewAndReLinkAfterReviewThroughHttp` (asserts the OPEN queue item).
- `POST /api/v1/imaging/orders/study-link-reviews/resolve` → `mismatchQueuesAReviewAndReLinkAfterReviewThroughHttp`, `reviewResolutionValidatesInputAndMissingReviewThroughHttp`.
- Domain kernel → `StudyLinkReviewTest` (5 tests: mismatch/no-link/queue, resolve + re-link history, intake fields, resolve validation, record invariants).

## Coverage

Command (targeted, with the agent attached): `mvn -q -B -Pno-security -f apps/api/pom.xml -Dtest='StudyLinkReviewTest,ImagingControllerHttpTest,ImagingServiceTest,RadDomainRecordsTest,RadTypesTest,RadPolicyTest' test jacoco:report`; counters read from `apps/api/target/site/jacoco/jacoco.xml`. Every touched class is 100% on instruction, line, branch, method and class:

| Class | Instruction | Line | Branch | Method | Class |
|---|---|---|---|---|---|
| ImagingService | 1645/1645 | 301/301 | 80/80 | 42/42 | 1/1 |
| ImagingController | 165/165 | 32/32 | 2/2 | 10/10 | 1/1 |
| ImagingController$IntakeRequest | 54/54 | 3/3 | – | 2/2 | 1/1 |
| ImagingController$StudyLinkRequest | 18/18 | 1/1 | – | 1/1 | 1/1 |
| ImagingController$StudyReviewRequest | 12/12 | 1/1 | – | 1/1 | 1/1 |
| StudyLinkReview | 99/99 | 14/14 | 16/16 | 3/3 | 1/1 |
| RadCommands$IntakeCommand | 73/73 | 6/6 | – | 3/3 | 1/1 |
| RadCommands$StudyReviewCommand | 28/28 | 4/4 | – | 2/2 | 1/1 |
| RadTypes$StudyLinkReviewStatus | 20/20 | 4/4 | – | 2/2 | 1/1 |
| RadTypes$RadEventType | 74/74 | 13/13 | – | 2/2 | 1/1 |

New branches and the test that takes each side:

- `intake` approved-modality guard `modality != null && !approved`: unapproved → `intakeRecordsModalityUrgencyAndClinicalQuestion` (`UNSUPPORTED_MODALITY`); null → same test (legacy constructor) and every out-of-scope intake test; approved → same test and `intakesAndReadsAnOrderThroughHttp`.
- `associateStudy.matches` `patient && accession`: wrong patient → `mismatchNeverLinksAndOpensOneOpenReview`; matching patient + wrong accession → same test; both matching → `reL
