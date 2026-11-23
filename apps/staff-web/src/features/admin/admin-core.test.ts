import { describe, expect, it } from 'vitest';
import {
  AUDIT_REVIEW_ROLES,
  adminChainFor,
  currentRoles,
  effectiveVersion,
  historyFor,
  isDeactivated,
  isValidCivilDate,
  nextVersion,
  proposeVersion,
  recomputeHash,
  resolveAccess,
  verifyChain,
  versionsFor,
} from './domain';
import type { AccessState, AuditEvent, ViewerPermission } from './domain';
import {
  adminAnchorCivilDate,
  alphaOrganizationId,
  betaOrganizationId,
  syntheticAccessProbes,
  syntheticAuditEvents,
  syntheticConfigurations,
  syntheticStaffEvents,
  tamperedAuditOrganizationId,
} from './fixtures';
import {
  adminKeys,
  adminMessage,
  adminText,
  accessStateMessageKey,
  outcomeMessageKey,
  proposalAnnouncement,
  staffEventMessageKey,
} from './messages';
import { must } from '../../../tests/must';

const alphaViewer: ViewerPermission = {
  organizationId: alphaOrganizationId,
  roles: ['tenant_admin'],
  activeMembership: true,
};

const findConfig = (organizationId: string, version: number) => {
  const found = syntheticConfigurations.find(
    (entry) => entry.organizationId === organizationId && entry.version === version,
  );
  if (!found) {
    throw new Error(`missing synthetic configuration version ${version} for ${organizationId}`);
  }
  return found;
};

describe('access state resolution', () => {
  it('conceals a resource that belongs to a different active organization', () => {
    expect(resolveAccess(alphaViewer, betaOrganizationId)).toBe('concealed');
    expect(
      resolveAccess(
        { organizationId: alphaOrganizationId, roles: [], activeMembership: false },
        betaOrganizationId,
      ),
    ).toBe('concealed');
  });

  it('denies same-tenant access without active membership or an allowed role', () => {
    expect(
      resolveAccess(
        { organizationId: alphaOrganizationId, roles: ['tenant_admin'], activeMembership: false },
        alphaOrganizationId,
      ),
    ).toBe('denied');
    expect(
      resolveAccess(
        { organizationId: alphaOrganizationId, roles: ['reception'], activeMembership: true },
        alphaOrganizationId,
      ),
    ).toBe('denied');
    expect(
      resolveAccess(
        { organizationId: alphaOrganizationId, roles: [], activeMembership: true },
        alphaOrganizationId,
      ),
    ).toBe('denied');
  });

  it('permits an active privileged member of the resource tenant', () => {
    expect(
      resolveAccess(
        { organizationId: alphaOrganizationId, roles: ['privacy_auditor'], activeMembership: true },
        alphaOrganizationId,
      ),
    ).toBe('permitted');
    expect(AUDIT_REVIEW_ROLES.has('tenant_admin')).toBe(true);
    expect(AUDIT_REVIEW_ROLES.has('reception')).toBe(false);
  });

  it('ships fixture probes for concealed, denied and permitted states', () => {
    const states = new Set<AccessState>(
      syntheticAccessProbes.map((probe) =>
        resolveAccess(probe.viewer, probe.resourceOrganizationId),
      ),
    );
    expect(states).toEqual(new Set<AccessState>(['permitted', 'denied', 'concealed']));
  });
});

