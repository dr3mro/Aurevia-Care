import type {
  LocalizedText,
  ServiceAvailabilityView,
  ServiceInfoFixture,
  ServiceProfileView,
  UrgentChannelView,
} from './types';

/**
 * Select the localized text for the active language, falling back to English.
 *
 * @param entries approved localized text
 * @param language active BCP-47 language key
 * @returns the localized value, or undefined when nothing approved is available
 */
export function localizedText(
  entries: readonly LocalizedText[],
  language: 'en' | 'ar',
): string | undefined {
  const exact = entries.find((entry) => entry.language === language);
  if (exact !== undefined) {
    return exact.value;
  }
  return entries.find((entry) => entry.language === 'en')?.value;
}

/**
 * Build the after-hours notice shown before a patient sends a message.
 *
 * The routine "next handling" line is always paired with the approved urgent
 * channel when the unit is closed, so after-hours guidance can never delay
 * time-sensitive care. When no service windows are published there is no
 * routine handling promise at all.
 *
 * @param profile effective service profile
 * @param availability server-computed availability
 * @param language active language key
 * @returns the display-ready notice lines
 */
export function noticeLines(
  profile: ServiceProfileView,
  availability: ServiceAvailabilityView,
  language: 'en' | 'ar',
): readonly string[] {
  const lines: string[] = [];
  const urgent = localizedText(profile.urgentInstructions, language);
  if (urgent !== undefined) {
    lines.push(urgent);
  }
  for (const channel of profile.urgentChannels) {
    lines.push(channelText(channel));
  }
  if (!availability.open && availability.nextHandlingAt !== undefined) {
    lines.push(
      `Routine queue next handling: ${formatFacilityInstant(availability.nextHandlingAt, availability.timeZone)} (${availability.timeZone}; ${availability.nextHandlingAt} UTC)`,
    );
  }
  return lines;
}

/**
 * Format an absolute UTC instant into the facility's local wall time for
 * presentation only. Service-hours arithmetic stays on the server. An invalid
 * zone falls back to the unambiguous invariant instant.
 *
 * @param iso ISO-8601 UTC instant stamped by the server
 * @param timeZone facility IANA time-zone identifier
 * @returns display text in the facility zone, or the raw instant on failure
 */
export function formatFacilityInstant(iso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone,
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

/**
 * Format one approved channel for display.
 *
 * @param channel approved channel projection
 * @returns a non-sensitive, display-ready string
 */
export function channelText(channel: UrgentChannelView): string {
  return `${channel.label}: ${channel.value}`;
}

/**
 * Create a synthetic, deterministic service-information client for MSG-06.
 *
 * @param fixture synthetic profiles and server-computed availability
 * @returns a typed client that never reads the device clock
 */
export function createSyntheticServiceInfoClient(fixture: ServiceInfoFixture) {
  return {
    /** Return the effective profile for a unit, if one is published. */
    get(unitId: string): ServiceProfileView | undefined {
      return fixture.profiles.find((profile) => profile.unitId === unitId);
    },
    /** Return the server-computed availability for a unit, if known. */
    status(unitId: string): ServiceAvailabilityView | undefined {
      return fixture.availability[unitId];
    },
    /** Return the approved display notice for a unit. */
    notice(unitId: string): readonly string[] {
      const profile = fixture.profiles.find((candidate) => candidate.unitId === unitId);
      const availability = fixture.availability[unitId];
      if (profile === undefined || availability === undefined) {
        return [];
      }
      return noticeLines(profile, availability, fixture.language);
    },
  };
}
