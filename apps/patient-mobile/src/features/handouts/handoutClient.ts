/**
 * Typed patient handout client (PAT-02, CLN-07, ARC-06).
 *
 * Every command reads the OS-backed credential immediately before the request
 * and holds no clinical state between calls. Listing rejects withheld handouts
 * at the trust boundary. A download requires a fresh server authorization for
 * the exact handout version, linked to the authenticated patient and still a
 * current release; a failed authorization never produces a document reference.
 */
import type { Locale } from '../appointments/patientDesign';
import type { ReportTransport as HandoutTransport, TokenStore } from '../reports/types';

import { handoutText } from './messages';
import { releasedOnly } from './presentation';
import type {
  HandoutAuthorization,
  HandoutAuthorizationState,
  HandoutKind,
  HandoutLanguage,
  HandoutReleaseState,
  HandoutSupersededBy,
  ReleasedHandout,
} from './types';

/** Protected handout resource family. */
const HANDOUT_PATH = '/api/v1/handouts';

/** Narrow a decoded JSON value to a non-empty string. */
function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Narrow a decoded JSON value to a positive integer version. */
function isVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1;
}

/** Narrow a decoded JSON value to a supported document language. */
function isLanguage(value: unknown): value is HandoutLanguage {
  return value === 'en' || value === 'ar';
}

/** Narrow a decoded JSON value to a patient-visible handout kind. */
function isKind(value: unknown): value is HandoutKind {
  return value === 'prescription' || value === 'order-sheet' || value === 'chart-summary';
}

/** Narrow a decoded JSON value to a known handout visibility state. */
function isReleaseState(value: unknown): value is HandoutReleaseState {
  return value === 'released' || value === 'withheld';
}

/** Narrow a decoded JSON value to a known authorization state. */
function isAuthorizationState(value: unknown): value is HandoutAuthorizationState {
  return value === 'current' || value === 'withdrawn';
}

/** Narrow a decoded JSON value to a plain object. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Normalize correction context to a complete pair or to no correction at all.
 *
 * An asymmetric or empty pair is rejected rather than rendered, because a
 * patient shown only half a correction cannot tell which version replaced what.
 */
function parseSuperseded(version: unknown, reason: unknown): HandoutSupersededBy | null {
  if (version === null && reason === null) {
    return null;
  }
  if (isVersion(version) && isText(reason)) {
    return { version, reason };
  }
  throw new Error('MALFORMED_HANDOUT');
}

/**
 * Parse a patient-visible handout projection, rejecting malformed payloads.
 *
 * Validation runs at the client trust boundary so a tampered response cannot
 * be rendered as a clinical document reference. `releaseState` is required:
 * without it a withheld handout would pass the visibility filter.
 */
export function parseReleasedHandout(value: unknown): ReleasedHandout {
  if (!isRecord(value)) {
    throw new Error('MALFORMED_HANDOUT');
  }
  const {
    handoutId,
    version,
    kind,
    issuingOrganization,
    releasedDate,
    language,
    releaseState,
    supersededByVersion,
    supersededByReason,
  } = value;
  if (!isText(handoutId)
      || !isVersion(version)
      || !isKind(kind)
      || !isText(issuingOrganization)
      || typeof releasedDate !== 'string'
      || !/^\d{4}-\d{2}-\d{2}$/.test(releasedDate)
      || !isLanguage(language)
      || !isReleaseState(releaseState)) {
    throw new Error('MALFORMED_HANDOUT');
  }
  const supersededBy = parseSuperseded(supersededByVersion, supersededByReason);
  return {
    handoutId,
    version,
    kind,
    issuingOrganization,
    releasedDate,
    language,
    releaseState,
    supersededBy,
  };
}

/**
 * Parse a server-issued download authorization and bind it to this download.
 *
 * A well-formed authorization is not enough: it must name the exact handout and
 * version the patient tapped, be linked to the authenticated patient, and still
 * describe a current release. These checks run on every download because
 * release state can change after the list was loaded.
 */
