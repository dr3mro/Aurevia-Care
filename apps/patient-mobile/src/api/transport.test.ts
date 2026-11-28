import { jest } from '@jest/globals';

import { TransportError, asTransportError, createTransport } from './transport';
import type { FetchImpl, HttpResponse, TokenProvider, TransportConfig } from './transport';

/** Synthetic values only: no real credential, patient data or endpoint. */
const UUID = '11111111-1111-4111-8111-111111111111';
const SECOND_UUID = '22222222-2222-4222-8222-222222222222';
const TOKEN = 'synthetic-session-token';

function response(
  status: number,
  body: unknown = {},
  headers: Record<string, string> = {},
): HttpResponse {
  const lower = new Map(
    Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]),
  );
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => lower.get(name.toLowerCase()) ?? null },
    json: async () => body,
  };
}

interface Harness {
  transport: ReturnType<typeof createTransport>;
  fetchImpl: jest.Mock<FetchImpl>;
  getToken: jest.Mock<TokenProvider>;
}

function harness(overrides: Partial<TransportConfig> = {}): Harness {
  const fetchImpl = jest.fn<FetchImpl>();
  const getToken = jest.fn<TokenProvider>().mockResolvedValue(TOKEN);
  const transport = createTransport({
    baseUrl: 'https://api.example.test',
    getToken,
    fetchImpl,
    ...overrides,
  });
  return { transport, fetchImpl, getToken };
}

function initAt(fetchImpl: jest.Mock<FetchImpl>, index: number): RequestInit {
  const init = fetchImpl.mock.calls[index]?.[1];
  if (init === undefined) {
    throw new Error(`no fetch init at call ${index}`);
  }
  return init;
}

function headersOf(init: RequestInit): Record<string, string> {
  return init.headers as Record<string, string>;
}

function setDev(value: boolean): void {
  Object.defineProperty(globalThis, '__DEV__', {
    value,
    configurable: true,
    writable: true,
  });
}

beforeEach(() => {
  jest.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(UUID);
});

afterEach(() => {
  jest.restoreAllMocks();
  jest.useRealTimers();
  setDev(true);
});

