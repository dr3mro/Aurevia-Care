import { render, fireEvent } from '@testing-library/react-native';

import { CareUnitDirectory } from './CareUnitDirectory';
import { createSyntheticCareUnitClient, eligibleUnits } from './directory';
import type { CareUnit, VerifiedRelationship } from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const OTHER_ORG = '22222222-2222-2222-2222-222222222222';
const PATIENT = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const OTHER_PATIENT = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const LAB = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const RADIOLOGY = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const FOREIGN = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
const NOW = '2026-11-19T12:00:00Z';

function unit(id: string, organizationId: string, name: string, active = true): CareUnit {
  return {
    id,
    organizationId,
    facilityId: 'facility-1',
    name,
    topics: ['collection', 'results'],
    active,
  };
}

function relationship(
  id: string,
  patientId: string,
  unitId: string,
  overrides: Partial<VerifiedRelationship> = {},
): VerifiedRelationship {
  return {
    id,
    organizationId: ORG,
    patientId,
    unitId,
    context: 'order',
    reference: 'order-ref',
    active: true,
    consumed: false,
    ...overrides,
  };
}

describe('MSG-01 eligible unit directory', () => {
  const units = [
    unit(LAB, ORG, 'Synthetic Laboratory'),
    unit(RADIOLOGY, ORG, 'Synthetic Radiology'),
    unit(FOREIGN, OTHER_ORG, 'Foreign Unit'),
    unit('ffffffff-ffff-ffff-ffff-ffffffffffff', ORG, 'Closed Unit', false),
  ];

  it('shows only units with an active verified relationship for this patient', () => {
    const relationships = [
      relationship('rel-1', PATIENT, LAB),
      relationship('rel-inactive', PATIENT, RADIOLOGY, { active: false }),
      relationship('rel-other-patient', OTHER_PATIENT, RADIOLOGY),
      relationship('rel-foreign', PATIENT, FOREIGN),
    ];
    const visible = eligibleUnits(units, relationships, PATIENT, NOW);
    expect(visible.map((candidate) => candidate.id)).toEqual([LAB]);
    expect(visible[0]?.topics).toEqual(['collection', 'results']);
  });

  it('shows a future-dated invitation while it is still valid', () => {
    const relationships = [relationship('rel-future', PATIENT, LAB, {
      context: 'invitation',
      reference: 'future-invite',
      expiresAt: '2026-11-20T00:00:00Z',
    })];
    expect(eligibleUnits(units, relationships, PATIENT, NOW).map((candidate) => candidate.id)).toEqual([LAB]);
  });

  it('hides expired and consumed invitations from the directory', () => {
    const relationships = [
      relationship('rel-expired', PATIENT, LAB, {
        context: 'invitation',
        reference: 'expired',
        expiresAt: '2026-11-18T00:00:00Z',
      }),
      relationship('rel-consumed', PATIENT, RADIOLOGY, {
        context: 'invitation',
        reference: 'consumed',
        consumed: true,
      }),
    ];
    expect(eligibleUnits(units, relationships, PATIENT, NOW)).toEqual([]);
  });
});

