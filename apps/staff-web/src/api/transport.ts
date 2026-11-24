import type { RequestDescriptor } from './client';

const CORRELATION_HEADER = 'X-Correlation-Id';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const DEFAULT_TIMEOUT_MS = 15_000;

/**
 * The distinct failure modes a staff screen can be asked to present (WEB-09).
 * Each value maps to a state view; none carries untrusted response text.
 */
export type TransportErrorKind =
  | 'csrf-missing'
  | 'session-expired'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'invalid'
  | 'precondition-required'
  | 'rate-limited'
  | 'server'
  | 'offline'
  | 'timeout';

/** Correlation, machine code and retry hint recovered from a failed call. */
export interface TransportErrorDetails {
  correlationId: string | undefined;
  code: string | undefined;
  retryAfterSeconds: number | undefined;
}

/**
 * A failed API call. The message is always derived from the kind, so response
 * body text is never copied into logs or UI through the error message.
 */
export class TransportError extends Error {
  readonly kind: TransportErrorKind;
  readonly correlationId: string | undefined;
  readonly code: string | undefined;
  readonly retryAfterSeconds: number | undefined;

  constructor(kind: TransportErrorKind, details: TransportErrorDetails) {
    super(`Request failed: ${kind}`);
    this.name = 'TransportError';
    this.kind = kind;
    this.correlationId = details.correlationId;
    this.code = details.code;
    this.retryAfterSeconds = details.retryAfterSeconds;
  }
}

/** Optional tuning for the transport; the default abort deadline is 15 s. */
export interface SendOptions {
  timeoutMs?: number;
  /**
   * Caller-owned cancellation signal. Aborting it cancels the underlying
   * fetch and suppresses the GET retry, because a deliberate cancellation
   * must never re-issue the request. An already-aborted signal rejects
   * before any fetch is issued. A caller abort rejects with a
   * `TransportError` of kind 'timeout'; callers that offer a retry affordance
   * must check `signal.aborted` before presenting it.
   */
  signal?: AbortSignal;
}

/** Map an HTTP status to the closest user-facing failure kind (WEB-09). */
function kindForStatus(status: number): TransportErrorKind {
  switch (status) {
    case 401:
      return 'session-expired';
    case 403:
      return 'forbidden';
    case 404:
      return 'not-found';
    case 409:
    case 412:
      return 'conflict';
    case 422:
      return 'invalid';
    case 428:
      return 'precondition-required';
    case 429:
      return 'rate-limited';
    default:
      // Any other non-ok response is an unexpected server-side condition.
      return 'server';
  }
}