describe('createTransport — request construction (PAT-01, ARC-03)', () => {
  it('sends a GET with bearer authorization, language, correlation id and accept header', async () => {
    const { transport, fetchImpl, getToken } = harness();
    fetchImpl.mockResolvedValue(response(200, { appointments: [] }));

    await expect(transport.get('/api/v1/appointments')).resolves.toEqual({ appointments: [] });

    expect(getToken).toHaveBeenCalledTimes(1);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('https://api.example.test/api/v1/appointments');
    const init = initAt(fetchImpl, 0);
    expect(init.method).toBe('GET');
    expect(headersOf(init)).toMatchObject({
      Authorization: `Bearer ${TOKEN}`,
      'Accept-Language': 'en',
      'X-Correlation-Id': UUID,
      Accept: 'application/json',
    });
    expect(init.body).toBeUndefined();
    expect(headersOf(init)['Content-Type']).toBeUndefined();
  });

  it('sends a JSON body and content type on POST', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, { ok: true }));

    await transport.post('/api/v1/commands', { confirmation: true });

    const init = initAt(fetchImpl, 0);
    expect(init.method).toBe('POST');
    expect(headersOf(init)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ confirmation: true }));
  });

  it('sends explicit conditional headers on a versioned POST', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, { ok: true }));

    await transport.post('/api/v1/conversations/conv-1/read', undefined, {
      'If-Match': '"3"',
      'Idempotency-Key': 'synthetic-key',
    });

    const headers = headersOf(initAt(fetchImpl, 0));
    expect(headers['If-Match']).toBe('"3"');
    expect(headers['Idempotency-Key']).toBe('synthetic-key');
    expect(headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('never lets conditional headers override the authenticated identity', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, { ok: true }));

    await transport.post('/api/v1/commands', undefined, {
      Authorization: 'Bearer forged',
      'X-Correlation-Id': 'forged',
    });

    const headers = headersOf(initAt(fetchImpl, 0));
    expect(headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(headers['X-Correlation-Id']).toBe(UUID);
  });

  it('rejects case variants of protected conditional headers', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, { ok: true }));

    await transport.post('/api/v1/commands', undefined, {
      authorization: 'Bearer forged',
      'x-correlation-id': 'forged',
      'content-type': 'text/plain',
    });

    const headers = headersOf(initAt(fetchImpl, 0));
    expect(headers.authorization).toBeUndefined();
    expect(headers['x-correlation-id']).toBeUndefined();
    expect(headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(headers['X-Correlation-Id']).toBe(UUID);
  });

  it('sends a JSON body and content type on PUT', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, { ok: true }));

    await transport.put('/api/v1/preferences', { language: 'ar' });

    const init = initAt(fetchImpl, 0);
    expect(init.method).toBe('PUT');
    expect(headersOf(init)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ language: 'ar' }));
  });

  it('uses the configured language for Accept-Language', async () => {
    const { transport, fetchImpl } = harness({ language: 'ar' });
    fetchImpl.mockResolvedValue(response(200, {}));

    await transport.get('/api/v1/reports');

    expect(headersOf(initAt(fetchImpl, 0))['Accept-Language']).toBe('ar');
  });

  it('generates a fresh correlation id on every call', async () => {
    jest.spyOn(globalThis.crypto, 'randomUUID').mockReturnValueOnce(SECOND_UUID);
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, {}));

    await transport.get('/api/v1/one');
    await transport.get('/api/v1/two');

    expect([
      headersOf(initAt(fetchImpl, 0))['X-Correlation-Id'],
      headersOf(initAt(fetchImpl, 1))['X-Correlation-Id'],
    ]).toEqual([SECOND_UUID, UUID]);
  });

  it('uses a non-empty fallback id when the platform has no randomUUID', async () => {
    jest.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => {
      throw new TypeError('randomUUID is unavailable');
    });
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(200, {}));

    await transport.get('/api/v1/one');

    const id = headersOf(initAt(fetchImpl, 0))['X-Correlation-Id'];
    expect(id).toEqual(expect.any(String));
    expect(id).not.toBe('');
    expect(id).not.toBe(UUID);
  });

  it('fails closed without sending when no token is available', async () => {
    const { transport, fetchImpl, getToken } = harness();
    getToken.mockResolvedValue(null);

    await expect(transport.get('/api/v1/x')).rejects.toMatchObject({
      kind: 'unauthenticated',
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('fails closed when the token is blank', async () => {
    const { transport, fetchImpl, getToken } = harness();
    getToken.mockResolvedValue('   ');

    await expect(transport.get('/api/v1/x')).rejects.toMatchObject({
      kind: 'unauthenticated',
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('reads a fresh credential on every call so revocation takes effect immediately', async () => {
    const { transport, fetchImpl, getToken } = harness();
    getToken
      .mockResolvedValueOnce('synthetic-token-one')
      .mockResolvedValueOnce('synthetic-token-two');
    fetchImpl.mockResolvedValue(response(200, {}));

    await transport.get('/api/v1/one');
    await transport.get('/api/v1/two');

    expect(getToken).toHaveBeenCalledTimes(2);
    expect(headersOf(initAt(fetchImpl, 0)).Authorization).toBe('Bearer synthetic-token-one');
    expect(headersOf(initAt(fetchImpl, 1)).Authorization).toBe('Bearer synthetic-token-two');
  });

  it('joins a trailing-slash origin and a path without a leading slash', async () => {
    const { transport, fetchImpl } = harness({ baseUrl: 'https://api.example.test/' });
    fetchImpl.mockResolvedValue(response(200, {}));

    await transport.get('api/v1/x');

    expect(fetchImpl.mock.calls[0]?.[0]).toBe('https://api.example.test/api/v1/x');
  });
});

describe('createTransport — base URL policy', () => {
  it('accepts an HTTPS base URL', () => {
    expect(() => harness({ baseUrl: 'https://secure.example.test/api' })).not.toThrow();
  });

  it('accepts http://localhost in development', async () => {
    setDev(true);
    const { transport, fetchImpl } = harness({ baseUrl: 'http://localhost:8080' });
    fetchImpl.mockResolvedValue(response(200, {}));

    await expect(transport.get('/api/v1/x')).resolves.toEqual({});
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/v1/x');
  });

  it('rejects http://localhost outside development', () => {
    setDev(false);
    expect(() => harness({ baseUrl: 'http://localhost:8080' })).toThrow(/HTTPS/);
  });

  it('rejects a non-localhost http base URL even in development', () => {
    setDev(true);
    expect(() => harness({ baseUrl: 'http://api.example.test' })).toThrow(/HTTPS/);
  });

  it('rejects a base URL without an http scheme', () => {
    expect(() => harness({ baseUrl: 'ftp://api.example.test' })).toThrow(/HTTPS/);
  });
});

describe.each([
  [401, 'unauthenticated'],
  [403, 'forbidden'],
  [409, 'conflict'],
  [412, 'conflict'],
  [422, 'validation'],
  [404, 'server'],
  [500, 'server'],
] as const)('createTransport — HTTP %i', (status, kind) => {
  it(`maps ${status} to ${kind}`, async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(status, { code: 'SOME_CODE' }));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(TransportError);
    expect(error).toMatchObject({ kind });
  });
});

describe('createTransport — error details', () => {
  it('exposes the server field errors on a validation failure', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(
      response(422, {
        fieldErrors: [
          { field: 'invitationCode', message: 'INVALID_CODE' },
          { field: 'language', message: 'UNSUPPORTED_LANGUAGE' },
        ],
      }),
    );

    const error = await transport.post('/api/v1/enrollment', {}).catch((reason: unknown) => reason);

    expect(error).toMatchObject({
      kind: 'validation',
      fieldErrors: [
        { field: 'invitationCode', message: 'INVALID_CODE' },
        { field: 'language', message: 'UNSUPPORTED_LANGUAGE' },
      ],
    });
  });

  it('keeps only well-formed field errors', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(
      response(422, {
        fieldErrors: [
          null,
          'not-an-object',
          {},
          { field: 7, message: 'x' },
          { field: 'x', message: 7 },
          [],
          { field: 'valid', message: 'VALID' },
        ],
      }),
    );

    const error = await transport.post('/api/v1/enrollment', {}).catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'validation', fieldErrors: [{ field: 'valid', message: 'VALID' }] });
  });

  it('returns no field errors when the server sends a non-array value', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(422, { fieldErrors: { field: 'x', message: 'y' } }));

    const error = await transport.post('/api/v1/enrollment', {}).catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'validation', fieldErrors: [] });
  });

  it('uses the response correlation id when the server supplies one', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(
      response(403, { code: 'FORBIDDEN' }, { 'X-Correlation-Id': 'server-correlation-id' }),
    );

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'forbidden', correlationId: 'server-correlation-id', code: 'FORBIDDEN' });
  });

  it('falls back to the request correlation id when the server omits it', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(403, {}));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'forbidden', correlationId: UUID });
  });

  it('tolerates a null error body', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(403, null));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'forbidden', code: undefined, fieldErrors: [] });
  });

  it('ignores a token-shaped correlation header instead of copying it into the error', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(
      response(403, {}, { 'X-Correlation-Id': `Bearer ${TOKEN}` }),
    );

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ correlationId: UUID });
  });

  it('ignores a non-string error code', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(response(403, { code: 42 }));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'forbidden', code: undefined });
  });

  it('tolerates an error body that is not JSON', async () => {
    const { transport, fetchImpl } = harness();
    const broken = {
      ...response(422),
      json: async () => {
        throw new Error('invalid json');
      },
    } as HttpResponse;
    fetchImpl.mockResolvedValue(broken);

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toMatchObject({ kind: 'validation', code: undefined, fieldErrors: [] });
  });
});

