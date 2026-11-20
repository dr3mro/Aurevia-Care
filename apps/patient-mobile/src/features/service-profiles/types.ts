/**
 * MSG-06 service-profile projections for the patient app.
 *
 * These are transport-safe views of the server's authoritative values. The
 * server computes availability and stamps absolute instants in UTC; the app
 * renders them with the facility's IANA zone label. No wall-clock service-hours
 * arithmetic is duplicated on the device.
 */

/** One approved piece of static guidance copy in a supported language. */
export interface LocalizedText {
  readonly language: 'en' | 'ar';
  readonly value: string;
}

/** A local wall-clock service window as published by the server. */
export interface ServiceWindowView {
  /** ISO weekday label, for example FRIDAY. */
  readonly weekday: string;
  /** Local start time (HH:mm). */
  readonly start: string;
  /** Local end time (HH:mm). */
  readonly end: string;
  /** Whether the window crosses local midnight. */
  readonly wraps: boolean;
}

/** The unit's published service hours in its own IANA zone. */
export interface ServiceHoursView {
  /** Facility IANA time-zone identifier, for display. */
  readonly timeZone: string;
  readonly windows: readonly ServiceWindowView[];
}

/** A site-approved, non-clinical contact channel reference. */
export interface UrgentChannelView {
  /** Stable channel key from the approved site configuration. */
  readonly key: string;
  /** Site-localized, non-sensitive label for display. */
  readonly label: string;
  /** Contact value resolved by the site configuration, never a clinical detail. */
  readonly value: string;
}

/** The effective, named-approved service profile of one care unit. */
export interface ServiceProfileView {
  readonly unitId: string;
  readonly organizationId: string;
  readonly unitName: string;
  readonly version: number;
  readonly serviceHours: ServiceHoursView;
  /** First-response target in minutes. */
  readonly responseTargetMinutes: number;
  /** Approved topic keys this unit handles. */
  readonly topics: readonly string[];
  /** Approved static urgent guidance. */
  readonly urgentInstructions: readonly LocalizedText[];
  /** Site-approved urgent and downtime channels. */
  readonly urgentChannels: readonly UrgentChannelView[];
  readonly queueOwner: string;
  readonly backupCoverage: readonly string[];
  readonly escalationChannel: string;
  /** ISO-8601 instant from which this version is effective. */
  readonly effectiveFrom: string;
}

/** Server-computed availability at one instant. */
export interface ServiceAvailabilityView {
  readonly open: boolean;
  /** Absolute UTC ISO-8601 instant the routine queue next opens, if closed. */
  readonly nextHandlingAt?: string;
  readonly timeZone: string;
}

/** A synthetic fixture for the in-memory service-information client. */
export interface ServiceInfoFixture {
  readonly profiles: readonly ServiceProfileView[];
  readonly availability: Readonly<Record<string, ServiceAvailabilityView>>;
  /** Default language for localized guidance, en or ar. */
  readonly language: 'en' | 'ar';
}
