# CLN-03 clause 2 — track verification, source and effective dates

## Clause

Track verification, source and effective dates, including explicit unknown and none states.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.TrackVerificationSourceEffectiveDatesIncludingExplicitTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/TrackVerificationSourceEffectiveDatesIncludingExplicitTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points `POST /api/v1/chart/records/entries`,
`POST /api/v1/chart/records/entries/{id}/verify` and
`GET /api/v1/chart/records/entries/{id}` with synthetic fixtures. It seeds one
organization with a `clinician` and a `reception` member and one synthetic patient,
and applies only the production Flyway migrations from `db/migrations`.

- `provenanceSourceEffectiveDatesAndExplicitStatesAreTrackedThroughHttp` (rule met) —
  records an `ALLERGY` with `presence: ABSENT` (an explicit "none" assertion),
  `source: CLINICIAN_OBSERVED` and an effective period, and an `ALLERGY` with
  `presence: UNKNOWN`, a coded `unknownReason` and `source: PATIENT_REPORTED`. It
  verifies the explicit-none entry with `If-Match: "1"` and asserts that the HTTP
  response and a fresh read report `VERIFIED`, the verifying actor and the next
  version, and that the stored row keeps the source, the effective start/end
  instants and the verification instant without timezone shift.
- `unknownAssertionWithoutAReasonIsRefused` (negative) — an `UNKNOWN` assertion
  with no coded reason is refused with HTTP 422, so an undetermined state cannot be
  recorded as if it were determined.
- `effectiveEndBeforeEffectiveStartIsRefused` (negative) — an effective period
  whose end precedes its start is refused with HTTP 422.
- `roleWithoutClinicalAuthorityCannotVerifyAnEntry` (negative) — a `reception`
  member, who may read and record, is refused HTTP 403 when verifying.
- `staleVersionCannotVerifyAnEntry` (negative) — verification with a stale
  `If-Match` version is refused with HTTP 412 and leaves the entry `UNVERIFIED`.

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.chart.record.ChartController` — the thin HTTP adapter
  for the record, verify, list and get routes under `/api/v1/chart/records`.
- `com.aurevia.smartclinic.chart.record.ChartService` — validation (source and
  presence required, `UNKNOWN` requires a reason, a reason is allowed only on
  `UNKNOWN`, effective end not before start), derivation of the verification
  actor and instant, and the version-bound verify transition.
- `com.aurevia.smartclinic.chart.record.RecordEntryCommand`,
  `com.aurevia.smartclinic.chart.record.ChartEntry` — the command and persisted
  record carrying source, presence, unknown reason, verification, verified-by,
  verified-at, effective-from, effective-to and measured-at.
- `com.aurevia.smartclinic.chart.record.EntrySource`,
  `com.aurevia.smartclinic.chart.record.PresenceState`,
  `com.aurevia.smartclinic.chart.record.VerificationState` — the enums for
  source, the explicit `PRESENT`/`ABSENT`/`UNKNOWN` assertion and verification.
- `com.aurevia.smartclinic.chart.record.ChartActionPolicy` — the role matrix that
  reserves `CHART_ENTRY_VERIFY` for the `clinician` role.
- `com.aurevia.smartclinic.chart.record.ChartRepository` — the RLS-bounded SQL
  boundary that persists all provenance columns and performs the atomic
  verification compare-and-set.
- `com.aurevia.smartclinic.api.IfMatch` — parses the `If-Match` version
  precondition used by the verify command.
- `db/migrations/V13__longitudinal_chart.sql` — the `chart_entry` table with its
  provenance columns, checks and forced row-level security.

## Gap

None.