describe('configuration versions', () => {
  it('scopes by tenant and key and orders by descending version', () => {
    const alpha = versionsFor(
      syntheticConfigurations,
      alphaOrganizationId,
      'facility.MAIN.booking',
    );
    expect(alpha.map((entry) => entry.version)).toEqual([4, 3, 2, 1]);
    expect(alpha.every((entry) => entry.organizationId === alphaOrganizationId)).toBe(true);
    expect(
      versionsFor(syntheticConfigurations, betaOrganizationId, 'facility.MAIN.booking'),
    ).toHaveLength(4);
    expect(versionsFor(syntheticConfigurations, alphaOrganizationId, 'missing.key')).toEqual([]);
  });

  it('breaks equal-version ties deterministically by effective date', () => {
    const tied = [
      { ...findConfig(alphaOrganizationId, 1), effectiveFrom: '2026-02-01' },
      { ...findConfig(alphaOrganizationId, 1), effectiveFrom: '2026-01-01' },
    ];
    expect(
      versionsFor(tied, alphaOrganizationId, 'facility.MAIN.booking').map((e) => e.effectiveFrom),
    ).toEqual(['2026-02-01', '2026-01-01']);
  });

  it('selects only approved versions already effective on the anchor date', () => {
    expect(
      effectiveVersion(
        syntheticConfigurations,
        alphaOrganizationId,
        'facility.MAIN.booking',
        adminAnchorCivilDate,
      )?.version,
    ).toBe(2);
    expect(
      effectiveVersion(
        syntheticConfigurations,
        betaOrganizationId,
        'facility.MAIN.booking',
        adminAnchorCivilDate,
      )?.value,
    ).toContain('Synthetic');
    expect(
      effectiveVersion(
        syntheticConfigurations,
        alphaOrganizationId,
        'missing.key',
        adminAnchorCivilDate,
      ),
    ).toBeUndefined();
    expect(
      effectiveVersion(
        syntheticConfigurations,
        alphaOrganizationId,
        'facility.MAIN.booking',
        '2025-01-01',
      ),
    ).toBeUndefined();
  });

  it('allocates the next version per tenant and key', () => {
    expect(nextVersion(syntheticConfigurations, alphaOrganizationId, 'facility.MAIN.booking')).toBe(
      5,
    );
    expect(nextVersion(syntheticConfigurations, betaOrganizationId, 'facility.MAIN.booking')).toBe(
      5,
    );
    expect(nextVersion(syntheticConfigurations, alphaOrganizationId, 'missing.key')).toBe(1);
  });

  it('accepts only real calendar dates and rejects crash-inducing input', () => {
    expect(isValidCivilDate('2026-07-01')).toBe(true);
    expect(isValidCivilDate('2026-02-28')).toBe(true);
    expect(isValidCivilDate('next tuesday')).toBe(false);
    expect(isValidCivilDate('2026-13-01')).toBe(false);
    expect(isValidCivilDate('2026-02-30')).toBe(false);
    expect(isValidCivilDate('')).toBe(false);
  });

  it('proposes a pending draft bound to the requesting tenant', () => {
    const draft = proposeVersion(
      syntheticConfigurations,
      betaOrganizationId,
      { key: 'facility.MAIN.booking', facilityCode: 'LAB' },
      {
        value: 'Synthetic proposed value',
        effectiveFrom: '2026-06-01',
      },
    );
    expect(draft.organizationId).toBe(betaOrganizationId);
    expect(draft.facilityCode).toBe('LAB');
    expect(draft.version).toBe(5);
    expect(draft.value).toBe('Synthetic proposed value');
    expect(draft.effectiveFrom).toBe('2026-06-01');
    expect(draft.approved).toBe(false);
    expect(draft.approver).toBeUndefined();
  });
});

describe('staff membership history', () => {
  it('returns one subject within one tenant in append order', () => {
    const active = historyFor(syntheticStaffEvents, alphaOrganizationId, 'synthetic.reception');
    expect(active.map((event) => event.kind)).toEqual(['invited', 'role_granted']);
    const deactivated = historyFor(syntheticStaffEvents, betaOrganizationId, 'synthetic.reception');
    expect(deactivated.map((event) => event.kind)).toEqual([
      'invited',
      'role_granted',
      'deactivated',
    ]);
    expect(historyFor(syntheticStaffEvents, alphaOrganizationId, 'missing.subject')).toEqual([]);
    expect(active.every((event) => event.organizationId === alphaOrganizationId)).toBe(true);
  });

  it('folds deactivation as an append-only event and clears granted roles', () => {
    const active = historyFor(syntheticStaffEvents, alphaOrganizationId, 'synthetic.reception');
    const deactivated = historyFor(syntheticStaffEvents, betaOrganizationId, 'synthetic.reception');
    expect(isDeactivated(active)).toBe(false);
    expect(isDeactivated(deactivated)).toBe(true);
    expect(currentRoles(active)).toEqual(['reception']);
    expect(currentRoles(deactivated)).toEqual([]);
    const deactivation = deactivated.find((event) => event.kind === 'deactivated');
    expect(deactivation?.reason ?? '').toContain('Synthetic');
  });
});

