/**
 * Patient handout contracts (PAT-02, CLN-07).
 *
 * A handout is an immutable, versioned PDF rendered by the clinician from a
 * committed clinical source. This projection carries only the metadata a patient
 * needs to recognise and request a copy: rendered bytes, credentials and other
 * clinical content are never part of it, so the app keeps no clinical cache.
 */

/** Patient-visible kinds of released handout (CLN-07). */
export type HandoutKind = 'prescription' | 'order-sheet' | 'chart-summary';

/** Language of the immutable rendered handout. */
export type HandoutLanguage = 'en' | 'ar';

/**
 * Visibility of a handout in the patient list (PAT-02, XCH-06).
 *
 * A withheld handout has not been released to this patient. It is rejected at
 * the client trust boundary and never reaches the screen, even if a server
 * response includes it.
 */
export type HandoutReleaseState = 'released' | 'withheld';

/** Lifecycle of the exact handout version at download time. */
export type HandoutAuthorizationState = 'current' | 'withdrawn';

/** The newer version that replaced this one, with the clinician's reason. */
export interface HandoutSupersededBy {
  readonly version: number;
  readonly reason: string;
}

/**
 * One released handout revision as shown in the patient inbox.
 *
 * `supersededBy` is non-null when a newer version has replaced this one: the
 * older handout is marked Corrected and the correction context is shown
 * (PAT-02). The immutable PDF itself is fetched only after a fresh download
 * authorization.
 */
export interface ReleasedHandout {
  readonly handoutId: string;
  readonly version: number;
  readonly kind: HandoutKind;
  readonly issuingOrganization: string;
  /** Civil date the handout was released, `YYYY-MM-DD` (UX-01). */
  readonly releasedDate: string;
  readonly language: HandoutLanguage;
  readonly releaseState: HandoutReleaseState;
  readonly supersededBy: HandoutSupersededBy | null;
}

/**
 * Server-issued authority to fetch one exact handout version (PAT-02, ARC-06).
 *
 * The client never derives this locally. Every download is authorized against
 * the current verified patient link and current release, and carries the source
 * checksum so the fetched bytes can be checked for integrity (CLN-07).
 */
export interface HandoutAuthorization {
  readonly handoutId: string;
  readonly version: number;
  readonly documentId: string;
  readonly checksum: string;
  readonly language: HandoutLanguage;
  /** Patient the document is linked to; must equal the authenticated patient. */
  readonly patientId: string;
  /** A non-current release grants no access. */
  readonly releaseState: HandoutAuthorizationState;
}
