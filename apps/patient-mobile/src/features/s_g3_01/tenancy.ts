import type { LocalId, OrganizationId, TenantKey } from './types';

/**
 * Build an opaque tenant-scoped key.
 *
 * A NUL separator cannot occur in a generated local identifier, so the same
 * local id in two organizations never collapses to one entry (IAM-01, ARC-01).
 *
 * @param organizationId active organization
 * @param localId organization-local identifier
 * @returns opaque key used by the feature's maps
 */
export function tenantKey(organizationId: OrganizationId, localId: LocalId): TenantKey {
  return `${organizationId}\u0000${localId}`;
}

/**
 * Read one entry through the active tenant key only.
 *
 * @param records tenant-keyed records
 * @param organizationId active organization
 * @param localId organization-local identifier
 * @returns the entry, or undefined when it belongs to another tenant
 */
export function lookup<T>(
  records: ReadonlyMap<TenantKey, T>,
  organizationId: OrganizationId,
  localId: LocalId,
): T | undefined {
  return records.get(tenantKey(organizationId, localId));
}
