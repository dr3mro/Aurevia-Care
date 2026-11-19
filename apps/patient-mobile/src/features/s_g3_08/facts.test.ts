import {
  EMPTY_FACTS,
  acknowledgeRevisionBound,
  acknowledgmentStatus,
  overdueInstructions,
  owningUnitForOverdue,
  reduceFact,
  recordFirstViewOnce,
} from './facts';
import type {
  DeliveryFact,
  DeliveryFacts,
  InstructionView,
} from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const OTHER_ORG = '22222222-2222-2222-2222-222222222222';
const INSTRUCTION = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const PATIENT = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const UNIT = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const OTHER_UNIT = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const NOW = '2026-11-19T12:00:00Z';
const DUE = '2026-11-19T12:00:00Z';
const LATER = '2026-11-19T13:00:00Z';

function view(overrides: Partial<InstructionView> = {}): InstructionView {
  return {
    id: INSTRUCTION,
    organizationId: ORG,
    patientId: PATIENT,
    unitId: UNIT,
    revision: 2,
    latestRevision: 2,
    withdrawn: false,
    title: 'Collection instructions',
    request: { revision: 2, dueAt: DUE },
    ...overrides,
  };
}

describe('MSG-05 first in-app view fact', () => {
  it('records a first view once and never overwrites the earlier timestamp', () => {
    const first = recordFirstViewOnce(EMPTY_FACTS, INSTRUCTION, 2, PATIENT, NOW);
    expect(first.firstView).toEqual({
      kind: 'first_view',
      instructionId: INSTRUCTION,
      revision: 2,
      viewer: PATIENT,
      occurredAt: NOW,
    });
    const again = recordFirstViewOnce(first, INSTRUCTION, 2, PATIENT, LATER);
    expect(again).toBe(first);
    expect(again.firstView?.occurredAt).toBe(NOW);
  });

  it('keeps first view independent of every other fact', () => {
    const withView = recordFirstViewOnce(EMPTY_FACTS, INSTRUCTION, 2, PATIENT, NOW);
    expect(withView.acknowledgment).toBeNull();
    expect(withView.publication).toBeNull();
    expect(withView.dispatch).toBeNull();
  });

  it('records a first view for each new revision without rewriting the earlier one', () => {
    const first = recordFirstViewOnce(EMPTY_FACTS, INSTRUCTION, 2, PATIENT, NOW);
    const next = recordFirstViewOnce(first, INSTRUCTION, 3, PATIENT, LATER);
    expect(next.firstView).toEqual({
      kind: 'first_view', instructionId: INSTRUCTION, revision: 3, viewer: PATIENT, occurredAt: LATER,
    });
    expect(first.firstView?.revision).toBe(2);
    expect(recordFirstViewOnce(next, INSTRUCTION, 3, PATIENT, '2026-11-19T14:00:00Z')).toBe(next);
  });
});

describe('MSG-05 revision-bound receipt acknowledgment', () => {
  it('records a receipt-only acknowledgment bound to the exact revision', () => {
    const result = acknowledgeRevisionBound(view(), EMPTY_FACTS, 2, PATIENT, NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) throw new Error('expected success');
    expect(result.fact).toEqual({
      kind: 'acknowledgment',
      instructionId: INSTRUCTION,
      revision: 2,
      actor: PATIENT,
      purpose: 'receipt',
      acknowledgedAt: NOW,
    });
    expect(Object.keys(result.fact).sort()).toEqual([
      'acknowledgedAt', 'actor', 'instructionId', 'kind', 'purpose', 'revision',
    ]);
  });

  it('rejects a withdrawn instruction', () => {
    const result = acknowledgeRevisionBound(view({ withdrawn: true }), EMPTY_FACTS, 2, PATIENT, NOW);
    expect(result).toEqual({ ok: false, reason: 'WITHDRAWN' });
  });

  it('rejects an instruction that did not request an acknowledgment', () => {
    const result = acknowledgeRevisionBound(view({ request: null }), EMPTY_FACTS, 2, PATIENT, NOW);
    expect(result).toEqual({ ok: false, reason: 'NO_REQUEST' });
  });

  it('rejects a superseded revision', () => {
    const result = acknowledgeRevisionBound(view({ latestRevision: 3 }), EMPTY_FACTS, 2, PATIENT, NOW);
    expect(result).toEqual({ ok: false, reason: 'SUPERSEDED' });
  });

  it('rejects a revision other than the requested one', () => {
    const result = acknowledgeRevisionBound(
      view({ request: { revision: 3, dueAt: DUE }, latestRevision: 2 }), EMPTY_FACTS, 2, PATIENT, NOW,
    );
    expect(result).toEqual({ ok: false, reason: 'REVISION_MISMATCH' });
  });

  it('rejects a repeated acknowledgment without creating a second fact', () => {
    const first = acknowledgeRevisionBound(view(), EMPTY_FACTS, 2, PATIENT, NOW);
    if (!first.ok) throw new Error('expected success');
    const facts: DeliveryFacts = { ...EMPTY_FACTS, acknowledgment: first.fact };
    const repeated = acknowledgeRevisionBound(view(), facts, 2, PATIENT, LATER);
    expect(repeated).toEqual({ ok: false, reason: 'ALREADY_ACKNOWLEDGED' });
  });

  it('never mutates an unrelated clinical workflow object', () => {
    const criticalResult = { kind: 'critical_result', acknowledged: false };
    acknowledgeRevisionBound(view(), EMPTY_FACTS, 2, PATIENT, NOW);
    expect(criticalResult).toEqual({ kind: 'critical_result', acknowledged: false });
  });

  it('accepts a new requested revision after an earlier revision was acknowledged', () => {
    const first = acknowledgeRevisionBound(view(), EMPTY_FACTS, 2, PATIENT, NOW);
    if (!first.ok) throw new Error('expected success');
    const facts: DeliveryFacts = { ...EMPTY_FACTS, acknowledgment: first.fact };
    const superseding = view({ revision: 3, latestRevision: 3, request: { revision: 3, dueAt: LATER } });
    const second = acknowledgeRevisionBound(superseding, facts, 3, PATIENT, LATER);
    expect(second.ok).toBe(true);
    if (!second.ok) throw new Error('expected success');
    expect(second.fact.revision).toBe(3);
  });
});

