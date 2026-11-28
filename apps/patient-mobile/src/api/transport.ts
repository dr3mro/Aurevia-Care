/**
 * Authenticated JSON transport for the patient app (PAT-01, ARC-03).
 *
 * Every call reads the current credential, sends an opaque correlation id and
 * a bounded abort deadline, and converts any failure into a typed error whose
 * message is derived only from the failure kind. Response bodies and tokens
 * are never copied into error messages or logs.
 */

/** The distinct failure modes a patient screen can present. */
export type TransportErrorKind =
  | 'network'
  | 'timeout'
  | 'unauthenticated'
  | 'forbidden'
  | 'conflict'
  | 'validation'
  | 'server';

/** One server-reported field problem on an invalid command. */
export interface FieldError {
  field: string;
  message: string;
}

/** Diagnostic metadata that is safe to carry alongside a typed failure. */
export interface TransportErrorDetails {
  correlationId: string;
  code?: string;
  fieldErrors?: FieldError[];
}

/** Generate an opaque request id, using the platform UUID when available. */
export function newCorrelationId(): string {
  try {
    return (globalThis.crypto as { randomUUID(): string }).randomUUID();
  } catch {
    const time = Date.now().toString(16);
    const entropy = Math.floor(Math.random() * 0xffffffff)
      .toString(16)
      .padStart(8, '0');
    return `${time}-${entropy}`;
  }
}

/**
 * A failed API call. The message is always derived from the kind, so neither
 * the request token nor untrusted response text can reach logs or the UI
 * through it.
 *
 * Callers that already have a request correlation id must pass it. When details
 * omit ``correlationId`` (tests, wrapping an unexpected throw), the error leaves
 * it undefined rather than inventing a server request reference.
 */
export class TransportError extends Error {
  readonly kind: TransportErrorKind;
  readonly correlationId: string | undefined;
  readonly code: string | undefined;
  readonly fieldErrors: readonly FieldError[];

  constructor(kind: TransportErrorKind, details: Partial<TransportErrorDetails> = {}) {
    super(`Request failed: ${kind}`);
    this.name = 'TransportError';
    this.kind = kind;
    this.correlationId = details.correlationId;
    this.code = details.code;
    this.fieldErrors = details.fieldErrors ?? [];
  }
}

/** Prefer a typed transport failure; wrap anything else as ``kind``. */
export function asTransportError(
  error: unknown,
  kind: TransportErrorKind = 'network',
): TransportError {
  if (error instanceof TransportError) {
    return error;
  }
  return new TransportError(kind, {});
}

/** Supplies the current session credential immediately before each request. */
export type TokenProvider = () => string | null | undefined | Promise<string | null | undefined>;

/** Minimal response surface used by the transport; the platform fetch fits it. */
export interface HttpResponse {
  ok: boolean;
  status: number;
  headers: { get(name: string): string | null };
  json(): Promise<unknown>;
}

/** Injectable fetch seam so tests can supply synthetic responses. */
export type FetchImpl = (url: string, init: RequestInit) => Promise<HttpResponse>;

/** Configuration for one authenticated API transport. */
export interface TransportConfig {
  /** API origin; HTTPS is required outside development. Paths are joined with one slash. */
  baseUrl: string;
  /** Read the credential for each request so revocation takes effect immediately. */
  getToken: TokenProvider;
  /** Fetch implementation; tests inject a synthetic one. */
  fetchImpl: FetchImpl;
  /** Value of Accept-Language; defaults to 'en'. */
  language?: string;
}

/** Versioned JSON operations exposed by the transport. */
export interface Transport {
  get<T = unknown>(path: string): Promise<T>;
  post<T = unknown>(
    path: string,
    body?: unknown,
    headers?: Readonly<Record<string, string>>,
  ): Promise<T>;
  put<T = unknown>(path: string, body?: unknown): Promise<T>;
}

interface ResolvedConfig {
  baseUrl: string;
  getToken: TokenProvider;
  fetchImpl: FetchImpl;
  language: string;
}

interface ErrorBody {
  code?: unknown;
  fieldErrors?: unknown;
}

const CORRELATION_HEADER = 'X-Correlation-Id';

/** Header names the transport owns; callers may not supply any case variant. */
const RESERVED_HEADERS = new Set([
  'authorization',
  'x-correlation-id',
  'accept',
  'accept-language',
  'content-type',
]);
const TIMEOUT_MS = 15_000;
const DEV_LOCALHOST = /^http:\/\/localhost(?::\d+)?(?:\/|$)/;
/** Only bounded opaque identifiers are accepted from a response header. */
const SAFE_CORRELATION_ID = /^[A-Za-z0-9._:-]{1,64}$/;

/** Narrow an untrusted JSON value to a plain object. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Narrow one untrusted entry to a well-formed field error. */
function isFieldError(value: unknown): value is FieldError {
  if (!isRecord(value)) {
    return false;
  }
  return typeof value.field === 'string' && typeof value.message === 'string';
}

/** Keep only well-formed field errors from an untrusted server payload. */
function parseFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const errors: FieldError[] = [];
  for (const entry of value) {
    if (isFieldError(entry)) {
      errors.push({ field: entry.field, message: entry.message });
    }
  }
  return errors;
}

