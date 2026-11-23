import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import { ADMIN_CHAIN_GENESIS, recomputeHash } from './domain';
import type {
  AccessProbe,
  AuditEvent,
  AuditEventFields,
  ConfigurationVersion,
  StaffHistoryEvent,
} from './domain';

/** Civil date anchor for the synthetic configuration review. */
export const adminAnchorCivilDate = '2026-03-09';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** The tenant whose stored audit chain is deliberately tampered for review. */
export const tamperedAuditOrganizationId = betaOrganizationId;

/** Configuration key reused by both tenants to prove UI-level isolation. */
export const adminConfigurationKey = 'facility.MAIN.booking';

/** Synthetic configuration versions, newest and future-dated entries included. */
export const syntheticConfigurations: readonly ConfigurationVersion[] = [
  {
    organizationId: alphaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 1,
    value: 'Synthetic alpha initial booking rule',
    effectiveFrom: '2026-01-01',
    approved: true,
    approver: 'Synthetic alpha admin A',
  },
  {
    organizationId: alphaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 2,
    value: 'Synthetic alpha revised booking rule',
    effectiveFrom: '2026-03-01',
    approved: true,
    approver: 'Synthetic alpha admin B',
  },
  {
    organizationId: alphaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 3,
    value: 'Synthetic alpha draft booking rule',
    effectiveFrom: '2026-04-01',
    approved: false,
  },
  {
    organizationId: alphaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 4,
    value: 'Synthetic alpha future booking rule',
    effectiveFrom: '2026-05-01',
    approved: true,
    approver: 'Synthetic alpha admin C',
  },
  {
    organizationId: betaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 1,
    value: 'Synthetic beta initial booking rule',
    effectiveFrom: '2026-01-01',
    approved: true,
    approver: 'Synthetic beta admin A',
  },
  {
    organizationId: betaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 2,
    value: 'Synthetic beta revised booking rule',
    effectiveFrom: '2026-03-01',
    approved: true,
    approver: 'Synthetic beta admin B',
  },
  {
    organizationId: betaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 3,
    value: 'Synthetic beta draft booking rule',
    effectiveFrom: '2026-04-01',
    approved: false,
  },
  {
    organizationId: betaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 4,
    value: 'Synthetic beta future booking rule',
    effectiveFrom: '2026-05-01',
    approved: true,
    approver: 'Synthetic beta admin C',
  },
];

/** Append-only staff events; the same subject id exists in both tenants. */
export const syntheticStaffEvents: readonly StaffHistoryEvent[] = [
  {
    organizationId: alphaOrganizationId,
    subject: 'synthetic.reception',
    sequence: 1,
    kind: 'invited',
    actor: 'Synthetic alpha admin A',
    effectiveFrom: '2026-01-05T08:00:00Z',
  },
  {
    organizationId: alphaOrganizationId,
    subject: 'synthetic.reception',
    sequence: 2,
    kind: 'role_granted',
    actor: 'Synthetic alpha admin A',
    effectiveFrom: '2026-01-06T08:00:00Z',
    role: 'reception',
  },
  {
    organizationId: alphaOrganizationId,
    subject: 'synthetic.auditor',
    sequence: 1,
    kind: 'invited',
    actor: 'Synthetic alpha admin A',
    effectiveFrom: '2026-01-05T08:00:00Z',
  },
  {
    organizationId: alphaOrganizationId,
    subject: 'synthetic.auditor',
    sequence: 2,
    kind: 'role_granted',
    actor: 'Synthetic alpha admin B',
    effectiveFrom: '2026-02-01T08:00:00Z',
    role: 'privacy_auditor',
  },
  {
    organizationId: alphaOrganizationId,
    subject: 'synthetic.auditor',
    sequence: 3,
    kind: 'deactivated',
    actor: 'Synthetic alpha admin B',
    effectiveFrom: '2026-03-02T08:00:00Z',
    reason: 'Synthetic access review closure',
  },
  {
    organizationId: betaOrganizationId,
    subject: 'synthetic.reception',
    sequence: 1,
    kind: 'invited',
    actor: 'Synthetic beta admin A',
    effectiveFrom: '2026-01-05T08:00:00Z',
  },
  {
    organizationId: betaOrganizationId,
    subject: 'synthetic.reception',
    sequence: 2,
    kind: 'role_granted',
    actor: 'Synthetic beta admin A',
    effectiveFrom: '2026-01-06T08:00:00Z',
    role: 'reception',
  },
  {
    organizationId: betaOrganizationId,
    subject: 'synthetic.reception',
    sequence: 3,
    kind: 'deactivated',
    actor: 'Synthetic beta admin C',
    effectiveFrom: '2026-03-03T08:00:00Z',
    reason: 'Synthetic tenant offboarding',
  },
];