describe('ARC-07 connection scoping', () => {
  function client(relationships: VerifiedRelationship[], activeUnits: CareUnit[] = [unit(LAB, ORG, 'Synthetic Laboratory')]) {
    return createSyntheticCareUnitClient({
      organizationId: ORG,
      verifiedCharts: [PATIENT],
      units: activeUnits,
      relationships,
      now: NOW,
      newId: () => 'connection-1',
    });
  }

  it('connects only through a verified relationship bound to the exact patient and unit', () => {
    const subject = client([relationship('rel-1', PATIENT, LAB)]);
    const result = subject.connect(PATIENT, LAB, 'order', 'order-ref');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.connection.patientId).toBe(PATIENT);
      expect(result.connection.unitId).toBe(LAB);
      expect(result.connection.state).toBe('active');
    }
    expect(subject.listConnections(PATIENT)).toHaveLength(1);
    expect(subject.listConnections(OTHER_PATIENT)).toHaveLength(0);
    expect(subject.listEligibleUnits(PATIENT).map((candidate) => candidate.id)).toEqual([LAB]);
    expect(subject.listEligibleUnits(OTHER_PATIENT)).toEqual([]);
  });

  it('denies wrong-unit, cross-tenant, inactive and unverified-relationship connects', () => {
    const subject = client([
      relationship('rel-1', PATIENT, LAB),
      relationship('rel-other-unit', PATIENT, RADIOLOGY, { reference: 'radiology-ref' }),
    ], [unit(LAB, ORG, 'Synthetic Laboratory'), unit(RADIOLOGY, ORG, 'Synthetic Radiology', false), unit(FOREIGN, OTHER_ORG, 'Foreign Unit')]);
    expect(subject.connect(PATIENT, LAB, 'order', 'radiology-ref').ok).toBe(false);
    expect(subject.connect(PATIENT, FOREIGN, 'order', 'order-ref').ok).toBe(false);
    expect(subject.connect(PATIENT, RADIOLOGY, 'order', 'radiology-ref').ok).toBe(false);
    expect(subject.connect(PATIENT, LAB, 'referral', 'order-ref').ok).toBe(false);
    expect(subject.connect(PATIENT, 'ffffffff-ffff-ffff-ffff-ffffffffffff', 'order', 'order-ref').ok).toBe(false);
    expect(subject.listConnections(PATIENT)).toHaveLength(0);
  });

  it('denies an unverified chart even when a matching relationship exists', () => {
    const subject = client([relationship('rel-unverified', OTHER_PATIENT, LAB)]);
    const denial = subject.connect(OTHER_PATIENT, LAB, 'order', 'order-ref');
    if (!denial.ok) expect(denial.reason).toBe('UNVERIFIED_CHART');
    expect(subject.listConnections(OTHER_PATIENT)).toEqual([]);
    expect(subject.listEligibleUnits(OTHER_PATIENT)).toEqual([]);
  });

  it('denies expired and consumed single-use invitations', () => {
    const subject = client([
      relationship('rel-expired', PATIENT, LAB, {
        context: 'invitation', reference: 'expired', expiresAt: '2026-11-18T00:00:00Z',
      }),
      relationship('rel-consumed', PATIENT, LAB, {
        context: 'invitation', reference: 'consumed', consumed: true,
      }),
    ]);
    const expired = subject.connect(PATIENT, LAB, 'invitation', 'expired');
    const consumed = subject.connect(PATIENT, LAB, 'invitation', 'consumed');
    if (!expired.ok) expect(expired.reason).toBe('EXPIRED_INVITATION');
    if (!consumed.ok) expect(consumed.reason).toBe('CONSUMED_INVITATION');
    expect(subject.listConnections(PATIENT)).toHaveLength(0);
  });

  it('returns the original connection for a repeated verified reference', () => {
    const subject = client([relationship('rel-1', PATIENT, LAB)]);
    const first = subject.connect(PATIENT, LAB, 'order', 'order-ref');
    const second = subject.connect(PATIENT, LAB, 'order', 'order-ref');
    if (first.ok && second.ok) expect(second.connection.id).toBe(first.connection.id);
    expect(subject.listConnections(PATIENT)).toHaveLength(1);
  });

  it('consumes a single-use invitation on first successful connect', () => {
    const subject = client([
      relationship('rel-invite', PATIENT, LAB, {
        context: 'invitation', reference: 'invite-ref', expiresAt: '2026-11-20T00:00:00Z',
      }),
      relationship('rel-order', PATIENT, LAB, { reference: 'order-ref-2' }),
    ]);
    expect(subject.connect(PATIENT, LAB, 'invitation', 'invite-ref').ok).toBe(true);
    const repeat = subject.connect(PATIENT, LAB, 'invitation', 'invite-ref');
    expect(repeat.ok).toBe(true);
    expect(subject.listConnections(PATIENT)).toHaveLength(1);
  });
});

describe('CareUnitDirectory component', () => {
  const units = [unit(LAB, ORG, 'Synthetic Laboratory')];

  it('renders organization, facility, name and topics with accessible connect action', async () => {
    const calls: CareUnit[] = [];
    const onConnect = (unit: CareUnit): void => { calls.push(unit); };
    const view = await render(<CareUnitDirectory units={units} isRTL={false} onConnect={onConnect} />);
    expect(view.getByText('Synthetic Laboratory')).toBeTruthy();
    expect(view.getByText('collection, results')).toBeTruthy();
    expect(view.getByText(ORG)).toBeTruthy();
    expect(view.getByText('facility-1')).toBeTruthy();
    const button = view.getByLabelText('Connect to Synthetic Laboratory');
    await fireEvent.press(button);
    expect(calls).toEqual([units[0]]);
  });

  it('uses a forward row layout for LTR locales', async () => {
    const view = await render(<CareUnitDirectory units={units} isRTL={false} onConnect={() => undefined} />);
    expect(view.getByTestId('unit-directory').props.style.flexDirection).toBe('row');
  });

  it('mirrors the row layout for RTL locales', async () => {
    const view = await render(<CareUnitDirectory units={units} isRTL={true} onConnect={() => undefined} />);
    expect(view.getByTestId('unit-directory').props.style.flexDirection).toBe('row-reverse');
  });

  it('shows an empty state when there is no verified relationship', async () => {
    const view = await render(<CareUnitDirectory units={[]} isRTL={false} onConnect={() => undefined} />);
    expect(view.getByText('No eligible care units')).toBeTruthy();
  });
});
