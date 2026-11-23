import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  IllegalVisitTransition,
  applyCommand,
  appointmentsForTenant,
  conflictingAppointment,
  isTerminal,
  nextVisitStates,
  replaceAppointment,
  selectWorklist,
  transitionVisitState,
  withinRange,
} from './domain';
import type { Appointment, VisitState } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  createSyntheticAppointment,
  schedulingAnchorCivilDate,
  syntheticAlphaAppointments,
  syntheticAppointments,
  syntheticBetaAppointments,
} from './fixtures';
import { schedulingKeys, schedulingText, visitStateMessageKey } from './messages';

const findAlpha = (id: string): Appointment => {
  const found = syntheticAlphaAppointments.find((appointment) => appointment.id === id);
  if (!found) {
    throw new Error(`missing synthetic alpha fixture ${id}`);
  }
  return found;
};

describe('tenant-scoped scheduling selection', () => {
  it('keeps two tenants with identical local identifiers apart', () => {
    const alpha = appointmentsForTenant(syntheticAppointments, alphaOrganizationId);
    const beta = appointmentsForTenant(syntheticAppointments, betaOrganizationId);

    expect(alpha.length).toBe(syntheticAlphaAppointments.length);
    expect(beta.length).toBe(syntheticBetaAppointments.length);
    expect(alpha.some((appointment) => appointment.organizationId === betaOrganizationId)).toBe(
      false,
    );
    expect(beta.some((appointment) => appointment.organizationId === alphaOrganizationId)).toBe(
      false,
    );
    expect(alpha.map((appointment) => appointment.id)).toContain('apt-001');
    expect(beta.map((appointment) => appointment.id)).toContain('apt-001');
    expect(alpha.find((appointment) => appointment.id === 'apt-001')?.patientName.latinName).toBe(
      'Synthetic Layla',
    );
    expect(beta.find((appointment) => appointment.id === 'apt-001')?.patientName.latinName).toBe(
      'Synthetic Reem',
    );
  });

  it('orders appointments by scheduled instant with a stable identifier tie-break', () => {
    const ordered = appointmentsForTenant(syntheticAppointments, alphaOrganizationId);
    expect(ordered.map((appointment) => appointment.id)).toEqual([
      'apt-003',
      'apt-002',
      'apt-001',
      'apt-004',
      'apt-005',
      'apt-006',
      'apt-007',
      'apt-008',
    ]);

    const tied: Appointment[] = [
      { ...findAlpha('apt-001'), id: 'b', scheduledAt: '2026-03-09T10:30:00Z' },
      { ...findAlpha('apt-002'), id: 'a', scheduledAt: '2026-03-09T10:30:00Z' },
    ];
    expect(
      appointmentsForTenant(tied, alphaOrganizationId).map((appointment) => appointment.id),
    ).toEqual(['a', 'b']);
  });

  it('returns no appointments for an organization with no fixtures', () => {
    expect(
      appointmentsForTenant(syntheticAppointments, '00000000-0000-0000-0000-000000000000'),
    ).toEqual([]);
  });
});