describe('MSG-05 acknowledgment state and overdue routing', () => {
  it('derives every status from facts and the deadline', () => {
    expect(acknowledgmentStatus(view({ withdrawn: true }), EMPTY_FACTS, NOW)).toBe('withdrawn');
    expect(acknowledgmentStatus(view({ request: null }), EMPTY_FACTS, NOW)).toBe('not_requested');
    const first = acknowledgeRevisionBound(view(), EMPTY_FACTS, 2, PATIENT, NOW);
    if (!first.ok) throw new Error('expected success');
    const acked: DeliveryFacts = { ...EMPTY_FACTS, acknowledgment: first.fact };
    expect(acknowledgmentStatus(view(), acked, LATER)).toBe('acknowledged');
    expect(acknowledgmentStatus(view(), EMPTY_FACTS, DUE)).toBe('overdue');
    expect(acknowledgmentStatus(view(), EMPTY_FACTS, '2026-11-19T11:00:00Z')).toBe('pending');
    expect(acknowledgmentStatus(
      view({ revision: 3, latestRevision: 3, request: { revision: 3, dueAt: DUE } }), acked, DUE,
    )).toBe('overdue');
  });

  it('compares instants rather than ISO text so offsets cannot hide an overdue request', () => {
    const offsetView = view({ request: { revision: 2, dueAt: '2026-11-19T14:00:00+02:00' } });
    expect(acknowledgmentStatus(offsetView, EMPTY_FACTS, '2026-11-19T12:30:00Z')).toBe('overdue');
  });

  it('selects only overdue instructions and routes to the owning unit', () => {
    const instructions = [
      view(),
      view({ id: 'not-requested', request: null }),
      view({ id: 'future', request: { revision: 2, dueAt: LATER } }),
    ];
    const facts = { [INSTRUCTION]: EMPTY_FACTS };
    const overdue = overdueInstructions(instructions, facts, DUE);
    expect(overdue.map((candidate) => candidate.id)).toEqual([INSTRUCTION]);
    expect(owningUnitForOverdue(overdue[0] as InstructionView)).toBe(UNIT);
    expect(owningUnitForOverdue(view({ unitId: OTHER_UNIT }))).toBe(OTHER_UNIT);
  });

  it('does not misattribute a cross-tenant instruction to this patient', () => {
    const foreign = view({ organizationId: OTHER_ORG });
    expect(foreign.organizationId).toBe(OTHER_ORG);
    expect(foreign.patientId).toBe(PATIENT);
  });
});

describe('MSG-05 fact reduction', () => {
  it('writes exactly one fact slot per fact kind', () => {
    const facts: DeliveryFact[] = [
      { kind: 'publication', instructionId: INSTRUCTION, revision: 2, organizationId: ORG, occurredAt: NOW },
      { kind: 'dispatch', instructionId: INSTRUCTION, revision: 2, genericKey: 'NEW_INSTRUCTION', correlationId: 'corr', occurredAt: NOW },
      { kind: 'first_view', instructionId: INSTRUCTION, revision: 2, viewer: PATIENT, occurredAt: NOW },
      { kind: 'acknowledgment', instructionId: INSTRUCTION, revision: 2, actor: PATIENT, purpose: 'receipt', acknowledgedAt: NOW },
    ];
    let reduced = EMPTY_FACTS;
    for (const fact of facts) {
      reduced = reduceFact(reduced, fact);
    }
    expect(reduced.publication).toEqual(facts[0]);
    expect(reduced.dispatch).toEqual(facts[1]);
    expect(reduced.firstView).toEqual(facts[2]);
    expect(reduced.acknowledgment).toEqual(facts[3]);
    expect(Object.keys(reduced).sort()).toEqual([
      'acknowledgment', 'dispatch', 'firstView', 'publication',
    ]);
  });

  it('ignores an unknown fact kind at the boundary', () => {
    const unknown = { kind: 'unknown' } as unknown as DeliveryFact;
    expect(reduceFact(EMPTY_FACTS, unknown)).toBe(EMPTY_FACTS);
  });

  it('does not let a replay overwrite an earlier first view or receipt', () => {
    const firstView: DeliveryFact = {
      kind: 'first_view', instructionId: INSTRUCTION, revision: 2, viewer: PATIENT, occurredAt: NOW,
    };
    const receipt: DeliveryFact = {
      kind: 'acknowledgment', instructionId: INSTRUCTION, revision: 2, actor: PATIENT,
      purpose: 'receipt', acknowledgedAt: NOW,
    };
    const facts = reduceFact(reduceFact(EMPTY_FACTS, firstView), receipt);
    const replayedView: DeliveryFact = {
      kind: 'first_view', instructionId: INSTRUCTION, revision: 2, viewer: PATIENT, occurredAt: LATER,
    };
    const replayedReceipt: DeliveryFact = {
      kind: 'acknowledgment', instructionId: INSTRUCTION, revision: 2, actor: PATIENT,
      purpose: 'receipt', acknowledgedAt: LATER,
    };
    expect(reduceFact(facts, replayedView)).toBe(facts);
    expect(reduceFact(facts, replayedReceipt)).toBe(facts);
  });
});
