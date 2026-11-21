import type {
  DownloadAuthorization,
  ReleasedReport,
  ReportLanguage,
  ReportTransport,
  TokenStore,
} from './types';

/** Protected report resource family. */
const REPORT_PATH = '/api/v1/released-reports';

/** Narrow a decoded JSON value to a non-empty string. */
function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Narrow a decoded JSON value to a positive integer revision. */
function isRevision(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1;
}

/** Narrow a decoded JSON value to a supported document language. */
function isLanguage(value: unknown): value is ReportLanguage {
  return value === 'en' || value === 'ar';
}

/** Narrow a decoded JSON value to a plain object. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Normalize correction context to a complete non-empty pair or to no correction at all.
 *
 * Returning typed values keeps the parsed projection free of `unknown` fields while rejecting
 * asymmetric or empty context that would render a misleading correction banner.
 */
function normalizeCorrection(
  reason: unknown,
  supersedes: unknown,
): { reason: string | null; supersedes: number | null } {
  if (reason === null && supersedes === null) {
    return { reason: null, supersedes: null };
  }
  if (isText(reason) && isRevision(supersedes)) {
    return { reason, supersedes };
  }
  throw new Error('MALFORMED_REPORT');
}

/**
 * Parse a patient-visible report projection, rejecting malformed or unexpected payloads.
 *
 * Validation happens at the client trust boundary so a tampered response cannot be rendered as a
 * clinical document reference.
 */
export function parseReleasedReport(value: unknown): ReleasedReport {
  if (!isRecord(value)) {
    throw new Error('MALFORMED_REPORT');
  }
  const { reportId, revision, issuingOrganization, effectiveDate, language, correctionReason, supersedesRevision } = value;
  if (!isText(reportId)
      || !isRevision(revision)
      || !isText(issuingOrganization)
      || typeof effectiveDate !== 'string'
      || !/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate)
      || !isLanguage(language)) {
    throw new Error('MALFORMED_REPORT');
  }
  const correction = normalizeCorrection(correctionReason, supersedesRevision);
  return {
    reportId,
    revision,
    issuingOrganization,
    effectiveDate,
    language,
    correctionReason: correction.reason,
    supersedesRevision: correction.supersedes,
  };
}

/**
 * Parse a server-issued download authorization and bind it to the requested revision.
 *
 * A well-formed authorization for another report or revision is not valid for this tap.
 */
function parseAuthorization(value: unknown, expectedReportId: string, expectedRevision: number): DownloadAuthorization {
  if (!isRecord(value)) {
    throw new Error('MALFORMED_AUTHORIZATION');
  }
  const { reportId, revision, documentId, checksum, language } = value;
  if (!isText(reportId) || !isRevision(revision) || !isText(documentId) || !isText(checksum)
      || !isLanguage(language)) {
    throw new Error('MALFORMED_AUTHORIZATION');
  }
  if (reportId !== expectedReportId) {
    throw new Error('AUTHORIZATION_MISMATCH');
  }
  if (revision !== expectedRevision) {
    throw new Error('AUTHORIZATION_MISMATCH');
  }
  return { reportId, revision, documentId, checksum, language };
}

/** The narrow capability the download confirmation flow needs from a client. */
export type DownloadAuthorizer = Pick<ReleasedReportClient, 'authorizeDownload'>;

/**
 * Typed patient report client (PAT-02).
 *
 * Every command reads the OS-backed credential immediately before the request and holds no
 * clinical state between calls. A download requires a fresh server authorization for the exact
 * revision; a failed authorization never produces a document reference.
 */
export class ReleasedReportClient {
  /** Create the client from a transport and protected credential store. */
  constructor(
    private readonly transport: ReportTransport,
    private readonly tokens: TokenStore,
  ) {}

  /** List the releases currently visible to the authenticated patient. */
  async listReleasedReports(): Promise<ReleasedReport[]> {
    const token = await this.requireToken();
    const body = await this.transport.getJson(REPORT_PATH, token);
    if (!Array.isArray(body)) {
      throw new Error('MALFORMED_RESPONSE');
    }
    return body.map(parseReleasedReport);
  }

  /** Ask the server to authorize a download of one exact revision. */
  async authorizeDownload(reportId: string, revision: number): Promise<DownloadAuthorization> {
    if (!isText(reportId)) {
      throw new Error('INVALID_REPORT_ID');
    }
    if (!isRevision(revision)) {
      throw new Error('INVALID_REVISION');
    }
    const token = await this.requireToken();
    const body = await this.transport.postJson(
      `${REPORT_PATH}/${encodeURIComponent(reportId)}/download`,
      token,
      { revision },
    );
    return parseAuthorization(body, reportId, revision);
  }

  private async requireToken(): Promise<string> {
    const token = await this.tokens.read();
    if (!isText(token)) {
      throw new Error('NOT_AUTHENTICATED');
    }
    return token;
  }
}

/** Result of loading the report list for display. */
export interface ReportLoadResult {
  readonly reports: ReleasedReport[];
  readonly error: string | null;
}

/**
 * Load reports for the screen, replacing any failure with a generic message.
 *
 * Clinical content is never partially cached: a failed load always yields an empty list.
 */
export async function loadReleasedReports(client: ReleasedReportClient): Promise<ReportLoadResult> {
  try {
    return { reports: await client.listReleasedReports(), error: null };
  } catch {
    return { reports: [], error: 'Unable to load released reports' };
  }
}