/** Read the XSRF-TOKEN cookie value, or undefined when it is absent. */
function csrfToken(): string | undefined {
  const entry = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${CSRF_COOKIE}=`));
  const token = entry?.slice(CSRF_COOKIE.length + 1).trim();
  // A blank token is an absent token: fail closed before any write is sent.
  return token === undefined || token === '' ? undefined : token;
}

/** Parse a numeric Retry-After header (seconds); ignore other formats. */
function retryAfterSeconds(value: string | null): number | undefined {
  if (value === null) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) ? seconds : undefined;
}

/** Extract the machine-readable error code without ever exposing body text. */
async function errorCode(response: Response): Promise<string | undefined> {
  try {
    const body = (await response.json()) as { code?: unknown } | null;
    const code = body?.code;
    return typeof code === 'string' ? code : undefined;
  } catch {
    return undefined;
  }
}

/** Build a typed error for a non-ok response, preserving diagnostic metadata. */
async function toTransportError(
  response: Response,
  requestCorrelationId: string,
): Promise<TransportError> {
  const correlationId = response.headers.get(CORRELATION_HEADER) ?? requestCorrelationId;
  const code = await errorCode(response);
  const retryAfter =
    response.status === 429 ? retryAfterSeconds(response.headers.get('Retry-After')) : undefined;
  return new TransportError(kindForStatus(response.status), {
    correlationId,
    code,
    retryAfterSeconds: retryAfter,
  });
}

/**
 * Relay caller cancellation into the transport's own controller and return a
 * cleanup that detaches the listener. Keeping the relay in one place means the
 * real fetch has a single abort signal whether the deadline or the caller
 * fires first; no listener is left on a signal the caller may reuse.
 */
function linkCallerAbort(
  controller: AbortController,
  externalSignal?: AbortSignal,
): () => void {
  const relayAbort = (): void => {
    controller.abort();
  };
  if (externalSignal !== undefined) {
    externalSignal.addEventListener('abort', relayAbort, { once: true });
  }
  return () => {
    externalSignal?.removeEventListener('abort', relayAbort);
  };
}

/** Perform one credentialed fetch with CSRF, timeout and typed failures. */
async function attempt(
  descriptor: RequestDescriptor,
  correlationId: string,
  timeoutMs: number,
  externalSignal?: AbortSignal,
): Promise<unknown> {
  // A cancelled request short-circuits before CSRF validation and before any
  // network call. It intentionally takes precedence over csrf-missing: a
  // cancelled request needs no cookie read and issues no fetch at all. The
  // abort failure deliberately uses the timeout kind so callers see one
  // "this fetch is no longer wanted" failure mode for every cancellation.
  if (externalSignal?.aborted === true) {
    throw new TransportError('timeout', {
      correlationId,
      code: undefined,
      retryAfterSeconds: undefined,
    });
  }
  const headers: Record<string, string> = {
    ...descriptor.headers,
    [CORRELATION_HEADER]: correlationId,
  };
  if (descriptor.method !== 'GET') {
    const token = csrfToken();
    if (token === undefined) {
      throw new TransportError('csrf-missing', {
        correlationId,
        code: undefined,
        retryAfterSeconds: undefined,
      });
    }
    headers[CSRF_HEADER] = token;
  }
  const body = descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body);
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  const controller = new AbortController();
  const unlinkCallerAbort = linkCallerAbort(controller, externalSignal);
  const init: RequestInit = {
    method: descriptor.method,
    headers,
    credentials: 'include',
    signal: controller.signal,
  };
  if (body !== undefined) {
    init.body = body;
  }

  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(descriptor.url, init);
    if (!response.ok) {
      throw await toTransportError(response, correlationId);
    }
    return await response.json();
  } catch (error) {
    if (controller.signal.aborted) {
      throw new TransportError('timeout', {
        correlationId,
        code: undefined,
        retryAfterSeconds: undefined,
      });
    }
    if (error instanceof TransportError) {
      throw error;
    }
    throw new TransportError('offline', {
      correlationId,
      code: undefined,
      retryAfterSeconds: undefined,
    });
  } finally {
    clearTimeout(timer);
    unlinkCallerAbort();
  }
}

/**
 * Send a versioned API request with credentialed fetch, CSRF protection for
 * writes, a bounded abort deadline and typed failures. Only GET requests are
 * retried, once, on a network failure or timeout, and only while the caller's
 * signal is not aborted; writes are never retried. An already-aborted signal
 * rejects before any fetch is issued.
 *
 * @returns The parsed JSON body of a successful response.
 * @throws TransportError for every failure, with the failure `kind` and the
 * correlation id, server error code and retry hint where available.
 */
export async function send<T = unknown>(
  descriptor: RequestDescriptor,
  options?: SendOptions,
): Promise<T> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const externalSignal = options?.signal;
  const correlationId = descriptor.headers[CORRELATION_HEADER] ?? crypto.randomUUID();
  try {
    return (await attempt(descriptor, correlationId, timeoutMs, externalSignal)) as T;
  } catch (error) {
    // attempt() normalises every failure into a TransportError before it escapes.
    const failure = error as TransportError;
    // A caller-initiated abort is deliberate: never re-issue a cancelled GET.
    const callerCancelled = externalSignal?.aborted === true;
    if (
      descriptor.method === 'GET' &&
      !callerCancelled &&
      (failure.kind === 'offline' || failure.kind === 'timeout')
    ) {
      return (await attempt(descriptor, correlationId, timeoutMs, externalSignal)) as T;
    }
    throw error;
  }
}