describe('audit chain', () => {
  it('scopes the chain to the active tenant in sequence order', () => {
    const alpha = adminChainFor(syntheticAuditEvents, alphaOrganizationId);
    expect(alpha.map((event) => event.sequence)).toEqual([1, 2, 3]);
    expect(alpha.every((event) => event.organizationId === alphaOrganizationId)).toBe(true);
    expect(adminChainFor(syntheticAuditEvents, '00000000-0000-0000-0000-000000000000')).toEqual([]);
  });

  it('verifies a complete chain and rejects an empty one', () => {
    const alpha = adminChainFor(syntheticAuditEvents, alphaOrganizationId);
    expect(verifyChain(alpha)).toBe(true);
    expect(verifyChain([])).toBe(false);
    expect(recomputeHash(must(alpha[0]))).toBe(must(alpha[0]).hash);
  });

  it('rejects a tampered stored hash and a broken previous link', () => {
    const beta = adminChainFor(syntheticAuditEvents, betaOrganizationId);
    expect(verifyChain(beta)).toBe(false);
    expect(beta.find((event) => event.hash !== recomputeHash(event))).toBeDefined();

    const brokenLink: AuditEvent[] = adminChainFor(syntheticAuditEvents, alphaOrganizationId).map(
      (event, index) => (index === 0 ? { ...event, previousHash: 'broken-link' } : event),
    );
    expect(verifyChain(brokenLink)).toBe(false);
    expect(tamperedAuditOrganizationId).toBe(betaOrganizationId);
  });

  it('exposes actor, purpose, scope, correlation, outcome and action on every row', () => {
    for (const event of syntheticAuditEvents) {
      expect(event.actor).toContain('Synthetic');
      expect(event.purpose.length).toBeGreaterThan(0);
      expect(event.scope.length).toBeGreaterThan(0);
      expect(event.correlationId.length).toBeGreaterThan(0);
      expect(event.action.length).toBeGreaterThan(0);
      expect(event.outcome === 'SUCCESS' || event.outcome === 'DENIED').toBe(true);
    }
  });
});

describe('localized labels', () => {
  it('gives every admin key a non-empty label in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of adminKeys) {
        expect(adminText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(adminText('ar', 'admin')).toMatch(/[\u0600-\u06FF]/);
    expect(adminText('en', 'admin')).not.toBe(adminText('ar', 'admin'));
    expect(proposalAnnouncement('en', 'MAIN', 5)).toBe('Proposed version 5 for MAIN');
    expect(proposalAnnouncement('ar', 'MAIN', 5)).toContain('5');
  });

  it('fills named placeholders and leaves unknown placeholders visible', () => {
    expect(adminMessage('en', 'facilityCreated', { name: 'Synthetic Lab' })).toBe(
      'Created facility Synthetic Lab',
    );
    expect(
      adminMessage('en', 'deactivated', {
        name: 'Synthetic Auditor',
        reason: 'Synthetic offboarding',
      }),
    ).toBe(
      'Deactivated Synthetic Auditor. Reason entered: Synthetic offboarding (not stored by the API)',
    );
    expect(adminMessage('en', 'facilityCreated')).toBe('Created facility {name}');
  });

  it('maps every access state, staff event kind and outcome to a defined key', () => {
    for (const state of ['permitted', 'denied', 'concealed'] as AccessState[]) {
      expect(adminKeys).toContain(accessStateMessageKey[state]);
    }
    for (const kind of ['invited', 'role_granted', 'deactivated'] as const) {
      expect(adminKeys).toContain(staffEventMessageKey[kind]);
    }
    for (const outcome of ['SUCCESS', 'DENIED', 'NOT_FOUND', 'CONFLICT'] as const) {
      expect(adminKeys).toContain(outcomeMessageKey[outcome]);
    }
  });
});
