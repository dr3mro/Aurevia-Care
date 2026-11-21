

const CORRELATION_HEADER = 'X-Correlation-Id';
const DEFAULT_TIMEOUT_MS = 15_000;


/** Failure modes the patient app surfaces for a failed request. */
export type TransportErrorKind =
  | 'network'
  | 'timeout'
  | 'unauthenticated'
  | 'forbidden'
  | 'conflict'
  | 'validation'
  | 'server';

/** Field-level validation errors recovered from a 422 response. */
export type TransportFieldErrors = Record<string, string[]>;

export class TransportError extends Error {
  readonly kind: TransportErrorKind;
  readonly correlationId: string;
  readonly status: number | undefined;
  readonly fieldErrors: TransportFieldErrors | undefined;

  constructor(
    kind: TransportErrorKind,
    correlationId: string,
    status?: number,
    fieldErrors?: TransportFieldErrors,
  ) {
    super(`Request failed: ${kind}`);
    this.name = 'TransportError';
    this.kind = kind;
    this.correlationId = correlationId;
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

export interface CreateTransportOptions {
  baseUrl: string;
  getToken: () => string | null | Promise<string | null>;
  fetchImpl?: typeof fetch;
  language?: string;
}

export interface TransportRequestOptions {
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface Transport {
  get<T = unknown>(path: string, options?: TransportRequestOptions): Promise<T>;
  post<T = unknown>(path: string, body?: unknown, options?: TransportRequestOptions): Promise<T>;
  put<T = unknown>(path: string, body?: unknown, options?: TransportRequestOptions): Promise<T>;
}

function joinUrl(baseUrl: string, path: string): string {
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${base}${suffix}`;
}

function isDevEnvironment(): boolean {
  const flag = (globalThis as { __DEV__?: unknown }).__DEV__;
  return flag === true;
}

function validateBaseUrl(baseUrl: string): void {
  if (baseUrl.startsWith('https://')) {
    return;
  }
  if (isDevEnvironment() && baseUrl.startsWith('http://localhost')) {
    return;
  }
  throw new Error('baseUrl must use https://');
}

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
  if (status >= 500) {
    return 'server';
  }
  return 'server';
}

async function parseFieldErrors(response: Response): Promise<TransportFieldErrors | undefined> {
  try {
    const body = (await response.json()) as { errors?: unknown; fieldErrors?: unknown } | null;
    const candidate = body?.fieldErrors ?? body?.errors;
    if (candidate !== null && typeof candidate === 'object') {
      return candidate as TransportFieldErrors;
    }
    return undefined;
  } catch {
    return undefined;
  }
}

export function createTransport(options: CreateTransportOptions): Transport {
  validateBaseUrl(options.baseUrl);
  const fetchImpl = options.fetchImpl ?? fetch;
  const language = options.language ?? 'en';

  async function send(method: string, path: string, body: unknown, requestOptions: TransportRequestOptions = {}): Promise<unknown> {
    const token = await options.getToken();
    const correlationId =
      typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `corr-${Date.now()}-${Math.random().toString(16).slice(2)}`;

    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Accept-Language': language,
      [CORRELATION_HEADER]: correlationId,
      ...(requestOptions.headers ?? {}),
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);

    let response: Response;
    try {
      const init: RequestInit = {
        method,
        headers,
        signal: controller.signal,
      };
      if (body !== undefined) {
        init.body = JSON.stringify(body);
      }
      response = await fetchImpl(joinUrl(options.baseUrl, path), init);
    } catch {
      clearTimeout(timer);
      if (controller.signal.aborted) {
        throw new TransportError('timeout', correlationId);
      }
      throw new TransportError('network', correlationId);
    } finally {
      // keep timer cleared in try success path too
    }
    // clear timer after response arrived
    clearTimeout(timer);

    if (!response.ok) {
      if (response.status === 422) {
        const fieldErrors = await parseFieldErrors(response);
        throw new TransportError('validation', correlationId, response.status, fieldErrors);
      }
      throw new TransportError(kindForStatus(response.status), correlationId, response.status);
    }

    if (response.status === 204) {
      return undefined;
    }
    try {
      return await response.json();
    } catch {
      return undefined;
    }
  }

  return {
    get: <T>(path: string, requestOptions?: TransportRequestOptions) => send('GET', path, undefined, requestOptions) as Promise<T>,
    post: <T>(path: string, body?: unknown, requestOptions?: TransportRequestOptions) => send('POST', path, body, requestOptions) as Promise<T>,
    put: <T>(path: string, body?: unknown, requestOptions?: TransportRequestOptions) => send('PUT', path, body, requestOptions) as Promise<T>,
  };
}
