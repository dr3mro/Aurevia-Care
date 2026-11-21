import type { ServiceProfileResponse } from './api';
import type { ServiceProfile } from './types';

/** Synthetic tenants; never real organization identifiers. */
export const alphaOrganizationId = '11111111-1111-1111-1111-111111111111';
export const betaOrganizationId = '22222222-2222-2222-2222-222222222222';

/** Synthetic unit identifiers, local to their tenant. */
export const alphaUnitId = 'synthetic-unit-001';
export const betaUnitId = 'synthetic-unit-002';

/** Civil date used to pin effective-date tests and previews. */
export const todayCivilDate = '2026-03-09';

/**
 * An enabled-safe synthetic profile: it has a named owner but no backup, so
 * the operational-warning state is exercised. Its change is pending approval.
 */
export const alphaProfile: ServiceProfile = {
  organizationId: alphaOrganizationId,
  unitId: alphaUnitId,
  version: 3,
  enabled: false,
  details: {
    serviceHours: 'Mon-Fri 09:00-17:00 (America/New_York)',
    responseTargetUrgent: '15 minutes',
    responseTargetRoutine: '1 business day',
    topics: 'appointments, results, prescriptions',
    urgentContactInstructions: 'Call the synthetic on-call phone and quote the unit name.',
    queueOwner: 'Synthetic Unit Lead',
    backupCoverage: '',
  },
  approval: {
    approver: 'Synthetic Clinical Lead',
    effectiveFrom: '2026-03-10',
    approvedAt: null,
  },
};

/**
 * A synthetic profile with a named backup but no queue owner, so the
 * missing-owner block and the approved/enabled states are both exercised.
 */
export const betaProfile: ServiceProfile = {
  organizationId: betaOrganizationId,
  unitId: betaUnitId,
  version: 5,
  enabled: true,
  details: {
    serviceHours: 'Daily 08:00-20:00 (Asia/Riyadh)',
    responseTargetUrgent: '10 minutes',
    responseTargetRoutine: '4 hours',
    topics: 'orders, reports',
    urgentContactInstructions: 'Use the site-approved synthetic downtime channel.',
    queueOwner: '',
    backupCoverage: 'Synthetic Backup Lead',
  },
  approval: {
    approver: 'Synthetic Pharmacy Lead',
    effectiveFrom: '2026-03-11',
    approvedAt: '2026-03-08T08:00:00Z',
  },
};

/** A profile version of zero denotes no published service profile yet. */
export const unpublishedProfile: ServiceProfile = {
  ...betaProfile,
  version: 0,
  enabled: false,
};

/** Build the wire response the API would return for a synthetic profile. */
export function profileResponse(profile: ServiceProfile): ServiceProfileResponse {
  return {
    organizationId: profile.organizationId,
    unitId: profile.unitId,
    version: profile.version,
    enabled: profile.enabled,
    serviceHours: profile.details.serviceHours,
    responseTargetUrgent: profile.details.responseTargetUrgent,
    responseTargetRoutine: profile.details.responseTargetRoutine,
    topics: profile.details.topics,
    urgentContactInstructions: profile.details.urgentContactInstructions,
    queueOwner: profile.details.queueOwner,
    backupCoverage: profile.details.backupCoverage,
    approver: profile.approval.approver,
    effectiveFrom: profile.approval.effectiveFrom,
    approvedAt: profile.approval.approvedAt,
  };
}
