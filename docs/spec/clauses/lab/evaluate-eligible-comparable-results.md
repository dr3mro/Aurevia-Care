# LAB-02 clause 5 — API test evidence

## Clause

Evaluate only eligible comparable results.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.lab.clauses.EvaluateEligibleComparableResultsTest`
(path: `apps/api/src/test/java/com/aurevia/lab/clauses/EvaluateEligibleComparableResultsTest.java`).
All seven tests are active (none disabled) and pass with synthetic data only. The tests are
kernel-level service-API tests in the style of the neighbouring clause proofs (no MockMvc): they
reuse the fixture shapes of `lab.result.DocumentedUnitConversionTest` (`numeric(...)`,
`conversion(...)`), the `LabCommands` capture/verify commands, an empty or range-populated
`LabPolicyCatalog` and a fixed `Clock`. They are evidence-only; no production code, existing test,
contract or migration was changed.

- `comparableReturnsOnlyPresentExactNumericObservations` — positive proof. A capture mixes two
  exact numerics with a qualified numeric, text, coded, unknown and absent observation.
  `LabResultService.comparable` returns exactly the two exact numerics in capture order, proving
  both sides of the eligibility filter and its ordering.
- `qualifiedNumericResultsAreNeverComparable` — negative proof (qualifier). For all four
  operators (`LT`, `GT`, `LTE`, `GTE`) `LabRules.eligible` is false and `LabRules.comparable` is
  false in both argument orders, including with a documented conversion catalog present.
- `textCodedUnknownAndAbsentObservationsAreNeverComparable` — negative proof (kind and presence).
  Text, coded, explicitly unknown and absent observations are ineligible and never comparable in
  either direction.
- `comparableRefusesDifferentTestIdentities` — negative proof (test identity). With the unit held
  identical, a different test code is never comparable in either direction.
- `undocumentedUnitMismatchIsNotComparable` — negative proof (unit). The same code with a
  different unit is not comparable with no conversion catalog; with an empty catalog no
  undocumented conversion is invented. The documented-conversion true side is covered by the
  existing `DocumentedUnitConversionTest.aDocumentedConversionJoinsMixedUnitsInEitherDirection`,
  run alongside this class.
- `otherTenantCannotReadTheComparableResults` — negative proof (tenant isolation). A report
  captured in the owner tenant is concealed as HTTP 404 `NOT_FOUND` when queried from a second
  tenant, while that second tenant still reads only its own report — no cross-tenant leak.
- `ineligibleObservationsReceiveNoInterpretationOnVerify` — negative proof (evaluation). Via the
  `verify` entry point, an approved reference range flags the exact numeric `HIGH`, while an
  otherwise identical qualified numeric and the text, coded, unknown and absent observations all
  remain `AbnormalFlag.NONE`. Verification evidence is still recorded on every observation, and
  only the exact numeric remains comparable in the issued revision. This proves `evaluate`'s
  else-branch refuses interpretation to ineligible results.

Production classes exercised through their normal kernel entry points:

- `com.aurevia.lab.result.LabRules` — `eligible` (present exact numeric only) and
  `comparable` (eligibility, test identity, unit equality, documented bidirectional conversion).
- `com.aurevia.lab.result.LabResultService` — `comparable(org, reportId)` filters the
  current revision with `LabRules::eligible`; `verify` reaches the private `evaluate`, which
  interprets only present numeric observations and sets `AbnormalFlag.NONE` for every other kind
  or presence; `load` conceals another tenant's report as 404.

Neighbouring implementations observed in the source survey (not changed by this task):
`it/lab/ComparabilityTest`, `it/lab/LabResultServiceTest` and
`lab/result/DocumentedUnitConversionTest` already cover the rule at branch level.
There is an HTTP surface for result entry and verification —
`labflow.results.LabResultEntryController` at `/api/v1/lab/results` delegates to
`labflow.results.ResultEntryService`, which wraps `LabResultService`; the generated contract
declares `/api/v1/lab/results/{id}/verify`. That route reaches the "evaluate only eligible"
behaviour on `verify`, but it does not expose the comparable subset.

## Gap

The rule itself is implemented and proven, so `Not built` does not apply; `Proven by tests` would
overclaim against the definition of done because the comparable filter is neither reachable at
the HTTP boundary nor durable. Two things are missing for the clause to be fully reachable and
durable:

1. **No HTTP read of the comparable subset.** `LabResultService.comparable(org, reportId)` has no
   production caller (`grep` of `apps/api/src/main/java` finds none) and
   `LabResultEntryController` exposes no comparable endpoint; `ResultEntryService.read` returns
   all observations unfiltered. The interpret-only-eligible behaviour is reachable through
   `POST /api/v1/lab/results/{id}/verify`, but the filtered comparable result is currently
   test-only.
2. **No persistence.** `LabResultService` keeps revisions and trigger events in in-memory maps;
   no migration or `Jdbc*` store backs `LabResultService` revisions (the separate
   `V25__observation_trending.sql` defines XCH-07 trend tables, not lab-result revisions), so
   revisions do not survive a restart.

Closing both needs a separate story (or two) with a migration carrying RLS and FORCE RLS, a
`Jdbc*` store behind a store interface, a tenant-guarded comparable read wired through the
injected service into a thin controller, an OpenAPI entry with regenerated client, and an
HTTP-level integration test. Those files are outside this task's two-file scope, so no
`task-requests` block is raised here: the gap is recorded for the integrator/owner to schedule.

This task added proof only; it changed no production code, no existing test, the contract or a
migration.
