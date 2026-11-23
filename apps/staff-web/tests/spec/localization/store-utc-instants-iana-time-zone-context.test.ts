import { describe, expect, it } from 'vitest';
import { formatCivilDate, formatInstant } from '../../../src/i18n/format';

// Synthetic values only. The instant sits on the 2026 spring-forward day in
// America/New_York so the zone context changes the rendered wall clock, while
// the civil date has no zone at all and must stay on its own calendar day.
// This suite proves client-side representation only; the at-rest half ("Store")
// belongs to the server migration and FollowUpHttpIntegrationTest.
const SPRING_FORWARD_INSTANT = '2026-03-08T06:30:00Z';
const FACILITY_ZONE = 'America/New_York';
const UTC_ZONE = 'UTC';
const CIVIL_DATE = '2026-02-28';

describe('UX-01 clause 2: UTC instants with IANA zone context and civil dates', () => {
  it('storesUtcInstantWithIanaZoneAndRetainsCivilDate', () => {
    const inFacilityZone = formatInstant(SPRING_FORWARD_INSTANT, FACILITY_ZONE, 'en');
    const inUtc = formatInstant(SPRING_FORWARD_INSTANT, UTC_ZONE, 'en');

    // One stored UTC instant renders differently under its facility zone than
    // under UTC; the spring-forward wall clock is 01:30 in New York.
    expect(inFacilityZone).not.toBe(inUtc);
    expect(inFacilityZone).toContain('1:30');

    // The civil date has no zone, so it stays on its own calendar day.
    const civil = formatCivilDate(CIVIL_DATE, 'en');
    expect(civil).toContain('February');
    expect(civil).toContain('28');
  });

  it('refusesAnInvalidIanaTimeZone', () => {
    expect(() => formatInstant(SPRING_FORWARD_INSTANT, 'Not/AZone', 'en')).toThrow(RangeError);
  });

  it('refusesAMissingOrMalformedInstant', () => {
    expect(() => formatInstant('not-an-instant', UTC_ZONE, 'en')).toThrow(RangeError);
    expect(() => formatInstant('', UTC_ZONE, 'en')).toThrow(RangeError);
  });
});