/** Map an HTTP status to the closest patient-facing failure kind. */
function kindForStatus(status: number): TransportErrorKind {
  if (status === 401) {
    return 'unauthenticated';
  }
  if (status === 403) {
    return 'forbidden';
  }
  if (status === 409 || status === 412) {
    return 'conflict';
  }
  if (status === 422) {
    return 'validation';
  }
  return 'server';
}

/**
 * Extract only structured metadata from an error body. Free-text fields are
 * deliberately ignored so they cannot become error messages or log content.
 */
async function readErrorDetails(response: HttpResponse): Promise<{
  code: string | undefined;
  fieldErrors: FieldError[];
}> {
  try {
    const body = (await response.json()) as ErrorBody | null;
    const code = typeof body?.code === 'string' ? body.code : undefined;
    return { code, fieldErrors: parseFieldErrors(body?.fieldErrors) };
  } catch {
    return { code: undefined, fieldErrors: [] };
  }
}

/** Build a typed error for a non-ok response without copying its body text. */
async function toTransportError(
  response: HttpResponse,
  requestCorrelationId: string,
): Promise<TransportError> {
  const suppliedId = response.headers.get(CORRELATION_HEADER);
  // A peer must not be able to place arbitrary text (for example an echoed
  // credential) into an error object that callers may log.
  const responseCorrelationId =
    suppliedId !== null && SAFE_CORRELATION_ID.test(suppliedId) ? suppliedId : requestCorrelationId;
  const details = await readErrorDetails(response);
  const transportErrorDetails: TransportErrorDetails = { correlationId: responseCorrelationId };
  if (details.code !== undefined) {
    transportErrorDetails.code = details.code;
  }
  transportErrorDetails.fieldErrors = details.fieldErrors;
  return new TransportError(kindForStatus(response.status), transportErrorDetails);
}

/** Fail closed on any origin that is not HTTPS, except development localhost. */
function assertSecureBaseUrl(baseUrl: string): void {
  if (baseUrl.startsWith('https://')) {
    return;
  }
  const isDevelopment = (globalThis as { __DEV__?: boolean }).__DEV__ === true;
  if (isDevelopment && DEV_LOCALHOST.test(baseUrl)) {
    return;
  }
  throw new Error('Transport base URL must use HTTPS (localhost is allowed only in development)');
}

/** Perform one credentialed request with a bounded deadline. */
async function send<T>(
  config: ResolvedConfig,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body: unknown,
  // Caller-supplied conditional headers (If-Match, Idempotency-Key). The
  // credential and correlation headers are applied after these, so an untrusted
  // caller cannot override the authenticated identity or request tracing.
  extraHeaders: Readonly<Record<string, string>> = {},
): Promise<T> {
  const correlationId = newCorrelationId();
  let controller: AbortController | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const token = await config.getToken();
    if (typeof token !== 'string' || token.trim().length === 0) {
      throw new TransportError('unauthenticated', { correlationId });
    }

    // Fetch header names are case-insensitive: a lower-case forgery would
    // otherwise become a second key that the runtime merges with ours.
    const safeExtra: Record<string, string> = {};
    for (const [name, value] of Object.entries(extraHeaders)) {
      if (!RESERVED_HEADERS.has(name.toLowerCase())) {
        safeExtra[name] = value;
      }
    }
    const headers: Record<string, string> = { ...safeExtra };
    headers.Authorization = `Bearer ${token}`;
    headers['Accept-Language'] = config.language;
    headers.Accept = 'application/json';
    headers[CORRELATION_HEADER] = correlationId;
    let serialized: string | undefined;
    if (body !== undefined) {
      serialized = JSON.stringify(body);
      headers['Content-Type'] = 'application/json';
    }

    const url = `${config.baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
    controller = new AbortController();
    timer = setTimeout(() => controller?.abort(), TIMEOUT_MS);
    const requestInit: RequestInit = {
      method,
      headers,
      signal: controller.signal,
    };
    if (serialized !== undefined) {
      requestInit.body = serialized;
    }
    const response = await config.fetchImpl(url, requestInit);
    if (!response.ok) {
      throw await toTransportError(response, correlationId);
    }
    return (await response.json()) as T;
  } catch (error) {
    // A typed HTTP failure must survive even if the deadline expires while its
    // error body is being read.
    if (error instanceof TransportError) {
      throw error;
    }
    if (controller?.signal.aborted === true) {
      throw new TransportError('timeout', { correlationId });
    }
    throw new TransportError('network', { correlationId });
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }
}

/**
 * Create a transport bound to one API origin.
 *
 * @returns GET, POST and PUT operations that parse JSON on success and throw
 * `TransportError` with a typed kind on every failure.
 */
export function createTransport(config: TransportConfig): Transport {
  assertSecureBaseUrl(config.baseUrl);
  const resolved: ResolvedConfig = { ...config, language: config.language ?? 'en' };
  return {
    get: <T>(path: string) => send<T>(resolved, 'GET', path, undefined),
    post: <T>(path: string, body?: unknown, extraHeaders?: Readonly<Record<string, string>>) =>
      send<T>(resolved, 'POST', path, body, extraHeaders),
    put: <T>(path: string, body?: unknown) => send<T>(resolved, 'PUT', path, body),
  };
}