describe('createTransport — failure modes', () => {
  it('maps a rejected fetch to a network error', async () => {
    const { transport, fetchImpl } = harness();
    fetchImpl.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(TransportError);
    expect(error).toMatchObject({ kind: 'network', correlationId: UUID });
  });

  it('aborts the request and maps it to timeout after 15 seconds', async () => {
    jest.useFakeTimers();
    const { transport, fetchImpl } = harness();
    fetchImpl.mockImplementation(
      (_url, init) =>
        new Promise<HttpResponse>((_resolve, reject) => {
          const signal = init?.signal;
          if (signal === undefined || signal === null) {
            throw new Error('missing abort signal');
          }
          signal.addEventListener('abort', () => reject(new Error('aborted')));
        }),
    );

    const pending = transport.get('/api/v1/x').catch((reason: unknown) => reason);
    await jest.advanceTimersByTimeAsync(15_000);

    await expect(pending).resolves.toMatchObject({ kind: 'timeout', correlationId: UUID });
    const init = initAt(fetchImpl, 0);
    expect(init.signal?.aborted).toBe(true);
  });

  it('maps a rejecting credential provider to a typed network error', async () => {
    const { transport, fetchImpl, getToken } = harness();
    getToken.mockRejectedValue(new Error('secure storage unavailable'));

    const error = await transport.get('/api/v1/x').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(TransportError);
    expect(error).toMatchObject({ kind: 'network', correlationId: UUID });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('does not relabel a typed HTTP failure as timeout when its error body stalls', async () => {
    jest.useFakeTimers();
    const { transport, fetchImpl } = harness();
    const stalled = {
      ...response(403, {}),
      json: () =>
        new Promise<unknown>((_resolve, reject) => {
          const signal = initAt(fetchImpl, 0).signal;
          if (signal === undefined || signal === null) {
            throw new Error('missing abort signal');
          }
          signal.addEventListener('abort', () => reject(new Error('aborted')));
        }),
    } as HttpResponse;
    fetchImpl.mockResolvedValue(stalled);

    const pending = transport.get('/api/v1/x').catch((reason: unknown) => reason);
    await jest.advanceTimersByTimeAsync(15_000);

    await expect(pending).resolves.toMatchObject({ kind: 'forbidden', correlationId: UUID });
  });

  it('never places the token or response body in the error message or logs', async () => {
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const { transport, fetchImpl } = harness();
    fetchImpl.mockResolvedValue(
      response(403, { message: 'SENSITIVE_BODY_TEXT', token: TOKEN }),
    );

    const error = (await transport.get('/api/v1/x').catch((reason: unknown) => reason)) as TransportError;

    expect(error.message).not.toContain('SENSITIVE_BODY_TEXT');
    expect(error.message).not.toContain(TOKEN);
    expect(JSON.stringify(error)).not.toContain(TOKEN);
    const logged = [...logSpy.mock.calls, ...errorSpy.mock.calls]
      .map((call) => JSON.stringify(call))
      .join('\n');
    expect(logged).not.toContain('SENSITIVE_BODY_TEXT');
    expect(logged).not.toContain(TOKEN);
  });
});

describe('TransportError construction seatbelt', () => {
  it('accepts empty details and leaves the correlation id unset', () => {
    const error = new TransportError('network', {});
    expect(error.kind).toBe('network');
    expect(error.correlationId).toBeUndefined();
  });

  it('accepts omitted details', () => {
    const error = new TransportError('server');
    expect(error.kind).toBe('server');
    expect(error.correlationId).toBeUndefined();
  });

  it('asTransportError returns the same instance for a TransportError', () => {
    const original = new TransportError('forbidden', { correlationId: 'corr-keep' });
    expect(asTransportError(original)).toBe(original);
  });

  it('asTransportError wraps an unknown throw as network by default', () => {
    const wrapped = asTransportError(new Error('boom'));
    expect(wrapped).toBeInstanceOf(TransportError);
    expect(wrapped.kind).toBe('network');
    expect(wrapped.correlationId).toBeUndefined();
    expect(wrapped.message).not.toContain('boom');
  });

  it('asTransportError uses the requested fallback kind', () => {
    expect(asTransportError('x', 'timeout').kind).toBe('timeout');
  });
});

