/**
 * Device revocation command tests (PAT-01, PRD-03).
 *
 * Every branch of the scoped write client and of the authenticated DELETE
 * adapter is exercised with synthetic data. No token, response body or patient
 * detail is used as an assertion value beyond the opaque identifiers that the
 * contract itself carries.
 */
import { jest } from '@jest/globals';
import * as SecureStore from 'expo-secure-store';

import { clearToken, saveToken } from '../../api/tokenStore';
import { TransportError } from '../../api/transport';
import type { FetchImpl, HttpResponse } from '../../api/transport';

import { createDeviceWriteClient, revokeDevice } from './deviceClient';
import type { DeviceWriteClient } from './deviceClient';

/** Synthetic native credential store; the real module reads it on each call. */
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
  };
});

/** One command with opaque, tenant-local identifiers. */
const command = { deviceId: 'dev-1', patientId: 'patient-1' } as const;

/** Build a fake HTTP response with an observable body reader. */
function reply(status: number, body: unknown = {}): HttpResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: jest.fn(async () => body) as HttpResponse['json'],
  };
}

/** Build a write client whose single DELETE is fully controlled by the test. */
function writeClient(remove: () => Promise<unknown>): DeviceWriteClient {
  return { delete: jest.fn(remove) as DeviceWriteClient['delete'] };
}

/** Read the single path passed to a fake write client. */
function deletedPath(client: DeviceWriteClient): string {
  const remove = client.delete as jest.Mock<(path: string) => Promise<unknown>>;
  expect(remove).toHaveBeenCalledTimes(1);
  return remove.mock.calls[0]![0]!;
}

describe('revokeDevice command mapping', () => {
  beforeEach(async () => {
    await clearToken();
  });

  it('sends exactly one DELETE with both identifiers encoded into the patient-scoped path', async () => {
    const client = writeClient(async () => undefined);

    const result = await revokeDevice(client, {
      deviceId: 'dev/one',
      patientId: 'patient 1',
    });

    expect(result.kind).toBe('revoked');
    expect(deletedPath(client)).toBe(
      '/api/v1/patient/appointments/devices/dev%2Fone?patientId=patient%201',
    );
  });

  it('maps a 401 failure to unauthenticated without any response text', async () => {
    const client = writeClient(async () => {
      throw new TransportError('unauthenticated', { correlationId: 'corr-1', code: 'SECRET' });
    });

    const result = await revokeDevice(client, command);

    expect(result).toEqual({ kind: 'unauthenticated' });
  });

  it('maps a concealed device (404 NOT_FOUND) to alreadyRevoked', async () => {
    const client = writeClient(async () => {
      throw new TransportError('server', { correlationId: 'corr-2', code: 'NOT_FOUND' });
    });

    const result = await revokeDevice(client, command);

    expect(result).toEqual({ kind: 'alreadyRevoked' });
  });

  it('maps a 409 conflict to alreadyRevoked', async () => {
    const client = writeClient(async () => {
      throw new TransportError('conflict', { correlationId: 'corr-3' });
    });

    const result = await revokeDevice(client, command);

    expect(result).toEqual({ kind: 'alreadyRevoked' });
  });

  it('maps a 412 conflict to alreadyRevoked', async () => {
    const client = writeClient(async () => {
      throw new TransportError('conflict', { correlationId: 'corr-4' });
    });

    const result = await revokeDevice(client, command);

    expect(result).toEqual({ kind: 'alreadyRevoked' });
  });

  it('returns a typed failure for other errors and never exposes response text', async () => {
    const client = writeClient(async () => {
      throw new Error('provider said:SECRET-PATIENT-DATA');
    });

    const result = await revokeDevice(client, command);

    expect(result.kind).toBe('failure');
    if (result.kind === 'failure') {
      expect(result.error.message).not.toContain('SECRET-PATIENT-DATA');
    }
  });
});