function parseAuthorization(
  value: unknown,
  expectedHandoutId: string,
  expectedVersion: number,
  expectedPatientId: string,
): HandoutAuthorization {
  if (!isRecord(value)) {
    throw new Error('MALFORMED_AUTHORIZATION');
  }
  const { handoutId, version, documentId, checksum, language, patientId, releaseState } = value;
  if (!isText(handoutId) || !isVersion(version) || !isText(documentId) || !isText(checksum)
      || !isLanguage(language) || !isText(patientId) || !isAuthorizationState(releaseState)) {
    throw new Error('MALFORMED_AUTHORIZATION');
  }
  if (handoutId !== expectedHandoutId || version !== expectedVersion) {
    throw new Error('AUTHORIZATION_MISMATCH');
  }
  if (patientId !== expectedPatientId) {
    throw new Error('WRONG_PATIENT');
  }
  // Fail closed on any non-current state. `withheld` must never grant access,
  // whether or not the projection layer has already filtered it out.
  if (releaseState !== 'current') {
    throw new Error('RELEASE_UNAVAILABLE');
  }
  return { handoutId, version, documentId, checksum, language, patientId, releaseState };
}

/** Client capability needed by the save/share confirmation flow. */
export type HandoutAuthorizer = Pick<HandoutClient, 'authorizeDownload'>;

/**
 * Client bound to one authenticated patient (PAT-02).
 *
 * @param transport protected handout transport
 * @param tokens OS-backed session credential store
 * @param patientId verified identity of the patient this client is bound to
 * @throws Error with code `INVALID_PATIENT` when the patient identity is blank
 */
export class HandoutClient {
  constructor(
    private readonly transport: HandoutTransport,
    private readonly tokens: TokenStore,
    private readonly patientId: string,
  ) {
    // Without a verified patient identity the WRONG_PATIENT check would compare
    // against a meaningless value. Fail closed at construction.
    if (!isText(patientId)) {
      throw new Error('INVALID_PATIENT');
    }
  }

  /** List handouts currently released to the authenticated patient. */
  async listHandouts(): Promise<ReleasedHandout[]> {
    const token = await this.requireToken();
    const body = await this.transport.getJson(HANDOUT_PATH, token);
    if (!Array.isArray(body)) {
      throw new Error('MALFORMED_RESPONSE');
    }
    return releasedOnly(body.map(parseReleasedHandout));
  }

  /** Ask the server to authorize a download of one exact handout version. */
  async authorizeDownload(handoutId: string, version: number): Promise<HandoutAuthorization> {
    if (!isText(handoutId)) {
      throw new Error('INVALID_HANDOUT_ID');
    }
    if (!isVersion(version)) {
      throw new Error('INVALID_VERSION');
    }
    const token = await this.requireToken();
    const body = await this.transport.postJson(
      `${HANDOUT_PATH}/${encodeURIComponent(handoutId)}/download`,
      token,
      { version },
    );
    return parseAuthorization(body, handoutId, version, this.patientId);
  }

  /** Read the protected credential at the moment of each command. */
  private async requireToken(): Promise<string> {
    const token = await this.tokens.read();
    if (!isText(token)) {
      throw new Error('NOT_AUTHENTICATED');
    }
    return token;
  }
}

/** Result of loading the handout list for display. */
export interface HandoutLoadResult {
  readonly handouts: ReleasedHandout[];
  readonly error: string | null;
}

/**
 * Load handouts for the screen, replacing any failure with a generic message.
 *
 * A failed load always yields an empty list: clinical content is never partially
 * cached or shown from a stale response.
 *
 * @param client handout client bound to the authenticated patient
 * @param locale locale used for the generic failure message
 */
export async function loadHandouts(client: HandoutClient, locale: Locale = 'en'): Promise<HandoutLoadResult> {
  try {
    return { handouts: await client.listHandouts(), error: null };
  } catch {
    return { handouts: [], error: handoutText(locale, 'loadError') };
  }
}
