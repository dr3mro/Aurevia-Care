# PT-01 clause 1 — accept a referral into a therapy episode and track evaluation, problems, baselines, goals, plan, interventions, outcomes, progress and discharge

## Clause

Accept a referral into a therapy episode, track evaluation, problems, baseline measurements, goals, planned frequency/duration, interventions, outcomes, progress and discharge.

## Status

Proven by tests

## Evidence

Active proof against real PostgreSQL (`synthetic-auth` profile, a fresh throwaway database
migrated from `db/migrations`, two seeded tenants with identical local identifiers, synthetic
fixtures only). Evidence source: **tool** — an executed targeted integration test; no clinical,
human or simulated-user evidence is claimed.

- Test class: `com.aurevia.therapy.clauses.AcceptReferralTherapyEpisodeTrackEvaluationProblemsTest`
  (path: `apps/api/src/test/java/com/aurevia/therapy/clauses/AcceptReferralTherapyEpisodeTrackEvaluationProblemsTest.java`).
- `acceptsReferralAndTracksEvaluationProblemsBaselinesGoalsAndPlan` — positive proof. Through the
  wired `TherapyController` HTTP entry point, `POST /api/v1/therapy/episodes` returns 201 with an
  `ACTIVE` episode bound to the patient and `GET /api/v1/therapy/episodes/{id}` returns the same
  episode. Through the wired `TherapyService`, `recordEvaluation` retains the problem list, two
  baseline measurements each with an explicit unit and value, two goals, `frequencyPerWeek = 3`
  and `durationWeeks = 6`; the aggregate read back from the durable store shows the state advanced
  `ACTIVE → IN_TREATMENT` and the version `1 → 2`.
- `tracksInterventionsOutcomesProgressAndDischarge` — positive proof. After evaluation (state
  `IN_TREATMENT`), a versioned outcome scale is defined and approved, then `recordOutcome` stores a
  reading bound to that approved version, its permitted value and its exact unit;
  `recordIntervention` stores an intervention with its author; `recordNote`/`signNote` store and sign
  a `PROGRESS` note with an attestation and checksum; a `DISCHARGE` note is signed and closes the
  episode. The aggregate read back shows one reading, one intervention, two signed notes, state
  `IN_TREATMENT → DISCHARGED` and version increments to 8.
- `refusesAcceptanceFromClinicianWithoutPtRole` — negative proof. An actor without the
  `pt_clinician` role is refused by `TherapyService.authorize` with an `ApiException` whose
  `status()` is 403 and `code()` is `FORBIDDEN` (service-level assertion, not an HTTP request).
- `concealsEpisodeFromAnotherTenant` — negative proof. A clinician of another active organization
  receives HTTP 404 from the controller's `GET` route and an `ApiException` with `status()` 404 /
  `code()` `NOT_FOUND` from the service read, under forced row level security.
- `refusesEvaluationWithStaleVersion` — negative proof. An otherwise-valid evaluation with a wrong
  `expectedVersion` is refused by `requireVersion` with an `ApiException` whose `status()` is 412 and
  `code()` is `STALE_VERSION` (service-level assertion).
- `refusesEvaluationWithMissingListsOrBaselineUnit` — negative proof. `recordEvaluation` refuses
  missing problem lists with `status()` 422 / `INVALID_EVALUATION` and a baseline without a unit with
  `status()` 422 / `MISSING_UNIT`; the episode remains `ACTIVE` at version 1, so an invalid
  evaluation does not mutate state (service-level assertions).
- `refusesDuplicateReferral` — negative proof. A second `acceptReferral` with the same synthetic
  referral id is refused by `hasReferral` with `status()` 409 / `CONFLICT`, and the first episode
  stays at version 1 (service-level assertion).
- `refusesInterventionBeforeEvaluation` — negative proof. An intervention attempted while the
  episode is still `ACTIVE`, before an evaluation, is refused by `requireState` with `status()` 409 /
  `INVALID_TRANSITION`; the episode stays `ACTIVE` and holds no interventions (service-level
  assertion).
