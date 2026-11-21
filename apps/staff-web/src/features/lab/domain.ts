import type { AccessionAction, AccessionState } from './types';

/**
 * The LAB-01 state machine, expressed as the exact set of commands a screen may
 * offer for each state. The UI never invents an action: a command appears only
 * when its state lists it, which keeps the client in step with the server guard
 * (`AccessionState.canTransitionTo`).
 */
export const ACTIONS_BY_STATE: Record<AccessionState, readonly AccessionAction[]> = {
  accessioned: ['collect'],
  collected: ['receive', 'reject'],
  received: ['reject'],
  rejected: ['recollect'],
  recollected: [],
};

/** Every wire state, in lifecycle order, for the state filter control. */
export const ACCESSION_STATES: readonly AccessionState[] = [
  'accessioned',
  'collected',
  'received',
  'rejected',
  'recollected',
];

/**
 * Return the commands a specimen in `state` may legally receive.
 *
 * @param state current accession lifecycle state
 * @returns the allowed actions, empty for a terminal state
 */
export function actionsForState(state: AccessionState): readonly AccessionAction[] {
  return ACTIONS_BY_STATE[state];
}

/**
 * Report whether an action may be offered in a state.
 *
 * @param state current accession lifecycle state
 * @param action command the screen is considering
 * @returns true only when the state machine lists the edge
 */
export function canOfferAction(state: AccessionState, action: AccessionAction): boolean {
  return actionsForState(state).includes(action);
}

/**
 * Report whether a state is terminal (no further command is available).
 *
 * @param state current accession lifecycle state
 * @returns true when the state has no outgoing command
 */
export function isTerminal(state: AccessionState): boolean {
  return actionsForState(state).length === 0;
}