describe('createDeviceWriteClient adapter', () => {
  beforeEach(async () => {
    await clearToken();
    await saveToken('synthetic-session-token');
  });

  it('rejects a non-HTTPS origin before any request', () => {
    expect(() =>
      createDeviceWriteClient({
        baseUrl: 'http://api.synthetic.example',
        fetchImpl: jest.fn<FetchImpl>(),
      }),
    ).toThrow(/HTTPS/);
  });

  it('allows a loopback origin only for development builds', () => {
    const previous = (globalThis as { __DEV__?: boolean }).__DEV__;
    try {
      (globalThis as { __DEV__?: boolean }).__DEV__ = true;
      expect(() =>
        createDeviceWriteClient({
          baseUrl: 'http://localhost:8080',
          fetchImpl: jest.fn<FetchImpl>(),
        }),
      ).not.toThrow();
      (globalThis as { __DEV__?: boolean }).__DEV__ = false;
      expect(() =>
        createDeviceWriteClient({
          baseUrl: 'http://localhost:8080',
          fetchImpl: jest.fn<FetchImpl>(),
        }),
      ).toThrow(/HTTPS/);
    } finally {
      // `previous` was read from an optional flag, so it may be undefined;
      // restore it through a type that admits that value under strict
      // optional-property typing rather than widening production types.
      (globalThis as { __DEV__?: boolean | undefined }).__DEV__ = previous;
    }
  });

  it('issues one authenticated DELETE for a body-less 204 without reading a body', async () => {
    const ok = reply(204);
    const fetchImpl = jest.fn<FetchImpl>(async () => ok);
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example/',
      fetchImpl,
      language: 'ar',
    });

    await expect(client.delete<void>('/api/v1/patient/appointments/devices/dev-1')).resolves.toBeUndefined();

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe('https://api.synthetic.example/api/v1/patient/appointments/devices/dev-1');
    expect(init.method).toBe('DELETE');
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer synthetic-session-token');
    expect(headers['Accept-Language']).toBe('ar');
    expect(headers['X-Correlation-Id']).toMatch(/^[A-Za-z0-9._:-]{1,64}$/);
    expect(ok.json).not.toHaveBeenCalled();
  });

  it('defaults Accept-Language to English when the host omits it', async () => {
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(204));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await client.delete('/path');

    const [, init] = fetchImpl.mock.calls[0]!;
    expect((init.headers as Record<string, string>)['Accept-Language']).toBe('en');
  });

  it('fails closed without a credential and never calls fetch', async () => {
    await clearToken();
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(204));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'unauthenticated' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('fails closed when the stored credential is blank', async () => {
    jest.mocked(SecureStore.getItemAsync).mockResolvedValueOnce('   ');
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(204));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'unauthenticated' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('wraps a credential-read failure as a typed network failure', async () => {
    jest
      .mocked(SecureStore.getItemAsync)
      .mockRejectedValueOnce(new Error('synthetic keychain failure'));
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(204));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'network' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('throws a typed unauthenticated failure for a 401', async () => {
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(401));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'unauthenticated' });
  });

  it('throws a typed NOT_FOUND failure for a concealed device 404', async () => {
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(404, { code: 'NOT_FOUND' }));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({
      kind: 'server',
      code: 'NOT_FOUND',
    });
  });

  it('throws a typed conflict failure for 409 and 412', async () => {
    for (const status of [409, 412]) {
      const fetchImpl = jest.fn<FetchImpl>(async () => reply(status));
      const client = createDeviceWriteClient({
        baseUrl: 'https://api.synthetic.example',
        fetchImpl,
      });

      await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'conflict' });
    }
  });

  it('throws a typed server failure for other non-success statuses', async () => {
    const fetchImpl = jest.fn<FetchImpl>(async () => reply(500));
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'server' });
  });

  it('wraps a rejected fetch as a typed network failure', async () => {
    const fetchImpl = jest.fn<FetchImpl>(async () => {
      throw new Error('synthetic transport failure');
    });
    const client = createDeviceWriteClient({
      baseUrl: 'https://api.synthetic.example',
      fetchImpl,
    });

    await expect(client.delete('/path')).rejects.toMatchObject({ kind: 'network' });
  });

  it('maps an aborted deadline to a timeout failure', async () => {
    jest.useFakeTimers();
    try {
      const fetchImpl = jest.fn<FetchImpl>((_url, init) =>
        new Promise<HttpResponse>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new Error('synthetic abort')));
        }),
      );
      const client = createDeviceWriteClient({
        baseUrl: 'https://api.synthetic.example',
        fetchImpl,
      });

      // Attach the rejection assertion before advancing the clock so the
      // deadline cannot reject an unobserved promise.
      const pending = expect(client.delete('/path')).rejects.toMatchObject({ kind: 'timeout' });
      await jest.advanceTimersByTimeAsync(20_000);
      await pending;
    } finally {
      jest.useRealTimers();
    }
  });
});