- `refusesEvaluationAfterDischarge` — negative proof. Once the discharge note is signed, a further
  evaluation with the current version is refused by `requireState` with an `ApiException` whose
  `status()` is 409 and `code()` is `INVALID_TRANSITION` (service-level assertion).

Production classes exercised:

- `com.aurevia.therapy.TherapyController` — the thin HTTP adapter publishing
  `POST /api/v1/therapy/episodes` (accept) and `GET /api/v1/therapy/episodes/{episodeId}` (read).
  Only these two commands are reachable over HTTP.
- `com.aurevia.therapy.TherapyService` — the domain service that implements every
  clause operation (`acceptReferral`, `recordEvaluation`, `recordIntervention`, `recordOutcome`,
  `defineOutcomeScale`/`approveOutcomeScale`, `recordNote`/`signNote`) and enforces the role matrix,
  version preconditions, input validation and `ACTIVE → IN_TREATMENT → DISCHARGED` state machine.
  The tracking operations are invoked **directly on the injected service**, not over REST.
- `com.aurevia.therapy.TherapyContract` — `Actor`, `ReferralRequest`,
  `EvaluationRequest`, `Measurement`, `Goal`, `OutcomeRequest`, `NoteRequest`, `NoteKind`,
  `EpisodeState` and `TherapyAggregate`, the immutable command and view types.
- `com.aurevia.therapy.JdbcTherapyStore` — the tenant-guarded JDBC boundary, backed by
  the therapy tables created by `db/migrations/V77__therapy_episode.sql` and `db/migrations/V85__therapy_store.sql`
  (enabled and forced row level security, tenant policies, append-only notes and problems).

Every fact named in the clause is asserted by an active test and survives a fresh migration over
real PostgreSQL: acceptance and episode read through the wired HTTP entry point, and the tracking
commands through the wired service and durable tenant-scoped store. The status is therefore
"Proven by tests" for the clause behaviour itself. How those commands are published (service call
rather than HTTP route, and no contract entry yet) is a delivery gap recorded below, not missing
clause behaviour.

## Gap

The tracked half of the clause is implemented and durable, but no controller route publishes it:

1. **No HTTP command routes for the tracking operations.** `TherapyController` exposes only referral
   acceptance and episode read. Evaluation, intervention, outcome, scale definition/approval and
   note drafting/signing are called directly on `TherapyService`; the results are observable on the
   `GET` aggregate, but the commands cannot be issued over HTTP and there is no HTTP-level test for
   them. Closing this needs controller routes plus HTTP-level integration tests.
2. **No published contract.** `/api/v1/therapy/episodes` is absent from `contracts/openapi.yaml`
   (the two real routes are baselined in `contracts/openapi.yaml`), so generated
   clients cannot call even the two existing routes. The generated contract still advertises a
   different `/api/v1/therapy/referrals/{id}/accept` path that no controller implements. Closing
   this needs OpenAPI `paths` entries, reconciliation of that stale generated path, and a
   regenerated client.

Closing both would need, in order, one cross-boundary backend task (new controller routes, directive
to reuse the existing service methods, HTTP-level tests with 100% coverage, and the OpenAPI entries
with route parity) and one contract/client task to regenerate and verify the client: **two separate
tasks**. These are adjacent follow-ups, not a request made by this evidence task — no task was
created and no production, contract or migration file was changed here. The successful HTTP proof of
referral acceptance and read does not depend on the contract entry because the routes are wired and
served.

No `@Disabled("GAP: ...")` placeholder test is added for the missing routes: this repository's
`scripts/check-constraints.py` rejects any newly added `@Disabled` line, so such a test would fail a
hard gate and break the green pull request. The gap is instead documented here with the exact files
and task count; a later implementation task can add the HTTP-level tests as active tests when the
routes exist.

The second sentence of PT-01 (versioned outcome scales and units, signing session/progress/discharge
notes, approved summaries and return acknowledgment) is the subject of other clause-evidence tasks;
this note proves only clause 1, the sentence quoted under `## Clause`. Units and scale versioning are
exercised here only as far as recording an outcome requires.
