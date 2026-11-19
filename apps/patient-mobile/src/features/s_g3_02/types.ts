/**
 * Patient report access contracts (PAT-02, ARC-07).
 *
 * These types describe only the patient-visible projection of a released report. Rendered clinical
 * bytes and credentials are never placed in these objects, so the app has no persistent clinical
 * cache to leak.
 */

/** Language of the immutable rendered report. */
export type ReportLanguage = 'en' | 'ar';

/**
 * One released revision as shown in the patient inbox.
 *
 * Correction context is part of the projection so a patient can tell a corrected revision from the
 * original without opening the document (PAT-02, XCH-06).
 */
export interface ReleasedReport {
  readonly reportId: string;
  readonly revision: number;
  readonly issuingOrganization: string;
  readonly effectiveDate: string;
  readonly language: ReportLanguage;
  readonly correctionReason: string | null;
  readonly supersedesRevision: number | null;
}

/**
 * Server-issued authority to fetch one exact document revision.
 *
 * The client never derives this locally: every download is authorized by the server against the
 * current verified link and current release (PAT-02).
 */
export interface DownloadAuthorization {
  readonly reportId: string;
  readonly revision: number;
  readonly documentId: string;
  readonly checksum: string;
  readonly language: ReportLanguage;
}

/** Minimal typed transport; the production adapter supplies the authenticated HTTP client. */
export interface ReportTransport {
  /** Fetch JSON from a protected GET route. */
  getJson(path: string, token: string): Promise<unknown>;
  /** Send JSON to a protected command route. */
  postJson(path: string, token: string, body: unknown): Promise<unknown>;
}

/** OS-backed credential storage boundary. */
export interface TokenStore {
  /** Read the current session credential, or {@code null} when absent. */
  read(): Promise<string | null>;
  /** Store a credential using OS-backed protection. */
  save(token: string): Promise<void>;
  /** Remove the stored credential. */
  clear(): Promise<void>;
}
