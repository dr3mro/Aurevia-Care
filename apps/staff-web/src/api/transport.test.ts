import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from './client';
import { TransportError, send } from './transport';

const generatedCorrelationId = '00000000-0000-0000-0000-0000000000aa';
const randomUuid = vi.fn<typeof crypto.randomUUID>(() => generatedCorrelationId);
let cookieValue = '';

// Deterministic cookie source: the transport reads document.cookie, so tests
// control its exact value instead of depending on jsdom's cookie jar.
Object.defineProperty(document, 'cookie', {
  configurable: true,
  get: () => cookieValue,
});

function freshFetchMock() {
  return vi.fn<typeof fetch>();
}
let fetchMock = freshFetchMock();

function descriptor(
  method: 'GET' | 'POST',
  overrides: Partial<RequestDescriptor> = {},
): RequestDescriptor {
  return {
    url: '/api/v1/facilities',
    method,
    headers: {},
    ...overrides,
  };
}

function responseParts(status: number, body: unknown = {}, headers: Record<string, string> = {}) {
  const lower = new Map(
    Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]),
  );
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => lower.get(name.toLowerCase()) ?? null },
    json: () => Promise.resolve(body),
  };
}

function response(
  status: number,
  body: unknown = {},
  headers: Record<string, string> = {},
): Response {
  return responseParts(status, body, headers) as unknown as Response;
}

function initAt(index: number): RequestInit {
  const init = fetchMock.mock.calls[index]?.[1];
  if (init === undefined) {
    throw new Error(`no fetch init at call ${index}`);
  }
  return init;
}

function headersOf(init: RequestInit): Record<string, string> {
  return init.headers as Record<string, string>;
}

