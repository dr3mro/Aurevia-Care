# XCH-07 clause 3 — keep qualifiers and qualitative results visible

## Clause

Keep qualifiers such as less-than and greater-than, qualitative results and incompatible units visible without treating them as directly comparable measurements.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.KeepQualifiersSuchLessGreaterQualitativeResultsTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/KeepQualifiersSuchLessGreaterQualitativeResultsTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points `POST /api/v1/trends/observations`,
`POST /api/v1/trends/comparability` and
`GET /api/v1/trends/{patientId}?testCode=HBA1C` with synthetic fixtures. It seeds two
tenants sharing the same patient MRN with a `clinician`, a `reception` and a
`tenant_admin` member in the owning tenant and applies only the production Flyway
migrations from `db/migrations`.

- `qualifiedQualitativeAndIncompatibleResultsRemainVisibleButAreNotTrendedOverHttp` (rule met) —
  defines an HBA1C comparability rule in the canonical unit `mmol/mol`, then records
  a `< 3.0` and a `> 9.0` qualified numeric, a `qualitativeValue`-only result
  ("Negative"), a `7.1 %` result in a non-canonical unit and one ordinary
  `6.1 mmol/mol` result. The HTTP trend read returns a single point for the
  ordinary result and four exclusions, each retaining its original qualifier,
  value, unit and reason: two `QUALIFIED_NUMERIC` (wire qualifiers `less_than` and
  `greater_than`), one `QUALITATIVE` and one `INCOMPATIBLE_UNIT`. A direct database
  read confirms the `less_than` wire value is persisted, so the exclusion survives
  a restart.
- `receptionCannotRecordOrReadTrendObservations` (negative, wrong role) — a
  `reception` member is refused HTTP 403 for both the trend read and the
  observation write, so a non-clinical role cannot see or create trend data.
- `clinicianOfAnotherTenantCannotReadThisPatientsTrend` (negative, wrong tenant) —
  a clinician of tenant A is refused HTTP 404 when reading the trend of a tenant B
  patient that shares the same MRN, proving row-level concealment of a foreign
  patient.
- `unknownQualifierIsRefused` (negative, missing input) — an observation carrying
  the unknown qualifier `approx` is refused HTTP 422 rather than being recorded
  with a guessed qualifier.
- `qualifiedNumericWithoutAValueIsRefused` (negative, missing input) — a qualified
  result with no numeric value is refused HTTP 422, so a qualifier cannot be
  attached to a value-less observation.

Production classes and files exercised by this request path:

- `com.aurevia.exchange.trend.TrendController` — the thin HTTP adapter
  for the observation-recording, comparability and trend-read routes under
  `/api/v1/trends`.
- `com.aurevia.exchange.trend.TrendService` — tenant-guarded commands;
  it validates input, authorizes each action, records observations, reads the
  effective comparability rule and returns a `TrendView`.
- `com.aurevia.exchange.trend.TrendAnalyzer` — the pure comparability
  decision: `classify` returns `QUALIFIED_NUMERIC`, `QUALITATIVE` or
  `INCOMPATIBLE_UNIT`, and `analyze` places those observations in
  `TrendView.excluded()` while only unqualified, final, numeric results in the
  canonical unit become `TrendPoint`s.
- `com.aurevia.exchange.trend.Qualifier` — parses `<`, `>`,
  `less_than` and `greater_than` into enum values, serializes the stable wire
  values `less_than`/`greater_than`, and refuses unknown values with HTTP 422.
- `com.aurevia.exchange.trend.TrendObservation` — the immutable
  observation with its validation guard that requires a numeric value whenever a
  qualifier is present.
- `com.aurevia.exchange.trend.ExclusionReason`,
  `com.aurevia.exchange.trend.ExcludedObservation`,
  `com.aurevia.exchange.trend.TrendView`,
  `com.aurevia.exchange.trend.TrendPoint` — the wire model of a trend
  read: typed exclusion reasons and the original observation kept unchanged beside
  the comparable points.
- `com.aurevia.exchange.trend.TrendActionPolicy` — the role matrix that
  reserves clinical trend read and observation write for the `clinician` role.
- `com.aurevia.exchange.trend.TrendRepository` — the RLS-bounded SQL
  boundary that persists observations and the versioned comparability rule.
- `db/migrations/V25__observation_trending.sql` — the `trend_observation` and
  `trend_comparability` tables with their qualifier check constraint, composite
  tenant keys, enforced row-level security and audit trail.

## Gap

None.
