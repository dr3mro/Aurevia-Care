import { describe, expect, it } from 'vitest';
import { ACCESSION_STATES, ACTIONS_BY_STATE, actionsForState, canOfferAction, isTerminal } from './domain';
import type { AccessionAction, AccessionState } from './types';

describe('lab accession state machine', () => {
  it('exposes the five authoritative wire states in lifecycle order', () => {
    expect(ACCESSION_STATES).toEqual([
      'accessioned',
      'collected',
      'received',
      'rejected',
      'recollected',
    ]);
  });

  it('offers only the commands the LAB-01 state machine allows', () => {
    expect(actionsForState('accessioned')).toEqual(['collect']);
    expect(actionsForState('collected')).toEqual(['receive', 'reject']);
    expect(actionsForState('received')).toEqual(['reject']);
    expect(actionsForState('rejected')).toEqual(['recollect']);
    expect(actionsForState('recollected')).toEqual([]);
    expect(ACCESSION_STATES.every((state) => state in ACTIONS_BY_STATE)).toBe(true);
  });

  const cases: Array<[AccessionState, AccessionAction, boolean]> = [
    ['accessioned', 'collect', true],
    ['accessioned', 'reject', false],
    ['collected', 'receive', true],
    ['collected', 'reject', true],
    ['collected', 'recollect', false],
    ['received', 'reject', true],
    ['received', 'receive', false],
    ['rejected', 'recollect', true],
    ['rejected', 'collect', false],
    ['recollected', 'recollect', false],
  ];

  it.each(cases)('canOfferAction(%s, %s) is %s', (state, action, expected) => {
    expect(canOfferAction(state, action)).toBe(expected);
  });

  it('marks only the recollected state as terminal', () => {
    expect(ACCESSION_STATES.map((state) => isTerminal(state))).toEqual([
      false,
      false,
      false,
      false,
      true,
    ]);
  });
});
