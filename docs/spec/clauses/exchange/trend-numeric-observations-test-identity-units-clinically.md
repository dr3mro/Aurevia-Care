# XCH-07 clause 1 — trend numeric observations only when comparability is established

## Clause

Trend numeric observations only when test identity, units and clinically relevant comparability are established.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.TrendNumericObservationsTestIdentityUnitsClinicallyTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/TrendNumericObservationsTestIdentityUnitsClinicallyTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the HTTP
entry points `POST /api/v1/trends/comparability`, `POST /api/v1/trends/observations` and
`GET /api/v1/trends/{patientId}?testCode=...` with synthetic fixtures. It uses its own database
`orevia_trend_identity_units_it` with Flyway migrations applied from `db/migrations`, seeds
one organization with a `clinician` and a `tenant_admin`, and gives each scenario a distinct test
code and a unique patient MRN so the shared database cannot make one scenario's policy change
another's expected rule version.

- `trendsNumericObservationsWhenIdentityUnitAndComparabilityMatch` (rule met) — defines a trendable
  HBA1C rule in `mmol/mol` / `LAB-HPLC`, records two final numeric observations in that unit and
  asserts that the HTTP read returns two points in effective-time order with their values and
  source unit, no exclusions, and the exact version allocated by that scenario's comparability POST.
- `anIncompatibleUnitIsVisibleButNotTrended` (negative) — a final `7.1 %` result under a `mmol/mol`
  rule yields no point, one exclusion with reason `INCOMPATIBLE_UNIT`, and the original observation
  retained with value, unit, effective time and identifier intact.
- `aDifferentTestIdentityIsVisibleButNotTrended` (negative) — a final `GLU` result read under an
  identity rule yields reason `DIFFERENT_TEST`, no point, and the GLU observation retained.
- `withoutAnEstablishedComparabilityRuleNothingIsTrended` (negative) — with no rule defined for the
  test code, a final numeric observation yields no point, `ruleVersion` 0, reason
  `COMPARABILITY_NOT_ESTABLISHED` on every exclusion, and the observation retained. The assertion
  on `excluded.size() == 1` prevents a vacuous pass on an empty list.
- `aDifferentComparabilityGroupIsNeverTrended` (negative) — a final numeric result with the same
  test identity and unit but a different assay group yields reason
  `DIFFERENT_COMPARABILITY_GROUP` and is not plotted; the assertion on the retained `testCode`
  distinguishes this from a `DIFFERENT_TEST` exclusion.
- `aRuleThatDisallowsTrendingKeepsObservationsVisibleButNotPlotted` (negative) — a rule explicitly
  marked `trendable: false` yields reason `NOT_TRENDABLE`, no point, and the observation retained.
- `aFutureEffectiveComparabilityRuleDoesNotEstablishTrending` (negative) — a rule whose
  `effectiveFrom` is in the future is not established for a current read, so the observation yields
  reason `COMPARABILITY_NOT_ESTABLISHED` and `ruleVersion` 0.

Comparison with the existing `it.exchangetrend.TrendingTest` suite is deliberate: that suite covers
the qualifier, qualitative and non-final revision gates, policy versioning with future and backdated
effective dates, invalid inputs and tenant/role denial; this standalone clause test re-proves the
identity, unit, assay-group, rule-absence, trendable and effective-date gates through the same HTTP
entry points so the clause has self-contained evidence independent of that suite.

Production classes and files exercised by this request path:

- `com.aurevia.exchange.trend.TrendController` — thin HTTP adapter for the
  comparability, observation and trend routes under `/api/v1/trends`.
- `com.aurevia.exchange.trend.TrendService` — validates commands, re-derives
  membership and role on each request, applies the admin/clinician role split and loads the
  effective rule before analysis. The GET path (`readTrend`) is where analysis is invoked; the two
  write paths never call the analyzer.
- `com.aurevia.exchange.trend.TrendRepository` — the RLS-bounded SQL boundary that
  writes observations and policy and reads the effective rule through the
  `public.current_trend_comparability` fold and the patient's observations.
- `public.current_trend_comparability` in `db/migrations/V25__observation_trending.sql` — the
  database fold that decides whether a comparability rule is established and effective
  (`effective_from <= now()`, latest effective instant wins, version breaks ties). This is the
  source of the no-rule and future-rule behavior, not the Java analyzer.
- `com.aurevia.exchange.trend.TrendAnalyzer` — on the GET path only, partitions the
  observations into comparable points and visible exclusions, classifying test identity,
  comparability group, revision, qualifier, numeric value and canonical unit and returning a
  closed `ExclusionReason` for every non-comparable result.
- `com.aurevia.exchange.trend.ComparabilityRule` — the versioned policy carrying the
  canonical unit, group, trendable flag and version; the POST response `version` is distinct from
  the GET response `ruleVersion`.
- `com.aurevia.exchange.trend.TrendObservation` — the immutable reported fact and its
  validation at the trust boundary.
- `com.aurevia.exchange.trend.ExclusionReason` — the closed enumeration that keeps an
  exclusion from being silent, including `COMPARABILITY_NOT_ESTABLISHED` and `NOT_TRENDABLE`.
- `com.aurevia.exchange.trend.TrendView`, `ExcludedObservation`, `TrendPoint` — the
  JSON result that keeps each excluded observation unchanged beside the comparable points.
- `db/migrations/V25__observation_trending.sql` — the append-only tables with forced row-level
  security, author/approver triggers, effective-dated policy and the fold above.

## Gap

None.
