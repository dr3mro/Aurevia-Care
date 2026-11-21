import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { ProfileDraft, ServiceProfile } from './types';

/**
 * Wire shape of one unit service profile. The authoritative OpenAPI contract
 * does not publish the care-unit service-profile family yet, so this module
 * owns the projection and the descriptor paths, as the scheduling feature does
 * for its unpublished command sub-paths.
 */
export interface ServiceProfileResponse {
  organizationId: string;
  unitId: string;
  version: number;
  enabled: boolean;
  serviceHours: string;
  responseTargetUrgent: string;
  responseTargetRoutine: string;
  topics: string;
  urgentContactInstructions: string;
  queueOwner: string;
  backupCoverage: string;
  approver: string;
  effectiveFrom: string;
  approvedAt: string | null;
}

/**
 * Build a descriptor using the shared builder's tenant, correlation,
 * idempotency and version headers, then substitute the care-unit path. The
 * builder's base path is a published contract path only to keep it type-safe.
 */
function profileDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, '/api/v1/configuration', meta, body);
  return { ...base, url: path, method };
}

/** Fetch one tenant's published service profile for a care unit. */
export function getServiceProfile(
  unitId: string,
  organizationId: string,
): Promise<ServiceProfileResponse> {
  return send<ServiceProfileResponse>(
    profileDescriptor('GET', `/api/v1/units/${encodeURIComponent(unitId)}/service-profile`, {
      activeOrganization: organizationId,
    }),
  );
}

/**
 * Propose a versioned change carrying the named approval and effective date.
 * The current version is sent as `If-Match` and the key is fresh per action.
 */
export function proposeServiceProfile(
  unitId: string,
  version: number,
  draft: ProfileDraft,
  organizationId: string,
): Promise<ServiceProfileResponse> {
  return send<ServiceProfileResponse>(
    profileDescriptor(
      'POST',
      `/api/v1/units/${encodeURIComponent(unitId)}/service-profile`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      { ...draft },
    ),
  );
}

/** Approve one proposed version; the actor identity is derived server-side. */
export function approveServiceProfile(
  unitId: string,
  version: number,
  organizationId: string,
): Promise<ServiceProfileResponse> {
  return send<ServiceProfileResponse>(
    profileDescriptor(
      'POST',
      `/api/v1/units/${encodeURIComponent(unitId)}/service-profile/versions/${version}/approve`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
    ),
  );
}

/** Enable or disable a unit for patient messages, guarded by its version. */
export function setUnitEnabled(
  unitId: string,
  version: number,
  enabled: boolean,
  organizationId: string,
): Promise<ServiceProfileResponse> {
  return send<ServiceProfileResponse>(
    profileDescriptor(
      'POST',
      `/api/v1/units/${encodeURIComponent(unitId)}/enabled`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      { enabled },
    ),
  );
}

/** Project a wire response into the tenant-scoped view used by the screen. */
export function toServiceProfile(response: ServiceProfileResponse): ServiceProfile {
  return {
    organizationId: response.organizationId,
    unitId: response.unitId,
    version: response.version,
    enabled: response.enabled,
    details: {
      serviceHours: response.serviceHours,
      responseTargetUrgent: response.responseTargetUrgent,
      responseTargetRoutine: response.responseTargetRoutine,
      topics: response.topics,
      urgentContactInstructions: response.urgentContactInstructions,
      queueOwner: response.queueOwner,
      backupCoverage: response.backupCoverage,
    },
    approval: {
      approver: response.approver,
      effectiveFrom: response.effectiveFrom,
      approvedAt: response.approvedAt,
    },
  };
}