describe('worklist day and week ranges', () => {
  it('matches a single civil day for the day range', () => {
    expect(withinRange(findAlpha('apt-001'), 'day', schedulingAnchorCivilDate)).toBe(true);
    expect(withinRange(findAlpha('apt-004'), 'day', schedulingAnchorCivilDate)).toBe(false);
  });

  it('uses a Monday-to-Sunday window for the week range', () => {
    expect(withinRange(findAlpha('apt-007'), 'week', schedulingAnchorCivilDate)).toBe(true);
    expect(withinRange(findAlpha('apt-008'), 'week', schedulingAnchorCivilDate)).toBe(false);
    expect(withinRange(findAlpha('apt-002'), 'week', schedulingAnchorCivilDate)).toBe(true);
  });

  it('anchors the week correctly when the anchor is a Sunday', () => {
    const beforeAnchor: Appointment = {
      ...findAlpha('apt-001'),
      scheduledAt: '2026-03-05T09:00:00Z',
    };
    expect(withinRange(beforeAnchor, 'week', '2026-03-08')).toBe(true);
    expect(withinRange(findAlpha('apt-001'), 'week', '2026-03-08')).toBe(false);
  });

  it('composes tenant selection, range filtering and time ordering', () => {
    expect(
      selectWorklist(
        syntheticAppointments,
        alphaOrganizationId,
        'day',
        schedulingAnchorCivilDate,
      ).map((a) => a.id),
    ).toEqual(['apt-003', 'apt-002', 'apt-001']);
    const week = selectWorklist(
      syntheticAppointments,
      alphaOrganizationId,
      'week',
      schedulingAnchorCivilDate,
    );
    expect(week.map((a) => a.id)).toContain('apt-007');
    expect(week.map((a) => a.id)).not.toContain('apt-008');
    expect(
      selectWorklist(
        syntheticAppointments,
        betaOrganizationId,
        'day',
        schedulingAnchorCivilDate,
      ).map((a) => a.id),
    ).toEqual(['apt-001']);
  });
});

describe('visit-state lifecycle', () => {
  const expected: Record<VisitState, readonly VisitState[]> = {
    booked: ['checked-in', 'cancelled', 'no-show'],
    'checked-in': ['in-progress', 'cancelled', 'no-show'],
    'in-progress': ['completed'],
    completed: [],
    cancelled: [],
    'no-show': [],
  };

  it('declares the legal next states for every visit state', () => {
    for (const state of Object.keys(expected) as VisitState[]) {
      expect(nextVisitStates(state)).toEqual(expected[state]);
    }
  });

  it('applies a legal transition and refuses an illegal one', () => {
    expect(transitionVisitState('booked', 'checked-in')).toBe('checked-in');
    expect(() => transitionVisitState('completed', 'booked')).toThrow(IllegalVisitTransition);
    expect(() => transitionVisitState('completed', 'booked')).toThrow(/completed -> booked/);
  });

  it('marks only terminal states as terminal', () => {
    expect(isTerminal('booked')).toBe(false);
    expect(isTerminal('checked-in')).toBe(false);
    expect(isTerminal('in-progress')).toBe(false);
    expect(isTerminal('completed')).toBe(true);
    expect(isTerminal('cancelled')).toBe(true);
    expect(isTerminal('no-show')).toBe(true);
  });
});

describe('scheduling commands', () => {
  it('checks in a booking and bumps its version', () => {
    const result = applyCommand(findAlpha('apt-001'), { kind: 'check-in' });
    expect(result.state).toBe('checked-in');
    expect(result.version).toBe(findAlpha('apt-001').version + 1);
  });

  it('assigns a non-terminal visit and bumps its version', () => {
    const result = applyCommand(findAlpha('apt-002'), {
      kind: 'assign',
      assignee: 'synthetic.nurse',
    });
    expect(result.assignee).toBe('synthetic.nurse');
    expect(result.version).toBe(findAlpha('apt-002').version + 1);
  });

  it('refuses to assign or check in a terminal visit', () => {
    expect(() => applyCommand(findAlpha('apt-004'), { kind: 'assign', assignee: 'x' })).toThrow(
      /terminal/,
    );
    expect(() => applyCommand(findAlpha('apt-004'), { kind: 'check-in' })).toThrow(
      IllegalVisitTransition,
    );
  });

  it('replaces only the matching tenant and identifier', () => {
    const updated = replaceAppointment(
      syntheticAppointments,
      alphaOrganizationId,
      'apt-001',
      (appointment) => ({ ...appointment, state: 'completed' }),
    );
    expect(updated).toHaveLength(syntheticAppointments.length);
    expect(
      updated.find((a) => a.organizationId === alphaOrganizationId && a.id === 'apt-001')?.state,
    ).toBe('completed');
    expect(
      updated.find((a) => a.organizationId === betaOrganizationId && a.id === 'apt-001')?.state,
    ).toBe('booked');

    expect(
      replaceAppointment(syntheticAppointments, alphaOrganizationId, 'missing', (a) => a),
    ).toEqual(syntheticAppointments);
    expect(replaceAppointment(syntheticAppointments, 'unknown', 'apt-001', (a) => a)).toEqual(
      syntheticAppointments,
    );
  });
});

