import type { components, paths } from './generated/openapi';

/** The only route prefix used by the V1 contract (ARC-03). */
export const API_PREFIX = '/api/v1';

/** A concrete path declared in the authoritative contract. */
export type ApiPath = keyof paths;

/** Server-derived response shapes used by synthetic fixtures. */
export type Facility = components['schemas']['Facility'];
export type StaffMember = components['schemas']['StaffMember'];

/** Metadata for a request descriptor; no value here is sent anywhere. */
export interface RequestMeta {
  activeOrganization: string;
  correlationId?: string;
  idempotencyKey?: string;
  ifMatch?: number;
}

/** A transport-free description of a versioned API request (ARC-03). */
export interface RequestDescriptor {
  url: string;
  method: 'GET' | 'POST';
  headers: Record<string, string>;
  body?: Record<string, unknown>;
}

/**
 * Build a descriptor for a contract path with explicit tenant, correlation,
 * idempotency and version-precondition metadata. This visual foundation never
 * hands the descriptor to a transport.
 */
export function buildRequestDescriptor(
  method: 'GET' | 'POST',
  path: ApiPath,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const headers: Record<string, string> = {
    'X-Active-Organization': meta.activeOrganization,
  };
  if (meta.correlationId !== undefined && meta.correlationId !== '') {
    headers['X-Correlation-Id'] = meta.correlationId;
  }
  if (meta.idempotencyKey !== undefined && meta.idempotencyKey !== '') {
    headers['Idempotency-Key'] = meta.idempotencyKey;
  }
  if (meta.ifMatch !== undefined) {
    headers['If-Match'] = String(meta.ifMatch);
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const descriptor: RequestDescriptor = { url: path, method, headers };
  if (body !== undefined) {
    descriptor.body = body;
  }
  return descriptor;
}

/** Fail closed if any future code tries to make a real application call. */
export function refuseNetworkCall(): never {
  throw new Error('staff-web foundation never calls application endpoints');
}