/** Fields a synthetic audit seed contributes to a chain. */
type AuditSeed = Omit<AuditEventFields, 'organizationId' | 'sequence' | 'previousHash'>;

/** Link a set of synthetic seeds into a valid hash chain for one tenant. */
function buildAuditChain(organizationId: string, seeds: readonly AuditSeed[]): AuditEvent[] {
  let previousHash = ADMIN_CHAIN_GENESIS;
  return seeds.map((seed, index) => {
    const fields: AuditEventFields = {
      ...seed,
      organizationId,
      sequence: index + 1,
      previousHash,
    };
    const hash = recomputeHash(fields);
    previousHash = hash;
    return { ...fields, hash };
  });
}

const alphaAuditSeeds: readonly AuditSeed[] = [
  {
    actor: 'Synthetic alpha admin A',
    purpose: 'Synthetic configuration review',
    scope: 'facility:MAIN',
    correlationId: 'synthetic-correlation-alpha-1',
    action: 'CONFIG_CREATE',
    resourceId: 'cfg-MAIN-1',
    outcome: 'SUCCESS',
    occurredAt: '2026-03-08T06:30:00Z',
  },
  {
    actor: 'Synthetic alpha privacy auditor',
    purpose: 'Synthetic audit query',
    scope: 'organization:alpha',
    correlationId: 'synthetic-correlation-alpha-2',
    action: 'AUDIT_READ',
    resourceId: 'audit-alpha',
    outcome: 'SUCCESS',
    occurredAt: '2026-03-08T07:00:00Z',
  },
  {
    actor: 'Synthetic alpha reception',
    purpose: 'Synthetic protected action',
    scope: 'facility:MAIN',
    correlationId: 'synthetic-correlation-alpha-3',
    action: 'FACILITY_READ',
    resourceId: 'facility-MAIN',
    outcome: 'DENIED',
    occurredAt: '2026-03-08T07:30:00Z',
  },
];

const betaAuditSeeds: readonly AuditSeed[] = [
  {
    actor: 'Synthetic beta admin B',
    purpose: 'Synthetic configuration review',
    scope: 'facility:LAB',
    correlationId: 'synthetic-correlation-beta-1',
    action: 'CONFIG_CREATE',
    resourceId: 'cfg-LAB-1',
    outcome: 'SUCCESS',
    occurredAt: '2026-03-08T06:30:00Z',
  },
  {
    actor: 'Synthetic beta privacy auditor',
    purpose: 'Synthetic audit query',
    scope: 'organization:beta',
    correlationId: 'synthetic-correlation-beta-2',
    action: 'AUDIT_READ',
    resourceId: 'audit-beta',
    outcome: 'SUCCESS',
    occurredAt: '2026-03-08T07:00:00Z',
  },
  {
    actor: 'Synthetic beta reception',
    purpose: 'Synthetic protected action',
    scope: 'facility:LAB',
    correlationId: 'synthetic-correlation-beta-3',
    action: 'FACILITY_READ',
    resourceId: 'facility-LAB',
    outcome: 'DENIED',
    occurredAt: '2026-03-08T07:30:00Z',
  },
];

const alphaAuditChain = buildAuditChain(alphaOrganizationId, alphaAuditSeeds);
const betaAuditChain = buildAuditChain(betaOrganizationId, betaAuditSeeds).map((event, index) =>
  index === 1 ? { ...event, hash: `x${event.hash.slice(1)}` } : event,
);

/** Synthetic audit chains for both tenants; beta carries a tampered hash. */
export const syntheticAuditEvents: readonly AuditEvent[] = [...alphaAuditChain, ...betaAuditChain];

/** Fixture-driven access reviews that show permitted, denied and concealed states. */
export const syntheticAccessProbes: readonly AccessProbe[] = [
  {
    id: 'permitted-review',
    label: 'Synthetic authorized auditor',
    resourceOrganizationId: alphaOrganizationId,
    viewer: {
      organizationId: alphaOrganizationId,
      roles: ['privacy_auditor'],
      activeMembership: true,
    },
  },
  {
    id: 'denied-review',
    label: 'Synthetic reception role',
    resourceOrganizationId: alphaOrganizationId,
    viewer: {
      organizationId: alphaOrganizationId,
      roles: ['reception'],
      activeMembership: true,
    },
  },
  {
    id: 'concealed-review',
    label: 'Synthetic resource review 3',
    resourceOrganizationId: betaOrganizationId,
    viewer: {
      organizationId: alphaOrganizationId,
      roles: ['tenant_admin'],
      activeMembership: true,
    },
  },
];