describe('protected-resource conflict detection', () => {
  it('detects a same-tenant occupant of the protected slot', () => {
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'room-1',
        scheduledAt: '2026-03-09T10:30:00Z',
      })?.id,
    ).toBe('apt-001');
  });

  it('never treats the other tenant with the same local id as a conflict', () => {
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: betaOrganizationId,
        resourceId: 'clinician-7',
        scheduledAt: '2026-03-09T09:00:00Z',
      }),
    ).toBeUndefined();
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'clinician-7',
        scheduledAt: '2026-03-09T09:00:00Z',
      })?.id,
    ).toBe('apt-002');
  });

  it('ignores cancelled and no-show visits and non-matching resource or time', () => {
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'room-1',
        scheduledAt: '2026-03-11T08:15:00Z',
      }),
    ).toBeUndefined();
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'room-1',
        scheduledAt: '2026-03-12T08:15:00Z',
      }),
    ).toBeUndefined();
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'room-9',
        scheduledAt: '2026-03-09T10:30:00Z',
      }),
    ).toBeUndefined();
    expect(
      conflictingAppointment(syntheticAppointments, {
        organizationId: alphaOrganizationId,
        resourceId: 'room-1',
        scheduledAt: '2026-03-09T23:00:00Z',
      }),
    ).toBeUndefined();
  });
});

describe('synthetic fixtures and localized labels', () => {
  it('shares local identifiers across tenants and contains every state', () => {
    const alphaIds = syntheticAlphaAppointments.map((a) => a.id);
    const betaIds = syntheticBetaAppointments.map((a) => a.id);
    expect(alphaIds.filter((id) => betaIds.includes(id))).toEqual(['apt-001', 'apt-002']);
    for (const appointment of syntheticAppointments) {
      expect(appointment.patientName.latinName).toContain('Synthetic');
    }
    expect(new Set(syntheticAppointments.map((a) => a.state))).toEqual(
      new Set<VisitState>([
        'booked',
        'checked-in',
        'in-progress',
        'completed',
        'cancelled',
        'no-show',
      ]),
    );
  });

  it('creates a synthetic booking bound to the requesting tenant', () => {
    const created = createSyntheticAppointment({
      organizationId: betaOrganizationId,
      resourceId: 'room-9',
      scheduledAt: '2026-03-09T07:00:00Z',
    });
    expect(created.organizationId).toBe(betaOrganizationId);
    expect(created.resourceId).toBe('room-9');
    expect(created.scheduledAt).toBe('2026-03-09T07:00:00Z');
    expect(created.state).toBe('booked');
    expect(created.version).toBe(1);
    expect(created.id).toContain(betaOrganizationId);
    expect(created.patientName.latinName).toContain('Synthetic');
  });

  it('provides a non-empty label in both bundles for every scheduling key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of schedulingKeys) {
        expect(schedulingText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(schedulingText('en', 'book')).not.toBe(schedulingText('ar', 'book'));
    expect(schedulingText('ar', 'book')).toMatch(/[\u0600-\u06FF]/);
  });

  it('maps every visit state to a defined message key', () => {
    for (const state of Object.keys(visitStateMessageKey) as VisitState[]) {
      expect(schedulingKeys).toContain(visitStateMessageKey[state]);
    }
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every feature component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const componentFiles = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(componentFiles.length).toBeGreaterThan(0);
    for (const name of componentFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
