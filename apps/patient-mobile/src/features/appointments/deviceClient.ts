/**
 * Scoped device-revocation command for the patient appointments tab
 * (PAT-01, PRD-03, ARC-03).
 *
 * The installed {@link Transport} exposes only GET, POST and PUT and parses a
 * JSON body on every success, so a body-less 204 DELETE cannot be expressed
 * through it. This module keeps that gap at one boundary: a small authenticated
 * write port that sends a real DELETE, and a command that reduces every outcome
 * to a closed set of kinds. Response text, provider tokens and credentials are
 * never copied into a result, a message or a log; only an opaque correlation id
 * survives a failure.
 */
import { loadToken } from '../../api/tokenStore';
import { asTransportError, newCorrelationId, TransportError } from '../../api/transport';
import type { FetchImpl, TransportErrorKind } from '../../api/transport';

/** Only a loopback development origin may use plain HTTP. */
const DEV_LOCALHOST = /^http:\/\/localhost(?::\d+)?(?:\/|$)/;

/** Bounded deadline for one revocation, matching the shared transport. */
const COMMAND_TIMEOUT_MS = 15_000;

/** The minimal authenticated write capability needed to revoke one device. */
export interface DeviceWriteClient {
  /**
   * Issue one authenticated DELETE to a patient-scoped path.
   *
   * @param path relative API path with both identifiers encoded by the caller
   * @returns a resolved promise on HTTP 204; no body is read or parsed
   * @throws TransportError on every failure, with a safe correlation id
   */
  delete<T = unknown>(path: string): Promise<T>;
}

/** One revocation command; both identifiers are tenant-local and opaque. */
export interface DeviceRevocationCommand {
  /** Active device whose registration is being revoked. */
  readonly deviceId: string;
  /** Verified linked patient that owns the device. */
  readonly patientId: string;
}

/** Outcome of one revocation attempt; no branch discloses clinical data. */
export type DeviceRevocationResult =
  | { readonly kind: 'revoked' }
  | { readonly kind: 'alreadyRevoked' }
  | { readonly kind: 'unauthenticated' }
  | { readonly kind: 'failure'; readonly error: TransportError };

/**
 * Revoke one registered device with a single command.
 *
 * The server treats revocation as idempotent and conceals an already-revoked
 * device behind a 404; a version conflict is reported as 409 or 412. Both
 * settle the command so the caller can refresh the visible devices without
 * claiming a clinical error. A 401 ends the session. Every other failure is
 * returned as a typed transport error so the caller can offer one deliberate
 * retry without issuing a second request itself.
 *
 * @param client authenticated device write port
 * @param command device id and verified patient id
 * @returns the typed outcome of the attempt
 */
export async function revokeDevice(
  client: DeviceWriteClient,
  command: DeviceRevocationCommand,
): Promise<DeviceRevocationResult> {
  try {
    // Both identifiers are encoded: the device id is a path segment and the
    // patient id a query parameter, so a guessed id cannot widen the scope.
    await client.delete<void>(
      `/api/v1/patient/appointments/devices/${encodeURIComponent(command.deviceId)}?patientId=${encodeURIComponent(command.patientId)}`,
    );
    return { kind: 'revoked' };
  } catch (error) {
    const failure = asTransportError(error, 'server');
    if (failure.kind === 'unauthenticated') {
      return { kind: 'unauthenticated' };
    }
    if (failure.code === 'NOT_FOUND' || failure.kind === 'conflict') {
      return { kind: 'alreadyRevoked' };
    }
    return { kind: 'failure', error: failure };
  }
}

/** Configuration for one device-management client bound to an API origin. */
export interface DeviceClientConfig {
  /** HTTPS API origin; paths are joined with exactly one slash. */
  readonly baseUrl: string;
  /** Injectable fetch seam so tests can supply synthetic HTTP replies. */
  readonly fetchImpl: FetchImpl;
  /** Value of Accept-Language; defaults to English. */
  readonly language?: string;
}

/**
 * Fail closed on any API origin that is not HTTPS, except development localhost.
 *
 * @param baseUrl configured API origin
 * @throws Error when the origin would send a session credential in clear text
 */
function assertSecureOrigin(baseUrl: string): void {
  if (baseUrl.startsWith('https://')) {
    return;
  }
  const isDevelopment = (globalThis as { __DEV__?: boolean }).__DEV__ === true;
  if (isDevelopment && DEV_LOCALHOST.test(baseUrl)) {
    return;
  }
  throw new Error('Device API base URL must use HTTPS (localhost is allowed only in development)');
}

/**
 * Build the real authenticated DELETE adapter for one signed-in API origin.
 *
 * The credential is read immediately before the request so a revocation takes
 * effect on the next action. The request carries an opaque correlation id and a
 * bounded abort deadline. A 204 is accepted without calling the body reader;
 * errors are reduced to typed transport kinds and never expose response text.
 *
 * @param config HTTPS origin, fetch seam and optional display language
 * @returns the single DELETE port used by {@link revokeDevice}
 */
export function createDeviceWriteClient(config: DeviceClientConfig): DeviceWriteClient {
  assertSecureOrigin(config.baseUrl);
  const language = config.language ?? 'en';
  const origin = config.baseUrl.replace(/\/+$/, '');

  return {
    async delete<T>(path: string): Promise<T> {
      const correlationId = newCorrelationId();
      let controller: AbortController | undefined;
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        const token = await loadToken();
        if (typeof token !== 'string' || token.trim().length === 0) {
          throw new TransportError('unauthenticated', { correlationId });
        }

        const abortController = new AbortController();
        controller = abortController;
        // Capture the controller in a local so the deadline never depends on a
        // nullable binding: the timer is armed only after one exists.
        timer = setTimeout(() => abortController.abort(), COMMAND_TIMEOUT_MS);
        const response = await config.fetchImpl(`${origin}/${path.replace(/^\/+/, '')}`, {
          method: 'DELETE',
          headers: {
            Authorization: `Bearer ${token}`,
            'Accept-Language': language,
            Accept: 'application/json',
            'X-Correlation-Id': correlationId,
          },
          signal: abortController.signal,
        });

        if (!response.ok) {
          if (response.status === 401) {
            throw new TransportError('unauthenticated', { correlationId });
          }
          if (response.status === 404) {
            // The server conceals a revoked or unknown device as NOT_FOUND.
            throw new TransportError('server', { correlationId, code: 'NOT_FOUND' });
          }
          if (response.status === 409 || response.status === 412) {
            throw new TransportError('conflict', { correlationId });
          }
          throw new TransportError('server', { correlationId });
        }
        // A 204 has no body: parsing it would misreport success as a failure.
        return undefined as T;
      } catch (error) {
        if (error instanceof TransportError) {
          throw error;
        }
        if (controller?.signal.aborted === true) {
          throw new TransportError('timeout', { correlationId });
        }
        const kind: TransportErrorKind = asTransportError(error).kind;
        throw new TransportError(kind, { correlationId });
      } finally {
        if (timer !== undefined) {
          clearTimeout(timer);
        }
      }
    },
  };
}