beforeEach(() => {
  cookieValue = 'theme=dark; XSRF-TOKEN=csrf-token; lang=en';
  fetchMock = freshFetchMock();
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(randomUuid);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('send — request construction', () => {
  it('sends a GET with credentials, a generated correlation id, no CSRF header and no body', async () => {
    cookieValue = '';
    const body = { facilities: [] };
    fetchMock.mockResolvedValue(response(200, body));

    await expect(send(descriptor('GET'))).resolves.toEqual(body);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const init = initAt(0);
    expect(init.method).toBe('GET');
    expect(init.credentials).toBe('include');
    expect(headersOf(init)['X-Correlation-Id']).toBe(generatedCorrelationId);
    expect(headersOf(init)['X-XSRF-TOKEN']).toBeUndefined();
    expect(init.body).toBeUndefined();
    expect(randomUuid).toHaveBeenCalledTimes(1);
  });

  it('reuses a correlation id already present on the descriptor', async () => {
    fetchMock.mockResolvedValue(response(200, {}));

    await send(descriptor('GET', { headers: { 'X-Correlation-Id': 'provided-id' } }));

    expect(headersOf(initAt(0))['X-Correlation-Id']).toBe('provided-id');
    expect(randomUuid).not.toHaveBeenCalled();
  });

  it('generates a fresh correlation id on every call', async () => {
    randomUuid
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000001')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000002');
    fetchMock.mockResolvedValue(response(200, {}));

    await send(descriptor('GET'));
    await send(descriptor('GET'));

    expect([
      headersOf(initAt(0))['X-Correlation-Id'],
      headersOf(initAt(1))['X-Correlation-Id'],
    ]).toEqual(['00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002']);
  });

  it('adds the CSRF header, JSON body and content type for a POST', async () => {
    fetchMock.mockResolvedValue(response(200, { ok: true }));

    await send(descriptor('POST', { body: { name: 'Synthetic Clinic' } }));

    const init = initAt(0);
    expect(headersOf(init)['X-XSRF-TOKEN']).toBe('csrf-token');
    expect(headersOf(init)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ name: 'Synthetic Clinic' }));
  });

  it('throws csrf-missing before sending when the cookie is absent', async () => {
    cookieValue = '';

    await expect(send(descriptor('POST', { body: {} }))).rejects.toMatchObject({
      kind: 'csrf-missing',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('throws csrf-missing before sending when the cookie is blank', async () => {
    cookieValue = 'XSRF-TOKEN=';

    await expect(send(descriptor('POST', { body: {} }))).rejects.toMatchObject({
      kind: 'csrf-missing',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe.each([
  [401, 'session-expired'],
  [403, 'forbidden'],
  [404, 'not-found'],
  [409, 'conflict'],
  [412, 'conflict'],
  [422, 'invalid'],
  [428, 'precondition-required'],
  [500, 'server'],
  [400, 'server'],
] as const)('send — status %i', (status, kind) => {
  it(`maps ${status} to ${kind} and keeps code and response correlation id`, async () => {
    fetchMock.mockResolvedValue(
      response(status, { code: 'SOME_CODE' }, { 'X-Correlation-Id': 'resp-id' }),
    );

    await expect(send(descriptor('POST', { body: {} }))).rejects.toMatchObject({
      kind,
      code: 'SOME_CODE',
      correlationId: 'resp-id',
    });
  });
});

describe('send — typed errors', () => {
  it('throws an instance of TransportError for an HTTP failure', async () => {
    fetchMock.mockResolvedValue(response(403, {}));

    const error = await send(descriptor('GET')).catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(TransportError);
  });

  it('falls back to the request correlation id when the response omits it', async () => {
    fetchMock.mockResolvedValue(response(403, {}));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({
      kind: 'forbidden',
      correlationId: generatedCorrelationId,
    });
  });

  it('maps 429 to rate-limited with retryAfterSeconds from Retry-After', async () => {
    fetchMock.mockResolvedValue(response(429, {}, { 'Retry-After': '5' }));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({
      kind: 'rate-limited',
      retryAfterSeconds: 5,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('leaves retryAfterSeconds undefined when Retry-After is absent', async () => {
    fetchMock.mockResolvedValue(response(429, {}));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({
      kind: 'rate-limited',
      retryAfterSeconds: undefined,
    });
  });

  it('ignores a non-numeric Retry-After', async () => {
    fetchMock.mockResolvedValue(
      response(429, {}, { 'Retry-After': 'Wed, 21 Oct 2015 07:28:00 GMT' }),
    );

    await expect(send(descriptor('GET'))).rejects.toMatchObject({
      kind: 'rate-limited',
      retryAfterSeconds: undefined,
    });
  });

  it('leaves code undefined when the error body has no code', async () => {
    fetchMock.mockResolvedValue(response(404, { message: 'not found' }));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({ code: undefined });
  });

  it('leaves code undefined when the error body is not JSON', async () => {
    const badBody = {
      ...responseParts(404),
      json: vi.fn(() => Promise.reject(new Error('invalid json'))),
    } as unknown as Response;
    fetchMock.mockResolvedValue(badBody);

    await expect(send(descriptor('GET'))).rejects.toMatchObject({
      kind: 'not-found',
      code: undefined,
    });
  });

  it('never includes response body text in the error message', async () => {
    fetchMock.mockResolvedValue(
      response(403, { code: 'FORBIDDEN', message: 'SENSITIVE_BODY_TEXT' }),
    );

    const error = (await send(descriptor('GET')).catch(
      (reason: unknown) => reason,
    )) as TransportError;

    expect(error.message).not.toContain('SENSITIVE_BODY_TEXT');
  });
});

describe('send — failures and retries', () => {
  it('maps a network failure to offline and does not retry a POST', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(send(descriptor('POST', { body: {} }))).rejects.toMatchObject({ kind: 'offline' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('maps an abort to timeout and does not retry a POST', async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );

    // Attach the handler before advancing timers so the rejection is observed
    // in the same microtask turn that produces it (no unhandled rejection).
    const settled = send(descriptor('POST', { body: {} })).catch((reason: unknown) => reason);
    await vi.advanceTimersByTimeAsync(15_000);

    await expect(settled).resolves.toMatchObject({ kind: 'timeout' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('retries a GET once on offline and returns the second result', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(response(200, { recovered: true }));

    await expect(send(descriptor('GET'))).resolves.toEqual({ recovered: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('retries a GET once on timeout using a supplied timeout', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockImplementationOnce(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            );
          }),
      )
      .mockResolvedValueOnce(response(200, { recovered: true }));

    const pending = send(descriptor('GET'), { timeoutMs: 25 });
    await vi.advanceTimersByTimeAsync(25);

    await expect(pending).resolves.toEqual({ recovered: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not retry a GET after two consecutive offline failures', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({ kind: 'offline' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not retry a GET on a non-retryable HTTP error', async () => {
    fetchMock.mockResolvedValue(response(401, {}));

    await expect(send(descriptor('GET'))).rejects.toMatchObject({ kind: 'session-expired' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('send — caller cancellation', () => {
  /** Keep the fetch pending until its own abort signal fires. */
  function pendingFetch(): void {
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );
  }

  it('cancels an in-flight GET with a caller signal and does not retry', async () => {
    pendingFetch();
    const controller = new AbortController();
    const settled = send(descriptor('GET'), { signal: controller.signal }).catch(
      (reason: unknown) => reason,
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const signal = initAt(0).signal;
    expect(signal?.aborted).toBe(false);

    controller.abort();

    await expect(settled).resolves.toMatchObject({ kind: 'timeout' });
    expect(signal?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('rejects an already-aborted caller signal without issuing a fetch', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(send(descriptor('GET'), { signal: controller.signal })).rejects.toMatchObject({
      kind: 'timeout',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('still retries a GET on internal timeout when the caller signal is un-aborted', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockImplementationOnce(
        (_url, init) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            );
          }),
      )
      .mockResolvedValueOnce(response(200, { recovered: true }));

    const controller = new AbortController();
    const pending = send(descriptor('GET'), { signal: controller.signal, timeoutMs: 25 });
    await vi.advanceTimersByTimeAsync(25);

    await expect(pending).resolves.toEqual({ recovered: true });
    expect(controller.signal.aborted).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
