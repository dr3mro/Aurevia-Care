# UX-01 clause 2 — store UTC instants with IANA time-zone context

## Clause

Store UTC instants with IANA time-zone context and retain civil dates where required.

## Status

Partly proven

## Evidence

The clause test is [store-utc-instants-iana-time-zone-context.test.ts](../../../../apps/staff-web/tests/spec/localization/store-utc-instants-iana-time-zone-context.test.ts). It is a Vitest suite in jsdom with synthetic constants only, no server or database setup, no helper added to `src/`, and no `it.skip`. It is enabled and green: 3 tests, 0 failures, 0 skipped.

- `storesUtcInstantWithIanaZoneAndRetainsCivilDate` (rule met) — one UTC ISO instant (`2026-03-08T06:30:00Z`) renders differently under its facility zone `America/New_York` than under `UTC`, showing the spring-forward wall clock `1:30`; a civil date (`2026-02-28`) formats at UTC midnight to `February 28`, so the calendar day does not shift.
- `refusesAnInvalidIanaTimeZone` (negative) — a non-IANA zone (`Not/AZone`) is refused with `RangeError` rather than silently rendered in a wrong zone.
- `refusesAMissingOrMalformedInstant` (negative) — a malformed (`not-an-instant`) or missing (`''`) instant is refused with `RangeError` rather than producing a misleading display value.

Production code exercised through its normal entry point:

- [format.ts](../../../../apps/staff-web/src/i18n/format.ts) — `formatInstant` formats the UTC instant with the caller's IANA zone and lets `Intl` apply daylight-saving transitions; `formatCivilDate` formats a civil `YYYY-MM-DD` at UTC midnight so no local-zone conversion can move the day. Both pass `numberingSystem: 'latn'`.
- [domain.ts](../../../../apps/staff-web/src/features/scheduling/domain.ts) — `Appointment.scheduledAt` is the UTC instant and `Appointment.timeZone` is the IANA display zone; `withinRange` and `rangeWindow` select worklists by UTC civil date.
- [api.ts](../../../../apps/staff-web/src/features/scheduling/api.ts) — `rangeWindow` builds half-open UTC day and week windows, keeping ordering independent of the display zone.
- [fixtures.ts](../../../../apps/staff-web/src/features/scheduling/fixtures.ts) — synthetic scheduling fixtures carry the `America/New_York` facility zone used by this rule.
- [messages.ts](../../../../apps/staff-web/src/i18n/messages.ts) — the `Locale` type of the interface locale passed to both formatters.
- [rtl-and-localization.md](../../../../docs/design/rtl-and-localization.md) section 5 states the rule these formatters implement: store and transmit UTC instants, render with an explicit IANA zone, and format civil dates at UTC midnight.

## Gap

This staff-web test proves the client-side **representation and formatting** half of the clause only; it does not write to or read from storage, so "store" is not proven here and the status is `Partly proven`. No test is left failing or skipped for the unproven half.

The at-rest half is a server concern and is outside this change's scope: [V74__follow_up_suggestions.sql](../../../../db/migrations/V74__follow_up_suggestions.sql) declares `public.follow_up_suggestion.encounter_at` as `timestamptz` and `time_zone` as `text`, and the server-side path is exercised separately by [FollowUpHttpIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/followup/it/FollowUpHttpIntegrationTest.java) (an HTTP-level test on real PostgreSQL that asserts a civil date across a daylight-saving change) and [JdbcFollowUpStoreTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/followup/JdbcFollowUpStoreTest.java) (the store row mapping). Those tests were not run or changed by this staff-web-only story; proving that a booking's UTC instant and IANA zone survive a database restart end to end belongs to a server-side task, not to this pull request.
